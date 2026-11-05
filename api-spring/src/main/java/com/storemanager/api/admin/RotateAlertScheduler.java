package com.storemanager.api.admin;

import com.storemanager.api.sysauth.MailService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 비밀값 교체가 RED 가 되면 관리자에게 메일 1회. 점검 화면을 아무도 안 열어도 돈다.
 * 같은 status 줄에는 한 번만 보낸다 - 마지막으로 알린 줄을 Redis(appendonly)에 기억한다.
 * 켜는 플래그는 readiness-summary 와 같다. 본문에는 경로·비밀값을 넣지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.readiness-summary.enabled", havingValue = "true")
public class RotateAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(RotateAlertScheduler.class);
    static final String ALERTED_KEY = "readiness:rotate-alerted";

    private final ReadinessService readiness;
    private final AdminAccessGuard admins;
    private final MailService mail;
    private final StringRedisTemplate redis;

    public RotateAlertScheduler(ReadinessService readiness, AdminAccessGuard admins, MailService mail,
            StringRedisTemplate redis) {
        this.readiness = readiness;
        this.admins = admins;
        this.mail = mail;
        this.redis = redis;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void check() {
        check(Instant.now());
    }

    void check(Instant now) {
        String line = readiness.rotateStatusLine();
        var item = ReadinessEvaluator.rotateItem(line, now);
        String last = redis.opsForValue().get(ALERTED_KEY);
        if (!"RED".equals(item.status())) {
            if (last != null) {
                redis.delete(ALERTED_KEY); // 복구됨 - 다음 실패는 새로 알린다
            }
            return;
        }
        String key = line.trim();
        if (key.equals(last)) {
            return;
        }
        boolean sent = false;
        for (String to : admins.emails()) {
            sent |= mail.sendAdminNotice(to, "[소담리뷰] 비밀값 교체 점검 빨강", "비밀값 교체: " + item.detail()
                    + "\n점검 화면의 '비밀값 교체' 항목과 교체 로그를 확인해 주세요.");
        }
        if (sent) {
            redis.opsForValue().set(ALERTED_KEY, key);
        } else {
            log.warn("비밀값 교체 RED 이나 메일을 보내지 못했습니다(미설정 또는 수신자 없음). 다음 검사에서 다시 시도합니다.");
        }
    }
}
