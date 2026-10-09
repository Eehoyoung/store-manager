package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 서비스 가능 판정의 정본(isServiceableAt) 경계 — DB 없이 돈다(2026-10-09).
 * ★ 같은 조건을 SQL 로 쓰는 3곳(findNeedingDraft·DailyBriefingService·worker/credentials.py)과 함께 움직인다.
 */
class SubscriptionServiceableTest {

    // 결제예정일 2026-10-10 KST → 제한 시작 2026-10-13 00:00 KST = 2026-10-12T15:00Z
    private static final Instant NEXT_BILLING = Instant.parse("2026-10-10T00:00:00Z");
    private static final Instant RESTRICTED_FROM = Instant.parse("2026-10-12T15:00:00Z");

    private static Subscription sub(String status, Instant nextBilling) {
        Subscription s = Subscription.builder().storeId(1L).priceKrw(new BigDecimal("30000")).status(status).build();
        s.changeNextBillingAt(nextBilling);
        return s;
    }

    @Test
    void 제한_시작은_결제예정일_KST_날짜에_3일을_더한_자정이다() {
        assertThat(Subscription.restrictedFrom(NEXT_BILLING)).isEqualTo(RESTRICTED_FROM);
        assertThat(sub("ACTIVE", NEXT_BILLING).getServiceUntil()).isEqualTo(RESTRICTED_FROM);
    }

    @Test
    void 제한_시작_직전까지는_서비스하고_그_순간부터_막는다() {
        Subscription s = sub("ACTIVE", NEXT_BILLING);
        assertThat(s.isServiceableAt(RESTRICTED_FROM.minusMillis(1))).isTrue();
        assertThat(s.isServiceableAt(RESTRICTED_FROM)).isFalse();
    }

    @Test
    void 카드_등록_전_UNPAID_는_서비스하지_않는다() {
        // 쿠폰 가입만 하고 카드를 등록하지 않은 상태 — 결제예정일·이용 시한이 없다.
        assertThat(sub("TRIAL", null).isServiceableAt(Instant.parse("2026-10-09T00:00:00Z"))).isFalse();
    }

    @Test
    void 정지_해지_상태는_시한이_남아도_서비스하지_않는다() {
        Instant now = Instant.parse("2026-10-09T00:00:00Z");
        assertThat(sub("SUSPENDED", NEXT_BILLING).isServiceableAt(now)).isFalse();
        assertThat(sub("CANCELED", NEXT_BILLING).isServiceableAt(now)).isFalse();
        assertThat(sub("TRIAL", NEXT_BILLING).isServiceableAt(now)).isTrue();
        assertThat(sub("ACTIVE", NEXT_BILLING).isServiceableAt(now)).isTrue();
    }
}
