package com.storemanager.api.billing;

/**
 * 가맹 브랜드 구간 단가표(부가세 별도, 2026-09-30 운영자 결정). 구간은 이 클래스 한 곳에서만 정의한다.
 *
 * <p>브랜드 소속 유료 이용 매장 수가 많을수록 매장당 단가가 낮아진다:
 * 1~49=30,000 / 50~99=29,000 / 100~199=27,000 / 200~299=26,000 / 300~399=25,000 /
 * 400~499=24,000 / 500~=23,000원.
 */
public final class PricingTier {

    private PricingTier() {
    }

    /** 각 구간의 상한(미만). 마지막 구간(500~)은 상한이 없어 배열 밖으로 떨어지면 그 값을 쓴다. */
    private static final int[] UPPER_BOUNDS = {50, 100, 200, 300, 400, 500};
    private static final int[] PRICES_KRW = {30000, 29000, 27000, 26000, 25000, 24000, 23000};

    /** 브랜드가 없거나 약정·스냅샷이 없을 때 쓰는 기본 단가(1~49 구간과 같다). */
    public static final int DEFAULT_UNIT_PRICE = PRICES_KRW[0];

    /** 유료 이용 매장 수에 대응하는 월 단가(부가세 별도). 음수는 0으로 취급한다. */
    public static int monthlyPrice(int paidStores) {
        int n = Math.max(paidStores, 0);
        for (int i = 0; i < UPPER_BOUNDS.length; i++) {
            if (n < UPPER_BOUNDS[i]) {
                return PRICES_KRW[i];
            }
        }
        return PRICES_KRW[PRICES_KRW.length - 1];
    }

    public static int vat(int unitPriceKrw) {
        return unitPriceKrw / 10;
    }

    public static int total(int unitPriceKrw) {
        return unitPriceKrw + vat(unitPriceKrw);
    }
}
