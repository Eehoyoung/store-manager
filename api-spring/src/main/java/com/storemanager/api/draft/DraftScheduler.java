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
    @Scheduled(fixedDelay = 60_000)
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
