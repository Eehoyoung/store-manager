package com.storemanager.api.agreement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.net.InetAddress;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 동의와 철회를 덮어쓰지 않고 시간순으로 보존하는 증적. */
@Entity
@Table(name = "user_agreement")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserAgreement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "store_id") private Long storeId;
    @Column(name = "agreement_code", nullable = false) private String agreementCode;
    @Column(name = "doc_version", nullable = false) private String docVersion;
    @Column(nullable = false) private boolean agreed;
    @Column(name = "agreed_at", nullable = false) private Instant agreedAt;
    @JdbcTypeCode(SqlTypes.INET)
    private InetAddress ip; // [PII] 로그 출력 금지
    @Column(name = "user_agent", length = 300) private String userAgent;
    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
