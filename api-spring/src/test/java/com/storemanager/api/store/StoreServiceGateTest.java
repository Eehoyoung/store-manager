package com.storemanager.api.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storemanager.api.billing.Subscription;
import com.storemanager.api.billing.SubscriptionRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 비용이 나가는 작업의 게이트를 잠근다.
 *
 * <p>★ 이 게이트가 없으면 미납·해지 매장에 DataAPI 호출(건당 과금)과 LLM 토큰을 계속 쓰게 된다.
 * "결제 연동이 아직이니 일단 전부 허용" 으로 바꾸지 말 것 — 그 순간 서비스 비용이 새기 시작한다.
 */
class StoreServiceGateTest {

    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final StoreServiceGate gate = new StoreServiceGate(subscriptionRepository);

    private static Store store() {
        return Store.builder().id(1L).ownerId(1L).name("가게").status("ACTIVE").activatedAt(Instant.now()).build();
    }

    private void subscription(String status) {
        subscription(status, null);
    }

    private void subscription(String status, Instant serviceUntil) {
        when(subscriptionRepository.findByStoreIdAndStatusNot(1L, "CANCELED"))
                .thenReturn(Optional.of(Subscription.builder().storeId(1L).status(status)
                        .serviceUntil(serviceUntil).build()));
    }

    @Test
    void 계약과_구독이_모두_살아_있어야_서비스한다() {
        subscription("ACTIVE", Instant.now().plusSeconds(3600));
        assertThat(gate.isServiceable(store())).isTrue();
    }

    @Test
    void 구독행이_아예_없으면_서비스하지_않는다() {
        // 위탁 동의만 하고 결제를 안 한 매장. activated_at 만 보던 시절의 구멍이다.
        when(subscriptionRepository.findByStoreIdAndStatusNot(1L, "CANCELED")).thenReturn(Optional.empty());
        assertThat(gate.isServiceable(store())).isFalse();
    }

    @Test
    void 연체는_이용_시한_안에서만_서비스한다() {
        // PAST_DUE 는 결제 실패 후 유예(GRACE_DAYS)를 이용 시한으로 표현한다 — 상태가 아니라 시한이 막는다.
        subscription("PAST_DUE", Instant.now().plusSeconds(3600));
        assertThat(gate.isServiceable(store())).isTrue();
        subscription("PAST_DUE", Instant.now().minusSeconds(1));
        assertThat(gate.isServiceable(store())).isFalse();
    }

    @Test
    void 정지와_해지는_이용_시한이_남아도_서비스하지_않는다() {
        // SUSPENDED·CANCELED 는 시한과 무관하게 상태 자체로 차단한다.
        for (String status : new String[] {"SUSPENDED", "CANCELED"}) {
            subscription(status, Instant.now().plusSeconds(3600));
            assertThat(gate.isServiceable(store())).as(status).isFalse();
        }
    }

    @Test
    void 입금_전_TRIAL_은_서비스하지_않는다() {
        // 2026-08-23 결정: 입금을 확인한 뒤에만 서비스한다.
        // TRIAL 은 가입 직후 기본값이다 - 여기가 열려 있으면 아무나 가입만으로 서비스를 받는다.
        subscription("TRIAL");
        assertThat(gate.isServiceable(store())).isFalse();
    }

    @Test
    void 체험이든_유료든_이용_시한_안에서만_서비스한다() {
        // 자동결제 전환(2026-09-29) — 쿠폰 체험도 카드 등록 시 service_until 이 찍힌다.
        subscription("TRIAL", Instant.now().plusSeconds(3600));
        assertThat(gate.isServiceable(store())).isTrue();

        subscription("TRIAL", Instant.now().minusSeconds(1));
        assertThat(gate.isServiceable(store())).isFalse();
    }

    @Test
    void 구독을_상태_지정_없이_만들면_서비스되지_않는다() {
        // 기본값(TRIAL, service_until 없음)은 결제 전(UNPAID) — 카드 등록 전에는 서비스하지 않는다.
        when(subscriptionRepository.findByStoreIdAndStatusNot(1L, "CANCELED"))
                .thenReturn(Optional.of(Subscription.builder().storeId(1L)
                        .priceKrw(new java.math.BigDecimal("30000")).build()));
        assertThat(gate.isServiceable(store())).isFalse();
    }

    @Test
    void 구독이_살아_있어도_자격증명위탁동의가_없으면_서비스하지_않는다() {
        // 두 게이트는 서로 다른 것을 뜻한다. 하나가 통과했다고 다른 하나를 건너뛰면 안 된다.
        subscription("ACTIVE");
        Store noConsent = Store.builder().id(1L).ownerId(1L).name("가게").status("ACTIVE").build();
        assertThat(gate.isServiceable(noConsent)).isFalse();
    }

    @Test
    void 정지되거나_삭제된_매장은_서비스하지_않는다() {
        subscription("ACTIVE");
        assertThat(gate.isServiceable(
                Store.builder().id(1L).ownerId(1L).name("가게").status("PAUSED").activatedAt(Instant.now()).build()))
                .isFalse();
        assertThat(gate.isServiceable(Store.builder().id(1L).ownerId(1L).name("가게").status("ACTIVE")
                .activatedAt(Instant.now()).deletedAt(Instant.now()).build())).isFalse();
    }

    @Test
    void 매장이_null_이면_서비스하지_않는다() {
        assertThat(gate.isServiceable(null)).isFalse();
    }
}
