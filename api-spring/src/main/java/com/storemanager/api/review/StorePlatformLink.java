package com.storemanager.api.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** store_platform_link 테이블 매핑 (docs/11 §2.3). 계정 1개에 매장 여러 개(F-7)를 지원하는 연결 테이블. */
@Entity
@Table(name = "store_platform_link")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class StorePlatformLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private String platform;

    @Column(name = "platform_store_id", nullable = false)
    private String platformStoreId; // DataAPI STOREID

    @Column(name = "store_name_snapshot")
    private String storeNameSnapshot;

    @Column(name = "avg_rating")
    private BigDecimal avgRating;

    @Column(name = "backfilled_at")
    private Instant backfilledAt;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** 워커가 보낸 최신 매장명·평점 스냅샷으로 갱신한다 (docs/08 §5 매핑). */
    public void updateSnapshot(String storeName, BigDecimal avgRating) {
        if (storeName != null) {
            this.storeNameSnapshot = storeName;
        }
        if (avgRating != null) {
            this.avgRating = avgRating;
        }
    }
}
