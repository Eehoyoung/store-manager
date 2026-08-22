package com.storemanager.api.user;

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

/** app_user 테이블 매핑 (docs/11 §2.2). 외부에는 publicId(UUID)만 노출한다. */
@Entity
@Table(name = "app_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    @Column(nullable = false)
    private String email; // [PII]

    @Column(name = "password_hash")
    private String passwordHash; // bcrypt. 소셜 로그인 시 NULL

    @Column(nullable = false)
    private String name; // [PII]

    private String phone; // [PII]

    @Column(name = "social_provider")
    private String socialProvider;

    @Column(name = "social_id")
    private String socialId;

    @Column(name = "biz_reg_no")
    private String bizRegNo; // [PII]

    @Builder.Default
    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "marketing_agreed_at")
    private Instant marketingAgreedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public void recordLogin(Instant at) {
        this.lastLoginAt = at;
    }

    public void updateProfile(String name, String phone) {
        this.name = name.trim();
        this.phone = phone == null || phone.isBlank() ? null : phone.trim();
        this.updatedAt = Instant.now();
    }

    public void changePassword(String encodedPassword) {
        this.passwordHash = encodedPassword;
        this.updatedAt = Instant.now();
    }
}
