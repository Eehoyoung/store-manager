package com.storemanager.api.sysauth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 시스템 관리자·가맹본부 담당자용 Redis 서버 세션 (docs/26a auth.session).
 *
 * <p>키 {@code sess:{ADMIN|HQ}:{sid}} → subject, TTL = 절대 만료(관리자 10분, 본부 8시간).
 * ★ 자동연장이 없다 — {@link #exists}/{@link #subject} 는 TTL 을 갱신하지 않는다
 * ({@code ExtensionAuthService.resolve} 의 슬라이딩 TTL 과 다른 점).
 *
 * <p>본부 세션은 담당자 단위로 {@code hqsess:user:{appUserId}} Set 에 sid 를 모아 두고,
 * 담당자 접근을 중지할 때 이 Set 을 모아 한 번에 전부 지운다({@link #revokeAllHqSessions}).
 */
@Component
public class SessionService {

    public enum Kind { ADMIN, HQ }

    private static final String HQ_USER_SET_PREFIX = "hqsess:user:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;

    public SessionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 세션을 만들고 sid 를 반환한다. TTL 이 곧 절대 만료 시각이다 — 이후 갱신하지 않는다. */
    public String create(Kind kind, String subject, Duration ttl) {
        String sid = randomToken();
        redis.opsForValue().set(key(kind, sid), subject, ttl);
        return sid;
    }

    public Optional<String> subject(Kind kind, String sid) {
        if (sid == null || sid.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redis.opsForValue().get(key(kind, sid)));
    }

    public boolean exists(Kind kind, String sid) {
        return subject(kind, sid).isPresent();
    }

    /** refresh 발급 시 새 access 토큰의 exp 상한으로 쓴다 — 세션을 연장하지 않기 위함. */
    public long remainingSeconds(Kind kind, String sid) {
        Long ttl = redis.getExpire(key(kind, sid), TimeUnit.SECONDS);
        return ttl == null ? 0 : Math.max(ttl, 0);
    }

    public void revoke(Kind kind, String sid) {
        if (sid != null) {
            redis.delete(key(kind, sid));
        }
    }

    /** 본부 세션 생성 시 담당자 단위 Set 에도 등록한다(강제 종료용). */
    public void trackHqSession(Long appUserId, String sid, Duration ttl) {
        String setKey = HQ_USER_SET_PREFIX + appUserId;
        redis.opsForSet().add(setKey, sid);
        // 세션 TTL 보다 여유 있게 둔다 — Set 자체가 사라져도 개별 sess 키는 각자의 TTL로 만료된다.
        redis.expire(setKey, ttl.plusDays(1));
    }

    /** 담당자 접근 중지·강제 로그아웃 — 그 사용자의 본부 세션을 전부 지운다. */
    public void revokeAllHqSessions(Long appUserId) {
        String setKey = HQ_USER_SET_PREFIX + appUserId;
        Set<String> sids = redis.opsForSet().members(setKey);
        if (sids != null) {
            for (String sid : sids) {
                redis.delete(key(Kind.HQ, sid));
            }
        }
        redis.delete(setKey);
    }

    private static String key(Kind kind, String sid) {
        return "sess:" + kind + ":" + sid;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
