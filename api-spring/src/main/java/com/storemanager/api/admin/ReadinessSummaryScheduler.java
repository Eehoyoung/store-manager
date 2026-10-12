package com.storemanager.api.admin;

import com.storemanager.api.sysauth.MailService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 09:30(KST) 빨강 항목만 관리자 주소로 메일 1통. RED 가 없으면 보내지 않는다.
 * 사장님 주소로는 보내지 않는다(app.admin.emails 만).
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.readiness-summary.enabled", havingValue = "true")
public class ReadinessSummaryScheduler {

    private final ReadinessService readiness;
    private final AdminAccessGuard admins;
    private final MailService mail;

    public ReadinessSummaryScheduler(ReadinessService readiness, AdminAccessGuard admins, MailService mail) {
        this.readiness = readiness;
        this.admins = admins;
        this.mail = mail;
    }

    @Scheduled(cron = "0 30 9 * * *", zone = "Asia/Seoul")
    public void sendDailySummary() {
        var reds = readiness.report().items().stream().filter(i -> "RED".equals(i.status())).toList();
        if (reds.isEmpty()) {
            return;
        }
        String subject = "[소담리뷰] 출시 점검 빨강 " + reds.size() + "건";
        StringBuilder body = new StringBuilder();
        reds.forEach(i -> body.append("- ").append(i.label()).append(": ").append(i.detail()).append('\n'));
        for (String to : admins.emails()) {
            mail.sendAdminNotice(to, subject, body.toString());
        }
    }
}
