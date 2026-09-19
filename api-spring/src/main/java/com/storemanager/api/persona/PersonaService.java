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
import com.storemanager.api.common.PersonalIdentifierMasker;
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
import com.storemanager.api.store.StoreFact;
import com.storemanager.api.store.StoreFactRepository;
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
    private final StoreFactRepository storeFactRepository;
    private final AppUserRepository appUserRepository;
    private final UnifiedReviewRepository unifiedReviewRepository;
    private final StyleSampleQueryRepository styleSampleQueryRepository;
    private final ReviewAnalysisRepository reviewAnalysisRepository;
    private final AiClient aiClient;
    private final BannedWordQueryRepository bannedWordQueryRepository;
    private final ObjectMapper objectMapper;
    private final AuditLogRepository auditLogRepository;

    public PersonaService(StorePersonaRepository storePersonaRepository, StoreRepository storeRepository,
            StoreFactRepository storeFactRepository,
            AppUserRepository appUserRepository, UnifiedReviewRepository unifiedReviewRepository,
            StyleSampleQueryRepository styleSampleQueryRepository, ReviewAnalysisRepository reviewAnalysisRepository,
            AiClient aiClient, BannedWordQueryRepository bannedWordQueryRepository, ObjectMapper objectMapper,
            AuditLogRepository auditLogRepository) {
        this.storePersonaRepository = storePersonaRepository;
        this.storeRepository = storeRepository;
        this.storeFactRepository = storeFactRepository;
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
                        req.persona().openingStyle(),
                        req.persona().bannedWords() == null ? List.of() : req.persona().bannedWords(),
                        bannedWordQueryRepository.findActiveGlobal(), req.persona().lengthMin(),
                        req.persona().lengthMax(), saved.getPersonaSeed())
                : new AiClientDtos.PersonaIn(saved.getTone(), saved.isUseEmoji(), saved.getEmojiLevel(),
                        saved.getCustomerTitle(), saved.getSignature(), saved.getOpeningStyle(),
                        saved.getBannedWords() == null ? List.of() : List.of(saved.getBannedWords()),
                        bannedWordQueryRepository.findActiveGlobal(), saved.getLengthMin(), saved.getLengthMax(),
                        saved.getPersonaSeed());

        AiClientDtos.ReviewIn reviewIn = new AiClientDtos.ReviewIn(review.getRating() == null ? 0 : review.getRating(),
                review.getBody() == null ? "" : review.getBody(), parseStringList(review.getOrderedMenus()),
                review.getPlatform());
        AiClientDtos.AnalyzeAndDraftRequest aiReq = new AiClientDtos.AnalyzeAndDraftRequest(
                String.valueOf(review.getId()), String.valueOf(store.getId()), reviewIn, personaIn,
                new AiClientDtos.OptionsIn(1, null, null), List.of(), java.util.Map.of());

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
                .map(s -> new StyleSampleResponse(String.valueOf(s.getId()), s.getSampleType(), s.getReviewText(), s.getReplyText(),
                        s.getRating() == null ? null : s.getRating().intValue(), s.getSource(),
                        s.getCreatedAt() == null ? null : s.getCreatedAt().toString()))
                .toList();
        return new StyleSampleListResponse(items, result.hasNext(),
                styleSampleQueryRepository.countByStoreIdAndSource(store.getId(), "MANUAL"));
    }

    /**
     * 답글 형식을 유형별로 저장한다 — 감사·사과·기타 3슬롯, 유형당 1건.
     *
     * <p>★ 같은 유형을 다시 보내면 <b>덮어쓴다</b>. 이전에는 '최대 3건' 을 COUNT 로 세서
     * 꽉 차면 거절했고, 사장님은 고치려면 지웠다가 다시 넣어야 했다. 슬롯 구조에서는
     * 그냥 다시 적으면 된다.
     *
     * <p>★ 유형을 고정한 이유: 예전에는 감사 답글만 3개 적어 두면 불만 리뷰에도 그 문체가
     * 예시로 붙었다. 유형이 있어야 리뷰에 맞는 예시를 고를 수 있다.
     */
    @Transactional
    public StyleSampleResponse addStyleSample(UUID ownerPublicId, UUID storePublicId, StyleSampleRequest req) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        styleSampleQueryRepository.lockStore(store.getId());
        // ★ 이 코퍼스는 ai-python 이 few-shot 예시로 Anthropic 에 보낸다(ai-python/rag.py).
        //   사장님이 답글 형식에 가게 전화번호를 적어 두는 일이 실제로 있어, 적재 시점에 지운다.
        String masked = PersonalIdentifierMasker.mask(req.replyText());
        ReplyStyleSample sample = styleSampleQueryRepository
                .findManualSlot(store.getId(), req.sampleType())
                .map(existing -> {
                    existing.replaceManualText(masked);
                    return existing;
                })
                .orElseGet(() -> ReplyStyleSample.builder()
                        .storeId(store.getId()).reviewText("")
                        .replyText(masked).source("MANUAL").sampleType(req.sampleType()).build());
        sample = styleSampleQueryRepository.save(sample);
        return new StyleSampleResponse(String.valueOf(sample.getId()), sample.getSampleType(), sample.getReviewText(),
                sample.getReplyText(), null, sample.getSource(), sample.getCreatedAt().toString());
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

    // ── 매장 사실 ─────────────────────────────────────────────────────────

    /**
     * GET /stores/{storeId}/facts. 입력 가능한 항목 목록을 함께 준다(화면이 목록을 베끼지 않게).
     */
    @Transactional(readOnly = true)
    public PersonaDtos.StoreFactsResponse getFacts(UUID ownerPublicId, String storePublicId) {
        Store store = loadOwnedStore(resolveUser(ownerPublicId), UUID.fromString(storePublicId));
        List<PersonaDtos.StoreFactDto> facts = storeFactRepository.findByStoreId(store.getId()).stream()
                .map(f -> new PersonaDtos.StoreFactDto(f.getFactKey(), f.getFactText()))
                .toList();
        return new PersonaDtos.StoreFactsResponse(storePublicId, facts, StoreFact.ALLOWED_KEYS);
    }

    /**
     * PUT /stores/{storeId}/facts. 보낸 것으로 통째로 맞춘다(빈 값은 삭제).
     *
     * <p>★ 허용 키 밖의 값은 조용히 버리지 않고 <b>400 으로 거절한다.</b> 사장님이 입력한 줄 알고
     * 있는데 답글에 안 나오면 원인을 찾을 길이 없다. 키 목록은 {@link StoreFact#ALLOWED_KEYS} 가 정본이다.
     *
     * <p>★ 여기 저장되는 값은 그대로 손님에게 읽힌다. AI 가 만든 문장을 여기 넣지 말 것 —
     * 이 테이블의 존재 이유가 "사람이 확정한 사실" 이라는 것 하나다([절대 규칙] 8번).
     */
    @Transactional
    public PersonaDtos.StoreFactsResponse replaceFacts(UUID ownerPublicId, String storePublicId,
            PersonaDtos.StoreFactsRequest req) {
        Store store = loadOwnedStore(resolveUser(ownerPublicId), UUID.fromString(storePublicId));
        List<PersonaDtos.StoreFactDto> incoming = req == null || req.facts() == null ? List.of() : req.facts();

        List<String> unknown = incoming.stream()
                .map(PersonaDtos.StoreFactDto::key)
                .filter(k -> !StoreFact.ALLOWED_KEYS.contains(k))
                .toList();
        if (!unknown.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    Map.of("unknownKeys", unknown, "allowedKeys", StoreFact.ALLOWED_KEYS));
        }

        storeFactRepository.deleteAll(storeFactRepository.findByStoreId(store.getId()));
        List<StoreFact> saved = incoming.stream()
                .filter(f -> f.text() != null && !f.text().isBlank())
                .map(f -> StoreFact.builder()
                        .storeId(store.getId())
                        .factKey(f.key())
                        .factText(f.text().trim())
                        .updatedAt(java.time.Instant.now())
                        .build())
                .toList();
        storeFactRepository.saveAll(saved);
        return new PersonaDtos.StoreFactsResponse(storePublicId,
                saved.stream().map(f -> new PersonaDtos.StoreFactDto(f.getFactKey(), f.getFactText())).toList(),
                StoreFact.ALLOWED_KEYS);
    }
}
