package com.storemanager.api.billing;

/**
 * 가맹 브랜드 구간 단가 조회 DTO(V49, 2026-09-30). admin·hq 두 컨트롤러가 함께 쓴다.
 *
 * <p>★ {@link HqBrandPricingResponse} 는 브랜드 단위 집계만 담는다 — 매장별 결제 상태·금액,
 * 약정 매장 수(committedStoreCount)는 넣지 않는다({@code hq.HqPricingDtoFieldsTest} 가 잠근다).
 */
public final class PricingDtos {

    private PricingDtos() {
    }

    /**
     * @param basis SNAPSHOT(그 달로 확정) | COMMITTED(브랜드 약정 단가) | DEFAULT(약정도 없어 기본 단가)
     *              | LIVE_ESTIMATE(다음 달 스냅샷 전, 지금 매장 수로 어림)
     * @param confirmed 그 달 청구에 실제로 쓰이는 값인가. 다음 달의 어림값(LIVE_ESTIMATE)만 false.
     */
    public record MonthPrice(String month, int unitPriceKrw, int totalKrw, Integer basisStoreCount, String basis,
            boolean confirmed) {
    }

    /** 관리자 콘솔 전용 — 약정 매장 수(committedStoreCount)를 포함한다. */
    public record AdminBrandPricingRow(String brandName, String status, Integer committedStoreCount,
            int paidStoreCount, MonthPrice lastMonth, MonthPrice thisMonth, MonthPrice nextMonth) {
    }

    /** 본부 조회 전용 — 매장별 결제 상태·금액·약정 매장 수를 노출하지 않는다. */
    public record HqBrandPricingResponse(String brandName, int paidStoreCount, MonthPrice lastMonth,
            MonthPrice thisMonth, MonthPrice nextMonth) {
    }
}
