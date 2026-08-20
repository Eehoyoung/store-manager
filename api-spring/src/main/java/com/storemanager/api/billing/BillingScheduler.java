package com.storemanager.api.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 청구(B3)·미납(B4) 일일 배치. PublishScheduler 와 동일한 @ConditionalOnProperty 패턴 —
 * app.scheduler.billing.enabled=false 로 끌 수 있다(테스트 프로파일은 이 값을 false 로 지정해 비활성).
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.billing.enabled", havingValue = "true")
public class BillingScheduler {

    private final BillingService billingService;

    public BillingScheduler(BillingService billingService) {
        this.billingService = billingService;
    }

    /** 매일 새벽 3시(KST) — 기간 만료 구독 청구서 발행 + 기간 이월. */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void invoiceBatch() {
        billingService.runDailyInvoiceBatch();
    }

    /** 매일 새벽 3시 30분(KST) — 미납 알림·구독 상태 전이. 청구 배치 이후에 돌려야 갓 발행된 청구가 곧바로 미납으로 잡히지 않는다. */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    public void overdueBatch() {
        billingService.runDailyOverdueBatch();
    }
}
