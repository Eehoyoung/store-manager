package com.storemanager.api.sysauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @Mock SetOperations<String, String> setOps;

    private SessionService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);
        service = new SessionService(redis);
    }

    @Test
    void 세션을_만들면_TTL과_함께_저장하고_그대로_조회된다() {
        String sid = service.create(SessionService.Kind.ADMIN, "boss@sodam.test", Duration.ofMinutes(10));

        verify(valueOps).set(eq("sess:ADMIN:" + sid), eq("boss@sodam.test"), eq(Duration.ofMinutes(10)));
        when(valueOps.get("sess:ADMIN:" + sid)).thenReturn("boss@sodam.test");

        assertThat(service.subject(SessionService.Kind.ADMIN, sid)).contains("boss@sodam.test");
        assertThat(service.exists(SessionService.Kind.ADMIN, sid)).isTrue();
    }

    @Test
    void 없는_세션은_빈값이다() {
        assertThat(service.subject(SessionService.Kind.HQ, "no-such-sid")).isEmpty();
        assertThat(service.exists(SessionService.Kind.HQ, "no-such-sid")).isFalse();
    }

    @Test
    void 로그아웃은_세션키를_지운다() {
        service.revoke(SessionService.Kind.ADMIN, "sid-1");
        verify(redis).delete("sess:ADMIN:sid-1");
    }

    @Test
    void remainingSeconds는_TTL이_없으면_0이다() {
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(null);
        assertThat(service.remainingSeconds(SessionService.Kind.HQ, "sid")).isZero();
    }

    /** ★ 담당자 접근 중지는 그 사용자의 본부 세션 전부를 지운다(docs/26a). */
    @Test
    void 본부_세션_강제종료는_담당자의_모든_sid를_지운다() {
        when(setOps.members("hqsess:user:7")).thenReturn(Set.of("sid-a", "sid-b"));

        service.revokeAllHqSessions(7L);

        verify(redis).delete("sess:HQ:sid-a");
        verify(redis).delete("sess:HQ:sid-b");
        verify(redis).delete("hqsess:user:7");
    }

    @Test
    void 강제종료할_세션이_없으면_세트만_지운다() {
        when(setOps.members("hqsess:user:9")).thenReturn(null);

        service.revokeAllHqSessions(9L);

        verify(redis, never()).delete(eq("sess:HQ:sid-a"));
        verify(redis).delete("hqsess:user:9");
    }
}
