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
 * subscription 테이블 매핑 (docs/11 2.7장). Groble 공식 스키마 수령 전 기존 결제 필드를 임의 재사용하지 않는다.
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

    /** 유료 전환 사전고지 메일 발송 성공 시각(V48). 비어 있으면 아직 고지하지 않았다. */
    @Column(name = "trial_notice_sent_at")
    private Instant trialNoticeSentAt;

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

    /** 청구 직전 매장 단위 잠금(조건부 UPDATE, V47). PG 호출은 트랜잭션 밖이라 행 잠금으로는 못 막는다. */
    @Column(name = "billing_lock_until")
    private Instant billingLockUntil;

    @Builder.Default
    @Column(name = "renewal_failures", nullable = false)
    private int renewalFailures = 0;

    /** 지금 적용 중인 배달앱 선택 명단(V50). null = 3개 상품(현재 의미). TIERED_PRICING_ENABLED 꺼짐이면 읽지 않는다. */
    @Column(name = "active_delivery_platforms")
    private String[] activeDeliveryPlatforms;

    /** 다음 결제부터 적용될 명단(V50, PR5 에서 사용). null = 대기 없음. */
    @Column(name = "pending_delivery_platforms")
    private String[] pendingDeliveryPlatforms;

    /** 마지막 상품 변경 요청 시각(V50, PR5 월 1회 판정용). */
    @Column(name = "plan_changed_at")
    private Instant planChangedAt;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** 선택한 배달앱 수. 명단이 없으면(null, 기존 행·체험 중 미선택) 3개다. */
    public int selectedDeliveryCount() {
        return activeDeliveryPlatforms == null ? 3 : Math.min(Math.max(activeDeliveryPlatforms.length, 1), 3);
    }

    public static final java.util.List<String> DELIVERY_PLATFORMS = java.util.List.of("BAEMIN", "COUPANGEATS", "YOGIYO");

    /** 지금 적용 중인 명단(null = 3개 상품 = 전부). */
    public java.util.Set<String> effectivePlatforms() {
        return activeDeliveryPlatforms == null ? new java.util.TreeSet<>(DELIVERY_PLATFORMS)
                : new java.util.TreeSet<>(java.util.Arrays.asList(activeDeliveryPlatforms));
    }

    /** 다음 결제에 쓸 명단: 대기(pending)가 있으면 그것, 없으면 현재. */
    public java.util.Set<String> nextPlatforms() {
        return pendingDeliveryPlatforms == null ? effectivePlatforms()
                : new java.util.TreeSet<>(java.util.Arrays.asList(pendingDeliveryPlatforms));
    }

    /**
     * 상품 변경 예약(다음 결제부터 적용). pending 만 쓴다. 현재 명단과 같은 값이면 대기를 비운다(취소).
     * 호출자가 월 1회·미납 검사를 끝낸 뒤 부른다.
     */
    public void reservePlan(java.util.Set<String> requested, Instant now) {
        boolean cancel = requested.equals(effectivePlatforms());
        this.pendingDeliveryPlatforms = cancel ? null : requested.toArray(new String[0]);
        // 취소는 월 1회를 소모하지 않는다(9/30 예약 → 10/1 취소가 10월 변경권을 먹지 않게).
        // 같은 달 예약 → 취소 → 재예약은 첫 예약 시각으로 여전히 막힌다.
        if (!cancel) {
            this.planChangedAt = now;
        }
        this.updatedAt = now;
    }

    /**
     * 킬 스위치 조건: 3개 미만 명단(현재 또는 대기)이 있는가. 꺼짐 가격은 3개 상품과 같으므로
     * 3개 명단은 꺼짐에서도 그대로 청구해도 된다.
     */
    public boolean hasSelectionBelowThree() {
        return (activeDeliveryPlatforms != null && activeDeliveryPlatforms.length < 3)
                || (pendingDeliveryPlatforms != null && pendingDeliveryPlatforms.length < 3);
    }

    /** 새 명단을 현재 명단으로 확정하고 대기를 비운다. 승격(갱신 성공)·카드 등록 결제에서 쓴다. */
    public void applyActivePlatforms(java.util.Set<String> platforms) {
        this.activeDeliveryPlatforms = platforms.toArray(new String[0]);
        this.pendingDeliveryPlatforms = null;
        this.updatedAt = Instant.now();
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

    /**
     * 운영자가 입금을 확인하고 서비스를 연다 (Groble 연동 전까지의 수동 경로, admin 전용).
     *
     * <p>★ 이 메서드가 admin 경로에서 유일하게 ACTIVE 를 만드는 자리다. 자동결제 경로는
     * {@link #beginTrial} · {@link #chargeSucceeded} 를 쓴다. 지우거나 합치지 말 것 —
     * {@code AdminSubscriptionService} 가 그대로 참조한다.
     */
    public void activateByOperator(Instant periodStart, Instant periodEnd) {
        this.status = "ACTIVE";
        this.currentPeriodStart = periodStart;
        this.currentPeriodEnd = periodEnd;
        this.canceledAt = null;
        // 자동결제가 없는 수동 활성화도 기간 끝을 결제예정일로 둔다. 유예 뒤 제한되는 것은 같다.
        changeNextBillingAt(periodEnd);
    }

    /**
     * 쿠폰으로 예약해 둔 체험을 카드 등록 시점에 시작한다. 체험 종료일이 곧 첫 결제예정일이다.
     * 청구는 하지 않는다 — {@link BillingService#checkout} 이 금액을 0으로 판정한다.
     */
    public void markTrialNoticeSent(Instant sentAt) {
        this.trialNoticeSentAt = sentAt;
        this.updatedAt = sentAt;
    }

    public void beginTrial(Instant now, Instant trialEnd, String billingKey, String channelKey) {
        this.trialEndsAt = trialEnd;
        this.currentPeriodStart = now;
        this.currentPeriodEnd = trialEnd;
        this.billingKey = billingKey;
        this.billingChannelKey = channelKey;
        this.autoRenew = true;
        changeNextBillingAt(trialEnd);
    }

    /**
     * 결제 기간 중 카드만 바꾼다. 청구는 없다.
     *
     * <p>★ autoRenew 를 켠다. 카드를 (다시) 등록하는 행위 자체가 자동결제 재개다 —
     * 자동결제를 끄면 빌링키를 지우므로({@link #disableAutoRenew}), 다시 켜는 유일한 길은
     * 카드를 다시 등록하는 것뿐이다(2026-09-29 결정).
     */
    public void swapBillingKey(String billingKey, String channelKey) {
        this.billingKey = billingKey;
        this.billingChannelKey = channelKey;
        this.autoRenew = true;
        this.updatedAt = Instant.now();
    }

    /** 결제 성공(최초 청구·유예 뒤 결제·정기 갱신 공통). 카드가 바뀌었으면 함께 반영한다. */
    public void chargeSucceeded(Instant periodStart, Instant periodEnd, String billingKey, String channelKey) {
        this.status = "ACTIVE";
        this.currentPeriodStart = periodStart;
        this.currentPeriodEnd = periodEnd;
        this.billingKey = billingKey;
        this.billingChannelKey = channelKey;
        this.autoRenew = true;
        this.renewalFailures = 0;
        changeNextBillingAt(periodEnd);
    }

    /** 정기 갱신 결제 실패. 상태는 그대로 두고 실패 횟수만 늘린다 — 유예·제한 판정은 {@link #serviceStateAt}이 한다. */
    public void recordChargeFailure() {
        this.renewalFailures++;
        this.updatedAt = Instant.now();
    }

    /**
     * 자동결제를 끄고 빌링키를 즉시 지운다(약관 제9조 제3항 제5호, 2026-09-29 결정).
     * 이미 결제한 기간({@code nextBillingAt}·{@code serviceUntil})은 건드리지 않는다 —
     * 해지해도 그 기간까지는 그대로 쓴다.
     *
     * <p>다시 켜는 길은 여기 없다. 카드가 없으므로 {@link #swapBillingKey} 로 카드를
     * 다시 등록해야 한다({@code BillingService.checkout}).
     *
     * @return 삭제해야 할 옛 빌링키. null 이면 애초에 카드가 없었다는 뜻이다.
     */
    public String disableAutoRenew() {
        String oldKey = this.billingKey;
        this.autoRenew = false;
        this.billingKey = null;
        this.billingChannelKey = null;
        this.updatedAt = Instant.now();
        return oldKey;
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
}
