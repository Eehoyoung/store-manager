package com.storemanager.api.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * payment 테이블 매핑 (docs/11 §2.7 + V11 계좌이체 컬럼). {@code pg_tx_id} 는 죽은 컬럼 — 값을 쓰지 않는다.
 * ★ {@code depositor_name} 은 [PII]. 로그·예외 메시지에 남기지 않는다(절대규칙 5 와 동일한 이유).
 */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey; // "구독ID:청구연월(yyyy-MM)" — 이중청구 방지(UNIQUE)

    @Column(name = "amount_krw", nullable = false)
    private BigDecimal amountKrw;

    @Column(name = "vat_krw", nullable = false)
    private BigDecimal vatKrw;

    @Builder.Default
    @Column(nullable = false)
    private String status = "PENDING"; // PENDING|PAID (계좌이체 흐름에서는 이 두 값만 쓴다)

    @Builder.Default
    @Column(nullable = false)
    private String method = "BANK_TRANSFER";

    @Column(name = "deposit_code")
    private String depositCode;

    @Column(name = "depositor_name")
    private String depositorName; // [PII]

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public BigDecimal totalKrw() {
        return amountKrw.add(vatKrw);
    }

    /**
     * 운영자 입금 확인(B6). 이미 PAID 면 아무 것도 바꾸지 않고 조용히 반환한다 —
     * 운영자가 확인 버튼을 두 번 눌러도 안전해야 한다(멱등).
     */
    public void markPaid(String depositorName, Instant paidAt, Long confirmedBy) {
        if ("PAID".equals(this.status)) {
            return;
        }
        this.status = "PAID";
        this.depositorName = depositorName;
        this.paidAt = paidAt;
        this.confirmedBy = confirmedBy;
    }
}
