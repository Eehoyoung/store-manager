package com.storemanager.api.franchise;

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

/** 가맹점 가입용 코드. 원문 대신 SHA-256 해시만 저장한다. */
@Entity
@Table(name = "franchise_join_code")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class FranchiseJoinCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "brand_name", nullable = false)
    private String brandName;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    /** 교체한 시스템 관리자의 adminRef(비식별). */
    @Column(name = "rotated_ref")
    private String rotatedRef;

    /** 코드 교체. 기존 승인 매장에는 영향을 주지 않는다(가맹코드는 소속 심사 시점에만 쓰인다). */
    public void rotate(String newCodeHash, String actorRef, Instant at) {
        this.codeHash = newCodeHash;
        this.active = true;
        this.rotatedAt = at;
        this.rotatedRef = actorRef;
    }

    public void changeActive(boolean active) {
        this.active = active;
    }
}
