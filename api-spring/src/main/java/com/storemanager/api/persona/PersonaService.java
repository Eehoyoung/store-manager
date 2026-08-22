package com.storemanager.api.persona;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.ai.AiClient;
import com.storemanager.api.ai.AiClientDtos;
import com.storemanager.api.ai.BannedWordQueryRepository;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.draft.ReviewAnalysis;
import com.storemanager.api.draft.ReviewAnalysisRepository;
import com.storemanager.api.persona.PersonaDtos.PersonaRequest;
import com.storemanager.api.persona.PersonaDtos.PersonaResponse;
import com.storemanager.api.persona.PersonaDtos.PreviewRequest;
import com.storemanager.api.persona.PersonaDtos.PreviewResponse;
import com.storemanager.api.persona.PersonaDtos.StyleSampleListResponse;
import com.storemanager.api.persona.PersonaDtos.StyleSampleRequest;
import com.storemanager.api.persona.PersonaDtos.StyleSampleResponse;
import com.storemanager.api.persona.PersonaDtos.WindowDto;
import com.storemanager.api.review.ReplyStyleSample;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StorePersona;
import com.storemanager.api.store.StorePersonaRepository;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
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
 * 페르소나 조회/수정/미리보기, 말투 학습 샘플 목록 (docs/13 §7, Sprint 5 P1~P4).
 * ★ 절대규칙 3: 미리보기는 AI 가 돌려준 riskLevel>=3 이면 내용을 만들지 않고 422 를 던진다.
 * ★ 절대규칙 1: 미리보기는 reply_draft/review_analysis 어디에도 쓰지 않는다 — 순수 조회다.
 */
@Service
public class PersonaService {

    private static final Logger log = LoggerFactory.getLogger(PersonaService.class);
    private static final short RISK_AUTO_BLOCK_LEVEL = 3; // CLAUDE.md 절대규칙 3

    private final StorePersonaRepository storePersonaRepository;
    private final StoreRepository storeRepository;
    private final AppUserRepository appUserRepository;
    private final UnifiedReviewRepository unifiedReviewRepository;
    private final StyleSampleQueryRepository styleSampleQueryRepository;
    private final ReviewAnalysisRepository reviewAnalysisRepository;
    private final AiClient aiClient;
    private final BannedWordQueryRepository bannedWordQueryRepository;
    private final ObjectMapper objectMapper;
    private final AuditLogRepository auditLogRepository;

    public PersonaService(StorePersonaRepository storePersonaRepository, StoreRepository storeRepository,
            AppUserRepository appUserRepository, UnifiedReviewRepository unifiedReviewRepository,
            StyleSampleQueryRepository styleSampleQueryRepository, ReviewAnalysisRepository reviewAnalysisRepository,
            AiClient aiClient, BannedWordQueryRepository bannedWordQueryRepository, ObjectMapper objectMapper,
            AuditLogRepository auditLogRepository) {
        this.storePersonaRepository = storePersonaRepository;
        this.storeRepository = storeRepository;
        this.appUserRepository = appUserRepository;
        this.unifiedReviewRepository = unifiedReviewRepository;
        this.styleSampleQueryRepository = styleSampleQueryRepository;
        this.reviewAnalysisRepository = reviewAnalysisRepository;
        this.aiClient = aiClient;
        this.bannedWordQueryRepository = bannedWordQueryRepository;
        this.objectMapper = objectMapper;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public PersonaResponse getPersona(UUID ownerPublicId, UUID storePublicId) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        StorePersona persona = loadPersona(store.getId());
        return toResponse(persona);
    }

    /** PUT /stores/{storeId}/persona (P1, P2) — 전체 필드 교체. */
    @Transactional
    public PersonaResponse updatePersona(UUID ownerPublicId, UUID storePublicId, PersonaRequest req) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        StorePersona persona = loadPersona(store.getId());

        validateCrossFields(req);
        persona.applyUpdate(req.tone(), req.useEmoji(), req.emojiLevel(), req.customerTitle(), req.signature(),
                req.openingStyle(), toArray(req.bannedWords()), req.lengthMin(), req.lengthMax(), req.delayHours(),
                writeWindowsJson(req.publishWindows()));
        return toResponse(persona);
    }

    /**
     * POST /stores/{storeId}/persona/preview (P3). 저장하지 않는다.
     * ★ AI 가 돌려준 분석의 riskLevel>=3 이면 내용을 응답에 담지 않고 422 RISK_LEVEL_TOO_HIGH.
     */
    @Transactional(readOnly = true)
    public PreviewResponse preview(UUID ownerPublicId, UUID storePublicId, PreviewRequest req) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        UnifiedReview review = loadOwnedReview(store, req.reviewId());
        StorePersona saved = loadPersona(store.getId());

        if (req.persona() != null) {
            validateCrossFields(req.persona());
        }

        // ★ 절대규칙 3 은 저장된 분석으로 먼저 판정한다. AI 재호출 결과만 믿으면
        // 분류가 비결정적인 탓에 이미 risk_level=3 으로 확정된 리뷰가 이번 호출에서만
        // 낮게 나와 미리보기가 통과할 수 있다(fail-open). 아는 위험은 호출 전에 막는다.
        // 부수 효과로 차단 대상 리뷰에 LLM 비용을 쓰지 않게 된다.
        ReviewAnalysis stored = reviewAnalysisRepository.findById(review.getId()).orElse(null);
        if (stored != null && stored.getRiskLevel() >= RISK_AUTO_BLOCK_LEVEL) {
            throw new ApiException(ErrorCode.RISK_LEVEL_TOO_HIGH,
                    Map.of("riskLevel", stored.getRiskLevel(),
                            "riskReasons", stored.getRiskReasons() == null ? List.of() : List.of(stored.getRiskReasons())));
        }
        AiClientDtos.PersonaIn personaIn = req.persona() != null
                ? new AiClientDtos.PersonaIn(req.persona().tone(), req.persona().useEmoji(),
                        req.persona().emojiLevel(), req.persona().customerTitle(), req.persona().signature(),
                        req.persona().bannedWords() == null ? List.of() : req.persona().bannedWords(),
                        bannedWordQueryRepository.findActiveGlobal(), req.persona().lengthMin(),
                        req.persona().lengthMax(), saved.getPersonaSeed())
                : new AiClientDtos.PersonaIn(saved.getTone(), saved.isUseEmoji(), saved.getEmojiLevel(),
                        saved.getCustomerTitle(), saved.getSignature(),
                        saved.getBannedWords() == null ? List.of() : List.of(saved.getBannedWords()),
                        bannedWordQueryRepository.findActiveGlobal(), saved.getLengthMin(), saved.getLengthMax(),
                        saved.getPersonaSeed());

        AiClientDtos.ReviewIn reviewIn = new AiClientDtos.ReviewIn(review.getRating() == null ? 0 : review.getRating(),
                review.getBody() == null ? "" : review.getBody(), parseStringList(review.getOrderedMenus()),
                review.getPlatform());
        AiClientDtos.AnalyzeAndDraftRequest aiReq = new AiClientDtos.AnalyzeAndDraftRequest(
                String.valueOf(review.getId()), String.valueOf(store.getId()), reviewIn, personaIn,
                new AiClientDtos.OptionsIn(1, null, null));

        AiClientDtos.AnalyzeAndDraftResponse aiRes = aiClient.analyzeAndDraft(aiReq);

        if (aiRes.analysis() != null && aiRes.analysis().riskLevel() >= RISK_AUTO_BLOCK_LEVEL) {
            throw new ApiException(ErrorCode.RISK_LEVEL_TOO_HIGH,
                    Map.of("riskLevel", aiRes.analysis().riskLevel(),
                            "riskReasons", aiRes.analysis().riskReasons() == null ? List.of() : aiRes.analysis().riskReasons()));
        }
        if (aiRes.blocked() || aiRes.drafts() == null || aiRes.drafts().isEmpty()) {
            throw new ApiException(ErrorCode.GUARDRAIL_BLOCKED,
                    Map.of("flags", aiRes.blockReasons() == null ? List.of() : aiRes.blockReasons()));
        }
        AiClientDtos.DraftOut d = aiRes.drafts().get(0);
        return new PreviewResponse(d.content(), d.tier(), d.model(), d.promptVersion(),
                d.guardrailFlags() == null ? List.of() : d.guardrailFlags());
    }

    /** GET /stores/{storeId}/persona/style-samples (P4). */
    @Transactional(readOnly = true)
    public StyleSampleListResponse listStyleSamples(UUID ownerPublicId, UUID storePublicId, int page, int size) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        Page<ReplyStyleSample> result = styleSampleQueryRepository.findByStoreId(store.getId(), PageRequest.of(page, size));
        List<StyleSampleResponse> items = result.getContent().stream()
                .map(s -> new StyleSampleResponse(String.valueOf(s.getId()), s.getReviewText(), s.getReplyText(),
                        s.getRating() == null ? null : s.getRating().intValue(), s.getSource(),
                        s.getCreatedAt() == null ? null : s.getCreatedAt().toString()))
                .toList();
        return new StyleSampleListResponse(items, result.hasNext(),
                styleSampleQueryRepository.countByStoreIdAndSource(store.getId(), "MANUAL"));
    }

    /** 가맹점주가 직접 등록하는 답글 형식은 매장당 최대 3건이다. */
    @Transactional
    public StyleSampleResponse addStyleSample(UUID ownerPublicId, UUID storePublicId, StyleSampleRequest req) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        styleSampleQueryRepository.lockStore(store.getId());
        if (styleSampleQueryRepository.countByStoreIdAndSource(store.getId(), "MANUAL") >= 3) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("styleSamples", "답글 형식은 최대 3건까지 등록할 수 있습니다."));
        }
        ReplyStyleSample sample = styleSampleQueryRepository.save(ReplyStyleSample.builder()
                .storeId(store.getId()).reviewText("").replyText(req.replyText()).source("MANUAL").build());
        return new StyleSampleResponse(String.valueOf(sample.getId()), sample.getReviewText(), sample.getReplyText(),
                null, sample.getSource(), sample.getCreatedAt().toString());
    }

    /**
     * DELETE /stores/{storeId}/persona/style-samples/{sampleId} (B4, docs/13 §7).
     * ★ reply_style_sample 은 말투 학습 RAG 코퍼스이자 핵심 자산이다(CLAUDE.md 데이터처리 6번) — 삭제는 감사로그를 남긴다.
     */
    @Transactional
    public void deleteStyleSample(UUID ownerPublicId, UUID storePublicId, Long sampleId) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        ReplyStyleSample sample = styleSampleQueryRepository.findById(sampleId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!sample.getStoreId().equals(store.getId())) {
            // ★ X1: 403 이 아니라 404 — 존재 여부를 흘리지 않는다.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        styleSampleQueryRepository.delete(sample);
        auditLogRepository.save(AuditLog.builder()
                .actorId(owner.getId())
                .actorType("USER")
                .action("STYLE_SAMPLE_DELETED")
                .targetType("REPLY_STYLE_SAMPLE")
                .targetId(sample.getId())
                .build());
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────

    private void validateCrossFields(PersonaRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (req.lengthMin() != null && req.lengthMax() != null && req.lengthMin() > req.lengthMax()) {
            fields.put("lengthMin", "lengthMin은 lengthMax 이하여야 합니다.");
        }
        if (req.publishWindows() != null) {
            for (int i = 0; i < req.publishWindows().size(); i++) {
                WindowDto w = req.publishWindows().get(i);
                try {
                    if (!LocalTime.parse(w.start()).isBefore(LocalTime.parse(w.end()))) {
                        fields.put("publishWindows[" + i + "]", "start는 end보다 이전이어야 합니다.");
                    }
                } catch (DateTimeParseException e) {
                    fields.put("publishWindows[" + i + "]", "HH:mm 형식이어야 합니다.");
                }
            }
        }
        if (!fields.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", fields));
        }
    }

    private StorePersona loadPersona(Long storeId) {
        return storePersonaRepository.findById(storeId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private UnifiedReview loadOwnedReview(Store store, String reviewIdStr) {
        UUID reviewId;
        try {
            reviewId = UUID.fromString(reviewIdStr);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        UnifiedReview review = unifiedReviewRepository.findByPublicId(reviewId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!review.getStoreId().equals(store.getId())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return review;
    }

    private PersonaResponse toResponse(StorePersona p) {
        return new PersonaResponse(String.valueOf(p.getStoreId()), p.getTone(), p.isUseEmoji(), p.getEmojiLevel(),
                p.getCustomerTitle(), p.getSignature(), p.getOpeningStyle(),
                p.getBannedWords() == null ? List.of() : List.of(p.getBannedWords()), p.getLengthMin(),
                p.getLengthMax(), p.getDelayHours(),
                readWindows(p.getPublishWindows()), p.getPersonaSeed(),
                p.getUpdatedAt() == null ? null : p.getUpdatedAt().toString());
    }

    private String[] toArray(List<String> list) {
        return list == null ? new String[0] : list.toArray(new String[0]);
    }

    private String writeWindowsJson(List<WindowDto> windows) {
        try {
            return objectMapper.writeValueAsString(windows == null ? List.of() : windows);
        } catch (JsonProcessingException e) {
            // @Pattern/@Valid 로 이미 형식을 검증한 뒤라 여기서 실패할 일은 없다 — 방어적 폴백.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", Map.of("publishWindows", "직렬화 실패")));
        }
    }

    private List<WindowDto> readWindows(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<WindowDto>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("persona.publish_windows 파싱 실패: {}", e.getMessage());
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
            return new ArrayList<>();
        }
    }

    private Store loadOwnedStore(AppUser owner, UUID storePublicId) {
        Store store = storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!store.getOwnerId().equals(owner.getId())) {
            // ★ X1: 403 이 아니라 404.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return store;
    }

    private AppUser resolveUser(UUID publicId) {
        return appUserRepository.findByPublicId(publicId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }
}
