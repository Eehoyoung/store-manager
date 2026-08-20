package com.storemanager.api.hq;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * franchise_hq_member 테이블 매핑 (docs/11 §2.7, FR-801).
 * 행이 존재하면 그 사용자는 해당 브랜드(store.brand_name)의 본부 사용자다.
 * ★ app_user 에 role 컬럼을 두지 않는다 — 본부 권한의 유일한 근거는 이 테이블이다.
 */
@Entity
@Table(name = "franchise_hq_member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class FranchiseHqMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "brand_name", nullable = false)
    private String brandName;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
