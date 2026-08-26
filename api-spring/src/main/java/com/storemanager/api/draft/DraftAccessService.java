package com.storemanager.api.draft;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림톡 링크 전용 초안 접근 (2026-08-27 신설).
 *
 * <p>로그인 없이 <b>초안 하나</b>를 보고 승인·거절한다. 세션을 만들지 않으므로
 * 다른 화면으로 넘어갈 수단 자체가 없다 — 화면은 매 호출마다 토큰을 들고 온다.
 *
 * <p><b>★ 이 경로는 인증 없이 열려 있다.</b> 그래서 지켜야 할 것이 네 가지다.
 * <ul>
 *   <li>토큰은 256비트 난수이고, DB 에는 SHA-256 해시만 둔다
 *   <li>유효기간 24시간
 *   <li>승인·거절은 1회 (조회는 여러 번 — 실수로 창을 닫았다고 링크가 죽으면 더 나쁘다)
 *   <li>범위는 발급 대상 초안 하나뿐
 * </ul>
 *
 * <p><b>★ 상태를 바꾸는 동작을 GET 으로 열지 말 것.</b> 카카오톡은 링크 미리보기를 만들려고
 * URL 을 미리 긁는다. 승인이 GET 이면 사장님이 누르기도 전에 자동 승인된다.
 * 조회만 GET, 승인·거절은 POST 다.
 */
@Service
public class DraftAccessService {

    /** 링크 유효기간. 하루가 지나도록 안 봤다면 브리핑과 화면에서 다시 만난다. */
    static final Duration TTL = Duration.ofHours(24);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DraftAccessTokenRepository tokenRepository;
    private final ReplyDraftRepository replyDraftRepository;
    private final UnifiedReviewRepository unifiedReviewRepository;
    private final ReviewAnalysisRepository reviewAnalysisRepository;
    private final StoreRepository storeRepository;
    private final AuditLogRepository auditLogRepository;

    public DraftAccessService(DraftAccessTokenRepository tokenRepository,
            ReplyDraftRepository replyDraftRepository, UnifiedReviewRepository unifiedReviewRepository,
            ReviewAnalysisRepository reviewAnalysisRepository, StoreRepository storeRepository,
            AuditLogRepository auditLogRepository) {
        this.tokenRepository = tokenRepository;
        this.replyDraftRepository = replyDraftRepository;
        this.unifiedReviewRepository = unifiedReviewRepository;
        this.reviewAnalysisRepository = reviewAnalysisRepository;
        this.storeRepository = storeRepository;
        this.auditLogRepository = auditLogRepository;
    }

    /** 링크 화면이 보여 줄 것. 계정·연락처·다른 리뷰는 담지 않는다. */
    public record DraftAccessView(String storeName, String platform, Integer rating, String reviewBody,
            String draftContent, List<String> riskReasons, List<String> guardrailFlags,
            int riskLevel, boolean actionable, String status) {
    }

    /**
     * 토큰을 발급하고 <b>평문</b>을 돌려준다. 평문은 이 반환값이 유일한 사본이다 —
     * 알림톡 링크에 실은 뒤 어디에도 남기지 말 것(로그 포함).
     */
    @Transactional
    public String issue(Long draftId, Long storeId) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String plain = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokenRepository.save(DraftAccessToken.builder()
                .tokenHash(hash(plain))
                .draftId(draftId)
                .storeId(storeId)
                .expiresAt(Instant.now().plus(TTL))
                .build());
        return plain;
    }

    /** 링크 화면 조회. 만료만 검사한다 — 이미 처리한 건도 결과를 다시 볼 수 있어야 한다. */
    @Transactional(readOnly = true)
    public DraftAccessView view(String plainToken) {
        DraftAccessToken token = require(plainToken);
        if (!token.isReadable(Instant.now())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("reason", "링크가 만료되었습니다."));
        }
        return render(token);
    }

    /** 승인. 실제 판정은 {@link RiskApprovalService} 가 하고, 여기서는 토큰만 검증한다. */
    @Transactional
    public DraftAccessView approve(String plainToken, boolean riskAcknowledged, String editedContent,
            RiskApprovalService riskApprovalService) {
        DraftAccessToken token = requireActionable(plainToken);
        ReplyDraft draft = draftOf(token);
        Store store = storeOf(token);

        riskApprovalService.approveForLink(store, draft, riskAcknowledged, editedContent);
        token.markUsed();
        audit(token, "DRAFT_APPROVED_VIA_LINK");
        return render(token);
    }

    @Transactional
    public DraftAccessView reject(String plainToken, RiskApprovalService riskApprovalService) {
        DraftAccessToken token = requireActionable(plainToken);
        ReplyDraft draft = draftOf(token);

        riskApprovalService.rejectForLink(draft);
        token.markUsed();
        audit(token, "DRAFT_REJECTED_VIA_LINK");
        return render(token);
    }

    // ── 내부 ────────────────────────────────────────────────────────────

    private DraftAccessToken require(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        // ★ 404 로 통일한다. "만료됨" 과 "없음" 을 구분해 주면 토큰 추측에 단서가 된다.
        return tokenRepository.findByTokenHash(hash(plainToken))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private DraftAccessToken requireActionable(String plainToken) {
        DraftAccessToken token = require(plainToken);
        if (!token.isActionable(Instant.now())) {
            throw new ApiException(ErrorCode.INVALID_DRAFT_STATE,
                    Map.of("reason", "이미 처리했거나 만료된 링크입니다."));
        }
        return token;
    }

    private ReplyDraft draftOf(DraftAccessToken token) {
        return replyDraftRepository.findById(token.getDraftId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Store storeOf(DraftAccessToken token) {
        return storeRepository.findById(token.getStoreId())
                .filter(s -> s.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private DraftAccessView render(DraftAccessToken token) {
        ReplyDraft draft = draftOf(token);
        Store store = storeOf(token);
        UnifiedReview review = unifiedReviewRepository.findById(draft.getReviewId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        ReviewAnalysis analysis = reviewAnalysisRepository.findById(draft.getReviewId()).orElse(null);
        return new DraftAccessView(
                store.getName(),
                review.getPlatform(),
                review.getRating() == null ? null : (int) review.getRating(),
                review.getBody(),
                draft.getContent(),
                analysis == null || analysis.getRiskReasons() == null ? List.of() : List.of(analysis.getRiskReasons()),
                draft.getGuardrailFlags() == null ? List.of() : List.of(draft.getGuardrailFlags()),
                analysis == null ? 0 : analysis.getRiskLevel(),
                token.isActionable(Instant.now()) && "BLOCKED".equals(draft.getStatus()),
                draft.getStatus());
    }

    private void audit(DraftAccessToken token, String action) {
        auditLogRepository.save(AuditLog.builder()
                // ★ actorId 가 없다 — 링크를 누른 사람이 누구인지 우리는 모른다.
                //   actorType 으로 '로그인 없이 링크로 들어온 행위' 임을 남긴다.
                .actorType("LINK")
                .action(action)
                .targetType("REPLY_DRAFT")
                .targetId(token.getDraftId())
                .build());
    }

    static String hash(String plain) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", e);
        }
    }
}
