package com.storemanager.api.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storemanager.api.agreement.TieredPricingFlag;
import com.storemanager.api.billing.Subscription;
import com.storemanager.api.billing.SubscriptionRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 선택형 요금(D38) 플랫폼 게이트. 판정 순서: 플래그 꺼짐 → NAVER → 명단 null → 명단 포함, 그 밖은 차단.
 * ★ 꺼짐이면 모든 플랫폼이 통과해야 한다 — 그래야 이 PR 이 운영 동작을 바꾸지 않는다.
 */
class StoreServiceGatePlatformTest {

    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);

    private StoreServiceGate gate(boolean on) {
        return new StoreServiceGate(subscriptionRepository, new TieredPricingFlag(on ? "true" : "false"));
    }

    private static Store store() {
        return Store.builder().id(1L).ownerId(1L).name("가게").status("ACTIVE").activatedAt(Instant.now()).build();
    }

    private void selected(String... platforms) {
        when(subscriptionRepository.findByStoreIdAndStatusNot(1L, "CANCELED"))
                .thenReturn(Optional.of(Subscription.builder().storeId(1L).status("ACTIVE")
                        .serviceUntil(Instant.now().plusSeconds(3600))
                        .activeDeliveryPlatforms(platforms.length == 0 ? null : platforms).build()));
    }

    @Test
    void 플래그_꺼짐이면_명단과_무관하게_모든_플랫폼이_통과한다() {
        selected("BAEMIN");
        StoreServiceGate off = gate(false);
        assertThat(off.isServiceable(store(), "YOGIYO")).isTrue();
        assertThat(off.isServiceable(store(), "COUPANGEATS")).isTrue();
        assertThat(off.isServiceable(store(), null)).isTrue();
        assertThat(off.isServiceable(store(), "UNKNOWN")).isTrue();
    }

    @Test
    void 기존_생성자는_플래그_꺼짐과_같다() {
        selected("BAEMIN");
        assertThat(new StoreServiceGate(subscriptionRepository).isServiceable(store(), "YOGIYO")).isTrue();
    }

    @Test
    void gateOnBlocksUnselectedPlatform() {
        selected("BAEMIN");
        StoreServiceGate on = gate(true);
        assertThat(on.isServiceable(store(), "BAEMIN")).isTrue();
        assertThat(on.isServiceable(store(), "YOGIYO")).isFalse();
        assertThat(on.isServiceable(store(), "COUPANGEATS")).isFalse();
    }

    @Test
    void 켜짐에서_네이버는_모든_상품에_포함된다() {
        selected("BAEMIN");
        assertThat(gate(true).isServiceable(store(), "NAVER")).isTrue();
        assertThat(gate(true).isServiceable(store(), "naver")).isFalse(); // 정확히 NAVER 만
    }

    @Test
    void 켜짐에서_명단이_없으면_3개_상품이다() {
        selected();
        StoreServiceGate on = gate(true);
        assertThat(on.isServiceable(store(), "BAEMIN")).isTrue();
        assertThat(on.isServiceable(store(), "YOGIYO")).isTrue();
        assertThat(on.isServiceable(store(), "COUPANGEATS")).isTrue();
    }

    @Test
    void gateUnknownPlatformBlockedWhenOn() {
        selected();
        StoreServiceGate on = gate(true);
        assertThat(on.isServiceable(store(), null)).isFalse();
        assertThat(on.isServiceable(store(), "UNKNOWN")).isFalse();
        assertThat(on.isServiceable(store(), "baemin")).isFalse();
    }

    @Test
    void 켜짐에서도_매장_판정이_먼저다() {
        when(subscriptionRepository.findByStoreIdAndStatusNot(1L, "CANCELED")).thenReturn(Optional.empty());
        assertThat(gate(true).isServiceable(store(), "NAVER")).isFalse();
        assertThat(gate(true).isPlatformSelected(1L, "BAEMIN")).isFalse();
    }
}
