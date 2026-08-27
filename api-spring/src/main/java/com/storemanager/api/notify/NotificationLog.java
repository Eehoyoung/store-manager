package com.storemanager.api.notify;

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

/** notification_log 테이블 매핑 (docs/11 §2.8). */
@Entity
@Table(name = "notification_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "store_id")
    private Long storeId;

    @Column(nullable = false)
    private String channel; // PUSH|ALIMTALK|EMAIL

    @Column(nullable = false)
    private String template;

    @Column(nullable = false)
    private String status; // RECORDED|QUEUED|SENDING|ACCEPTED|DELIVERED|FAILED|SKIPPED

    @Builder.Default
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload = "{}";

    @Column(name = "ref_type")
    private String refType;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Builder.Default
    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    void markSending() {
        this.status = "SENDING";
        this.attemptCount++;
        this.nextAttemptAt = null;
        this.errorCode = null;
        this.errorMessage = null;
    }

    void markAccepted(String messageId) {
        this.status = "ACCEPTED";
        this.providerMessageId = messageId;
    }

    void markDelivered(Instant at) {
        this.status = "DELIVERED";
        this.deliveredAt = at;
        this.errorCode = null;
        this.errorMessage = null;
    }

    void markFailed(String code, String message) {
        this.status = "FAILED";
        this.nextAttemptAt = null;
        this.errorCode = truncate(code, 40);
        this.errorMessage = truncate(message, 500);
    }

    void markSkipped(String code) {
        this.status = "SKIPPED";
        this.nextAttemptAt = null;
        this.errorCode = truncate(code, 40);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
