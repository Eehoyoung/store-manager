package com.storemanager.api.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 카드 정보는 PG 창으로만 보낸다. 결제번호는 서버가 발급하고 공식 조회 결과로만 활성화한다. */
@Service
public class PortOnePaymentService {
    private static final Logger log = LoggerFactory.getLogger(PortOnePaymentService.class);
    private static final BigDecimal PRICE = BigDecimal.valueOf(30000);
    private static final BigDecimal VAT = BigDecimal.valueOf(3000);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final String secret;
    private final String pgStoreId;
    private final String channelKey;
    private final URI apiBase;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final AppUserRepository users;
    private final StoreRepository stores;
    private final SubscriptionRepository subscriptions;
    private final PaymentRepository payments;
    private final TransactionTemplate transactions;

    @Autowired
    public PortOnePaymentService(@Value("${app.portone.api-secret:}") String secret,
            @Value("${app.portone.store-id:}") String pgStoreId,
            @Value("${app.portone.inicis-channel-key:}") String channelKey,
            AppUserRepository users, StoreRepository stores, SubscriptionRepository subscriptions,
            PaymentRepository payments, PlatformTransactionManager transactionManager) {
        this(secret, pgStoreId, channelKey, URI.create("https://api.portone.io"),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                users, stores, subscriptions, payments, transactionManager);
    }

    PortOnePaymentService(String secret, String pgStoreId, String channelKey, URI apiBase, HttpClient http,
            AppUserRepository users, StoreRepository stores, SubscriptionRepository subscriptions,
            PaymentRepository payments, PlatformTransactionManager transactionManager) {
        this.secret = secret.trim();
        this.pgStoreId = pgStoreId.trim();
        this.channelKey = channelKey.trim();
        this.apiBase = apiBase;
        this.http = http;
        this.users = users;
        this.stores = stores;
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record Checkout(String paymentId, String portoneStoreId, String channelKey, int amount) {}
    public record BillingStatus(boolean autoRenew, Instant nextBillingAt) {}

    @Transactional
    public Checkout begin(UUID ownerId, UUID storeId) {
        requireConfigured();
        Store store = ownedStore(ownerId, storeId);
        Subscription sub = subscriptions.findActiveishForUpdate(store.getId()).orElseGet(() ->
                subscriptions.save(Subscription.builder().storeId(store.getId()).priceKrw(PRICE).build()));
        if ("ACTIVE".equals(sub.getStatus()) && sub.getCurrentPeriodEnd() != null
                && sub.getCurrentPeriodEnd().isAfter(Instant.now())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    java.util.Map.of("reason", "현재 결제 기간이 끝난 뒤 다음 달 요금을 결제할 수 있습니다."));
        }
        return new Checkout("review-" + UUID.randomUUID(), pgStoreId, channelKey,
                PRICE.add(VAT).intValueExact());
    }

    /** 네트워크 호출은 DB 트랜잭션 밖에서 수행한다. */
    public void verifyProvider(UUID ownerId, UUID storeId, String paymentId) {
        requireConfigured();
        Payment payment = paymentForOwner(ownerId, storeId, paymentId);
        if ("PAID".equals(payment.getStatus())) return;
        JsonNode paid;
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                    URI.create(apiBase + "/payments/" + URLEncoder.encode(paymentId, StandardCharsets.UTF_8)
                            + "?storeId=" + URLEncoder.encode(pgStoreId, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "PortOne " + secret)
                    .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("포트원 결제 조회 응답 상태: {}", response.statusCode());
                throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
            }
            paid = json.readTree(response.body());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("포트원 결제 조회 실패: {}", e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (!"PAID".equals(paid.path("status").asText())
                || !pgStoreId.equals(paid.path("storeId").asText())
                || !"KRW".equals(paid.path("currency").asText())
                || paid.path("amount").path("total").asLong(-1) != payment.totalKrw().longValueExact()
                || !channelKey.equals(paid.path("channel").path("key").asText())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    java.util.Map.of("reason", "결제 확인이 완료되지 않았습니다. 잠시 후 다시 확인해 주세요."));
        }
        transactions.executeWithoutResult(status -> activate(ownerId, storeId, paymentId));
    }

    public BillingStatus registerBillingKey(UUID ownerId, UUID storeId, String billingKey) {
        requireConfigured();
        Store store = ownedStore(ownerId, storeId);
        AppUser owner = users.findByPublicIdAndDeletedAtIsNull(ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        String customerId = "store-" + store.getPublicId();
        JsonNode key = get("/billing-keys/" + encode(billingKey) + "?storeId=" + encode(pgStoreId));
        String issuedChannel = key.path("channels").path(0).path("key").asText();
        if (!"ISSUED".equals(key.path("status").asText())
                || !customerId.equals(key.path("customer").path("id").asText())
                || !channelKey.equals(issuedChannel)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    java.util.Map.of("reason", "등록한 결제수단을 확인할 수 없습니다."));
        }

        Subscription sub = transactions.execute(status -> subscriptions.findActiveishForUpdate(store.getId())
                .orElseGet(() -> subscriptions.save(Subscription.builder().storeId(store.getId()).priceKrw(PRICE).build())));
        String paymentId = "review-" + UUID.randomUUID();
        Payment payment = transactions.execute(status -> payments.save(Payment.builder()
                .subscriptionId(sub.getId()).idempotencyKey("portone:" + paymentId).pgTxId(paymentId)
                .amountKrw(PRICE).vatKrw(VAT).method("PORTONE_CARD").build()));
        LinkedHashMap<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", customerId);
        customer.put("name", java.util.Map.of("full", owner.getName()));
        customer.put("email", owner.getEmail());
        if (owner.getPhone() != null) customer.put("phoneNumber", owner.getPhone());
        JsonNode charged = post("/payments/" + encode(paymentId) + "/billing-key", java.util.Map.of(
                "storeId", pgStoreId, "billingKey", billingKey, "orderName", "소담리뷰 월 이용료",
                "customer", customer, "amount", java.util.Map.of("total", payment.totalKrw().longValueExact()),
                "currency", "KRW"));
        if (!"PAID".equals(charged.path("status").asText())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    java.util.Map.of("reason", "결제가 승인되지 않았습니다."));
        }
        Instant now = Instant.now();
        Instant next = now.atZone(KST).plusMonths(1).toInstant();
        transactions.executeWithoutResult(status -> {
            Subscription locked = subscriptions.findActiveishForUpdate(store.getId()).orElseThrow();
            Payment saved = payments.findByPgTxId(paymentId).orElseThrow();
            saved.markProviderPaid(now);
            locked.enableAutoRenew(billingKey, issuedChannel, now, next);
        });
        return new BillingStatus(true, next);
    }

    @Transactional
    public BillingStatus stopAutoRenew(UUID ownerId, UUID storeId) {
        Store store = ownedStore(ownerId, storeId);
        Subscription sub = subscriptions.findActiveishForUpdate(store.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        sub.disableAutoRenew();
        return new BillingStatus(false, sub.getNextBillingAt());
    }

    void renewDue() {
        Instant now = Instant.now();
        for (Subscription sub : subscriptions
                .findByAutoRenewTrueAndBillingKeyIsNotNullAndNextBillingAtLessThanEqual(now)) {
            renew(sub.getStoreId(), now);
        }
    }

    private void renew(Long storeId, Instant now) {
        Subscription sub = subscriptions.findByStoreIdAndStatusNot(storeId, "CANCELED").orElse(null);
        if (sub == null || !sub.isAutoRenew() || sub.getBillingKey() == null) return;
        String paymentId = "renew-" + storeId + "-" + sub.getNextBillingAt().getEpochSecond() + "-" + sub.getRenewalFailures();
        Payment payment = transactions.execute(status -> payments.findByPgTxId(paymentId).orElseGet(() ->
                payments.save(Payment.builder().subscriptionId(sub.getId()).idempotencyKey("portone:" + paymentId)
                        .pgTxId(paymentId).amountKrw(PRICE).vatKrw(VAT).method("PORTONE_CARD").build())));
        try {
            JsonNode charged = post("/payments/" + encode(paymentId) + "/billing-key", java.util.Map.of(
                    "storeId", pgStoreId, "billingKey", sub.getBillingKey(), "orderName", "소담리뷰 월 이용료",
                    "customer", java.util.Map.of("id", "store-" + storePublicId(storeId)),
                    "amount", java.util.Map.of("total", payment.totalKrw().longValueExact()), "currency", "KRW"));
            if (!"PAID".equals(charged.path("status").asText())) throw new IllegalStateException("declined");
            Instant next = sub.getNextBillingAt().atZone(KST).plusMonths(1).toInstant();
            transactions.executeWithoutResult(status -> {
                subscriptions.findActiveishForUpdate(storeId).orElseThrow().recordRenewal(now, next);
                payments.findByPgTxId(paymentId).orElseThrow().markProviderPaid(now);
            });
        } catch (RuntimeException e) {
            transactions.executeWithoutResult(status -> subscriptions.findActiveishForUpdate(storeId)
                    .orElseThrow().recordRenewalFailure());
            log.warn("자동결제 실패 (storeId={}, attempt={})", storeId, sub.getRenewalFailures() + 1);
        }
    }

    private UUID storePublicId(Long storeId) {
        return stores.findById(storeId).orElseThrow().getPublicId();
    }

    private JsonNode get(String path) {
        return send(HttpRequest.newBuilder(URI.create(apiBase + path)).GET().build());
    }

    private JsonNode post(String path, Object body) {
        try {
            return send(HttpRequest.newBuilder(URI.create(apiBase + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build());
        } catch (java.io.IOException e) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "PortOne " + secret);
            if (request.bodyPublisher().isPresent()) builder.header("Content-Type", "application/json");
            HttpRequest authorized = builder
                    .method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())).build();
            HttpResponse<String> response = http.send(authorized, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
            return json.readTree(response.body());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void activate(UUID ownerId, UUID storeId, String paymentId) {
        Store store = ownedStore(ownerId, storeId);
        Subscription sub = subscriptions.findActiveishForUpdate(store.getId()).orElseThrow(
                () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Payment payment = payments.findByPgTxId(paymentId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!payment.getSubscriptionId().equals(sub.getId()) || !"PORTONE_CARD".equals(payment.getMethod()))
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        if ("PAID".equals(payment.getStatus())) return;
        Instant now = Instant.now();
        Instant start = sub.getTrialEndsAt() != null && sub.getTrialEndsAt().isAfter(now)
                ? sub.getTrialEndsAt() : now;
        sub.activateFromProvider(start, start.atZone(KST).plusMonths(1).toInstant());
        payment.markProviderPaid(now);
    }

    private Payment paymentForOwner(UUID ownerId, UUID storeId, String paymentId) {
        Store store = ownedStore(ownerId, storeId);
        Payment payment = payments.findByPgTxId(paymentId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Subscription sub = subscriptions.findByStoreIdAndStatusNot(store.getId(), "CANCELED")
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!payment.getSubscriptionId().equals(sub.getId()) || !"PORTONE_CARD".equals(payment.getMethod()))
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        return payment;
    }

    private Store ownedStore(UUID ownerId, UUID storeId) {
        AppUser owner = users.findByPublicIdAndDeletedAtIsNull(ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        Store store = stores.findByPublicIdAndDeletedAtIsNull(storeId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!store.getOwnerId().equals(owner.getId())) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        return store;
    }

    private void requireConfigured() {
        if (secret.isBlank() || pgStoreId.isBlank() || channelKey.isBlank())
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
