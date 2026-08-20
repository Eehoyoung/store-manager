package com.storemanager.api.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.ai.AiClient;
import com.storemanager.api.ai.AiClientDtos;
import com.storemanager.api.ai.AiClientDtos.AnalyzeAndDraftResponse;
import com.storemanager.api.ai.AiClientDtos.DraftOut;
import com.storemanager.api.ai.LlmUsageLog;
import com.storemanager.api.ai.LlmUsageLogRepository;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.draft.DraftDtos.ApproveRequest;
import com.storemanager.api.draft.DraftDtos.BulkApproveRequest;
import com.storemanager.api.draft.DraftDtos.BulkApproveResponse;
import com.storemanager.api.draft.DraftDtos.BulkFilter;
import com.storemanager.api.draft.DraftDtos.DraftListResponse;
import com.storemanager.api.draft.DraftDtos.DraftResponse;
import com.storemanager.api.draft.DraftDtos.GenerateDraftsRequest;
import com.storemanager.api.draft.DraftDtos.GenerateDraftsResponse;
import com.storemanager.api.draft.DraftDtos.PatchDraftRequest;
import com.storemanager.api.notify.Notifier;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StorePersona;
import com.storemanager.api.store.StorePersonaRepository;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 답글 초안 생성·승인 큐(S3, S5, S6, S8). docs/13 §6.
 * ★ 리뷰 본문은 AiClient 를 통해 있는 그대로 전달만 하고 여기서 생성·가공하지 않는다(절대규칙 1).
 * ★ risk_level >= 3 인 초안은 승인할 수 없다(절대규칙 3) — 여기와 PublishScheduler 양쪽에서 검증한다.
 */
@Service
public class DraftService {

    private static final Logger log = LoggerFactory.getLogger(DraftService.class);
    private static final short RISK_AUTO_BLOCK_LEVEL = 3; // CLAUDE.md 절대규칙 3

    private final ReplyDraftRepository replyDraftRepository;
    private final ReviewAnalysisRepository reviewAnalysisRepository;
    private final UnifiedReviewRepository unifiedReviewRepository;
    private final StoreRepository storeRepository;
    private final StorePersonaRepository storePersonaRepository;
    private final AppUserRepository appUserRepository;
    private final AiClient aiClient;
    private final LlmUsageLogRepository llmUsageLogRepository;
    private final AuditLogRepository auditLogRepository;
    private final Notifier notifier;
    private final ObjectMapper objectMapper;

    public DraftService(ReplyDraftRepository replyDraftRepository, ReviewAnalysisRepository reviewAnalysisRepository,
            UnifiedReviewRepository unifiedReviewRepository, StoreRepository storeRepository,
            StorePersonaRepository storePersonaRepository, AppUserRepository appUserRepository, AiClient aiClient,
            LlmUsageLogRepository llmUsageLogRepository, AuditLogRepository auditLogRepository, Notifier notifier,
            ObjectMapper objectMapper) {
        this.replyDraftRepository = replyDraftRepository;
        this.reviewAnalysisRepository = reviewAnalysisRepository;
        this.unifiedReviewRepository = unifiedReviewRepository;
        this.storeRepository = storeRepository;
        this.storePersonaRepository = storePersonaRepository;
        this.appUserRepository = appUserRepository;
        this.aiClient = aiClient;
        this.llmUsageLogRepository = llmUsageLogRepository;
        this.auditLogRepository = auditLogRepository;
        this.notifier = notifier;
        this.objectMapper = objectMapper;
    }

    /**
     * POST /reviews/{reviewId}/drafts (S3). AI 호출 → review_analysis UPSERT → reply_draft 저장 → 자동승인 시도.
     *
     * <p>★ noRollbackFor 가 반드시 필요하다. 가드레일 전량 차단 시 우리는 422 를 던지면서도
     * review_analysis 와 status=BLOCKED 초안은 반드시 남겨야 한다 — 절대규칙 3 이 요구하는
     * "사람 검수 큐로 보낸다" 가 이 행들로 성립하기 때문이다. 기본 설정에서는 RuntimeException 이
     * 트랜잭션을 롤백시켜 차단 기록이 통째로 사라지고, 사장님에게는 422 만 남아 위험 리뷰가
     * 어디에도 쌓이지 않는다(실기동에서 실제로 확인한 동작이다).
     * 이 메서드에서 던지는 다른 ApiException 은 모두 쓰기 이전 단계라 롤백 대상이 없다.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public GenerateDraftsResponse generateDrafts(UUID ownerPublicId, Long reviewId, GenerateDraftsRequest req) {
        AppUser owner = resolveUser(ownerPublicId);
        UnifiedReview review = unifiedReviewRepository.findById(reviewId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Store store = loadOwnedStore(owner, review.getStoreId());
        StorePersona persona = storePersonaRepository.findById(store.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));

        AiClientDtos.AnalyzeAndDraftRequest aiReq = buildAiRequest(review, persona, req);
        AnalyzeAndDraftResponse aiRes = aiClient.analyzeAndDraft(aiReq);

        upsertAnalysis(reviewId, aiRes);

        List<ReplyDraft> saved = new ArrayList<>();
        if (aiRes.blocked()) {
            // 전량 차단 — 실제 내용이 없으므로 감사 목적의 빈 콘텐츠로 BLOCKED 행을 남긴다(내용 생성이 아니라 "생성 시도 기록").
            ReplyDraft blocked = ReplyDraft.builder()
                    .reviewId(reviewId)
                    .storeId(store.getId())
                    .content("")
                    .status("BLOCKED")
                    .generatedBy("AI")
                    .model(aiRes.analysis() == null ? null : aiRes.analysis().model())
                    .promptVersion(aiRes.analysis() == null ? null : aiRes.analysis().promptVersion())
                    .guardrailFlags(aiRes.blockReasons() == null ? new String[0] : aiRes.blockReasons().toArray(new String[0]))
                    .build();
            replyDraftRepository.save(blocked);
            saved.add(blocked);

            if (aiRes.analysis() != null && aiRes.analysis().riskLevel() >= RISK_AUTO_BLOCK_LEVEL) {
                notifyHighRisk(store, reviewId);
            }
            throw new ApiException(ErrorCode.GUARDRAIL_BLOCKED,
                    Map.of("flags", aiRes.blockReasons() == null ? List.of() : aiRes.blockReasons(), "attempts", 1));
        }

        for (DraftOut d : aiRes.drafts()) {
            ReplyDraft draft = ReplyDraft.builder()
                    .reviewId(reviewId)
                    .storeId(store.getId())
                    .content(d.content())
                    .status("DRAFT")
                    .generatedBy("AI")
                    .model(d.model())
                    .promptVersion(d.promptVersion())
                    .tier(d.tier())
                    .tokenIn(d.tokenIn())
                    .tokenOut(d.tokenOut())
                    .costKrw(java.math.BigDecimal.valueOf(d.costKrw()))
                    .guardrailFlags(d.guardrailFlags() == null ? new String[0] : d.guardrailFlags().toArray(new String[0]))
                    .similarityMax(d.similarityMax())
                    .build();
            replyDraftRepository.save(draft);
            saved.add(draft);

            llmUsageLogRepository.save(LlmUsageLog.builder()
                    .storeId(store.getId())
                    .draftId(draft.getId())
                    .purpose("GENERATE")
                    .tier(d.tier())
                    .model(d.model())
                    .tokenIn(d.tokenIn())
                    .tokenOut(d.tokenOut())
                    .costKrw(java.math.BigDecimal.valueOf(d.costKrw()))
                    .build());

            tryAutoApprove(store, persona, review, draft, aiRes.analysis());
        }

        if (aiRes.analysis() != null && aiRes.analysis().riskLevel() >= RISK_AUTO_BLOCK_LEVEL) {
            notifyHighRisk(store, reviewId);
        }

        return new GenerateDraftsResponse(saved.stream().map(DraftResponse::from).toList());
    }

    /** PATCH /drafts/{draftId} — 사장님이 직접 수정. generated_by=AI_EDITED (절대규칙 4: 직접 입력은 허용). */
    @Transactional
    public DraftResponse patch(UUID ownerPublicId, Long draftId, PatchDraftRequest req) {
        AppUser owner = resolveUser(ownerPublicId);
        ReplyDraft draft = loadOwnedDraft(owner, draftId);
        draft.editContent(req.content());
        return DraftResponse.from(draft);
    }

    /** POST /drafts/{draftId}/approve (S5, S6, S7). */
    @Transactional
    public DraftResponse approve(UUID ownerPublicId, Long draftId, ApproveRequest req) {
        AppUser owner = resolveUser(ownerPublicId);
        ReplyDraft draft = loadOwnedDraft(owner, draftId);

        if ("BLOCKED".equals(draft.getStatus())) {
            throw new ApiException(ErrorCode.GUARDRAIL_BLOCKED,
                    Map.of("flags", List.of(draft.getGuardrailFlags())));
        }
        ReviewAnalysis analysis = reviewAnalysisRepository.findById(draft.getReviewId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        rejectIfTooRisky(analysis);

        UnifiedReview review = unifiedReviewRepository.findById(draft.getReviewId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        StorePersona persona = storePersonaRepository.findById(draft.getStoreId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));

        String publishMode = req == null || req.publishMode() == null ? "SCHEDULED" : req.publishMode();
        Instant scheduledAt = "IMMEDIATE".equals(publishMode)
                ? Instant.now()
                : PublishScheduleCalculator.compute(review.getCollectedAt(), persona.getDelayHours(),
                        parseWindows(persona.getPublishWindows()));

        draft.approve(owner.getId(), scheduledAt);
        return DraftResponse.from(draft);
    }

    /** POST /drafts/{draftId}/reject. */
    @Transactional
    public DraftResponse reject(UUID ownerPublicId, Long draftId) {
        AppUser owner = resolveUser(ownerPublicId);
        ReplyDraft draft = loadOwnedDraft(owner, draftId);
        draft.reject();
        return DraftResponse.from(draft);
    }

    /**
     * POST /drafts/bulk-approve (S5, S6). risk_level>=3 이거나 필터 불일치면 던지지 않고 skippedReasons 에 집계한다.
     * ponytail: N+1 로 분석/리뷰를 개별 조회한다 — 승인 큐 규모(스토어당 최대 수십 건)에서는 충분하다.
     * 배치가 수백 건 이상으로 커지면 조인 쿼리로 교체.
     */
    @Transactional
    public BulkApproveResponse bulkApprove(UUID ownerPublicId, BulkApproveRequest req) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = storeRepository.findByPublicIdAndDeletedAtIsNull(UUID.fromString(req.storeId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!store.getOwnerId().equals(owner.getId())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        StorePersona persona = storePersonaRepository.findById(store.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        BulkFilter filter = req.filter() == null ? new BulkFilter(null, null, null) : req.filter();

        List<ReplyDraft> candidates = replyDraftRepository.findByStoreIdAndStatus(store.getId(), "DRAFT");
        int approved = 0;
        Map<String, Integer> skippedReasons = new LinkedHashMap<>();

        for (ReplyDraft draft : candidates) {
            ReviewAnalysis analysis = reviewAnalysisRepository.findById(draft.getReviewId()).orElse(null);
            UnifiedReview review = unifiedReviewRepository.findById(draft.getReviewId()).orElse(null);
            if (analysis == null || review == null) {
                skippedReasons.merge("RESOURCE_NOT_FOUND", 1, Integer::sum);
                continue;
            }
            if (analysis.getRiskLevel() >= RISK_AUTO_BLOCK_LEVEL
                    || (filter.maxRiskLevel() != null && analysis.getRiskLevel() > filter.maxRiskLevel())) {
                skippedReasons.merge("RISK_LEVEL_TOO_HIGH", 1, Integer::sum);
                continue;
            }
            if (filter.minRating() != null && (review.getRating() == null || review.getRating() < filter.minRating())) {
                skippedReasons.merge("FILTER_MISMATCH", 1, Integer::sum);
                continue;
            }
            if (filter.category() != null && !filter.category().isEmpty()
                    && !filter.category().contains(analysis.getCategory())) {
                skippedReasons.merge("FILTER_MISMATCH", 1, Integer::sum);
                continue;
            }

            Instant scheduledAt = PublishScheduleCalculator.compute(review.getCollectedAt(), persona.getDelayHours(),
                    parseWindows(persona.getPublishWindows()));
            draft.approve(owner.getId(), scheduledAt);
            approved++;
        }

        return new BulkApproveResponse(approved, candidates.size() - approved, skippedReasons);
    }

    /** GET /stores/{storeId}/drafts (승인 큐). */
    @Transactional(readOnly = true)
    public DraftListResponse listQueue(UUID ownerPublicId, UUID storePublicId, String status, int page, int size) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!store.getOwnerId().equals(owner.getId())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        Page<ReplyDraft> result = status == null || status.isBlank()
                ? replyDraftRepository.findByStoreId(store.getId(), PageRequest.of(page, size))
                : replyDraftRepository.findByStoreIdAndStatus(store.getId(), status, PageRequest.of(page, size));
        return new DraftListResponse(result.getContent().stream().map(DraftResponse::from).toList(), result.hasNext());
    }

    /** GET /drafts/{draftId}. */
    @Transactional(readOnly = true)
    public DraftResponse getOne(UUID ownerPublicId, Long draftId) {
        AppUser owner = resolveUser(ownerPublicId);
        return DraftResponse.from(loadOwnedDraft(owner, draftId));
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────

    private void rejectIfTooRisky(ReviewAnalysis analysis) {
        if (analysis.getRiskLevel() >= RISK_AUTO_BLOCK_LEVEL) {
            throw new ApiException(ErrorCode.RISK_LEVEL_TOO_HIGH,
                    Map.of("riskLevel", analysis.getRiskLevel(), "riskReasons", List.of(analysis.getRiskReasons())));
        }
    }

    /** 자동승인 규칙 엔진(S8). 조건을 하나라도 어기면 DRAFT 로 남겨 사람 검수 큐에 둔다. */
    private void tryAutoApprove(Store store, StorePersona persona, UnifiedReview review, ReplyDraft draft,
            AiClientDtos.AnalysisOut analysis) {
        if (analysis == null || !persona.isAutoPublish()) {
            return;
        }
        boolean ratingOk = review.getRating() != null && review.getRating() >= persona.getAutoMinRating();
        boolean riskOk = analysis.riskLevel() <= persona.getAutoMaxRisk() && analysis.riskLevel() < RISK_AUTO_BLOCK_LEVEL;
        boolean flagsOk = draft.getGuardrailFlags() == null || draft.getGuardrailFlags().length == 0;
        if (!ratingOk || !riskOk || !flagsOk) {
            return;
        }
        Instant scheduledAt = PublishScheduleCalculator.compute(review.getCollectedAt(), persona.getDelayHours(),
                parseWindows(persona.getPublishWindows()));
        draft.approve(null, scheduledAt);
        auditLogRepository.save(AuditLog.builder()
                .actorType("SYSTEM")
                .action("DRAFT_AUTO_APPROVED")
                .targetType("REPLY_DRAFT")
                .targetId(draft.getId())
                .build());
    }

    private void notifyHighRisk(Store store, Long reviewId) {
        notifier.send(store.getOwnerId(), store.getId(), "ALIMTALK", "HIGH_RISK_REVIEW", "UNIFIED_REVIEW", reviewId);
    }

    private AiClientDtos.AnalyzeAndDraftRequest buildAiRequest(UnifiedReview review, StorePersona persona,
            GenerateDraftsRequest req) {
        List<String> menus = parseStringList(review.getOrderedMenus());
        AiClientDtos.ReviewIn reviewIn = new AiClientDtos.ReviewIn(
                review.getRating() == null ? 0 : review.getRating(),
                review.getBody() == null ? "" : review.getBody(),
                menus,
                review.getPlatform());
        AiClientDtos.PersonaIn personaIn = new AiClientDtos.PersonaIn(
                persona.getTone(), persona.isUseEmoji(), persona.getEmojiLevel(), persona.getCustomerTitle(),
                persona.getSignature(),
                persona.getBannedWords() == null ? List.of() : List.of(persona.getBannedWords()),
                persona.getLengthMin(), persona.getLengthMax(), persona.getPersonaSeed());
        int variants = req != null && req.variants() != null ? req.variants() : 1;
        String instruction = req == null ? null : req.instruction();
        AiClientDtos.OptionsIn optionsIn = new AiClientDtos.OptionsIn(variants, instruction, null);
        // ★ store_id/review_id 는 ai-python 이 pgvector 조회에 BIGINT 로 그대로 쓰므로 public_id 가 아니라
        // 내부 BIGSERIAL id 를 문자열로 넘긴다(ai-python/rag.py: "store_id = %s::bigint").
        return new AiClientDtos.AnalyzeAndDraftRequest(String.valueOf(review.getId()), String.valueOf(review.getStoreId()),
                reviewIn, personaIn, optionsIn);
    }

    private void upsertAnalysis(Long reviewId, AnalyzeAndDraftResponse res) {
        if (res.analysis() == null) {
            return;
        }
        ReviewAnalysis analysis = reviewAnalysisRepository.findById(reviewId)
                .orElseGet(() -> ReviewAnalysis.builder().reviewId(reviewId).build());
        analysis.applyIncoming(res.analysis().category(), res.analysis().sentiment(),
                res.analysis().issueTags() == null ? new String[0] : res.analysis().issueTags().toArray(new String[0]),
                (short) res.analysis().riskLevel(),
                res.analysis().riskReasons() == null ? new String[0] : res.analysis().riskReasons().toArray(new String[0]),
                res.analysis().model(), res.analysis().promptVersion());
        reviewAnalysisRepository.save(analysis);
    }

    private List<PublishScheduleCalculator.Window> parseWindows(String publishWindowsJson) {
        if (publishWindowsJson == null || publishWindowsJson.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, String>> raw = objectMapper.readValue(publishWindowsJson,
                    new TypeReference<List<Map<String, String>>>() {
                    });
            List<PublishScheduleCalculator.Window> windows = new ArrayList<>();
            for (Map<String, String> w : raw) {
                windows.add(new PublishScheduleCalculator.Window(
                        java.time.LocalTime.parse(w.get("start")), java.time.LocalTime.parse(w.get("end"))));
            }
            return windows;
        } catch (JsonProcessingException e) {
            log.warn("persona.publish_windows 파싱 실패, 윈도우 없음으로 처리합니다: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private Store loadOwnedStore(AppUser owner, Long storeId) {
        Store store = storeRepository.findById(storeId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!store.getOwnerId().equals(owner.getId())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return store;
    }

    private ReplyDraft loadOwnedDraft(AppUser owner, Long draftId) {
        ReplyDraft draft = replyDraftRepository.findById(draftId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        loadOwnedStore(owner, draft.getStoreId());
        return draft;
    }

    private AppUser resolveUser(UUID publicId) {
        return appUserRepository.findByPublicId(publicId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }
}
