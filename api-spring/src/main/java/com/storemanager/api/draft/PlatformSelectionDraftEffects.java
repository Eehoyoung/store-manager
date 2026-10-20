package com.storemanager.api.draft;

import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 선택형 요금(D38) 승격으로 배달앱이 빠지거나 다시 들어올 때 초안·큐 잔여 잡에 일어나는 일.
 * 호출자(BillingService)의 트랜잭션 안에서 부른다. 환불은 없다.
 *
 * <p>★ 호출자 트랜잭션은 PG 결제가 이미 승인된 뒤의 확정 트랜잭션이다. Redis 장애가 그 트랜잭션을
 * 롤백시키면 카드는 청구됐는데 기간이 연장되지 않는다. 그래서 Redis 오류는 전부 삼키고 로그만 남긴다.
 * 차단(block)은 즉시 쓴다 - 롤백돼도 남는 것은 '막는' 키뿐이다(최대 24시간). 해제(unblock)는 커밋 뒤에만 한다
 * - 롤백되면 키가 그대로 남아 막힌 쪽으로 틀린다. 어느 쪽도 게시가 열리는 방향으로 틀리지 않는다.
 */
@Component
public class PlatformSelectionDraftEffects {

    private static final Logger log = LoggerFactory.getLogger(PlatformSelectionDraftEffects.class);

    private final ReplyDraftRepository drafts;
    private final PublishBlockKeys blockKeys;
    private final StringRedisTemplate redis;

    public PlatformSelectionDraftEffects(ReplyDraftRepository drafts, PublishBlockKeys blockKeys,
            StringRedisTemplate redis) {
        this.drafts = drafts;
        this.blockKeys = blockKeys;
        this.redis = redis;
    }

    /**
     * 빠진 배달앱: SCHEDULED 초안은 Redis 차단 키를 쓰고(큐에 이미 들어간 잡 대비), 아직 큐에 안 들어간 것은
     * BLOCKED(PLATFORM_NOT_SELECTED) 로 바꾼다. 이미 큐에 들어간 잡은 DB 를 건드리지 않는다 - 워커가 키를 보고 멈춘다.
     */
    public void onRemoved(Long storeId, Set<String> platforms) {
        if (platforms.isEmpty()) {
            return;
        }
        List<ReplyDraft> scheduled = drafts.findScheduledByStoreAndPlatforms(storeId, platforms);
        List<Long> ids = scheduled.stream().map(ReplyDraft::getId).toList();
        guarded(() -> blockKeys.block(ids), storeId).run();
        for (ReplyDraft d : scheduled) {
            if (!isQueued(d.getId())) {
                d.blockForScheduling("PLATFORM_NOT_SELECTED");
                drafts.save(d);
            }
        }
    }

    /**
     * 다시 선택된 배달앱: 차단 키를 지우고, PLATFORM_NOT_SELECTED 로 막힌 초안은 재생성 가능한 FAILED 로 돌린다.
     * ★ 자동 재게시 금지 - SCHEDULED 로 되살리지 않는다.
     */
    public void onReselected(Long storeId, Set<String> platforms) {
        if (platforms.isEmpty()) {
            return;
        }
        List<ReplyDraft> candidates = drafts.findReselectCandidates(storeId, platforms);
        List<Long> ids = candidates.stream().map(ReplyDraft::getId).toList();
        afterCommit(() -> blockKeys.unblock(ids), storeId);
        for (ReplyDraft d : candidates) {
            if (d.releaseForRedraftAfterReselect()) {
                drafts.save(d);
            }
        }
    }

    /** 큐에 들어갔는지 모르면(Redis 오류) 안 들어간 것으로 보고 DB 에서 막는다 - 게시가 열리는 쪽으로 틀리지 않는다. */
    private boolean isQueued(Long draftId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(PublishScheduler.dispatchKey(draftId)));
        } catch (RuntimeException e) {
            log.warn("dispatch 키 확인 실패 - DB 에서 차단합니다 draftId={}", draftId);
            return false;
        }
    }

    // ponytail: Redis 쓰기 실패는 ERROR 로그만 - 차단 실패면 큐 잔여 잡을 워커가 못 막고, 해제 실패면 24시간 막힌다.
    //   빈도가 생기면 재시도 큐로.
    private static Runnable guarded(Runnable redisWrite, Long storeId) {
        return () -> {
            try {
                redisWrite.run();
            } catch (RuntimeException e) {
                log.error("선택형 요금 Redis 차단 키 처리 실패 storeId={}", storeId);
            }
        };
    }

    private static void afterCommit(Runnable redisWrite, Long storeId) {
        Runnable guarded = guarded(redisWrite, storeId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    guarded.run();
                }
            });
        } else {
            guarded.run();
        }
    }
}
