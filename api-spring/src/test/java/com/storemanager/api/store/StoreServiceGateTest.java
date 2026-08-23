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
        when(subscriptionRepository.findByStoreIdAndStatusNot(1L, "CANCELED"))
                .thenReturn(Optional.of(Subscription.builder().storeId(1L).status(status).build()));
    }

    @Test
    void 계약과_구독이_모두_살아_있어야_서비스한다() {
        subscription("ACTIVE");
        assertThat(gate.isServiceable(store())).isTrue();
    }

    @Test
    void 구독행이_아예_없으면_서비스하지_않는다() {
        // 전자계약만 하고 결제를 안 한 매장. activated_at 만 보던 시절의 구멍이다.
        when(subscriptionRepository.findByStoreIdAndStatusNot(1L, "CANCELED")).thenReturn(Optional.empty());
        assertThat(gate.isServiceable(store())).isFalse();
    }

    @Test
    void 연체와_정지_해지는_서비스하지_않는다() {
        for (String status : new String[] {"PAST_DUE", "SUSPENDED", "CANCELED"}) {
            subscription(status);
            assertThat(gate.isServiceable(store())).as(status).isFalse();
        }
    }

    @Test
    void 체험중은_서비스한다() {
        subscription("TRIAL");
        assertThat(gate.isServiceable(store())).isTrue();
    }

    @Test
    void 구독이_살아_있어도_전자계약이_없으면_서비스하지_않는다() {
        // 두 게이트는 서로 다른 것을 뜻한다. 하나가 통과했다고 다른 하나를 건너뛰면 안 된다.
        subscription("ACTIVE");
        Store noContract = Store.builder().id(1L).ownerId(1L).name("가게").status("ACTIVE").build();
        assertThat(gate.isServiceable(noContract)).isFalse();
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
