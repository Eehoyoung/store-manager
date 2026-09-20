package com.storemanager.api.draft;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.common.PersonalIdentifierMasker;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.storemanager.api.review.ReplyStyleSample;
import com.storemanager.api.review.ReplyStyleSampleRepository;

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
    private final ReplyStyleSampleRepository replyStyleSampleRepository;
    private final StringRedisTemplate stringRedisTemplate;

    public RiskApprovalService(ReplyDraftRepository replyDraftRepository,
            UnifiedReviewRepository unifiedReviewRepository, ReviewAnalysisRepository reviewAnalysisRepository,
            StoreRepository storeRepository, StorePersonaRepository storePersonaRepository,
            AppUserRepository appUserRepository, AuditLogRepository auditLogRepository, StoreServiceGate serviceGate,
            ReplyStyleSampleRepository replyStyleSampleRepository, StringRedisTemplate stringRedisTemplate) {
        this.replyDraftRepository = replyDraftRepository;
        this.unifiedReviewRepository = unifiedReviewRepository;
        this.reviewAnalysisRepository = reviewAnalysisRepository;
        this.storeRepository = storeRepository;
        this.storePersonaRepository = storePersonaRepository;
        this.appUserRepository = appUserRepository;
        this.auditLogRepository = auditLogRepository;
        this.serviceGate = serviceGate;
        this.replyStyleSampleRepository = replyStyleSampleRepository;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 예약된 답글을 게시 전에 취소한다.
     *
     * <p><b>★ 이미 워커로 넘어간 건은 취소할 수 없다.</b> {@code PublishScheduler} 가 디스패치할 때
     * {@code dispatch:draft:{id}} 키를 잡는다. 그 키가 살아 있으면 게시 잡이 이미 나간 것이고,
     * 여기서 상태만 BLOCKED 로 바꿔 봐야 <b>답글은 그대로 게시된다.</b> 그러면 화면은 "취소됨" 이라고
     * 하는데 플랫폼에는 답글이 달린, 가장 나쁜 상태가 된다. 차라리 취소를 거절하고 사실대로 말한다.
     *
     * <p>키 이름을 {@code PublishScheduler} 와 맞춰야 한다 — 한쪽만 바꾸면 이 방어가 조용히 꺼진다.
     */
    @Transactional
    public DraftDtos.DraftResponse cancelScheduled(UUID ownerPublicId, UUID draftPublicId) {
        AppUser owner = resolveUser(ownerPublicId);
        ReplyDraft draft = replyDraftRepository.findByPublicId(draftPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        loadOwnedStore(owner, draft.getStoreId());

        if (!"SCHEDULED".equals(draft.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_DRAFT_STATE,
                    Map.of("currentStatus", draft.getStatus(), "reason", "예약된 답글만 취소할 수 있습니다."));
        }
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(PublishScheduler.dispatchKey(draft.getId())))) {
            throw new ApiException(ErrorCode.INVALID_DRAFT_STATE,
                    Map.of("currentStatus", draft.getStatus(),
                            "reason", "이미 게시 처리가 시작되어 취소할 수 없습니다."));
        }

        draft.cancelByOwner(owner.getId());
        replyDraftRepository.save(draft);
        auditLogRepository.save(AuditLog.builder().actorId(owner.getId()).actorType("OWNER")
                .action("DRAFT_OWNER_CANCELED").targetType("REPLY_DRAFT").targetId(draft.getId()).build());

        UnifiedReview review = unifiedReviewRepository.findById(draft.getReviewId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        return DraftDtos.DraftResponse.from(draft, review.getPublicId());
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

        UnifiedReview review = doApprove(store, draft, owner.getId(), editedContent);

        auditLogRepository.save(AuditLog.builder()
                .actorId(owner.getId())
                .actorType("OWNER")
                .action("DRAFT_RISK_APPROVED")
                .targetType("REPLY_DRAFT")
                .targetId(draft.getId())
                .build());
        return DraftDtos.DraftResponse.from(draft, review.getPublicId());
    }

    /**
     * 알림톡 링크에서 들어온 승인.
     *
     * <p>★ 소유자 검증만 토큰이 대신한다({@code DraftAccessService}). 나머지 — 위험 사유
     * 단독인지, 사유를 확인했는지, 내용이 280자를 넘지 않는지 — 는 <b>로그인 경로와 똑같이</b>
     * 검사한다. 진입 경로가 다르다고 규칙이 느슨해지면 그 경로가 우회로가 된다.
     */
    @Transactional
    public void approveForLink(Store store, ReplyDraft draft, boolean riskAcknowledged, String editedContent) {
        if (!serviceGate.isServiceable(store)) {
            throw new ApiException(ErrorCode.SUBSCRIPTION_INACTIVE);
        }
        requireApprovable(draft);
        if (!riskAcknowledged) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    Map.of("riskAcknowledged", "차단 사유를 확인해야 게시할 수 있습니다."));
        }
        // ★ approved_by 는 매장 소유자로 남긴다 — 링크를 누른 사람이 누구인지는 모르지만,
        //   책임 주체는 그 매장의 사장님이다. 링크로 들어왔다는 사실은 감사로그의
        //   actorType='LINK' 에 남는다.
        doApprove(store, draft, store.getOwnerId(), editedContent);
    }

    @Transactional
    public void rejectForLink(ReplyDraft draft) {
        requireApprovable(draft);
        draft.rejectByHuman(null);
        replyDraftRepository.save(draft);
    }

    /** 로그인 경로와 링크 경로가 공유하는 승인 본문. 사본을 만들지 말 것. */
    private UnifiedReview doApprove(Store store, ReplyDraft draft, Long approverId, String editedContent) {
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
        // ★ 학습 루프: 사장님이 **고친** 답글만 코퍼스로 돌려보낸다.
        //   AI 초안을 그대로 승인한 건은 넣지 않는다 — 자기 출력을 예시로 되먹이면
        //   문체가 자기 자신으로 수렴하고 오류가 증폭된다.
        captureEditedReply(store.getId(), review, draft, content);

        draft.approveByHuman(approverId, Instant.now(), content, scheduledAt);
        replyDraftRepository.save(draft);
        return review;
    }

    /**
     * 사장님이 손댄 답글을 말투 코퍼스에 적재한다. 이 시스템에서 신호가 가장 강한 데이터다 —
     * AI 가 쓴 것과 사람이 고친 것의 차이가 곧 "이 매장이 원하는 답글" 이기 때문이다.
     *
     * <p>★ 넣지 않는 경우가 셋이다. (1) 고치지 않고 그대로 승인 — 그건 AI 출력이라
     * 되먹이면 안 된다. (2) 이미 같은 문장이 코퍼스에 있음. (3) 본문이 빈 경우.
     *
     * <p>★ 적재 전에 식별자를 지운다. 이 코퍼스는 ai-python 이 직접 읽어 few-shot 으로
     * Anthropic 에 보내므로, 마스킹하지 않으면 개인정보가 국외로 나간다
     * (CollectResultService.saveStyleSample 과 같은 이유).
     */
    private void captureEditedReply(Long storeId, UnifiedReview review, ReplyDraft draft, String editedContent) {
        if (editedContent == null || editedContent.isBlank()) {
            return;   // 고치지 않고 승인 — AI 출력이므로 학습하지 않는다
        }
        if (editedContent.equals(draft.getContent())) {
            return;   // 화면에서 전문을 되보냈을 뿐 실제로는 같은 문장이다
        }
        String maskedReply = PersonalIdentifierMasker.mask(editedContent);
        if (replyStyleSampleRepository.existsByStoreIdAndReplyText(storeId, maskedReply)) {
            return;
        }
        // 검색 축은 이미 계산해 둔 분석 결과에서 가져온다. 없으면 비워 두고 최신순 폴백에 맡긴다.
        String category = null;
        String[] issueTags = new String[0];
        ReviewAnalysis analysis = reviewAnalysisRepository.findById(review.getId()).orElse(null);
        if (analysis != null) {
            category = analysis.getCategory();
            issueTags = analysis.getIssueTags() == null ? new String[0] : analysis.getIssueTags();
        }
        replyStyleSampleRepository.save(ReplyStyleSample.builder()
                .storeId(storeId)
                .reviewText(PersonalIdentifierMasker.mask(review.getBody() == null ? "" : review.getBody()))
                .replyText(maskedReply)
                .rating(review.getRating())
                .source("EDITED")
                .category(category)
                .issueTags(issueTags)
                .build());
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
