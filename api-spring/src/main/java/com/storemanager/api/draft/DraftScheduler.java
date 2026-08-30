package com.storemanager.api.draft;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 답글 생성 스케줄러. 수집된 리뷰 중 초안이 없는 것을 골라 생성을 돌린다.
 *
 * <p>★ 이게 없으면 풀자동화 체인이 끊긴다. 수집은 unified_review 까지만 하고, 생성은 지금까지
 * POST /reviews/{id}/drafts 로 사람이 직접 호출해야만 일어났다. 즉 리뷰를 모아만 두고 답글은
 * 하나도 달리지 않는 상태였다.
 *
 * <p>★ 배치 크기를 작게 둔다. 한 번에 많이 돌리면 LLM 비용이 한꺼번에 나가고, 잘못된 프롬프트
 * 변경이 대량으로 반영된 뒤에야 드러난다. 밀린 건은 다음 주기에 이어서 처리된다.
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.draft.enabled", havingValue = "true")
public class DraftScheduler {

    private static final Logger log = LoggerFactory.getLogger(DraftScheduler.class);
    private static final int BATCH_SIZE = 20;

    /**
     * 폴링 주기. <b>프롬프트 캐시 TTL(5분)보다 반드시 짧아야 한다.</b>
     *
     * <p>★ 분류 시스템 프롬프트는 매 호출 동일하고 4,096 토큰을 넘겨 캐시된다. 캐시 항목은
     * 마지막 접근으로부터 5분 뒤 만료되고, 읽기는 기본 입력가의 0.1배로 과금된다. 이 배치가
     * 20건을 연달아 호출하는 동안은 물론이고, <b>주기 사이 60초도 5분 안</b>이라 처리할 리뷰가
     * 이어지는 한 캐시가 끊기지 않는다.
     *
     * <p>이 값을 5분 이상으로 늘리면 주기마다 캐시 재작성(1.25배)이 일어나 입력 원가가
     * 약 10배가 된다. 2026-08-30 실측 기준 골든셋 564건이 590원 → 2,200원이 되는 것과 같은 차이다.
     * {@code DraftSchedulerCacheWindowTest} 가 이 제약을 잠근다.
     *
     * <p>배치 크기를 작게 두는 이유는 별개다 — 잘못된 프롬프트 변경이 한꺼번에 반영되는 것을 막는다.
     */
    static final long POLL_INTERVAL_MS = 60_000;

    /** Anthropic 프롬프트 캐시의 기본 TTL. 이보다 긴 주기는 캐시를 매번 버린다. */
    static final long PROMPT_CACHE_TTL_MS = 5 * 60_000;

    private final UnifiedReviewRepository unifiedReviewRepository;
    private final StoreRepository storeRepository;
    private final AppUserRepository appUserRepository;
    private final DraftService draftService;

    public DraftScheduler(UnifiedReviewRepository unifiedReviewRepository, StoreRepository storeRepository,
            AppUserRepository appUserRepository, DraftService draftService) {
        this.unifiedReviewRepository = unifiedReviewRepository;
        this.storeRepository = storeRepository;
        this.appUserRepository = appUserRepository;
        this.draftService = draftService;
    }

    /**
     * ★ @Transactional 을 붙이지 않는다. generateDrafts 가 리뷰 1건마다 자기 트랜잭션을 열어야
     * 한 건의 실패가 배치 전체를 롤백시키지 않는다. 특히 가드레일 전량 차단은 예외를 던지면서도
     * BLOCKED 행을 남겨야 하는데(절대규칙 3), 바깥 트랜잭션이 있으면 그 기록까지 함께 사라진다.
     */
    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void generatePendingDrafts() {
        List<UnifiedReview> pending = unifiedReviewRepository.findNeedingDraft(PageRequest.of(0, BATCH_SIZE));
        if (pending.isEmpty()) {
            return;
        }
        int done = 0;
        int failed = 0;
        for (UnifiedReview review : pending) {
            Store store = storeRepository.findById(review.getStoreId()).orElse(null);
            AppUser owner = store == null ? null : appUserRepository.findById(store.getOwnerId()).orElse(null);
            if (owner == null) {
                continue;
            }
            try {
                draftService.generateDrafts(owner.getPublicId(), review.getPublicId(), null);
                done++;
            } catch (ApiException e) {
                // 가드레일 전량 차단(422)·경합으로 초안이 이미 생김(409) 등. 둘 다 정상 흐름이며
                // BLOCKED 행이 남으므로 다음 주기에 다시 집히지 않는다.
                failed++;
            } catch (RuntimeException e) {
                // AI 서비스 장애 등. 초안이 안 생겼으니 다음 주기에 다시 시도된다.
                // ★ 리뷰 본문·자격증명이 섞일 수 있는 예외 상세는 남기지 않는다.
                failed++;
                log.warn("답글 생성 실패 reviewId={} type={}", review.getId(), e.getClass().getSimpleName());
            }
        }
        log.info("답글 생성 배치 대상={} 생성={} 미생성={}", pending.size(), done, failed);
    }
}
