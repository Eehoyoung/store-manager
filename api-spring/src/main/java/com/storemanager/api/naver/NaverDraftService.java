package com.storemanager.api.naver;

import com.storemanager.api.ai.AiClient;
import com.storemanager.api.ai.AiClientDtos;
import com.storemanager.api.ai.AiClientDtos.AnalyzeAndDraftResponse;
import com.storemanager.api.ai.AiClientDtos.DraftOut;
import com.storemanager.api.ai.BannedWordQueryRepository;
import com.storemanager.api.ai.LlmUsageLog;
import com.storemanager.api.ai.LlmUsageLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.draft.PersonalIdentifierMasker;
import com.storemanager.api.draft.ReplyDraftRepository;
import com.storemanager.api.naver.NaverDtos.DraftRequest;
import com.storemanager.api.naver.NaverDtos.DraftResponse;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StorePersona;
import com.storemanager.api.store.StorePersonaRepository;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.store.StoreServiceGate;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 네이버 리뷰 초안 생성 (IMPLEMENTATION_PLAN_NAVER.md §2.3, §6).
 *
 * <p>★ ai-python 파이프라인은 배달 3사와 완전히 동일하게 재사용한다. platform="NAVER" 문자열만
 * 다르고 나머지(분류·risk 룰·T0~T3 라우팅·RAG·가드레일)는 무변경이다({@code AiClientDtos.ReviewIn.platform} 이
 * 자유 문자열이라 가능하다).
 *
 * <p>★ {@code ReplyDraft}·{@code UnifiedReview} 는 절대 만들지 않는다. 그 순간 워커의 게시 큐
 * (PublishScheduler → worker/publish.py)에 진입해 서버가 네이버에 직접 요청하는 경로가 열린다 —
 * 이는 docs/naver/03-compliance.md 절대 규칙 1 위반이다.
 */
@Service
public class NaverDraftService {

    private static final Logger log = LoggerFactory.getLogger(NaverDraftService.class);
    private static final short RISK_BULK_BLOCK_LEVEL = 2; // docs/naver/03 §Tier 1 성립조건 3번

    private final NaverReviewEventRepository naverReviewEventRepository;
    private final StoreRepository storeRepository;
    private final StorePersonaRepository storePersonaRepository;
    private final AppUserRepository appUserRepository;
    private final AiClient aiClient;
    private final BannedWordQueryRepository bannedWordQueryRepository;
    private final LlmUsageLogRepository llmUsageLogRepository;
    private final ReplyDraftRepository replyDraftRepository;
    private final StoreServiceGate serviceGate;

    public NaverDraftService(NaverReviewEventRepository naverReviewEventRepository, StoreRepository storeRepository,
            StorePersonaRepository storePersonaRepository, AppUserRepository appUserRepository, AiClient aiClient,
            BannedWordQueryRepository bannedWordQueryRepository, LlmUsageLogRepository llmUsageLogRepository,
            ReplyDraftRepository replyDraftRepository, StoreServiceGate serviceGate) {
        this.naverReviewEventRepository = naverReviewEventRepository;
        this.storeRepository = storeRepository;
        this.storePersonaRepository = storePersonaRepository;
        this.appUserRepository = appUserRepository;
        this.aiClient = aiClient;
        this.bannedWordQueryRepository = bannedWordQueryRepository;
        this.llmUsageLogRepository = llmUsageLogRepository;
        this.replyDraftRepository = replyDraftRepository;
        this.serviceGate = serviceGate;
    }

    @Transactional
    public DraftResponse generateDraft(UUID ownerPublicId, DraftRequest req) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, req.storeId());
        StorePersona persona = storePersonaRepository.findById(store.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!serviceGate.isServiceable(store)) {
            throw new ApiException(ErrorCode.SUBSCRIPTION_INACTIVE);
        }

        NaverReviewEvent existing = naverReviewEventRepository
                .findByStoreIdAndReviewHash(store.getId(), req.reviewHash()).orElse(null);
        if (existing != null && ("APPROVED".equals(existing.getStatus()) || "POSTED".equals(existing.getStatus()))) {
            // 이미 사람이 확정했거나 게시된 건 — 재호출하지 않고 그대로 돌려준다(불필요한 LLM 비용 방지).
            return toResponse(existing);
        }

        AiClientDtos.AnalyzeAndDraftRequest aiReq = buildAiRequest(store, persona, req);
        AnalyzeAndDraftResponse aiRes = aiClient.analyzeAndDraft(aiReq);

        String draftContent = null;
        List<String> guardrailFlags = List.of();
        if (!aiRes.drafts().isEmpty()) {
            DraftOut d = aiRes.drafts().get(0);
            draftContent = d.content();
            guardrailFlags = d.guardrailFlags() == null ? List.of() : d.guardrailFlags();
            llmUsageLogRepository.save(LlmUsageLog.builder()
                    .storeId(store.getId())
                    .purpose("GENERATE_NAVER")
                    .tier(d.tier())
                    .model(d.model())
                    .tokenIn(d.tokenIn())
                    .tokenOut(d.tokenOut())
                    .costKrw(BigDecimal.valueOf(d.costKrw()))
                    .build());
        }
        if (aiRes.blocked() && aiRes.blockReasons() != null && !aiRes.blockReasons().isEmpty()) {
            guardrailFlags = aiRes.blockReasons();
        }

        Short rating = req.rating() == null ? null : req.rating().shortValue();
        short riskLevel = aiRes.analysis() == null ? 0 : (short) aiRes.analysis().riskLevel();
        String category = aiRes.analysis() == null ? null : aiRes.analysis().category();
        String[] flagsArray = guardrailFlags.toArray(new String[0]);

        NaverReviewEvent event;
        if (existing == null) {
            event = NaverReviewEvent.builder()
                    .storeId(store.getId())
                    .reviewHash(req.reviewHash())
                    .rating(rating)
                    .category(category)
                    .riskLevel(riskLevel)
                    .draftContent(draftContent)
                    .guardrailFlags(flagsArray)
                    .blocked(aiRes.blocked())
                    .draftedAt(Instant.now())
                    .build();
        } else {
            existing.refreshDraft(rating, category, riskLevel, draftContent, flagsArray, aiRes.blocked());
            event = existing;
        }
        naverReviewEventRepository.save(event);

        return toResponse(event);
    }

    private DraftResponse toResponse(NaverReviewEvent event) {
        boolean bulkApprovable = event.getRating() != null && event.getRating() >= 3 && !event.isBlocked()
                && event.getRiskLevel() < RISK_BULK_BLOCK_LEVEL;
        List<String> flags = event.getGuardrailFlags() == null ? List.of() : List.of(event.getGuardrailFlags());
        return new DraftResponse(event.getReviewHash(), event.getStatus(), event.getDraftContent(),
                event.isBlocked(), flags, event.getRiskLevel(), event.getCategory(), bulkApprovable);
    }

    private AiClientDtos.AnalyzeAndDraftRequest buildAiRequest(Store store, StorePersona persona, DraftRequest req) {
        // ★ 2차 방어 — 확장이 브라우저 로컬에서 1차 마스킹을 했더라도 여기서 다시 마스킹한다.
        // 신뢰 경계를 넘어온 입력은 항상 다시 검증한다(DraftService.buildAiRequest 와 동일한 이유).
        AiClientDtos.ReviewIn reviewIn = new AiClientDtos.ReviewIn(
                req.rating() == null ? 0 : req.rating(),
                PersonalIdentifierMasker.mask(req.body() == null ? "" : req.body()),
                List.of(),
                "NAVER");
        AiClientDtos.PersonaIn personaIn = new AiClientDtos.PersonaIn(
                persona.getTone(), persona.isUseEmoji(), persona.getEmojiLevel(), persona.getCustomerTitle(),
                PersonalIdentifierMasker.mask(persona.getSignature()),
                PersonalIdentifierMasker.mask(persona.getOpeningStyle()),
                persona.getBannedWords() == null ? List.of() : List.of(persona.getBannedWords()),
                bannedWordQueryRepository.findActiveGlobal(), persona.getLengthMin(), persona.getLengthMax(),
                persona.getPersonaSeed());
        AiClientDtos.OptionsIn optionsIn = new AiClientDtos.OptionsIn(1, null, null);
        // reviewId 는 내부 식별자가 없으므로 review_hash 앞 16자로 대체한다(ai-python 은 로그 상관용으로만 쓴다).
        String reviewIdForAi = req.reviewHash().length() > 16 ? req.reviewHash().substring(0, 16) : req.reviewHash();
        return new AiClientDtos.AnalyzeAndDraftRequest(reviewIdForAi, String.valueOf(store.getId()), reviewIn,
                personaIn, optionsIn, recentReplies(store.getId()));
    }

    private List<String> recentReplies(Long storeId) {
        try {
            return replyDraftRepository.findRecentPublishedContents(
                    storeId, Instant.now().minus(30, ChronoUnit.DAYS), PageRequest.of(0, 20));
        } catch (RuntimeException e) {
            log.warn("최근 게시 답글 조회 실패 storeId={} error={}", storeId, e.getClass().getSimpleName());
            return List.of();
        }
    }

    private Store loadOwnedStore(AppUser owner, String storePublicId) {
        UUID publicId = parseUuid(storePublicId);
        Store store = storeRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!store.getOwnerId().equals(owner.getId())) {
            // ★ 403 이 아니라 404 — 남의 매장 storeId 존재 여부를 흘리지 않는다(기존 관례와 동일).
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return store;
    }

    static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private AppUser resolveUser(UUID publicId) {
        return appUserRepository.findByPublicId(publicId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }
}
