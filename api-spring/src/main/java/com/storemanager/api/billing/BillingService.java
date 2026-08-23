package com.storemanager.api.billing;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.billing.BillingDtos.BillingMethod;
import com.storemanager.api.billing.BillingDtos.CancellationRequestResponse;
import com.storemanager.api.billing.BillingDtos.ConfirmPaymentRequest;
import com.storemanager.api.billing.BillingDtos.ConfirmPaymentResponse;
import com.storemanager.api.billing.BillingDtos.PaymentItem;
import com.storemanager.api.billing.BillingDtos.PaymentListResponse;
import com.storemanager.api.billing.BillingDtos.SubscriptionResponse;
import com.storemanager.api.billing.BillingDtos.TransferInfo;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.notify.NotificationLogRepository;
import com.storemanager.api.notify.Notifier;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 레거시 자체 계좌이체 처리. 신규 결제에는 사용하지 않으며 삭제 전 데이터 호환용으로만 남긴다.
 * ★ 이 클래스는 depositorName·계좌번호를 로그에 남기지 않는다(절대규칙 5 와 동일한 이유, B7).
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final BigDecimal PRICE_KRW = BigDecimal.valueOf(30000);
    private static final BigDecimal VAT_KRW = BigDecimal.valueOf(3000); // 10%
    private static final String DEPOSIT_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 0/O,1/I/L 등 혼동 문자 제외
    private static final int DEPOSIT_CODE_LEN = 6;
    private static final int DEPOSIT_CODE_MAX_ATTEMPTS = 10;
    private static final long OVERDUE_NOTIFY_D3 = 3;
    private static final long OVERDUE_NOTIFY_D7 = 7;
    private static final long PAST_DUE_DAYS = 14;
    private static final long SUSPEND_DAYS = 21;

    private final SubscriptionRepository subscriptionRepository;
    private final PaymentRepository paymentRepository;
    private final StoreRepository storeRepository;
    private final AppUserRepository appUserRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final Notifier notifier;
    private final AuditLogRepository auditLogRepository;
    private final String bankName;
    private final String accountNo;
    private final String accountHolder;

    public BillingService(SubscriptionRepository subscriptionRepository, PaymentRepository paymentRepository,
            StoreRepository storeRepository, AppUserRepository appUserRepository,
            NotificationLogRepository notificationLogRepository, Notifier notifier,
            AuditLogRepository auditLogRepository,
            @Value("${app.billing.bank-name}") String bankName,
            @Value("${app.billing.account-no}") String accountNo,
            @Value("${app.billing.account-holder}") String accountHolder) {
        this.subscriptionRepository = subscriptionRepository;
        this.paymentRepository = paymentRepository;
        this.storeRepository = storeRepository;
        this.appUserRepository = appUserRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.notifier = notifier;
        this.auditLogRepository = auditLogRepository;
        this.bankName = bankName;
        this.accountNo = accountNo;
        this.accountHolder = accountHolder;
    }

    // ── 구독 ─────────────────────────────────────────────────────────────

    /**
     * 구독 시작(B1). 전자계약(§10)은 보류 결정이라 이번 범위는 명시적 시작 API 만 제공한다 —
     * "매장 활성화 시 자동 생성" 훅은 계약 연동(Sprint 6)과 함께 추가한다.
     * uq_sub_store(status<>'CANCELED') 로 매장당 활성 구독은 1건뿐이다 — 중복 시 409.
     */
    @Transactional
    public SubscriptionResponse createSubscription(UUID ownerPublicId, UUID storePublicId) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        if (subscriptionRepository.findByStoreIdAndStatusNot(store.getId(), "CANCELED").isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE);
        }
        Instant now = Instant.now();
        Instant periodEnd = now.atZone(KST).plusMonths(1).toInstant();
        Subscription sub = Subscription.builder()
                .storeId(store.getId())
                .priceKrw(PRICE_KRW)
                // ★ 상태를 지정하지 않는다 = TRIAL(서비스 대기). 입금을 확인한 운영자만 ACTIVE 로 올린다.
                //   여기서 ACTIVE 를 넣으면 돈을 받기 전에 서비스가 시작된다(2026-08-23 결정).
                .currentPeriodStart(now)
                .currentPeriodEnd(periodEnd)
                .build();
        subscriptionRepository.save(sub);
        issueInvoiceIfAbsent(sub, sub.getCurrentPeriodStart(), now); // 첫 기간 청구서 즉시 발행(B2)
        return toSubscriptionResponse(sub);
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse getSubscription(UUID ownerPublicId, UUID storePublicId) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        Subscription sub = findActiveish(store.getId());
        return toSubscriptionResponse(sub);
    }

    /**
     * Groble 해지 API 규격 수령 전에는 실제 CANCELED 전이를 만들지 않는다. 요청만 접수해 운영자가
     * 확인할 수 있게 남기며, 같은 요청을 반복해도 최초 시각과 감사로그 1건을 유지한다.
     */
    @Transactional
    public CancellationRequestResponse requestCancellation(UUID ownerPublicId, UUID storePublicId) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        Subscription sub = subscriptionRepository.findActiveishForUpdate(store.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (sub.requestCancellation(Instant.now().truncatedTo(ChronoUnit.MICROS))) {
            auditLogRepository.save(AuditLog.builder()
                    .actorId(owner.getId())
                    .actorType("USER")
                    .action("SUBSCRIPTION_CANCELLATION_REQUESTED")
                    .targetType("SUBSCRIPTION")
                    .targetId(sub.getId())
                    .build());
        }
        return new CancellationRequestResponse("REQUESTED", sub.getCancellationRequestedAt().toString());
    }

    @Transactional(readOnly = true)
    public PaymentListResponse listPayments(UUID ownerPublicId, UUID storePublicId, int page, int size) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        Subscription sub = findActiveish(store.getId());
        Page<Payment> result = paymentRepository.findBySubscriptionIdOrderByCreatedAtDesc(sub.getId(),
                PageRequest.of(page, size));
        List<PaymentItem> items = result.getContent().stream().map(this::toPaymentItem).toList();
        return new PaymentListResponse(items, result.hasNext());
    }

    // ── 청구 배치(B3, 일 1회) ────────────────────────────────────────────

    /**
     * ★ 이중청구 금지 — idempotency_key("구독ID:청구연월")로 구조적으로 막는다. 배치가 두 번 돌아도
     * 청구가 두 번 생기지 않는다: 첫 실행에서 기간을 이월시키므로 두 번째 실행은 애초에 이 구독을 대상에서
     * 찾지 못하고, 설사 같은 상태에서 재호출돼도 issueInvoiceIfAbsent 의 existsByIdempotencyKey 확인과
     * DB UNIQUE 제약이 이중으로 막는다.
     */
    @Transactional
    public void runDailyInvoiceBatch() {
        Instant now = Instant.now();
        List<Subscription> due = subscriptionRepository.findByStatusAndCurrentPeriodEndLessThanEqual("ACTIVE", now);
        for (Subscription sub : due) {
            Instant newStart = sub.getCurrentPeriodEnd();
            Instant newEnd = newStart.atZone(KST).plusMonths(1).toInstant();
            issueInvoiceIfAbsent(sub, newStart, now);
            sub.rollPeriod(newStart, newEnd);
        }
    }

    private void issueInvoiceIfAbsent(Subscription sub, Instant billingPeriodStart, Instant issuedAt) {
        String billingMonth = YearMonth.from(billingPeriodStart.atZone(KST)).toString(); // yyyy-MM
        String idempotencyKey = sub.getId() + ":" + billingMonth;
        if (paymentRepository.existsByIdempotencyKey(idempotencyKey)) {
            return;
        }
        Payment payment = Payment.builder()
                .subscriptionId(sub.getId())
                .idempotencyKey(idempotencyKey)
                .amountKrw(PRICE_KRW)
                .vatKrw(VAT_KRW)
                .status("PENDING")
                .method("BANK_TRANSFER")
                .depositCode(generateDepositCode())
                .dueAt(issuedAt.plus(Duration.ofDays(7)))
                .build();
        paymentRepository.save(payment);
    }

    // ── 미납 처리(B4, 일 1회) ────────────────────────────────────────────

    @Transactional
    public void runDailyOverdueBatch() {
        Instant now = Instant.now();
        List<Payment> overdue = paymentRepository.findByStatusAndDueAtBefore("PENDING", now);
        for (Payment payment : overdue) {
            long daysOverdue = Duration.between(payment.getDueAt(), now).toDays();
            Subscription sub = subscriptionRepository.findById(payment.getSubscriptionId()).orElse(null);
            if (sub == null) {
                continue;
            }
            notifyOverdueIfDue(payment, sub, daysOverdue);
            transitionSubscriptionIfOverdue(sub, daysOverdue);
        }
    }

    /** ★ 같은 단계에서 매일 반복 발송되지 않도록 notification_log 발송 횟수로 단계를 판별한다(0회→D+3, 1회→D+7). */
    private void notifyOverdueIfDue(Payment payment, Subscription sub, long daysOverdue) {
        if (daysOverdue < OVERDUE_NOTIFY_D3) {
            return;
        }
        long sentCount = notificationLogRepository.countByRefTypeAndRefIdAndTemplate("PAYMENT", payment.getId(),
                "PAYMENT_OVERDUE");
        boolean dueForD3 = sentCount == 0;
        boolean dueForD7 = sentCount == 1 && daysOverdue >= OVERDUE_NOTIFY_D7;
        if (!dueForD3 && !dueForD7) {
            return;
        }
        Store store = storeRepository.findById(sub.getStoreId()).orElse(null);
        if (store == null) {
            return;
        }
        notifier.send(store.getOwnerId(), store.getId(), "ALIMTALK", "PAYMENT_OVERDUE", "PAYMENT", payment.getId());
    }

    private void transitionSubscriptionIfOverdue(Subscription sub, long daysOverdue) {
        if (daysOverdue >= SUSPEND_DAYS) {
            if (!"SUSPENDED".equals(sub.getStatus())) {
                sub.suspend();
                // ★ 서비스 중단이므로 감사로그를 남긴다.
                auditLogRepository.save(AuditLog.builder()
                        .actorType("SYSTEM")
                        .action("SUBSCRIPTION_SUSPENDED")
                        .targetType("SUBSCRIPTION")
                        .targetId(sub.getId())
                        .build());
            }
        } else if (daysOverdue >= PAST_DUE_DAYS) {
            sub.markPastDue();
        }
    }

    // ── 운영자 입금 확인(B6, X-Internal-Token) ──────────────────────────

    @Transactional
    public ConfirmPaymentResponse confirmPayment(Long paymentId, ConfirmPaymentRequest req) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if ("PAID".equals(payment.getStatus())) {
            // ★ 재확인은 실패가 아니다 — 운영자가 두 번 눌러도 안전해야 한다.
            return toConfirmResponse(payment);
        }
        if (payment.totalKrw().compareTo(BigDecimal.valueOf(req.amountKrw())) != 0) {
            // ★ 금액 불일치를 조용히 통과시키지 않는다. 사유만 남기고 depositorName 등은 로그로 남기지 않는다(B7).
            log.warn("입금액 불일치로 확인 거절 (paymentId={})", paymentId);
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("reason", "AMOUNT_MISMATCH"));
        }
        payment.markPaid(req.depositorName(), req.paidAt(), req.confirmedByUserId());
        subscriptionRepository.findById(payment.getSubscriptionId()).ifPresent(Subscription::restoreActiveIfOverdue);
        return toConfirmResponse(payment);
    }

    // ── 변환·헬퍼 ────────────────────────────────────────────────────────

    private Subscription findActiveish(Long storeId) {
        return subscriptionRepository.findByStoreIdAndStatusNot(storeId, "CANCELED")
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private SubscriptionResponse toSubscriptionResponse(Subscription sub) {
        long price = sub.getPriceKrw().longValue();
        long vat = VAT_KRW.longValue();
        return new SubscriptionResponse(sub.getStatus(), sub.getPlanCode(), price, vat, price + vat,
                sub.getCurrentPeriodStart() == null ? null : sub.getCurrentPeriodStart().toString(),
                sub.getCurrentPeriodEnd() == null ? null : sub.getCurrentPeriodEnd().toString(),
                sub.getCancellationRequestedAt() == null ? null : sub.getCancellationRequestedAt().toString(),
                new BillingMethod("BANK_TRANSFER"));
    }

    private PaymentItem toPaymentItem(Payment p) {
        TransferInfo transferInfo = "PENDING".equals(p.getStatus())
                ? new TransferInfo(bankName, accountNo, accountHolder, p.getDepositCode(),
                        "입금자명에 " + p.getDepositCode() + " 를 적어 주세요")
                : null;
        return new PaymentItem(String.valueOf(p.getId()), p.getStatus(), p.getAmountKrw().longValue(),
                p.getVatKrw().longValue(), p.totalKrw().longValue(),
                p.getDueAt() == null ? null : p.getDueAt().toString(),
                p.getPaidAt() == null ? null : p.getPaidAt().toString(), transferInfo);
    }

    private ConfirmPaymentResponse toConfirmResponse(Payment p) {
        return new ConfirmPaymentResponse(String.valueOf(p.getId()), p.getStatus(),
                p.getPaidAt() == null ? null : p.getPaidAt().toString());
    }

    /** 사람이 입금자명에 적기 쉬운 영숫자 코드(혼동 문자 제외). PENDING 건과 충돌하면 재생성한다. */
    private String generateDepositCode() {
        SecureRandom random = new SecureRandom();
        for (int attempt = 0; attempt < DEPOSIT_CODE_MAX_ATTEMPTS; attempt++) {
            StringBuilder sb = new StringBuilder(DEPOSIT_CODE_LEN);
            for (int i = 0; i < DEPOSIT_CODE_LEN; i++) {
                sb.append(DEPOSIT_CODE_CHARS.charAt(random.nextInt(DEPOSIT_CODE_CHARS.length())));
            }
            String code = sb.toString();
            // ★ 상태와 무관하게 전역 중복을 본다(V11 인덱스와 동일 기준). 완료된 청구의 코드도 재사용하지 않는다.
            if (!paymentRepository.existsByDepositCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("입금코드 생성 실패 — 재시도 초과");
    }

    private Store loadOwnedStore(AppUser owner, UUID storePublicId) {
        Store store = storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!store.getOwnerId().equals(owner.getId())) {
            // ★ X1: 403 이 아니라 404 — 남의 매장 storeId 존재 여부를 흘리지 않는다(기존 패턴과 통일).
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return store;
    }

    private AppUser resolveUser(UUID publicId) {
        return appUserRepository.findByPublicId(publicId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }
}
