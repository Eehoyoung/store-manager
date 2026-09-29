package com.storemanager.api.sysauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * OtpService 단위테스트 — 실제 Redis 대신 Map 기반 가짜 저장소로 opsForValue()를 흉내낸다.
 * docs/26a auth.otp: 만료, 재사용, 5회 실패, 속도 제한.
 */
class OtpServiceTest {

    private final Map<String, String> store = new HashMap<>();
    private final Set<String> setIfAbsentKeys = new HashSet<>();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private AuditLogRepository auditLogRepository;
    private OtpService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        auditLogRepository = mock(AuditLogRepository.class);
        store.clear();
        setIfAbsentKeys.clear();

        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(inv -> store.get((String) inv.getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any());
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return setIfAbsentKeys.add(key);
        });
        // 실제 Redis INCR 처럼 키별로 누적한다(시도 횟수 원자 카운터).
        when(valueOps.increment(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            long next = Long.parseLong(store.getOrDefault(key, "0")) + 1;
            store.put(key, String.valueOf(next));
            return next;
        });
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(120L);
        // 실제 Redis DEL 처럼 지웠는지를 돌려준다 — 성공 판정이 "삭제에 성공한 한 요청" 이다.
        when(redis.delete(anyString())).thenAnswer(inv -> store.remove((String) inv.getArgument(0)) != null);

        service = new OtpService(redis, new ObjectMapper(), auditLogRepository, "test-jwt-secret-value-1234567890");
    }

    @Test
    void 발급한_코드로_검증하면_성공하고_재사용은_실패한다() {
        String code = service.issue(OtpService.Kind.ADMIN, "boss@sodam.test", "1.2.3.4").orElseThrow();

        assertThat(service.verify(OtpService.Kind.ADMIN, "boss@sodam.test", code)).isTrue();
        // ★ 검증 성공 시 즉시 삭제 — 같은 코드로 다시 검증하면 실패해야 한다(재사용 불가).
        assertThat(service.verify(OtpService.Kind.ADMIN, "boss@sodam.test", code)).isFalse();
    }

    @Test
    void 잘못된_코드는_실패한다() {
        service.issue(OtpService.Kind.ADMIN, "boss@sodam.test", "1.2.3.4");

        assertThat(service.verify(OtpService.Kind.ADMIN, "boss@sodam.test", "000000")).isFalse();
    }

    @Test
    void 요청하지_않은_이메일_검증은_항상_실패한다() {
        assertThat(service.verify(OtpService.Kind.HQ, "nobody@sodam.test", "123456")).isFalse();
    }

    @Test
    void 오답_5회면_코드가_삭제되어_정답도_더이상_통하지_않는다() {
        String code = service.issue(OtpService.Kind.HQ, "hq@sodam.test", "1.2.3.4").orElseThrow();

        for (int i = 0; i < 5; i++) {
            service.verify(OtpService.Kind.HQ, "hq@sodam.test", "999999");
        }

        assertThat(service.verify(OtpService.Kind.HQ, "hq@sodam.test", code)).isFalse();
    }

    @Test
    void 이메일당_60초_이내_재요청은_속도제한으로_코드를_만들지_않는다() {
        Optional<String> first = service.issue(OtpService.Kind.ADMIN, "boss@sodam.test", "1.2.3.4");
        Optional<String> second = service.issue(OtpService.Kind.ADMIN, "boss@sodam.test", "1.2.3.4");

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
    }

    @Test
    void 발급과_검증마다_감사로그를_남긴다() {
        String code = service.issue(OtpService.Kind.ADMIN, "boss@sodam.test", "1.2.3.4").orElseThrow();
        service.verify(OtpService.Kind.ADMIN, "boss@sodam.test", code);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        org.mockito.Mockito.verify(auditLogRepository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(AuditLog::getAction)
                .contains("OTP_REQUESTED", "OTP_VERIFIED");
        // ★ 이메일·OTP 원문은 절대 담지 않는다.
        assertThat(captor.getAllValues()).noneSatisfy(log -> assertThat(log.getDetail())
                .containsIgnoringCase("boss@sodam.test").containsIgnoringCase(code));
    }
}
