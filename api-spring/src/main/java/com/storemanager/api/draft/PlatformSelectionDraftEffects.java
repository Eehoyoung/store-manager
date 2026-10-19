package com.storemanager.api.draft;

import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 선택형 요금(D38) 승격으로 배달앱이 빠지거나 다시 들어올 때 초안·큐 잔여 잡에 일어나는 일.
 * 호출자(BillingService)의 트랜잭션 안에서 부른다. 환불은 없다.
 */
@Component
public class PlatformSelectionDraftEffects {

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
        blockKeys.block(scheduled.stream().map(ReplyDraft::getId).toList());
        for (ReplyDraft d : scheduled) {
            if (!Boolean.TRUE.equals(redis.hasKey(PublishScheduler.dispatchKey(d.getId())))) {
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
        blockKeys.unblock(candidates.stream().map(ReplyDraft::getId).toList());
        for (ReplyDraft d : candidates) {
            if (d.releaseForRedraftAfterReselect()) {
                drafts.save(d);
            }
        }
    }
}
