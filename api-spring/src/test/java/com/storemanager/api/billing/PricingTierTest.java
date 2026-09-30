package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 가맹 브랜드 구간 단가표(V49) 경계값. */
class PricingTierTest {

    @Test
    void 구간_경계를_정확히_나눈다() {
        assertThat(PricingTier.monthlyPrice(0)).isEqualTo(30000);
        assertThat(PricingTier.monthlyPrice(1)).isEqualTo(30000);
        assertThat(PricingTier.monthlyPrice(49)).isEqualTo(30000);
        assertThat(PricingTier.monthlyPrice(50)).isEqualTo(29000);
        assertThat(PricingTier.monthlyPrice(99)).isEqualTo(29000);
        assertThat(PricingTier.monthlyPrice(100)).isEqualTo(27000);
        assertThat(PricingTier.monthlyPrice(199)).isEqualTo(27000);
        assertThat(PricingTier.monthlyPrice(200)).isEqualTo(26000);
        assertThat(PricingTier.monthlyPrice(299)).isEqualTo(26000);
        assertThat(PricingTier.monthlyPrice(300)).isEqualTo(25000);
        assertThat(PricingTier.monthlyPrice(399)).isEqualTo(25000);
        assertThat(PricingTier.monthlyPrice(400)).isEqualTo(24000);
        assertThat(PricingTier.monthlyPrice(499)).isEqualTo(24000);
        assertThat(PricingTier.monthlyPrice(500)).isEqualTo(23000);
        assertThat(PricingTier.monthlyPrice(10000)).isEqualTo(23000);
    }

    @Test
    void 음수_입력은_0으로_취급한다() {
        assertThat(PricingTier.monthlyPrice(-5)).isEqualTo(PricingTier.DEFAULT_UNIT_PRICE);
    }

    @Test
    void 부가세와_합계를_계산한다() {
        assertThat(PricingTier.vat(30000)).isEqualTo(3000);
        assertThat(PricingTier.total(30000)).isEqualTo(33000);
        assertThat(PricingTier.vat(23000)).isEqualTo(2300);
        assertThat(PricingTier.total(23000)).isEqualTo(25300);
    }
}
