package com.storemanager.api.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 승격 부수효과의 Redis 오류는 결제 확정 트랜잭션을 롤백시키면 안 된다(카드는 이미 청구됨).
 * 오류가 나도 DB 전이는 하고, 큐 여부를 모르면 DB 에서 막는 쪽으로 간다.
 */
class PlatformSelectionDraftEffectsTest {

    private final ReplyDraftRepository drafts = mock(ReplyDraftRepository.class);
    private final PublishBlockKeys blockKeys = mock(PublishBlockKeys.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final PlatformSelectionDraftEffects effects = new PlatformSelectionDraftEffects(drafts, blockKeys, redis);

    private static ReplyDraft scheduled() {
        return ReplyDraft.builder().id(7L).reviewId(1L).storeId(100L).status("SCHEDULED").content("답글")
                .generatedBy("AI").scheduledAt(Instant.now().plusSeconds(60)).build();
    }

    @Test
    void redisFailureDoesNotThrowAndStillBlocksInDb() {
        ReplyDraft d = scheduled();
        when(drafts.findScheduledByStoreAndPlatforms(eq(100L), anyCollection())).thenReturn(List.of(d));
        doThrow(new RedisConnectionFailureException("down")).when(blockKeys).block(any());
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        effects.onRemoved(100L, Set.of("YOGIYO")); // 예외가 밖으로 나오지 않는다

        assertThat(d.getStatus()).isEqualTo("BLOCKED");
        assertThat(d.getGuardrailFlags()).containsExactly("PLATFORM_NOT_SELECTED");
        verify(drafts).save(d);
    }

    @Test
    void unblockFailureDoesNotThrow() {
        when(drafts.findReselectCandidates(eq(100L), anyCollection())).thenReturn(List.of());
        doThrow(new RedisConnectionFailureException("down")).when(blockKeys).unblock(any());

        effects.onReselected(100L, Set.of("YOGIYO")); // 트랜잭션 밖이면 즉시 실행 - 그래도 삼킨다
    }
}
