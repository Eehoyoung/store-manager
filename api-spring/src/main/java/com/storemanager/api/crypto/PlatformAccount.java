package com.storemanager.api.crypto;

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
 * platform_account 테이블 매핑 (docs/11 §2.3).
 * 평문 비밀번호 필드는 존재하지 않는다 — enc_password/enc_dek 는 CredentialService 를 통해서만 다룬다.
 */
@Entity
@Table(name = "platform_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PlatformAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false)
    private String platform;

    @Column(name = "login_id", nullable = false)
    private String loginId; // [PII]

    @Column(name = "enc_password", nullable = false)
    private byte[] encPassword; // [PII] DEK로 암호화된 암호문. CredentialService 외부에서 다루지 않는다.

    @Column(name = "enc_dek", nullable = false)
    private byte[] encDek;

    @Column(name = "kms_key_id", nullable = false)
    private String kmsKeyId;

    @Builder.Default
    @Column(name = "enc_algorithm", nullable = false)
    private String encAlgorithm = "AES-256-GCM";

    @Column(name = "enc_nonce", nullable = false)
    private byte[] encNonce;

    @Column(name = "password_fingerprint", nullable = false)
    private byte[] passwordFingerprint; // HMAC-SHA256 지문. 복호화·DataAPI 전송에 사용하지 않는다.

    @Builder.Default
    @Column(name = "link_status", nullable = false)
    private String linkStatus = "PENDING";

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * 등록 시 사장님이 지정한 매장. 첫 수집에서 플랫폼 STOREID 를 발견하면 이 매장과 연결한다.
     * 이 값이 없으면 발견한 플랫폼 매장을 어디에 붙일지 알 수 없어 리뷰가 전부 버려진다.
     */
    @Column(name = "intended_store_id")
    private Long intendedStoreId;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** DataAPI 로그인 실패 등 연동 오류 발생 시 상태를 전이한다 (docs/08 F-1, FR-105, collect-result action=LINK_ERROR). */
    public void markLinkError(String ecode) {
        this.linkStatus = "ERROR";
        this.lastErrorCode = ecode;
        this.lastErrorAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * DataAPI 조회가 실제로 성공했을 때 연동 완료로 전이한다.
     *
     * ★ '요청을 보냈다' 가 아니라 'data.RESULT == SUCCESS 를 받았다' 일 때만 호출한다(절대규칙 2).
     *   verifiedAt 은 처음 성공한 시점을 남기고, lastSyncedAt 은 매번 갱신한다 —
     *   "언제부터 연동됐나" 와 "마지막으로 언제 돌았나" 는 다른 질문이다.
     */
    public void markVerified() {
        this.linkStatus = "LINKED";
        this.lastErrorCode = null;
        this.lastErrorAt = null;
        if (this.verifiedAt == null) {
            this.verifiedAt = Instant.now();
        }
        this.lastSyncedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** DataAPI 검증 전 상태. 외부 규격이 없을 때 성공으로 오판하지 않는다. */
    public void markVerificationPending(String reason) {
        this.linkStatus = "PENDING";
        this.lastErrorCode = reason;
        this.lastErrorAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** 연동 철회 시 암호문을 즉시 무효화하고 계정을 재사용하지 못하게 한다. */
    public void revoke() {
        this.linkStatus = "REVOKED";
        this.revokedAt = Instant.now();
        this.encPassword = new byte[0];
        this.encDek = new byte[0];
        this.encNonce = new byte[0];
        this.updatedAt = Instant.now();
    }
}
