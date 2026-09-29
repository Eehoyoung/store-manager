package com.storemanager.api.hq;

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

/**
 * franchise_hq_member 테이블 매핑 (docs/11 §2.7, FR-801 · docs/26a schema.V46 확장).
 * 행이 존재하면 그 사용자는 해당 브랜드(store.brand_name)의 본부 사용자다.
 * ★ app_user 에 role 컬럼을 두지 않는다 — 본부 권한의 유일한 근거는 이 테이블이다.
 * ★ name 컬럼을 따로 두지 않는다 — 표시 이름은 항상 app_user.name 을 조인해 쓴다(중복 방지).
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

    @Builder.Default
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "brand_name", nullable = false)
    private String brandName;

    private String title;

    @Builder.Default
    @Column(nullable = false)
    private String status = "ACTIVE";

    /** 초대한 시스템 관리자의 adminRef(비식별). 관리자는 app_user 가 없어 FK 를 쓰지 않는다. */
    @Column(name = "invited_ref")
    private String invitedRef;

    @Builder.Default
    @Column(name = "invited_at", nullable = false)
    private Instant invitedAt = Instant.now();

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_ref")
    private String revokedRef;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public void recordLogin(Instant at) {
        this.lastLoginAt = at;
    }

    public void updateProfile(String title) {
        if (title != null) {
            this.title = title;
        }
    }

    public void activate() {
        this.status = "ACTIVE";
        this.revokedAt = null;
        this.revokedRef = null;
    }

    public void revoke(String actorRef, Instant at) {
        this.status = "REVOKED";
        this.revokedAt = at;
        this.revokedRef = actorRef;
    }
}
