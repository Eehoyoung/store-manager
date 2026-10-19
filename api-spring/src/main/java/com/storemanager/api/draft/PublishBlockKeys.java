package com.storemanager.api.draft;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 큐({@code q:publish})에 이미 들어간 게시 잡을 워커가 게시 직전에 멈추게 하는 Redis 차단 키.
 *
 * <p>★ 키는 draft 단위다 - 게시 payload 는 고정계약이라 storeId 가 없다.
 * 워커는 같은 키 형식({@code publish:blocked:draft:{draftId}})을 확인해 중단하고 failReason
 * {@code PLATFORM_NOT_SELECTED} 로 보고한다. 쓰기(하향 승격)·삭제(재선택)는 PR5 가 호출한다.
 * 서비스 간 HTTP 가 아니라 공유 Redis 다(readiness 키와 같은 방식).
 */
@Component
public class PublishBlockKeys {

    static final String KEY_PREFIX = "publish:blocked:draft:";
    static final String VALUE = "PLATFORM_NOT_SELECTED";
    /** 큐 체류 시간 가정(추정). 큐 지연이 길면 상향. */
    static final Duration TTL = Duration.ofSeconds(86400);

    private final StringRedisTemplate redis;

    public PublishBlockKeys(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public static String key(Long draftId) {
        return KEY_PREFIX + draftId;
    }

    public void block(Collection<Long> draftIds) {
        for (Long id : draftIds) {
            redis.opsForValue().set(key(id), VALUE, TTL);
        }
    }

    public void unblock(Collection<Long> draftIds) {
        if (draftIds.isEmpty()) {
            return;
        }
        List<String> keys = draftIds.stream().map(PublishBlockKeys::key).toList();
        redis.delete(keys);
    }
}
