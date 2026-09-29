package com.storemanager.api.franchise;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * franchise_brand 테이블 매핑 (docs/26a schema.V46). 브랜드 활성 상태의 정본.
 * SUSPENDED 면 {@code HqAccessGuard} 가 그 브랜드의 모든 본부 조회를 404 로 막는다.
 */
@Entity
@Table(name = "franchise_brand")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class FranchiseBrand {

    @Id
    @Column(name = "brand_name")
    private String brandName;

    @Builder.Default
    @Column(nullable = false)
    private String status = "ACTIVE";

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_ref")
    private String createdRef;

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public void changeStatus(String status) {
        this.status = status;
    }
}
