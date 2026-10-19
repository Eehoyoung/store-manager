package com.storemanager.api.billing;

/**
 * 한 번의 청구 금액을 (공급가, VAT, 합계) 쌍으로 들고 다닌다. 합계는 항상 공급가 + VAT 이다.
 * ★ 금액을 만드는 곳은 여기 두 팩토리뿐이다. 합계에서 VAT 를 {@code unit/10} 으로 다시 구하면
 * 25,000원이 24,999원이 된다(22,727 + 2,272) — {@link PricingTier#vat}·{@link PricingTier#total} 을
 * 이 클래스 밖에서 부르지 말 것(PriceQuoteTest 가 grep 으로 막는다).
 */
public record PriceQuote(int supply, int vat, int total) {

    /** 공급가 기준 가격(가맹 구간 단가·기본 단가). VAT = 공급가의 10%. */
    public static PriceQuote ofSupply(int supplyKrw) {
        return new PriceQuote(supplyKrw, PricingTier.vat(supplyKrw), PricingTier.total(supplyKrw));
    }

    /** VAT 포함 정가 기준(선택형 요금 25,000/29,000/33,000). 공급가 = 포함가÷1.1 원 미만 반올림, VAT = 포함가 − 공급가. */
    public static PriceQuote ofTotal(int totalKrw) {
        int supply = (int) ((20L * totalKrw + 11) / 22); // round(total / 1.1), 정수 연산
        return new PriceQuote(supply, totalKrw - supply, totalKrw);
    }
}
