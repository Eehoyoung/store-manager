package com.storemanager.api.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.ai.AiClient;
import com.storemanager.api.ai.AiClientDtos;
import com.storemanager.api.ai.AiClientDtos.AnalyzeAndDraftResponse;
import com.storemanager.api.ai.AiClientDtos.DraftOut;
import com.storemanager.api.ai.BannedWordQueryRepository;
import com.storemanager.api.ai.LlmUsageLog;
import com.storemanager.api.ai.LlmUsageLogRepository;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.draft.DraftDtos.DraftResponse;
import com.storemanager.api.draft.DraftDtos.GenerateDraftsRequest;
import com.storemanager.api.draft.DraftDtos.GenerateDraftsResponse;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 답글 생성·자동예약(S3, S8). docs/13 §6.
 * ★ 리뷰 본문은 AiClient 를 통해 있는 그대로 전달만 하고 여기서 생성·가공하지 않는다(절대규칙 1).
 * ★ risk_level >= 3 인 초안은 승인할 수 없다(절대규칙 3) — 여기와 PublishScheduler 양쪽에서 검증한다.
 */
@Service
public class DraftService {

    private static final Logger log = LoggerFactory.getLogger(DraftService.class);
    private static final short RISK_AUTO_BLOCK_LEVEL = 3; // CLAUDE.md 절대규칙 3
    // ★ BLOCKED 가 여기 있어야 한다. 수집은 매 폴링마다 최근 2일을 재조회하므로(데이터처리 2번),
    //   BLOCKED 를 빼면 위험 리뷰를 폴링 주기마다 다시 분석한다 — 그것도 T3(가장 비싼 모델)로.
    //   차단된 건은 다시 만들지 않는다. 풀자동화에서 재생성 UI 는 폐기됐다.
    private static final List<String> ACTIVE_REPLY_STATUSES =
            List.of("DRAFT", "SCHEDULED", "PUBLISHED", "ALREADY_REPLIED", "BLOCKED");

    private final ReplyDraftRepository replyDraftRepository;
    private final ReviewAnalysisRepository reviewAnalysisRepository;
    private final UnifiedReviewRepository unifiedReviewRepository;
    private final StoreRepository storeRepository;
    private final StorePersonaRepository storePersonaRepository;
    private final AppUserRepository appUserRepository;
    private final AiClient aiClient;
    private final BannedWordQueryRepository bannedWordQueryRepository;
    private final LlmUsageLogRepository llmUsageLogRepository;
    private final AuditLogRepository auditLogRepository;
    private final Notifier notifier;
    private final ObjectMapper objectMapper;

    public DraftService(ReplyDraftRepository replyDraftRepository, ReviewAnalysisRepository reviewAnalysisRepository,
            UnifiedReviewRepository unifiedReviewRepository, StoreRepository storeRepository,
            StorePersonaRepository storePersonaRepository, AppUserRepository appUserRepository, AiClient aiClient,
            BannedWordQueryRepository bannedWordQueryRepository, LlmUsageLogRepository llmUsageLogRepository,
            AuditLogRepository auditLogRepository, Notifier notifier, ObjectMapper objectMapper) {
        this.replyDraftRepository = replyDraftRepository;
        this.reviewAnalysisRepository = reviewAnalysisRepository;
        this.unifiedReviewRepository = unifiedReviewRepository;
        this.storeRepository = storeRepository;
        this.storePersonaRepository = storePersonaRepository;
        this.appUserRepository = appUserRepository;
        this.aiClient = aiClient;
        this.bannedWordQueryRepository = bannedWordQueryRepository;
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
    public GenerateDraftsResponse generateDrafts(UUID ownerPublicId, UUID reviewPublicId, GenerateDraftsRequest req) {
        AppUser owner = resolveUser(ownerPublicId);
        UnifiedReview review = unifiedReviewRepository.findByPublicId(reviewPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Long reviewId = review.getId();
        Store store = loadOwnedStore(owner, review.getStoreId());
        StorePersona persona = storePersonaRepository.findById(store.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (replyDraftRepository.existsByReviewIdAndStatusIn(reviewId, ACTIVE_REPLY_STATUSES)) {
            throw new ApiException(ErrorCode.INVALID_DRAFT_STATE,
                    Map.of("reviewId", reviewId, "reason", "ACTIVE_OR_COMPLETED_REPLY_EXISTS"));
        }

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

        return new GenerateDraftsResponse(saved.stream().map(d -> DraftResponse.from(d, reviewPublicId)).toList());
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────

    /** 풀자동 게시. 분석·가드레일을 통과한 risk 0~1 답글은 사람 승인 없이 예약한다. */
    private void tryAutoApprove(Store store, StorePersona persona, UnifiedReview review, ReplyDraft draft,
            AiClientDtos.AnalysisOut analysis) {
        if (analysis == null || analysis.model() == null || "stub".equalsIgnoreCase(analysis.model())) {
            draft.blockForAutomationUnavailable();
            return;
        }
        // AI 가 risk>=2 를 G8_RISK 로 차단하므로 여기까지 오는 초안은 보통 0~1이다.
        // 임계값이 바뀌어도 절대규칙 risk>=3 차단은 유지한다.
        boolean riskOk = analysis.riskLevel() < RISK_AUTO_BLOCK_LEVEL;
        boolean flagsOk = draft.getGuardrailFlags() == null || draft.getGuardrailFlags().length == 0;
        if (!riskOk || !flagsOk) {
            List<String> flags = new ArrayList<>();
            if (!riskOk) {
                flags.add("RISK_LEVEL_TOO_HIGH");
            }
            if (!flagsOk) {
                flags.addAll(List.of(draft.getGuardrailFlags()));
            }
            draft.blockForGeneration(flags);
            return;
        }
        Instant scheduledAt = PublishScheduleCalculator.compute(review.getCollectedAt(), persona.getDelayHours(),
                parseWindows(persona.getPublishWindows()));
        draft.scheduleAutomatically(scheduledAt);
        auditLogRepository.save(AuditLog.builder()
                .actorType("SYSTEM")
                .action("DRAFT_AUTO_SCHEDULED")
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
                persona.getSignature(), persona.getOpeningStyle(),
                persona.getBannedWords() == null ? List.of() : List.of(persona.getBannedWords()),
                bannedWordQueryRepository.findActiveGlobal(), persona.getLengthMin(), persona.getLengthMax(),
                persona.getPersonaSeed());
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
            // ★ X1: 403 이 아니라 404(Sprint 5 B5) — 남의 매장 storeId 존재 여부를 흘리지 않는다.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return store;
    }

    private AppUser resolveUser(UUID publicId) {
        return appUserRepository.findByPublicId(publicId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }
}
