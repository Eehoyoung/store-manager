package com.storemanager.api.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.collect.CollectionJobRepository;
import com.storemanager.api.collect.DataApiCallLog;
import com.storemanager.api.collect.DataApiCallLogRepository;
import com.storemanager.api.crypto.PlatformAccountRepository;
import com.storemanager.api.internal.CollectResultRequest.ExistingReply;
import com.storemanager.api.internal.CollectResultRequest.ReviewBlock;
import com.storemanager.api.internal.CollectResultRequest.StoreBlock;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public CollectResultService(StorePlatformLinkRepository storePlatformLinkRepository,
            StoreRepository storeRepository, UnifiedReviewRepository unifiedReviewRepository,
            ReplyStyleSampleRepository replyStyleSampleRepository, StoreMenuRepository storeMenuRepository,
            CollectionJobRepository collectionJobRepository, DataApiCallLogRepository dataApiCallLogRepository,
            PlatformAccountRepository platformAccountRepository, Pseudonymizer pseudonymizer,
            ObjectMapper objectMapper) {
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
    }

    @Transactional
    public CollectResultSummary ingest(CollectResultRequest req) {
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
                // 사용자가 아직 이 매장을 우리 시스템에 매핑하지 않았다 — 실패가 아니라 건너뛴다.
                skipped++;
                continue;
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

        updateCollectionJob(req, reviewsNew);
        logDataApiCall(req, resolvedAccountId);

        return new CollectResultSummary(processed, skipped, reviewsNew);
    }

    /** @return 새로 생성된 리뷰면 true, 기존 리뷰 갱신이면 false */
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
