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
 * subscription 테이블 매핑 (docs/11 §2.7). 계좌이체 전용 — {@code billing_key} 는 죽은 컬럼(V11 코멘트 참고, 값을 쓰지 않는다).
 * ★ TRIAL 은 이번 범위가 아니다. DDL 기본값은 TRIAL 이지만 이 서비스는 구독 생성 시 항상 ACTIVE 를 명시적으로 넣는다.
 * 상태 전이는 반드시 이 클래스의 메서드를 통해서만 한다(ReplyDraft 와 동일한 원칙).
 */
@Entity
@Table(name = "subscription")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Builder.Default
    @Column(name = "plan_code", nullable = false)
    private String planCode = "STANDARD";

    @Column(name = "price_krw", nullable = false)
    private BigDecimal priceKrw;

    @Builder.Default
    @Column(nullable = false)
    private String status = "ACTIVE"; // TRIAL|ACTIVE|PAST_DUE|SUSPENDED|CANCELED — TRIAL 은 이번 범위에서 쓰지 않는다

    @Column(name = "current_period_start")
    private Instant currentPeriodStart;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** 청구 배치(B3)가 기간이 지난 구독을 다음 기간으로 이월할 때 호출한다. */
    public void rollPeriod(Instant newStart, Instant newEnd) {
        this.currentPeriodStart = newStart;
        this.currentPeriodEnd = newEnd;
    }

    /** 미납 D+14(B4). ACTIVE 에서만 전이한다 — 이미 PAST_DUE/SUSPENDED 면 아무 것도 하지 않는다(멱등). */
    public void markPastDue() {
        if ("ACTIVE".equals(this.status)) {
            this.status = "PAST_DUE";
        }
    }

    /** 미납 D+21(B4). 서비스 중단 — 이미 SUSPENDED 면 멱등하게 무시한다(감사로그는 호출부에서 남긴다). */
    public void suspend() {
        this.status = "SUSPENDED";
    }

    /** 입금 확인(B6)으로 PAST_DUE/SUSPENDED 였던 구독을 ACTIVE 로 복구한다. 그 외 상태는 건드리지 않는다. */
    public void restoreActiveIfOverdue() {
        if ("PAST_DUE".equals(this.status) || "SUSPENDED".equals(this.status)) {
            this.status = "ACTIVE";
        }
    }
}
