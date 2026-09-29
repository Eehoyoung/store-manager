package com.storemanager.api.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.storemanager.api.agreement.AgreementService;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.billing.BillingDtos.AutoRenewRequest;
import com.storemanager.api.billing.BillingDtos.BillingView;
import com.storemanager.api.billing.BillingDtos.CheckoutRequest;
import com.storemanager.api.billing.BillingDtos.CustomerInfo;
import com.storemanager.api.billing.BillingDtos.HeldReplyResumeResponse;
import com.storemanager.api.billing.BillingDtos.PaymentItem;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.draft.ReplyDraft;
import com.storemanager.api.draft.ReplyDraftRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.store.StoreServiceGate;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 포트원 V2 빌링키 자동결제(소담한판 방식 이식, 2026-09-29). 요금제는 하나(월 33,000원, VAT 포함).
 *
 * <p>흐름: 화면이 PG 결제창으로 빌링키를 받는다 → {@link #checkout} 이 이 매장 것인지 확인하고 청구한다 →
 * 매일 00:10(KST) 결제예정일이 된 매장을 {@link #renewAllDue} 가 청구한다.
 *
 * <p>되돌리면 안 되는 지점:
 * <ul>
 *   <li>PG 호출을 {@code @Transactional} 안에 넣지 말 것 — 응답을 기다리는 동안 커넥션을 붙든다.
 *       쓰기는 {@link #writes}로 짧게 감싼다.
 *   <li>PG를 부르기 전에 PENDING 행을 먼저 남길 것 — 결제 후 기록이 실패해도 흔적이 남는다.
 *   <li>청구 전 매장 단위 잠금(조건부 UPDATE)을 뺄 것 — 두 번 누르면 두 번 결제된다.
 *   <li>정기 갱신은 결제예정일을 기준으로 한 달씩 민다. 유예 기간에도 서비스를 받았기 때문이다.
 *       이용 제한 뒤 결제하면 그날부터 새 주기다.
 * </ul>
 */
@Service
public class BillingService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final BigDecimal PRICE_KRW = BigDecimal.valueOf(30000);
    private static final BigDecimal VAT_KRW = BigDecimal.valueOf(3000);
    private static final long AMOUNT_TOTAL = 33000L;
    private static final int TRIAL_DAYS = 30;
    private static final Duration LOCK = Duration.ofMinutes(2);
    /** 코드 문자열은 여기 상수로만 둔다 — AgreementService 는 다른 담당자 소유다. */
    private static final String BILLING_CONSENT_CODE = "BILLING_AUTO_PAYMENT";

    /** 이용 제한으로 보류된 답글의 단독 가드레일 플래그(PublishScheduler·CollectResultService 와 동일 상수). */
    private static final String HELD_REPLY_FLAG = "STORE_INACTIVE";

    private final PortOneClient portOneClient;
    private final SubscriptionRepository subscriptions;
    private final PaymentRepository payments;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final AgreementService agreementService;
    private final EntityManager entityManager;
    private final TransactionTemplate writes;
    private final ReplyDraftRepository replyDrafts;
    private final AuditLogRepository auditLogs;
    private final StoreServiceGate serviceGate;
    /**
     * 무료체험 쿠폰번호(2026-09-28). 개별 전달하는 비공개 코드라 저장소에 적지 않고 env 로만 준다.
     * ★ 비어 있으면 어떤 코드도 받지 않는다(fail-closed). 코드가 곧 DataAPI·LLM 비용이다.
     */
    private final String promotionCode;
    private final int promotionLimit;

    public BillingService(PortOneClient portOneClient, SubscriptionRepository subscriptions,
            PaymentRepository payments, StoreRepository stores, AppUserRepository users,
            AgreementService agreementService, EntityManager entityManager,
            PlatformTransactionManager transactionManager,
            ReplyDraftRepository replyDrafts, AuditLogRepository auditLogs, StoreServiceGate serviceGate,
            @Value("${app.promotion.code:}") String promotionCode,
            @Value("${app.promotion.limit:30}") int promotionLimit) {
        this.portOneClient = portOneClient;
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.stores = stores;
        this.users = users;
        this.agreementService = agreementService;
        this.entityManager = entityManager;
        this.writes = new TransactionTemplate(transactionManager);
        this.replyDrafts = replyDrafts;
        this.auditLogs = auditLogs;
        this.serviceGate = serviceGate;
        this.promotionCode = promotionCode == null ? "" : promotionCode.trim().toUpperCase(java.util.Locale.ROOT);
        this.promotionLimit = promotionLimit;
    }

    // ── 가입 시 쿠폰 예약(체험은 아직 시작하지 않는다) ─────────────────────

    /**
     * 가입 트랜잭션 안에서 쿠폰 한도만 원자적으로 귀속한다. ★ 체험은 여기서 시작하지 않는다 —
     * 카드를 등록({@link #checkout})해야 30일이 시작된다. 그 전까지는 UNPAID다.
     */
    @Transactional
    public Subscription startLaunchTrial(Store store, String rawPromotionCode) {
        String code = rawPromotionCode == null ? "" : rawPromotionCode.trim().toUpperCase(java.util.Locale.ROOT);
        if (promotionCode.isEmpty() || !promotionCode.equals(code)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("reason", "INVALID_PROMOTION_CODE"));
        }
        if (subscriptions.findByStoreIdAndStatusNot(store.getId(), "CANCELED").isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE);
        }
        // 동시에 여러 가입이 들어와도 한도를 넘지 않도록 프로모션 코드 단위 트랜잭션 락을 잡는다.
        entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtext(?1))")
                .setParameter(1, code)
                .getSingleResult();
        if (subscriptions.countByPromotionCode(code) >= promotionLimit) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("reason", "PROMOTION_SOLD_OUT"));
        }
        return subscriptions.save(Subscription.builder()
                .storeId(store.getId())
                .priceKrw(PRICE_KRW)
                .status("TRIAL")
                .promotionCode(code)
                .build());
    }

    // ── 결제창 등록·청구(사장님 경로) ───────────────────────────────────

    /**
     * 결제수단을 등록하고 청구한다.
     * <ul>
     *   <li>예약된 쿠폰 체험(카드 미등록): 카드만 등록하고 30일 체험을 시작한다. 청구 없음.
     *   <li>체험·결제 기간 중: 카드만 바꾼다. 청구 없음.
     *   <li>정지(SUSPENDED): 거절한다 — 운영자가 직접 재개해야 한다.
     *   <li>그 외(UNPAID·유예·제한): 지금 33,000원을 청구한다.
     * </ul>
     */
    public BillingView checkout(UUID ownerPublicId, UUID storePublicId, CheckoutRequest req, String ip,
            String userAgent) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        if (!portOneClient.enabled()) {
            throw PortOneClient.unavailable();
        }
        agreementService.requireCurrentVersion(req.billingConsentVersion());
        agreementService.record(owner.getId(), store.getId(), BILLING_CONSENT_CODE, req.billingConsentAgreed(), ip,
                userAgent);
        if (!req.billingConsentAgreed()) {
            throw new ApiException(ErrorCode.BILLING_CONSENT_REQUIRED);
        }
        String channelKey = verifyBillingKey(store, req.billingKey());
        lock(store.getId());
        try {
            Subscription sub = subscriptions.findByStoreIdAndStatusNot(store.getId(), "CANCELED").orElseThrow();
            Instant now = Instant.now();
            String state = sub.serviceStateAt(now);
            String oldKey = sub.getBillingKey();

            if (isTrialPending(sub)) {
                Instant trialEnd = now.plus(TRIAL_DAYS, ChronoUnit.DAYS);
                writes.executeWithoutResult(status -> {
                    Subscription row = subscriptions.findByStoreIdAndStatusNot(store.getId(), "CANCELED").orElseThrow();
                    row.beginTrial(now, trialEnd, req.billingKey(), channelKey);
                    subscriptions.save(row);
                });
                discardReplacedKey(oldKey, req.billingKey());
                return view(owner, store);
            }
            if ("TRIAL".equals(state) || "ACTIVE".equals(state)) {
                writes.executeWithoutResult(status -> {
                    Subscription row = subscriptions.findByStoreIdAndStatusNot(store.getId(), "CANCELED").orElseThrow();
                    row.swapBillingKey(req.billingKey(), channelKey);
                    subscriptions.save(row);
                });
                discardReplacedKey(oldKey, req.billingKey());
                return view(owner, store);
            }
            if ("SUSPENDED".equals(state)) {
                throw new ApiException(ErrorCode.SUBSCRIPTION_INACTIVE);
            }

            // UNPAID · GRACE · RESTRICTED — 지금 청구한다.
            boolean grace = "GRACE".equals(state);
            Instant periodStart = grace ? sub.getNextBillingAt() : now;
            Instant periodEnd = periodStart.atZone(KST).plusMonths(1).toInstant();
            String paymentId = "sub-" + store.getId() + "-" + randomToken();
            PortOneClient.Charge charge = charge(sub.getId(), paymentId, "소담리뷰 월 이용료", req.billingKey(),
                    customer(store, owner));
            if (charge.outcome() == PortOneClient.Outcome.DECLINED) {
                discardRejectedKey(oldKey, req.billingKey());
                throw new ApiException(ErrorCode.PAYMENT_DECLINED, Map.of("reason", charge.reason()));
            }
            if (charge.outcome() == PortOneClient.Outcome.UNKNOWN) {
                throw new ApiException(ErrorCode.PAYMENT_PENDING);
            }
            writes.executeWithoutResult(status -> {
                Subscription row = subscriptions.findByStoreIdAndStatusNot(store.getId(), "CANCELED").orElseThrow();
                row.chargeSucceeded(periodStart, periodEnd, req.billingKey(), channelKey);
                subscriptions.save(row);
            });
            discardReplacedKey(oldKey, req.billingKey());
            return view(owner, store);
        } finally {
            unlock(store.getId());
        }
    }

    /**
     * 자동결제 on/off. 해지가 아니다 — 꺼도 다음 결제예정일까지는 그대로 쓴다.
     *
     * <p>★ 끄면 빌링키를 즉시 삭제한다(약관 제9조 제3항 제5호, 2026-09-29 결정). 다시 켜는 길은
     * 여기 없다 — 카드가 없으므로 {@code checkout} 으로 카드를 다시 등록해야 한다.
     */
    public BillingView setAutoRenew(UUID ownerPublicId, UUID storePublicId, AutoRenewRequest req, String ip,
            String userAgent) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        if (req.on()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("reason", "카드를 다시 등록해 주세요"));
        }
        String[] oldKey = new String[1];
        writes.executeWithoutResult(status -> {
            Subscription sub = subscriptions.findByStoreIdAndStatusNot(store.getId(), "CANCELED")
                    .filter(s -> s.getBillingKey() != null)
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
            oldKey[0] = sub.disableAutoRenew();
            subscriptions.save(sub);
        });
        agreementService.record(owner.getId(), store.getId(), BILLING_CONSENT_CODE, false, ip, userAgent);
        if (oldKey[0] != null) {
            portOneClient.deleteBillingKey(oldKey[0]);
        }
        return view(owner, store);
    }

    // ── 보류 답글 재개(결제 재개 후 사람 확인) ──────────────────────────

    /**
     * 이용 제한으로 보류됐던 답글을 사장님이 확인하고 되살린다.
     *
     * <p>★ 결제만으로 자동 재예약하지 않는다(약관 제9조의5 제6항) — 이 메서드가 호출돼야만 재개된다.
     * draftIds 를 생략하거나 비우면 보류 답글 전부, 지정하면 그 초안만(전부 이 매장의 보류
     * 답글이어야 한다 — 하나라도 아니면 전체를 거절해 부분 반영으로 혼란을 만들지 않는다).
     */
    @Transactional
    public HeldReplyResumeResponse resumeHeldReplies(UUID ownerPublicId, UUID storePublicId, List<UUID> draftIds) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        if (!serviceGate.isServiceable(store)) {
            throw new ApiException(ErrorCode.SUBSCRIPTION_PAYMENT_REQUIRED);
        }
        List<ReplyDraft> held = heldReplies(store.getId());
        List<ReplyDraft> targets = held;
        if (draftIds != null && !draftIds.isEmpty()) {
            Map<UUID, ReplyDraft> byPublicId = held.stream().collect(Collectors.toMap(ReplyDraft::getPublicId, d -> d));
            targets = new ArrayList<>();
            for (UUID id : draftIds) {
                ReplyDraft d = byPublicId.get(id);
                if (d == null) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            Map.of("draftId", id, "reason", "이 매장의 보류 답글이 아닙니다."));
                }
                targets.add(d);
            }
        }
        for (ReplyDraft d : targets) {
            d.resumeAfterReactivation(owner.getId());
            replyDrafts.save(d);
            auditLogs.save(AuditLog.builder().actorId(owner.getId()).actorType("OWNER")
                    .action("DRAFT_RESUMED_BY_OWNER").targetType("REPLY_DRAFT").targetId(d.getId()).build());
        }
        return new HeldReplyResumeResponse(targets.size(), view(owner, store));
    }

    /** BLOCKED 이고 STORE_INACTIVE 단독으로 막힌 답글만 '보류 답글' 이다. */
    private List<ReplyDraft> heldReplies(Long storeId) {
        return replyDrafts.findByStoreIdAndStatus(storeId, "BLOCKED").stream()
                .filter(BillingService::isHeldReply)
                .toList();
    }

    private static boolean isHeldReply(ReplyDraft d) {
        String[] flags = d.getGuardrailFlags();
        return flags != null && flags.length == 1 && HELD_REPLY_FLAG.equals(flags[0]);
    }

    @Transactional(readOnly = true)
    public BillingView view(UUID ownerPublicId, UUID storePublicId) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        return view(owner, store);
    }

    // ── 정기 갱신(스케줄러, 매일 00:10 KST) ─────────────────────────────

    public void renewAllDue() {
        Instant tomorrow = LocalDate.now(KST).plusDays(1).atStartOfDay(KST).toInstant();
        for (Subscription sub : subscriptions.findByNextBillingAtBefore(tomorrow)) {
            renew(sub.getStoreId());
        }
    }

    /** 결제예정일이 된 매장 하나를 청구한다. 스케줄러와 테스트가 같은 길을 쓴다. */
    void renew(Long storeId) {
        try {
            lock(storeId);
        } catch (ApiException busy) {
            return;
        }
        try {
            Subscription sub = subscriptions.findByStoreIdAndStatusNot(storeId, "CANCELED").orElse(null);
            Instant now = Instant.now();
            if (sub == null || sub.getNextBillingAt() == null) {
                return;
            }
            if (sub.getNextBillingAt().atZone(KST).toLocalDate().isAfter(now.atZone(KST).toLocalDate())) {
                return;
            }
            // 제한이 시작되면 더 시도하지 않는다. 사장님이 카드를 다시 등록해 결제하면 checkout()이 풀어 준다.
            if ("RESTRICTED".equals(sub.serviceStateAt(now))) {
                return;
            }
            if (!sub.isAutoRenew() || sub.getBillingKey() == null || !portOneClient.enabled()) {
                return;
            }
            int failures = sub.getRenewalFailures();
            // 같은 예정일·같은 시도 번호면 같은 id다. 스케줄러가 두 번 돌아도 포트원이 ALREADY_PAID 로 막는다.
            String paymentId = "renew-" + storeId + "-" + sub.getNextBillingAt().getEpochSecond() + "-" + failures;
            Store store = stores.findById(storeId).orElseThrow();
            AppUser owner = users.findById(store.getOwnerId()).orElse(null);
            Map<String, Object> customer = owner == null ? Map.of("id", customerId(store)) : customer(store, owner);
            PortOneClient.Charge charge = charge(sub.getId(), paymentId, "소담리뷰 월 이용료", sub.getBillingKey(), customer);
            Instant periodStart = sub.getNextBillingAt();
            Instant periodEnd = periodStart.atZone(KST).plusMonths(1).toInstant();
            switch (charge.outcome()) {
                case PAID -> writes.executeWithoutResult(status -> {
                    Subscription row = subscriptions.findByStoreIdAndStatusNot(storeId, "CANCELED").orElseThrow();
                    row.chargeSucceeded(periodStart, periodEnd, row.getBillingKey(), row.getBillingChannelKey());
                    subscriptions.save(row);
                });
                case DECLINED -> writes.executeWithoutResult(status -> {
                    Subscription row = subscriptions.findByStoreIdAndStatusNot(storeId, "CANCELED").orElseThrow();
                    row.recordChargeFailure();
                    subscriptions.save(row);
                });
                // 모르면 아무것도 바꾸지 않는다. 다음 실행에서 같은 paymentId 로 다시 부르면 결과가 드러난다.
                case UNKNOWN -> {
                }
            }
        } finally {
            unlock(storeId);
        }
    }

    // ── 청구 공통 ────────────────────────────────────────────────────────

    private PortOneClient.Charge charge(Long subscriptionId, String paymentId, String orderName, String billingKey,
            Map<String, Object> customer) {
        writes.executeWithoutResult(status -> {
            if (payments.findByPgTxId(paymentId).isPresent()) {
                return;
            }
            payments.save(Payment.builder().subscriptionId(subscriptionId).idempotencyKey("portone:" + paymentId)
                    .pgTxId(paymentId).amountKrw(PRICE_KRW).vatKrw(VAT_KRW).method("PORTONE_CARD").build());
        });
        PortOneClient.Charge charge = portOneClient.pay(paymentId, billingKey, orderName, AMOUNT_TOTAL, customer);
        if (charge.outcome() != PortOneClient.Outcome.UNKNOWN) {
            writes.executeWithoutResult(status -> {
                Payment p = payments.findByPgTxId(paymentId).orElseThrow();
                if (charge.outcome() == PortOneClient.Outcome.PAID) {
                    p.markPaid(Instant.now());
                } else {
                    p.markFailed(charge.reason());
                }
            });
        }
        return charge;
    }

    /** 이 매장 고객 id로, 우리가 허용한 채널에서 발급된, 살아 있는 빌링키인지. 남의 빌링키를 끼워 넣는 길을 막는다. */
    private String verifyBillingKey(Store store, String billingKey) {
        JsonNode info = portOneClient.billingKey(billingKey);
        String channelKey = info.path("channels").path(0).path("key").asText("");
        boolean ok = "ISSUED".equals(info.path("status").asText())
                && customerId(store).equals(info.path("customer").path("id").asText())
                && channelKey.equals(portOneClient.channelKey());
        if (!ok) {
            throw new ApiException(ErrorCode.BILLING_KEY_INVALID);
        }
        return channelKey;
    }

    private static boolean isTrialPending(Subscription s) {
        return "TRIAL".equals(s.getStatus()) && s.getTrialEndsAt() == null && s.getPromotionCode() != null;
    }

    private void discardReplacedKey(String oldKey, String newKey) {
        if (oldKey != null && !oldKey.equals(newKey)) {
            portOneClient.deleteBillingKey(oldKey);
        }
    }

    private void discardRejectedKey(String oldKey, String newKey) {
        if (!newKey.equals(oldKey)) {
            portOneClient.deleteBillingKey(newKey);
        }
    }

    private static String customerId(Store store) {
        return "store-" + store.getPublicId();
    }

    private static Map<String, Object> customer(Store store, AppUser owner) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", customerId(store));
        c.put("name", Map.of("full", owner.getName()));
        c.put("email", owner.getEmail());
        if (owner.getPhone() != null && !owner.getPhone().isBlank()) {
            c.put("phoneNumber", owner.getPhone());
        }
        return c;
    }

    private static String randomToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private void lock(Long storeId) {
        Instant now = Instant.now();
        Integer got = writes.execute(status -> {
            if (subscriptions.findByStoreIdAndStatusNot(storeId, "CANCELED").isEmpty()) {
                // 구독 행이 없으면 UNPAID 와 같다. 잠금을 걸 행이 있어야 하므로 먼저 만든다.
                subscriptions.saveAndFlush(Subscription.builder().storeId(storeId).priceKrw(PRICE_KRW).build());
            }
            return subscriptions.lockBilling(storeId, now, now.plus(LOCK));
        });
        if (got == null || got == 0) {
            throw new ApiException(ErrorCode.BILLING_BUSY);
        }
    }

    private void unlock(Long storeId) {
        writes.executeWithoutResult(status -> subscriptions.unlockBilling(storeId));
    }

    // ── 조회 ─────────────────────────────────────────────────────────────

    private BillingView view(AppUser owner, Store store) {
        Instant now = Instant.now();
        Optional<Subscription> subOpt = subscriptions.findByStoreIdAndStatusNot(store.getId(), "CANCELED");
        String state = subOpt.map(s -> s.serviceStateAt(now)).orElse("UNPAID");
        boolean trialPending = subOpt.map(BillingService::isTrialPending).orElse(false);
        long chargeNow = trialPending || "TRIAL".equals(state) || "ACTIVE".equals(state) ? 0L : AMOUNT_TOTAL;
        List<PaymentItem> items = subOpt.map(s -> payments
                        .findBySubscriptionIdOrderByCreatedAtDesc(s.getId(), PageRequest.of(0, 12))
                        .getContent().stream().map(BillingService::toItem).toList())
                .orElse(List.of());
        String lastPaidAt = items.stream().filter(p -> "PAID".equals(p.status())).map(PaymentItem::paidAt)
                .findFirst().orElse(null);
        CustomerInfo customer = new CustomerInfo(customerId(store), owner.getName(), owner.getEmail(),
                owner.getPhone() == null ? "" : owner.getPhone());
        return new BillingView(portOneClient.enabled(), portOneClient.storeId(), portOneClient.channelKey(), customer,
                state, trialPending, TRIAL_DAYS,
                subOpt.map(Subscription::getTrialEndsAt).map(Instant::toString).orElse(null),
                subOpt.map(Subscription::getNextBillingAt).map(Instant::toString).orElse(null),
                subOpt.map(Subscription::getNextBillingAt).map(Subscription::restrictedFrom).map(Instant::toString)
                        .orElse(null),
                lastPaidAt,
                subOpt.map(s -> s.getBillingKey() != null).orElse(false),
                subOpt.map(Subscription::isAutoRenew).orElse(false),
                subOpt.map(Subscription::getRenewalFailures).orElse(0),
                AMOUNT_TOTAL, chargeNow, AgreementService.CURRENT_VERSION, items, heldReplies(store.getId()).size());
    }

    private static PaymentItem toItem(Payment p) {
        return new PaymentItem(p.totalKrw().longValue(), p.getStatus(),
                p.getCreatedAt() == null ? null : p.getCreatedAt().toString(),
                p.getPaidAt() == null ? null : p.getPaidAt().toString(),
                p.getFailCode() == null ? "" : p.getFailCode());
    }

    private AppUser resolveUser(UUID publicId) {
        return users.findByPublicIdAndDeletedAtIsNull(publicId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    private Store loadOwnedStore(AppUser owner, UUID storePublicId) {
        Store store = stores.findByPublicIdAndDeletedAtIsNull(storePublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!store.getOwnerId().equals(owner.getId())) {
            // 남의 매장 storeId 존재 여부를 흘리지 않는다(기존 패턴과 통일).
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return store;
    }
}
