package com.storemanager.api.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** store 테이블 매핑 (docs/11 §2.2). */
@Entity
@Table(name = "store")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false)
    private String name;

    @Column(name = "brand_name")
    private String brandName;

    private String category;

    private String address;

    @Builder.Default
    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public void applyUpdate(String name, String category, String address) {
        if (name != null) {
            this.name = name;
        }
        if (category != null) {
            this.category = category;
        }
        if (address != null) {
            this.address = address;
        }
    }

    public void softDelete() {
        this.status = "DELETED";
        this.deletedAt = Instant.now();
    }

    /**
     * 회원 탈퇴에 따른 매장 정지.
     *
     * <p>★ activated_at 을 반드시 비운다. 이 값이 남아 있으면 수집·생성·게시가 계속 돌아
     * 탈퇴한 사람의 매장에 답글이 달리고 우리 비용이 나간다(StoreServiceGate 참고).
     */
    public void softDeleteForWithdrawal(Instant at) {
        this.status = "DELETED";
        this.deletedAt = at;
        this.activatedAt = null;
        this.updatedAt = at;
    }

    public void assignBrand(String brandName) {
        this.brandName = brandName;
        this.updatedAt = Instant.now();
    }
}
