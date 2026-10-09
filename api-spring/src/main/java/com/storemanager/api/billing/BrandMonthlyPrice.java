package com.storemanager.api.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * brand_monthly_price 테이블 매핑(V49, 2026-09-30). 매월 25일 00:05(KST) 스냅샷이 '다음 달'
 * 유료 매장 수와 단가를 확정해 담는다. 확정 이후에는 매장 수가 늘거나 줄어도 그 달 단가는
 * 바뀌지 않는다 — 청구 중간에 단가가 흔들리면 안 되기 때문이다({@link BrandPricingService}).
 */
@Entity
@Table(name = "brand_monthly_price")
@IdClass(BrandMonthlyPrice.Key.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrandMonthlyPrice {

    @Id
    @Column(name = "brand_name", nullable = false)
    private String brandName;

    @Id
    @Column(name = "billing_month", nullable = false)
    private LocalDate billingMonth;

    @Column(name = "paid_store_count", nullable = false)
    private int paidStoreCount;

    @Column(name = "unit_price", nullable = false)
    private int unitPrice;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** 단가 변경 안내(또는 무변경 확인) 처리 시각. null 이면 아직 처리하지 않았다. */
    @Column(name = "notified_at")
    private Instant notifiedAt;

    public void markNotified(Instant at) {
        this.notifiedAt = at;
    }

    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String brandName;
        private LocalDate billingMonth;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(brandName, key.brandName) && Objects.equals(billingMonth, key.billingMonth);
        }

        @Override
        public int hashCode() {
            return Objects.hash(brandName, billingMonth);
        }
    }
}
