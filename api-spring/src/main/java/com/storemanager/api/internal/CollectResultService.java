package com.storemanager.api.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.collect.CollectionJobRepository;
import com.storemanager.api.collect.DataApiCallLog;
import com.storemanager.api.collect.DataApiCallLogRepository;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.crypto.PlatformAccountRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.draft.ReplyDraft;
import com.storemanager.api.draft.ReplyDraftRepository;
import com.storemanager.api.draft.ReviewAnalysis;
import com.storemanager.api.draft.ReviewAnalysisRepository;
import com.storemanager.api.internal.CollectResultRequest.ExistingReply;
import com.storemanager.api.internal.CollectResultRequest.Publish;
import com.storemanager.api.internal.CollectResultRequest.ReviewBlock;
import com.storemanager.api.internal.CollectResultRequest.StoreBlock;
import com.storemanager.api.notify.Notifier;
import com.storemanager.api.review.Pseudonymizer;
import com.storemanager.api.review.ReplyStyleSample;
import com.storemanager.api.review.ReplyStyleSampleRepository;
import com.storemanager.api.review.StoreMenu;
import com.storemanager.api.review.StoreMenuRepository;
import com.storemanager.api.review.StorePlatformLink;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워커가 보낸 수집 결과를 정규화해 적재한다 (docs/13 §11.2 계약 + 코디네이터 확정 조정사항, docs/08 §5 매핑표).
 * ★ 이 클래스는 요청 본문(특히 authorRaw)을 절대 로그로 남기지 않는다. jobId 처럼 PII 가 아닌 값만 로그에 남긴다.
 */
@Service
public class CollectResultService {

    private static final Logger log = LoggerFactory.getLogger(CollectResultService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String DISPATCH_KEY_PREFIX = "dispatch:draft:"; // PublishScheduler 와 동일 키(고정계약)
    private static final short MAX_PUBLISH_RETRY = 3;
    private static final String RISK_LEVEL_TOO_HIGH_REASON = "RISK_LEVEL_TOO_HIGH";
    private static final Set<String> NON_RETRYABLE_PUBLISH_GUARDS = Set.of(
            RISK_LEVEL_TOO_HIGH_REASON, "STORE_INACTIVE", "DATAAPI_WRITE_DISABLED");

    private final StorePlatformLinkRepository storePlatformLinkRepository;
    private final StoreRepository storeRepository;
    private final UnifiedReviewRepository unifiedReviewRepository;
    private final ReplyStyleSampleRepository replyStyleSampleRepository;
    private final StoreMenuRepository storeMenuRepository;
    private final CollectionJobRepository collectionJobRepository;
    private final DataApiCallLogRepository dataApiCallLogRepository;
    private final PlatformAccountRepository platformAccountRepository;
    private final Pseudonymizer pseudonymizer;
    private final ObjectMapper objectMapper;
    private final ReplyDraftRepository replyDraftRepository;
    private final ReviewAnalysisRepository reviewAnalysisRepository;
    private final Notifier notifier;
    private final StringRedisTemplate stringRedisTemplate;
    private final AuditLogRepository auditLogRepository;

    public CollectResultService(StorePlatformLinkRepository storePlatformLinkRepository,
            StoreRepository storeRepository, UnifiedReviewRepository unifiedReviewRepository,
            ReplyStyleSampleRepository replyStyleSampleRepository, StoreMenuRepository storeMenuRepository,
            CollectionJobRepository collectionJobRepository, DataApiCallLogRepository dataApiCallLogRepository,
            PlatformAccountRepository platformAccountRepository, Pseudonymizer pseudonymizer,
            ObjectMapper objectMapper, ReplyDraftRepository replyDraftRepository, Notifier notifier,
            ReviewAnalysisRepository reviewAnalysisRepository, StringRedisTemplate stringRedisTemplate,
            AuditLogRepository auditLogRepository) {
        this.storePlatformLinkRepository = storePlatformLinkRepository;
        this.storeRepository = storeRepository;
        this.unifiedReviewRepository = unifiedReviewRepository;
        this.replyStyleSampleRepository = replyStyleSampleRepository;
        this.storeMenuRepository = storeMenuRepository;
        this.collectionJobRepository = collectionJobRepository;
        this.dataApiCallLogRepository = dataApiCallLogRepository;
        this.platformAccountRepository = platformAccountRepository;
        this.pseudonymizer = pseudonymizer;
        this.objectMapper = objectMapper;
        this.replyDraftRepository = replyDraftRepository;
        this.reviewAnalysisRepository = reviewAnalysisRepository;
        this.notifier = notifier;
        this.stringRedisTemplate = stringRedisTemplate;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public CollectResultSummary ingest(CollectResultRequest req) {
        if (req.publish() != null) {
            return handlePublishResult(req);
        }

        Long requestAccountId = parseLongOrNull(req.accountId());

        if ("LINK_ERROR".equals(req.action())) {
            handleLinkError(requestAccountId, req.ecode());
        }

        int processed = 0;
        int skipped = 0;
        int reviewsNew = 0;
        Long resolvedAccountId = requestAccountId;

        List<StoreBlock> stores = req.stores() == null ? List.of() : req.stores();
        for (StoreBlock storeBlock : stores) {
            StorePlatformLink link = storePlatformLinkRepository
                    .findByPlatformAndPlatformStoreId(req.platform(), storeBlock.platformStoreId())
                    .orElse(null);
            if (link == null) {
                link = autoLink(requestAccountId, req.platform(), storeBlock);
            }
            if (link == null) {
                // 사용자가 아직 이 매장을 우리 시스템에 매핑하지 않았다 — 실패가 아니라 건너뛴다.
                skipped++;
                continue;
            }
            if (requestAccountId == null || !requestAccountId.equals(link.getAccountId())) {
                throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            if (resolvedAccountId == null) {
                resolvedAccountId = link.getAccountId();
            }

            Store store = storeRepository.findById(link.getStoreId()).orElse(null);
            if (store == null || store.getActivatedAt() == null) {
                // 전자계약 미서명 매장(docs/11 §2.7 주석) — 워커/Spring 모두 처리하지 않는다.
                skipped++;
                continue;
            }

            link.updateSnapshot(storeBlock.storeName(), storeBlock.avgRating());
            processed++;

            List<ReviewBlock> reviews = storeBlock.reviews();
            if (reviews != null) {
                for (ReviewBlock rb : reviews) {
                    if (ingestReview(store, link, req.platform(), rb)) {
                        reviewsNew++;
                    }
                }
            }
        }

        // 수집이 실제로 성공했으면 계정을 '연동 완료' 로 전이한다.
        // ★ 이게 없으면 몇 번을 성공해도 화면은 영원히 '검증 보류' 로 남는다 — 사장님은
        //   연동이 된 건지 아닌지 알 수 없고, 우리도 verified_at 을 근거로 쓸 수 없다.
        // ★ processed > 0 을 조건으로 둔다. 매장이 하나도 매핑되지 않았다면 자격증명이 맞는지는
        //   확인됐어도 '쓸 수 있는 연동' 은 아니다 — 사람이 매장을 매핑해야 한다.
        if (!"FAILED".equals(req.status()) && processed > 0 && resolvedAccountId != null) {
            platformAccountRepository.findById(resolvedAccountId)
                    .ifPresent(PlatformAccount::markVerified);
        }

        updateCollectionJob(req, reviewsNew);
        logDataApiCall(req, resolvedAccountId);

        return new CollectResultSummary(processed, skipped, reviewsNew);
    }

    /**
     * 게시 결과 수신(S10, 오케스트레이터 고정계약). 워커가 실제로 보내는 값 기준(오케스트레이터 계약 보완 메모):
     * action=PUBLISHED|ALREADY_REPLIED|FAIL|LINK_ERROR 를 최상위 action 필드로 구분한다.
     * 디스패치 토큰과 계정·플랫폼·현재 상태가 모두 일치한 결과만 반영하고 키를 삭제한다.
     * ★ 요청 본문을 로깅하지 않는다.
     */
    private CollectResultSummary handlePublishResult(CollectResultRequest req) {
        Publish publish = req.publish();
        Long draftId = publish.draftId();
        ReplyDraft draft = draftId == null ? null : replyDraftRepository.findById(draftId).orElse(null);
        if (draft == null) {
            log.warn("게시 결과 수신: reply_draft 를 찾을 수 없어 무시합니다.");
            return new CollectResultSummary(0, 0, 0);
        }

        String action = req.action();
        if (!Set.of("PUBLISHED", "ALREADY_REPLIED", "FAIL", "LINK_ERROR").contains(action)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        verifyPublishCallback(req, draft);
        stringRedisTemplate.delete(DISPATCH_KEY_PREFIX + draftId);

        if ("PUBLISHED".equals(action)) {
            draft.markPublished(publish.platformCommentId());
        } else if ("ALREADY_REPLIED".equals(action)) {
            draft.markAlreadyReplied();
            markReviewHasOwnerReply(draft.getReviewId());
        } else if ("FAIL".equals(action)) {
            handlePublishFail(draft, req.ecode(), publish.failReason());
        } else if ("LINK_ERROR".equals(action)) {
            draft.markFailed(req.ecode(), publish.failReason());
            handleLinkError(parseLongOrNull(req.accountId()), req.ecode());
            notifyLinkError(draft);
        }

        return new CollectResultSummary(0, 0, 0);
    }

    /**
     * FAIL 분기(오케스트레이터 계약 보완). failReason=RISK_LEVEL_TOO_HIGH 는 워커가 절대규칙 3 이중검증으로
     * DataAPI 를 아예 호출하지 않고 거절한 경우다 — 재시도 대상이 아니라 사람 검수(BLOCKED)로 종결한다.
     * 그 외 실패는 retry_count<3 이면 지수 백오프로 재예약, 3회 소진 시 FAILED 로 확정한다.
     */
    private void handlePublishFail(ReplyDraft draft, String ecode, String failReason) {
        if (NON_RETRYABLE_PUBLISH_GUARDS.contains(failReason)) {
            draft.blockAtPublishGuard(failReason, failReason);
            auditLogRepository.save(AuditLog.builder()
                    .actorType("WORKER")
                    .action("DRAFT_BLOCKED_AT_PUBLISH_GUARD")
                    .targetType("REPLY_DRAFT")
                    .targetId(draft.getId())
                    .build());
            if (RISK_LEVEL_TOO_HIGH_REASON.equals(failReason)) {
                notifyHighRisk(draft);
            }
            return;
        }
        if (draft.getRetryCount() < MAX_PUBLISH_RETRY) {
            short nextRetryCount = (short) (draft.getRetryCount() + 1);
            Instant nextScheduledAt = Instant.now().plus(Duration.ofMinutes(1L << nextRetryCount));
            draft.retryLater(nextScheduledAt, failReason);
        } else {
            draft.markFailed(ecode, failReason);
        }
    }

    private void verifyPublishCallback(CollectResultRequest req, ReplyDraft draft) {
        String expectedToken = stringRedisTemplate.opsForValue().get(DISPATCH_KEY_PREFIX + draft.getId());
        String actualToken = req.publish().dispatchToken();
        if (expectedToken == null || actualToken == null || !MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8), actualToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        if (!"SCHEDULED".equals(draft.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_DRAFT_STATE);
        }

        UnifiedReview review = unifiedReviewRepository.findById(draft.getReviewId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        StorePlatformLink link = storePlatformLinkRepository.findById(review.getLinkId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Long accountId = parseLongOrNull(req.accountId());
        if (accountId == null || !accountId.equals(link.getAccountId())
                || !req.platform().equals(review.getPlatform()) || !draft.getStoreId().equals(link.getStoreId())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }

        if ("PUBLISHED".equals(req.action())) {
            ReviewAnalysis analysis = reviewAnalysisRepository.findById(review.getId())
                    .orElseThrow(() -> new ApiException(ErrorCode.INVALID_DRAFT_STATE));
            Store store = storeRepository.findById(draft.getStoreId())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
            boolean active = store.getActivatedAt() != null && "ACTIVE".equals(store.getStatus())
                    && store.getDeletedAt() == null;
            if (!active || analysis.getRiskLevel() >= 3) {
                throw new ApiException(ErrorCode.INVALID_DRAFT_STATE);
            }
        }
    }

    /** ALREADY_REPLIED — unified_review.has_owner_reply 를 true 로 갱신한다(F-9, 사장님이 앱에서 직접 답글). */
    private void markReviewHasOwnerReply(Long reviewId) {
        unifiedReviewRepository.findById(reviewId).ifPresent(review -> review.applyIncoming(review.getRating(),
                review.getBody(), review.getAuthorMasked(), review.getAuthorHash(), review.getOrderedMenus(),
                review.getImageUrls(), review.getPlatformExtra(), review.getReviewStatus(), review.getWrittenAt(),
                true, review.getExistingReply(), review.getExistingReplyId()));
    }

    private static final short LOW_RATING_THRESHOLD = 2; // S11: 별점 2 이하 수신 시 사장님 알림

    private void notifyHighRisk(ReplyDraft draft) {
        storeRepository.findById(draft.getStoreId()).ifPresent(store -> notifier.send(store.getOwnerId(),
                store.getId(), "ALIMTALK", "HIGH_RISK_REVIEW", "REPLY_DRAFT", draft.getId()));
    }

    private void notifyLinkError(ReplyDraft draft) {
        storeRepository.findById(draft.getStoreId()).ifPresent(store -> notifier.send(store.getOwnerId(),
                store.getId(), "ALIMTALK", "PLATFORM_LINK_ERROR", "REPLY_DRAFT", draft.getId()));
    }

    /** @return 새로 생성된 리뷰면 true, 기존 리뷰 갱신이면 false */
    /**
     * 첫 수집에서 발견한 플랫폼 매장을, 등록할 때 사장님이 지정한 매장에 연결한다.
     *
     * ★ 확실할 때만 연결한다. 틀리게 붙이면 남의 매장 리뷰가 내 매장에 쌓이고, 거기에 답글이 달린다.
     * 되돌릴 수 없는 사고이므로 아래 조건을 모두 만족할 때만 만든다.
     *   - 요청이 어느 계정에서 왔는지 확실할 것 (requestAccountId)
     *   - 그 계정에 사장님이 지정한 매장이 있을 것 (intendedStoreId)
     *   - 그 매장이 아직 이 플랫폼에 연결되지 않았을 것
     *     → 1계정 N매장(F-7)에서 둘째 매장부터는 어느 매장인지 알 수 없다. 건너뛰고 사람이 매핑한다.
     *   - 매장이 활성화(전자계약 완료)돼 있을 것
     */
    private StorePlatformLink autoLink(Long requestAccountId, String platform, StoreBlock storeBlock) {
        if (requestAccountId == null || storeBlock.platformStoreId() == null) {
            return null;
        }
        PlatformAccount account = platformAccountRepository.findById(requestAccountId).orElse(null);
        if (account == null || account.getRevokedAt() != null || account.getIntendedStoreId() == null
                || !platform.equals(account.getPlatform())) {
            return null;
        }
        Store store = storeRepository.findById(account.getIntendedStoreId()).orElse(null);
        if (store == null || store.getDeletedAt() != null || store.getActivatedAt() == null
                || !store.getOwnerId().equals(account.getOwnerId())) {
            return null;
        }
        boolean alreadyLinked = storePlatformLinkRepository.findByAccountIdOrderByCreatedAtAsc(requestAccountId)
                .stream().anyMatch(existing -> existing.getStoreId().equals(store.getId()));
        if (alreadyLinked) {
            return null;
        }
        StorePlatformLink created = storePlatformLinkRepository.saveAndFlush(StorePlatformLink.builder()
                .storeId(store.getId())
                .accountId(requestAccountId)
                .platform(platform)
                .platformStoreId(storeBlock.platformStoreId())
                .storeNameSnapshot(storeBlock.storeName())
                .avgRating(storeBlock.avgRating())
                .build());
        log.info("플랫폼 매장 자동 연결 accountId={} storeId={} platform={}", requestAccountId, store.getId(), platform);
        return created;
    }

    private boolean ingestReview(Store store, StorePlatformLink link, String platform, ReviewBlock rb) {
        UnifiedReview review = unifiedReviewRepository
                .findByPlatformAndPlatformReviewId(platform, rb.platformReviewId())
                .orElse(null);
        boolean isNew = review == null;

        Pseudonymizer.Result masked = pseudonymizer.mask(rb.authorRaw());
        ExistingReply existingReply = rb.existingReply();
        boolean hasOwnerReply = existingReply != null;
        Instant writtenAt = toKstMidnightUtc(rb.writtenDate());
        Short rating = rb.rating() == null ? null : rb.rating().shortValue();

        String orderedMenusJson = toJson(rb.orderedMenus() == null ? List.of() : rb.orderedMenus());
        String imageUrlsJson = toJson(rb.imageUrls() == null ? List.of() : rb.imageUrls());
        String platformExtraJson = toJson(rb.platformExtra() == null ? Map.of() : rb.platformExtra());

        if (isNew) {
            review = UnifiedReview.builder()
                    .storeId(store.getId())
                    .linkId(link.getId())
                    .platform(platform)
                    .platformReviewId(rb.platformReviewId())
                    .build();
        }
        // collected_at 은 builder 기본값(최초 저장 시각) 그대로 둔다 — 재수신 시에도 여기서 건드리지 않는다.
        review.applyIncoming(rating, rb.body(), masked.maskedAuthor(), masked.authorHash(),
                orderedMenusJson, imageUrlsJson, platformExtraJson, rb.reviewStatus(), writtenAt, hasOwnerReply,
                hasOwnerReply ? existingReply.contents() : null,
                hasOwnerReply ? existingReply.id() : null);
        unifiedReviewRepository.save(review);

        if (hasOwnerReply) {
            saveStyleSample(store.getId(), rb.body(), existingReply.contents(), rating);
        }

        upsertMenus(store.getId(), platform, rb.orderedMenus());

        // S11 저별점 알림 — 새로 들어온 리뷰에 대해서만 보낸다.
        // 수집은 매 폴링마다 최근 2일을 재조회하므로(CLAUDE.md 데이터처리 2번), isNew 로 막지 않으면
        // 같은 저별점 리뷰로 사장님에게 폴링 주기마다 알림이 반복 발송된다.
        if (isNew && rating != null && rating <= LOW_RATING_THRESHOLD) {
            notifier.send(store.getOwnerId(), store.getId(), "ALIMTALK", "LOW_RATING_REVIEW",
                    "UNIFIED_REVIEW", review.getId());
        }
        return isNew;
    }

    /** RC_LIST 기존 답글을 말투 학습 코퍼스로 적재한다(docs/08 §5). (store_id, reply_text) 중복은 적재하지 않는다. */
    private void saveStyleSample(Long storeId, String reviewBody, String replyText, Short rating) {
        if (replyText == null || replyText.isBlank()) {
            return;
        }
        if (replyStyleSampleRepository.existsByStoreIdAndReplyText(storeId, replyText)) {
            return;
        }
        replyStyleSampleRepository.save(ReplyStyleSample.builder()
                .storeId(storeId)
                .reviewText(reviewBody == null ? "" : reviewBody)
                .replyText(replyText)
                .rating(rating)
                .source("RC_LIST")
                // embedding: TODO Sprint 3 담당 — pgvector 임베딩은 여기서 채우지 않는다
                .build());
    }

    /**
     * menu_id 가 계약에 없어 항상 null 로 온다 — menu_name 기준으로 중복을 막는다.
     * ponytail: menu_id 가 있는 케이스는 현재 계약에 없어 분기하지 않는다. 계약이 menu_id 를 보내기 시작하면 추가.
     */
    private void upsertMenus(Long storeId, String platform, List<String> menuNames) {
        if (menuNames == null) {
            return;
        }
        for (String name : menuNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            if (storeMenuRepository.existsByStoreIdAndPlatformAndMenuIdIsNullAndMenuName(storeId, platform, name)) {
                continue;
            }
            storeMenuRepository.save(StoreMenu.builder()
                    .storeId(storeId)
                    .platform(platform)
                    .menuName(name)
                    .build());
        }
    }

    /** action=LINK_ERROR — DataAPI 로그인 실패 등으로 연동이 끊겼다는 신호(FR-105). platform_account 상태를 전이한다. */
    private void handleLinkError(Long accountId, String ecode) {
        if (accountId == null) {
            log.warn("LINK_ERROR 액션이지만 accountId 를 정수로 해석할 수 없어 상태 전이를 건너뜁니다.");
            return;
        }
        platformAccountRepository.findById(accountId).ifPresentOrElse(
                account -> account.markLinkError(ecode),
                () -> log.warn("platform_account(id={}) 를 찾을 수 없어 LINK_ERROR 상태 전이를 건너뜁니다.", accountId));
    }

    /** 워커가 이미 채번해 둔 collection_job 을 결과로 갱신한다. Spring 은 새 job 을 만들지 않는다. */
    private void updateCollectionJob(CollectResultRequest req, int reviewsNew) {
        Long jobId = parseLongOrNull(req.jobId());
        if (jobId == null) {
            // 워커가 uuid4().hex 로 자체 생성한 jobId — 우리 BIGSERIAL collection_job.id 와 대응되지 않는다.
            log.warn("jobId 를 정수로 해석할 수 없어 collection_job 갱신을 건너뜁니다.");
            return;
        }
        Integer reviewsFound = req.stats() == null ? null : req.stats().found();
        collectionJobRepository.findById(jobId).ifPresentOrElse(
                job -> job.applyResult(req.status(), reviewsFound, reviewsNew, req.ecode()),
                () -> log.warn("collection_job(id={}) 을 찾을 수 없어 상태 갱신을 건너뜁니다.", jobId));
    }

    private void logDataApiCall(CollectResultRequest req, Long resolvedAccountId) {
        dataApiCallLogRepository.save(DataApiCallLog.builder()
                .accountId(resolvedAccountId)
                .platform(req.platform())
                .endpoint("reviewManagement")
                .result(req.status())
                .ecode(req.ecode())
                .latencyMs(req.stats() == null ? null : req.stats().latencyMs())
                .build());
    }

    /** REVIEWDATE 는 시각 정보가 없다(F-3) — 해당 일자 00:00 KST 를 UTC 로 환산해 저장한다. */
    private Instant toKstMidnightUtc(String writtenDate) {
        return LocalDate.parse(writtenDate).atStartOfDay(KST).toInstant();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 리뷰 필드(메뉴명 등) 직렬화 실패는 데이터 문제이지 서버 장애가 아니다 — 빈 값으로 대체하고 계속 진행한다.
            log.warn("리뷰 부가정보 JSON 직렬화 실패, 빈 값으로 대체합니다: {}", e.getMessage());
            return value instanceof Map ? "{}" : "[]";
        }
    }

    /** jobId/accountId 는 계약상 문자열이라 항상 숫자로 해석되리라는 보장이 없다 — 실패 시 null. */
    private Long parseLongOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    record CollectResultSummary(int storesProcessed, int storesSkipped, int reviewsNew) {
    }
}
