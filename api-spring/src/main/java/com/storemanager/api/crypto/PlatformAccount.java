package com.storemanager.api.crypto;

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
    }
}
