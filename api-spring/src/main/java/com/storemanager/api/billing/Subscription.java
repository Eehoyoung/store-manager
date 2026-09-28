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
 * subscription 테이블 매핑 (docs/11 §2.7). Groble 공식 스키마 수령 전 기존 결제 필드를 임의 재사용하지 않는다.
 * ★ 일반 TRIAL 은 입금 대기라 서비스하지 않는다. 단, 서버가 검증해 promotion_code 와
 * trial_ends_at 을 함께 기록한 OPEN30 체험은 종료 시각 전까지만 서비스한다.
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

    /**
     * TRIAL|ACTIVE|PAST_DUE|SUSPENDED|CANCELED
     *
     * ★ 기본값은 반드시 '서비스하지 않는' 상태여야 한다. 예전 기본값이 ACTIVE 라, 상태를 적지 않고
     *   구독을 만들면 입금 없이 곧바로 서비스가 시작됐다. DDL 기본값(TRIAL)과도 어긋나 있었다.
     * ★ ACTIVE 는 입금을 확인한 뒤에만 명시적으로 넣는다(2026-08-23 결정).
     */
    @Builder.Default
    @Column(nullable = false)
    private String status = "TRIAL";

    @Column(name = "current_period_start")
    private Instant currentPeriodStart;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    @Column(name = "promotion_code")
    private String promotionCode;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "cancellation_requested_at")
    private Instant cancellationRequestedAt;

    @Column(name = "billing_key")
    private String billingKey;

    @Column(name = "billing_channel_key")
    private String billingChannelKey;

    @Builder.Default
    @Column(name = "auto_renew", nullable = false)
    private boolean autoRenew = false;

    @Column(name = "next_billing_at")
    private Instant nextBillingAt;

    /** 이용 가능 시한 = 결제예정일 KST 날짜 + 3일 00:00. {@link #changeNextBillingAt} 만 쓴다(V47). */
    @Column(name = "service_until")
    private Instant serviceUntil;

    @Builder.Default
    @Column(name = "renewal_failures", nullable = false)
    private int renewalFailures = 0;

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

    /** 결제예정일(D)이 지나도 D+2 23:59:59(KST)까지 서비스하고 D+3 00:00부터 막는다(소담한판과 동일). */
    public static final int GRACE_DAYS = 2;
    private static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");

    /** 이용 제한이 시작되는 시각. */
    public static Instant restrictedFrom(Instant nextBillingAt) {
        return nextBillingAt.atZone(KST).toLocalDate().plusDays(GRACE_DAYS + 1).atStartOfDay(KST).toInstant();
    }

    /**
     * 결제예정일과 이용 시한을 함께 바꾸는 유일한 자리. 둘이 갈라지면 판정이 두 벌이 된다.
     * null 이면 결제 전(UNPAID) — 서비스하지 않는다. 쿠폰 체험도 카드를 등록해야 시작된다.
     */
    public void changeNextBillingAt(Instant next) {
        this.nextBillingAt = next;
        this.serviceUntil = next == null ? null : restrictedFrom(next);
        this.updatedAt = Instant.now();
    }

    /**
     * 비용을 써도 되는가. ★ 같은 조건을 SQL 로 쓰는 곳이 있다 — 함께 바꿀 것:
     * UnifiedReviewRepository.findNeedingDraft · DailyBriefingService · worker/credentials.py.
     * 조건: status NOT IN ('SUSPENDED','CANCELED') AND service_until > now.
     */
    public boolean isServiceableAt(Instant now) {
        return !"SUSPENDED".equals(this.status)
                && !"CANCELED".equals(this.status)
                && this.serviceUntil != null
                && now.isBefore(this.serviceUntil);
    }

    /** 화면용 상태. 저장하지 않고 날짜로 계산한다 — 스케줄러가 늦어도 판정이 밀리지 않는다. */
    public String serviceStateAt(Instant now) {
        if ("CANCELED".equals(this.status)) return "CANCELED";
        if ("SUSPENDED".equals(this.status)) return "SUSPENDED";
        if (this.nextBillingAt == null || this.serviceUntil == null) return "UNPAID";
        if (now.isBefore(this.nextBillingAt)) {
            return this.trialEndsAt != null && now.isBefore(this.trialEndsAt) ? "TRIAL" : "ACTIVE";
        }
        return now.isBefore(this.serviceUntil) ? "GRACE" : "RESTRICTED";
    }

    /** 체험 종료 뒤 첫 유료기간을 연다. 반복 배치에서도 기간이 계속 밀리지 않게 한 번만 전이한다. */
    public void openFirstPaidPeriodAfterTrial(Instant paidPeriodEnd) {
        if (this.trialEndsAt != null
                && (this.currentPeriodStart == null || this.currentPeriodStart.isBefore(this.trialEndsAt))) {
            this.currentPeriodStart = this.trialEndsAt;
            this.currentPeriodEnd = paidPeriodEnd;
            this.updatedAt = Instant.now();
        }
    }

    /** 미납 D+14(B4). ACTIVE 에서만 전이한다 — 이미 PAST_DUE/SUSPENDED 면 아무 것도 하지 않는다(멱등). */
    public void markPastDue() {
        if ("ACTIVE".equals(this.status) || "TRIAL".equals(this.status)) {
            this.status = "PAST_DUE";
            this.updatedAt = Instant.now();
        }
    }

    /** 미납 D+21(B4). 서비스 중단 — 이미 SUSPENDED 면 멱등하게 무시한다(감사로그는 호출부에서 남긴다). */
    /**
     * 운영자가 입금을 확인하고 서비스를 연다 (Groble 연동 전까지의 수동 경로).
     *
     * <p>★ 이 메서드가 유일하게 ACTIVE 를 만드는 정상 경로다. 가입·구독생성은 TRIAL 로 남으며
     * 서비스되지 않는다(2026-08-23 결정). 자동 활성화를 다시 만들지 말 것.
     */
    public void activateByOperator(Instant periodStart, Instant periodEnd) {
        this.status = "ACTIVE";
        this.currentPeriodStart = periodStart;
        this.currentPeriodEnd = periodEnd;
        this.canceledAt = null;
        // 자동결제가 없는 수동 활성화도 기간 끝을 결제예정일로 둔다. 유예 뒤 제한되는 것은 같다.
        changeNextBillingAt(periodEnd);
    }

    public void activateFromProvider(Instant periodStart, Instant periodEnd) {
        this.status = "ACTIVE";
        this.currentPeriodStart = periodStart;
        this.currentPeriodEnd = periodEnd;
        this.canceledAt = null;
        this.updatedAt = Instant.now();
    }

    public void enableAutoRenew(String billingKey, String channelKey, Instant paidAt, Instant nextBillingAt) {
        this.billingKey = billingKey;
        this.billingChannelKey = channelKey;
        this.autoRenew = true;
        this.renewalFailures = 0;
        activateFromProvider(paidAt, nextBillingAt);
        this.nextBillingAt = nextBillingAt;
    }

    public void recordRenewal(Instant paidAt, Instant nextBillingAt) {
        this.currentPeriodStart = paidAt;
        this.currentPeriodEnd = nextBillingAt;
        this.nextBillingAt = nextBillingAt;
        this.renewalFailures = 0;
        this.status = "ACTIVE";
        this.updatedAt = paidAt;
    }

    public void recordRenewalFailure() {
        this.renewalFailures++;
        this.updatedAt = Instant.now();
    }

    public void disableAutoRenew() {
        this.autoRenew = false;
        this.updatedAt = Instant.now();
    }

    /**
     * 회원 탈퇴에 따른 즉시 해지. 해지 접수(cancellation_requested_at)와 다르다 —
     * 그건 '다음 주기부터' 지만 이건 지금 끊는 것이다.
     *
     * <p>★ 이걸 빠뜨리면 탈퇴한 사람에게 요금이 청구된다.
     */
    public void cancelImmediately() {
        this.status = "CANCELED";
        this.canceledAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void suspend() {
        this.status = "SUSPENDED";
    }

    /** 입금 확인(B6)으로 PAST_DUE/SUSPENDED 였던 구독을 ACTIVE 로 복구한다. 그 외 상태는 건드리지 않는다. */
    public void restoreActiveIfOverdue() {
        if ("TRIAL".equals(this.status) || "PAST_DUE".equals(this.status) || "SUSPENDED".equals(this.status)) {
            this.status = "ACTIVE";
            this.updatedAt = Instant.now();
        }
    }

    /** Groble 해지 완료가 아니라 우리 시스템에 접수된 요청만 기록한다. */
    public boolean requestCancellation(Instant requestedAt) {
        if (this.cancellationRequestedAt != null) {
            return false;
        }
        this.cancellationRequestedAt = requestedAt;
        this.updatedAt = requestedAt;
        return true;
    }
}
