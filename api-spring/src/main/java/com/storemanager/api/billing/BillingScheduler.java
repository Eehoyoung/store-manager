package com.storemanager.api.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정기 자동결제 갱신(docs/13 §9, 2026-09-29). app.scheduler.billing.enabled=false 로 끌 수 있다
 * (테스트 프로파일은 이 값을 false 로 지정해 비활성 — BillingServiceIT는 renew()를 직접 호출한다).
 *
 * <p>★ 무통장 청구·미납 배치(구 runDailyInvoiceBatch/runDailyOverdueBatch)는 포트원 전환과 함께
 * 제거했다. 요금제가 하나뿐이고 카드 자동결제만 받는 지금, 그 배치들과 함께 돌리면 서로 다른 결제
 * 수단을 같은 구독에 이중으로 청구하려 든다.
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.billing.enabled", havingValue = "true")
public class BillingScheduler {

    private final BillingService billingService;
    private final TrialConversionNoticeService trialNotices;

    public BillingScheduler(BillingService billingService, TrialConversionNoticeService trialNotices) {
        this.billingService = billingService;
        this.trialNotices = trialNotices;
    }

    /** 매일 09:00(Asia/Seoul). 체험 종료 7일 전에 들어온 구독에 유료 전환 사전고지를 보낸다. */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void trialConversionNotice() {
        trialNotices.sendDue(java.time.Instant.now());
    }

    /** 매일 00:10(Asia/Seoul). 오늘(KST)이 결제예정일인 매장을 모두 청구한다. */
    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Seoul")
    public void renew() {
        billingService.renewAllDue();
    }
}
