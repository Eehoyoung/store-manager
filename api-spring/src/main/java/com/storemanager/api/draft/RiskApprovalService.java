package com.storemanager.api.draft;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StorePersona;
import com.storemanager.api.store.StorePersonaRepository;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.store.StoreServiceGate;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 위험 리뷰 초안의 사람 승인 (2026-08-27 신설).
 *
 * <p>배경: 위험 리뷰는 자동 게시하지 않는다(절대규칙 3). 예전에는 거기서 끝이라 사장님이
 * 식중독 리뷰 앞에서 백지를 마주했다. 지금은 <b>권장 답글을 만들어 두고</b> 사람이
 * 승인하거나 고쳐서 게시한다.
 *
 * <p><b>★ 절대규칙 3 은 그대로다.</b> 규칙은 "자동 게시 금지 / 사람 검수 큐로 보낸다" 이지
 * "게시 금지" 가 아니었다. 사람이 사유를 읽고 판단하는 것이 곧 검수다. 자동 경로
 * ({@code DraftService.tryAutoApprove})는 여전히 {@code risk>=3} 을 통과시키지 않는다.
 *
 * <p><b>★ 승인은 위험 사유로만 막힌 초안에만 연다.</b> 가드레일이 잡은 건
 * (금전 보상 약속·개인정보·금칙어) 사람이 승인해도 게시하지 않는다. 그건 상황이 민감한 게
 * 아니라 <b>내용 자체가 규칙을 어긴 것</b>이고, 절대규칙 4 를 사람 승인으로 우회하는 통로를
 * 만들면 규칙이 규칙이 아니게 된다. {@link #APPROVABLE_FLAG} 하나만 달린 초안이 아니면 거부한다.
 */
@Service
public class RiskApprovalService {

    /**
     * 승인 가능한 유일한 차단 사유.
     *
     * <p>★ 이 목록을 늘리지 말 것. 늘리는 순간 "사람이 승인하면 가드레일을 넘을 수 있다" 가
     * 된다. 새 차단 사유를 승인 대상으로 만들고 싶다면, 그것이 <b>내용 문제인지 상황 문제인지</b>
     * 부터 판정하라. 내용 문제면 답은 승인이 아니라 재생성이다.
     */
    static final String APPROVABLE_FLAG = "RISK_LEVEL_TOO_HIGH";

    private final ReplyDraftRepository replyDraftRepository;
    private final UnifiedReviewRepository unifiedReviewRepository;
    private final ReviewAnalysisRepository reviewAnalysisRepository;
    private final StoreRepository storeRepository;
    private final StorePersonaRepository storePersonaRepository;
    private final AppUserRepository appUserRepository;
    private final AuditLogRepository auditLogRepository;
    private final StoreServiceGate serviceGate;

    public RiskApprovalService(ReplyDraftRepository replyDraftRepository,
            UnifiedReviewRepository unifiedReviewRepository, ReviewAnalysisRepository reviewAnalysisRepository,
            StoreRepository storeRepository, StorePersonaRepository storePersonaRepository,
            AppUserRepository appUserRepository, AuditLogRepository auditLogRepository, StoreServiceGate serviceGate) {
        this.replyDraftRepository = replyDraftRepository;
        this.unifiedReviewRepository = unifiedReviewRepository;
        this.reviewAnalysisRepository = reviewAnalysisRepository;
        this.storeRepository = storeRepository;
        this.storePersonaRepository = storePersonaRepository;
        this.appUserRepository = appUserRepository;
        this.auditLogRepository = auditLogRepository;
        this.serviceGate = serviceGate;
    }

    /**
     * 위험 초안을 승인해 게시 예약한다.
     *
     * @param riskAcknowledged 차단 사유를 확인했다는 표시. <b>false 면 거부한다.</b>
     *        화면의 체크박스가 이 값을 보낸다. 서버가 직접 검사하는 이유는, 화면을 거치지 않고
     *        API 를 부르면 체크박스가 아무 의미도 없기 때문이다.
     * @param editedContent 사람이 고친 본문. null 이면 AI 초안을 그대로 쓴다.
     */
    @Transactional
    public DraftDtos.DraftResponse approve(UUID ownerPublicId, UUID draftPublicId, boolean riskAcknowledged,
            String editedContent) {
        AppUser owner = resolveUser(ownerPublicId);
        ReplyDraft draft = replyDraftRepository.findByPublicId(draftPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Store store = loadOwnedStore(owner, draft.getStoreId());

        if (!serviceGate.isServiceable(store)) {
            throw new ApiException(ErrorCode.SUBSCRIPTION_INACTIVE);
        }
        requireApprovable(draft);

        // ★ 서버에서 막는다. 화면만 믿으면 API 직접 호출로 우회된다.
        if (!riskAcknowledged) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    Map.of("riskAcknowledged", "차단 사유를 확인해야 게시할 수 있습니다."));
        }

        UnifiedReview review = unifiedReviewRepository.findById(draft.getReviewId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        StorePersona persona = storePersonaRepository.findById(store.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));

        String content = editedContent == null || editedContent.isBlank() ? null : editedContent.trim();
        if (content != null && content.length() > 280) {
            // 플랫폼 300자, 이모지 여유 — CLAUDE.md 데이터처리 8번
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("content", "답글은 280자를 넘을 수 없습니다."));
        }

        Instant scheduledAt = PublishScheduleCalculator.compute(review.getCollectedAt(), persona.getDelayHours(),
                PublishScheduleCalculator.parseWindows(persona.getPublishWindows()));
        draft.approveByHuman(owner.getId(), Instant.now(), content, scheduledAt);
        replyDraftRepository.save(draft);

        auditLogRepository.save(AuditLog.builder()
                .actorId(owner.getId())
                .actorType("OWNER")
                .action("DRAFT_RISK_APPROVED")
                .targetType("REPLY_DRAFT")
                .targetId(draft.getId())
                .build());
        return DraftDtos.DraftResponse.from(draft, review.getPublicId());
    }

    /** 게시하지 않기로 한다. BLOCKED 로 남되 누가 판단했는지 기록한다. */
    @Transactional
    public DraftDtos.DraftResponse reject(UUID ownerPublicId, UUID draftPublicId) {
        AppUser owner = resolveUser(ownerPublicId);
        ReplyDraft draft = replyDraftRepository.findByPublicId(draftPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        loadOwnedStore(owner, draft.getStoreId());
        requireApprovable(draft);

        draft.rejectByHuman(owner.getId());
        replyDraftRepository.save(draft);

        auditLogRepository.save(AuditLog.builder()
                .actorId(owner.getId())
                .actorType("OWNER")
                .action("DRAFT_RISK_REJECTED")
                .targetType("REPLY_DRAFT")
                .targetId(draft.getId())
                .build());
        UnifiedReview review = unifiedReviewRepository.findById(draft.getReviewId()).orElse(null);
        return DraftDtos.DraftResponse.from(draft, review == null ? null : review.getPublicId());
    }

    /**
     * 이 초안이 사람 승인 대상인가.
     *
     * <p>★ 여기가 이 클래스의 전부다. 조건을 느슨하게 만들면 가드레일이 무력해진다.
     */
    private void requireApprovable(ReplyDraft draft) {
        if (!"BLOCKED".equals(draft.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_DRAFT_STATE,
                    Map.of("currentStatus", draft.getStatus(), "reason", "BLOCKED 초안만 승인할 수 있습니다."));
        }
        String[] flags = draft.getGuardrailFlags() == null ? new String[0] : draft.getGuardrailFlags();
        boolean onlyRisk = flags.length == 1 && APPROVABLE_FLAG.equals(flags[0]);
        if (!onlyRisk) {
            // 가드레일·모델 부재·생성 실패로 막힌 건은 사람이 승인해도 게시하지 않는다.
            throw new ApiException(ErrorCode.GUARDRAIL_BLOCKED,
                    Map.of("flags", List.of(flags),
                            "reason", "위험도 외의 사유로 차단된 초안은 승인할 수 없습니다."));
        }
        if (draft.getContent() == null || draft.getContent().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_DRAFT_STATE,
                    Map.of("reason", "내용이 없는 초안은 승인할 수 없습니다."));
        }
        // 분석 기록이 없으면 위험도를 모른다는 뜻이다 — 모르는 것은 안전하다는 뜻이 아니다.
        ReviewAnalysis analysis = reviewAnalysisRepository.findById(draft.getReviewId()).orElse(null);
        if (analysis == null) {
            throw new ApiException(ErrorCode.INVALID_DRAFT_STATE,
                    Map.of("reason", "분석 기록이 없어 승인할 수 없습니다."));
        }
    }

    private AppUser resolveUser(UUID publicId) {
        return appUserRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    private Store loadOwnedStore(AppUser owner, Long storeId) {
        Store store = storeRepository.findById(storeId)
                .filter(s -> s.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!store.getOwnerId().equals(owner.getId())) {
            // ★ 403 이 아니라 404 — 남의 매장 초안이 존재하는지 흘리지 않는다.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return store;
    }
}
