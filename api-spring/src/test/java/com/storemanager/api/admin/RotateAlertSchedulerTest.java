package com.storemanager.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storemanager.api.sysauth.MailService;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RotateAlertSchedulerTest {

    static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    static final String FAIL1 = "1 2026-10-02T19:00:00Z";
    static final String FAIL2 = "2 2027-01-02T19:00:00Z";
    static final String OK = "0 2026-10-02T19:00:00Z";

    final Map<String, String> store = new HashMap<>();
    final ReadinessService readiness = mock(ReadinessService.class);
    final AdminAccessGuard admins = mock(AdminAccessGuard.class);
    final MailService mail = mock(MailService.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final RotateAlertScheduler sched;

    @SuppressWarnings("unchecked")
    RotateAlertSchedulerTest() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(a -> store.get(a.<String>getArgument(0)));
        doAnswer(a -> store.put(a.<String>getArgument(0), a.<String>getArgument(1))).when(ops)
                .set(anyString(), anyString());
        when(redis.delete(anyString())).thenAnswer(a -> store.remove(a.<String>getArgument(0)) != null);
        when(admins.emails()).thenReturn(Set.of("ops@example.com"));
        when(mail.sendAdminNotice(anyString(), anyString(), anyString())).thenReturn(true);
        sched = new RotateAlertScheduler(readiness, admins, mail, redis);
    }

    void run(String line) {
        when(readiness.rotateStatusLine()).thenReturn(line);
        sched.check(NOW);
    }

    @Test
    void 같은_줄은_한_번만_새_실패_줄은_다시() {
        run(FAIL1);
        run(FAIL1);
        verify(mail, times(1)).sendAdminNotice(anyString(), anyString(), anyString());
        run(FAIL2);
        verify(mail, times(2)).sendAdminNotice(anyString(), anyString(), anyString());
    }

    @Test
    void 초록_복귀_뒤_다시_빨강이면_한_번() {
        run(FAIL1);
        run(OK);
        run(FAIL1);
        verify(mail, times(2)).sendAdminNotice(anyString(), anyString(), anyString());
    }

    @Test
    void 정상_미등록은_보내지_않고_메일_실패면_기억하지_않아_재시도() {
        run(OK);
        run(null);
        verify(mail, times(0)).sendAdminNotice(any(), any(), any());
        when(mail.sendAdminNotice(anyString(), anyString(), anyString())).thenReturn(false);
        run(FAIL1);
        assertThat(store).doesNotContainKey(RotateAlertScheduler.ALERTED_KEY);
        when(mail.sendAdminNotice(anyString(), anyString(), anyString())).thenReturn(true);
        run(FAIL1);
        assertThat(store).containsEntry(RotateAlertScheduler.ALERTED_KEY, FAIL1);
        verify(mail, times(2)).sendAdminNotice(eq("ops@example.com"), anyString(), anyString());
    }
}
