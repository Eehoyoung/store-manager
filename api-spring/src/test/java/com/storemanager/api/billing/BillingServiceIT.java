package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storemanager.api.agreement.AgreementService;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.billing.BillingDtos.AutoRenewRequest;
import com.storemanager.api.billing.BillingDtos.BillingView;
import com.storemanager.api.billing.BillingDtos.CheckoutRequest;
import com.storemanager.api.billing.BillingDtos.HeldReplyResumeResponse;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.draft.ReplyDraft;
import com.storemanager.api.draft.ReplyDraftRepository;
import com.storemanager.api.franchise.FranchiseBrand;
import com.storemanager.api.franchise.FranchiseBrandRepository;
import com.storemanager.api.review.StorePlatformLink;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 포트원 V2 빌링키 자동결제(docs/13 §9, 2026-09-29). 소담한판 BillingTest 패턴 이식 —
 * 진짜 포트원을 부르지 않는다. 같은 규격으로 답하는 작은 서버가 빌링키 조회·빌링키 결제에 답하고,
 * 테스트는 우리 쪽 판정(누구 빌링키인지, 언제 청구하는지, 결제일이 어떻게 넘어가는지, 언제 막히는지)만 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class BillingServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static final String CHANNEL = "channel-key-inicis-test";
    static final HttpServer SERVER;
    /** billingKey → 빌링키 조회 응답의 customer.id */
    static final Map<String, String> KEYS = new ConcurrentHashMap<>();
    static final List<String> PAID = new CopyOnWriteArrayList<>();
    static final List<String> DELETED = new CopyOnWriteArrayList<>();
    static final List<String> PAID_BODIES = new CopyOnWriteArrayList<>();
    /** true 면 결제를 카드사 거절(PG_PROVIDER)로 응답한다. */
    static volatile boolean decline;
    /** true 면 결제 응답 자체를 502(PG_PROVIDER 아님)로 답해 서버가 재조회하게 만든다. */
    static volatile boolean upstream5xx;
    /** upstream5xx 뒤 재조회(GET /payments/{id})가 뭐라고 답할지. */
    static volatile String confirmStatus = "PAID";

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                String method = ex.getRequestMethod();
                int status = 200;
                String body = "{}";
                if (path.startsWith("/billing-keys/")) {
                    String key = path.substring("/billing-keys/".length());
                    key = key.contains("?") ? key.substring(0, key.indexOf('?')) : key;
                    if (method.equals("DELETE")) {
                        DELETED.add(key);
                    } else if (!KEYS.containsKey(key)) {
                        status = 404;
                        body = "{\"type\":\"BILLING_KEY_NOT_FOUND\"}";
                    } else {
                        body = "{\"status\":\"ISSUED\",\"billingKey\":\"" + key + "\",\"customer\":{\"id\":\""
                                + KEYS.get(key) + "\"},\"channels\":[{\"key\":\"" + CHANNEL + "\"}]}";
                    }
                } else if (path.startsWith("/payments/") && path.endsWith("/billing-key") && method.equals("POST")) {
                    String paymentId = path.split("/")[2];
                    String sent = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (PAID.contains(paymentId)) {
                        status = 409;
                        body = "{\"type\":\"ALREADY_PAID\"}";
                    } else if (decline) {
                        status = 400;
                        body = "{\"type\":\"PG_PROVIDER\",\"pgCode\":\"01\",\"pgMessage\":\"[1254][실시간빌링실패|잔액부족]\"}";
                    } else if (upstream5xx) {
                        status = 502;
                        body = "{\"type\":\"UPSTREAM_ERROR\"}";
                    } else {
                        PAID.add(paymentId);
                        PAID_BODIES.add(sent);
                        body = "{\"payment\":{\"pgTxId\":\"tx\",\"paidAt\":\"2026-09-29T00:00:00Z\"}}";
                    }
                } else if (path.startsWith("/payments/") && method.equals("GET")) {
                    // 재조회(confirm). upstream5xx 로 502 를 받은 뒤에만 호출된다.
                    if ("PAID".equals(confirmStatus)) {
                        body = "{\"status\":\"PAID\"}";
                    } else if ("FAILED".equals(confirmStatus)) {
                        body = "{\"status\":\"FAILED\",\"failure\":{\"reason\":\"재조회 실패\"}}";
                    } else {
                        body = "{\"status\":\"PENDING\"}";
                    }
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            });
            SERVER.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void portone(DynamicPropertyRegistry r) {
        r.add("app.portone.api-base", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        r.add("app.portone.api-secret", () -> "test-secret");
        r.add("app.portone.store-id", () -> "store-test");
        r.add("app.portone.inicis-channel-key", () -> CHANNEL);
    }

    @Autowired BillingService billingService;
    @Autowired AppUserRepository appUserRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired ReplyDraftRepository replyDraftRepository;
    @Autowired UnifiedReviewRepository unifiedReviewRepository;
    @Autowired StorePlatformLinkRepository storePlatformLinkRepository;
    @Autowired CredentialService credentialService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired FranchiseBrandRepository franchiseBrandRepository;

    private record 매장픽스처(UUID ownerPublicId, UUID storePublicId, Long storeId, Long ownerId) {}

    private 매장픽스처 매장을_만든다(String email) {
        AppUser owner = appUserRepository.save(AppUser.builder().email(email).passwordHash("dummy").name("사장")
                .phone("01000000000").build());
        Store store = storeRepository.save(Store.builder().ownerId(owner.getId()).name("청구매장-" + email).build());
        return new 매장픽스처(owner.getPublicId(), store.getPublicId(), store.getId(), owner.getId());
    }

    private Subscription 구독을_만든다(Long storeId, String status, Instant nextBillingAt, String billingKey) {
        Subscription.SubscriptionBuilder b = Subscription.builder().storeId(storeId)
                .priceKrw(new java.math.BigDecimal("30000")).status(status).autoRenew(billingKey != null)
                .billingKey(billingKey).billingChannelKey(billingKey == null ? null : CHANNEL);
        Subscription s = b.build();
        if (nextBillingAt != null) {
            s.changeNextBillingAt(nextBillingAt);
        }
        return subscriptionRepository.save(s);
    }

    private String issue(Long storeId) {
        Store store = storeRepository.findById(storeId).orElseThrow();
        String key = "billing-key-" + System.nanoTime();
        KEYS.put(key, "store-" + store.getPublicId());
        return key;
    }

    private Subscription row(Long storeId) {
        return subscriptionRepository.findByStoreIdAndStatusNot(storeId, "CANCELED").orElseThrow();
    }

    private CheckoutRequest checkout(String billingKey) {
        return new CheckoutRequest(billingKey, true, AgreementService.CURRENT_VERSION);
    }

    /** StoreServiceGate 가 서비스 가능으로 보게 자격증명 위탁 동의(activatedAt)까지 찍는다. */
    private 매장픽스처 서비스가능_매장을_만든다(String email) {
        매장픽스처 f = 매장을_만든다(email);
        Store store = storeRepository.findById(f.storeId()).orElseThrow();
        store.activateByCredentialConsent(Instant.now());
        storeRepository.save(store);
        return f;
    }

    /** BLOCKED·STORE_INACTIVE 단독인 '보류 답글' 하나를 FK 를 만족시켜 만든다(review_id·store_id 참조). */
    private ReplyDraft 보류답글을_만든다(Long storeId, Long ownerId) {
        String id = UUID.randomUUID().toString();
        var account = credentialService.save(ownerId, "BAEMIN", id, "dummy");
        var link = storePlatformLinkRepository.save(StorePlatformLink.builder().storeId(storeId)
                .accountId(account.getId()).platform("BAEMIN").platformStoreId(id).build());
        var review = unifiedReviewRepository.save(UnifiedReview.builder().storeId(storeId).linkId(link.getId())
                .platform("BAEMIN").platformReviewId(UUID.randomUUID().toString()).writtenAt(Instant.now()).build());
        return replyDraftRepository.save(ReplyDraft.builder().reviewId(review.getId()).storeId(storeId)
                .status("BLOCKED").content("보류된 답글 내용").generatedBy("AI")
                .guardrailFlags(new String[] {"STORE_INACTIVE"}).build());
    }

    @BeforeEach
    void reset() {
        decline = false;
        upstream5xx = false;
        confirmStatus = "PAID";
    }

    // ── 쿠폰 예약 → 카드 등록 시 체험 시작 ─────────────────────────────

    @Test
    void 예약된_쿠폰체험은_카드등록_시점에_시작되고_청구하지_않는다() {
        매장픽스처 f = 매장을_만든다("trial-checkout@example.com");
        subscriptionRepository.save(Subscription.builder().storeId(f.storeId())
                .priceKrw(new java.math.BigDecimal("30000")).status("TRIAL").promotionCode("OPEN30").build());
        assertThat(row(f.storeId()).serviceStateAt(Instant.now())).isEqualTo("UNPAID");

        BillingView view = billingService.checkout(f.ownerPublicId(), f.storePublicId(), checkout(issue(f.storeId())),
                "127.0.0.1", "junit");

        assertThat(view.chargeNowKrw()).isZero();
        assertThat(view.serviceState()).isEqualTo("TRIAL");
        Subscription s = row(f.storeId());
        assertThat(s.getTrialEndsAt()).isEqualTo(s.getNextBillingAt());
        assertThat(paymentRepository.findBySubscriptionIdOrderByCreatedAtDesc(s.getId(),
                org.springframework.data.domain.PageRequest.of(0, 12)).getContent()).isEmpty();
    }

    // ── UNPAID → 카드 등록 시 즉시 청구 ────────────────────────────────

    @Test
    void 결제전_매장은_카드등록과_동시에_33000원을_청구하고_ACTIVE가_된다() {
        매장픽스처 f = 매장을_만든다("unpaid-checkout@example.com");

        BillingView view = billingService.checkout(f.ownerPublicId(), f.storePublicId(), checkout(issue(f.storeId())),
                "127.0.0.1", "junit");

        assertThat(view.chargeNowKrw()).isEqualTo(0L); // 응답은 이미 청구가 반영된 뒤의 상태(ACTIVE)를 본다
        assertThat(view.serviceState()).isEqualTo("ACTIVE");
        assertThat(view.hasCard()).isTrue();
        Subscription s = row(f.storeId());
        assertThat(s.getStatus()).isEqualTo("ACTIVE");
        assertThat(PAID_BODIES.get(PAID_BODIES.size() - 1)).contains("\"total\":33000");
        assertThat(s.getNextBillingAt()).isAfter(Instant.now().plus(Duration.ofDays(29)));
    }

    @Test
    void 동의하지_않으면_청구하지_않는다() {
        매장픽스처 f = 매장을_만든다("no-consent@example.com");
        CheckoutRequest req = new CheckoutRequest(issue(f.storeId()), false, AgreementService.CURRENT_VERSION);

        assertThatThrownBy(() -> billingService.checkout(f.ownerPublicId(), f.storePublicId(), req, "127.0.0.1", "junit"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.BILLING_CONSENT_REQUIRED));
        // 동의 검사는 잠금·청구보다 먼저 실패하므로 구독 행조차 만들어지지 않는다.
        assertThat(subscriptionRepository.findByStoreIdAndStatusNot(f.storeId(), "CANCELED")).isEmpty();
    }

    // ── 결제 거절 ───────────────────────────────────────────────────────

    @Test
    void 거절된_결제는_상태를_바꾸지_않고_새_빌링키를_버린다() {
        매장픽스처 f = 매장을_만든다("declined@example.com");
        decline = true;
        String key = issue(f.storeId());

        ApiException error = (ApiException) assertThatThrownBy(
                () -> billingService.checkout(f.ownerPublicId(), f.storePublicId(), checkout(key), "127.0.0.1", "junit"))
                .isInstanceOf(ApiException.class).actual();
        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_DECLINED);
        assertThat(error.getDetails().get("reason").toString()).contains("잔액부족");
        assertThat(row(f.storeId()).getStatus()).isEqualTo("TRIAL"); // 기본값 그대로 — ACTIVE 로 바뀌지 않았다
        assertThat(row(f.storeId()).getBillingKey()).isNull();
        assertThat(DELETED).contains(key);
        assertThat(row(f.storeId()).getBillingLockUntil()).isNull(); // 실패해도 잠금은 풀린다
    }

    // ── 5xx 는 재조회로 판정한다 ───────────────────────────────────────

    @Test
    void 응답없는_5xx는_재조회로_결제완료를_확인한다() {
        매장픽스처 f = 매장을_만든다("upstream-paid@example.com");
        upstream5xx = true;
        confirmStatus = "PAID";

        BillingView view = billingService.checkout(f.ownerPublicId(), f.storePublicId(), checkout(issue(f.storeId())),
                "127.0.0.1", "junit");

        assertThat(view.serviceState()).isEqualTo("ACTIVE");
        assertThat(row(f.storeId()).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void 재조회도_모르면_결제대기로_응답하고_상태를_바꾸지_않는다() {
        매장픽스처 f = 매장을_만든다("upstream-unknown@example.com");
        upstream5xx = true;
        confirmStatus = "PENDING";

        assertThatThrownBy(() -> billingService.checkout(f.ownerPublicId(), f.storePublicId(),
                checkout(issue(f.storeId())), "127.0.0.1", "junit"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_PENDING));
        assertThat(row(f.storeId()).getStatus()).isNotEqualTo("ACTIVE");
    }

    // ── 남의 빌링키 ────────────────────────────────────────────────────

    @Test
    void 남의_매장_고객id로_발급된_빌링키는_거부한다() {
        매장픽스처 f = 매장을_만든다("foreign-key@example.com");
        String foreign = "billing-key-foreign-" + System.nanoTime();
        KEYS.put(foreign, "store-" + UUID.randomUUID());

        assertThatThrownBy(() -> billingService.checkout(f.ownerPublicId(), f.storePublicId(), checkout(foreign),
                "127.0.0.1", "junit"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.BILLING_KEY_INVALID));
    }

    // ── 동시 체크아웃 ──────────────────────────────────────────────────

    @Test
    void 이미_처리중인_결제는_BILLING_BUSY_로_거절한다() {
        매장픽스처 f = 매장을_만든다("busy@example.com");
        Subscription s = 구독을_만든다(f.storeId(), "TRIAL", null, null);
        // @Modifying 쿼리는 트랜잭션이 필요하다 — BillingService 내부는 TransactionTemplate 으로 감싸지만
        // 테스트에서 직접 부를 때는 여기서 감싼다.
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> subscriptionRepository.lockBilling(f.storeId(), Instant.now(),
                        Instant.now().plusSeconds(60)));

        assertThatThrownBy(() -> billingService.checkout(f.ownerPublicId(), f.storePublicId(),
                checkout(issue(f.storeId())), "127.0.0.1", "junit"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.BILLING_BUSY));
        assertThat(paymentRepository.findBySubscriptionIdOrderByCreatedAtDesc(s.getId(),
                org.springframework.data.domain.PageRequest.of(0, 12)).getContent()).isEmpty();
    }

    // ── 정기 갱신 ──────────────────────────────────────────────────────

    @Test
    void 갱신이_두번_실행돼도_한번만_청구된다() {
        매장픽스처 f = 매장을_만든다("renew-twice@example.com");
        String key = issue(f.storeId());
        구독을_만든다(f.storeId(), "ACTIVE", Instant.now().minus(Duration.ofMinutes(1)), key);

        billingService.renew(f.storeId());
        int paidAfterFirst = PAID_BODIES.size();
        billingService.renew(f.storeId()); // ★ 같은 결제예정일 — 재실행돼도 같은 paymentId 로 ALREADY_PAID

        assertThat(PAID_BODIES.size()).isEqualTo(paidAfterFirst); // 두 번째는 포트원 새 청구를 만들지 않는다
        assertThat(row(f.storeId()).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void 갱신_거절은_실패횟수만_늘리고_다음날_까지_서비스한다() {
        매장픽스처 f = 매장을_만든다("renew-decline@example.com");
        String key = issue(f.storeId());
        구독을_만든다(f.storeId(), "ACTIVE", Instant.now().minus(Duration.ofDays(1)), key);
        decline = true;

        billingService.renew(f.storeId());

        Subscription s = row(f.storeId());
        assertThat(s.getRenewalFailures()).isEqualTo(1);
        assertThat(s.serviceStateAt(Instant.now())).isEqualTo("GRACE");
    }

    @Test
    void 유예중_결제는_원래_결제예정일을_기준으로_한달을_민다() {
        매장픽스처 f = 매장을_만든다("grace-renew@example.com");
        String key = issue(f.storeId());
        구독을_만든다(f.storeId(), "ACTIVE", Instant.now().minus(Duration.ofDays(1)), key);
        // ★ DB 왕복(TIMESTAMPTZ 마이크로초 절삭) 이후의 값을 기준값으로 삼는다 — Instant.now() 원본(나노초)과
        // 직접 비교하면 정밀도 차이로 항상 실패한다.
        Instant due = row(f.storeId()).getNextBillingAt();

        billingService.renew(f.storeId());

        Subscription s = row(f.storeId());
        assertThat(s.getCurrentPeriodStart()).isEqualTo(due); // 기준점은 원래 결제예정일 그대로
    }

    @Test
    void 제한된_구독은_갱신을_시도하지_않는다() {
        매장픽스처 f = 매장을_만든다("restricted-renew@example.com");
        String key = issue(f.storeId());
        Instant longOverdue = ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()
                .minusDays(10).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant();
        Subscription s = 구독을_만든다(f.storeId(), "ACTIVE", longOverdue, key);
        assertThat(row(f.storeId()).serviceStateAt(Instant.now())).isEqualTo("RESTRICTED");

        billingService.renew(f.storeId());

        assertThat(paymentRepository.findBySubscriptionIdOrderByCreatedAtDesc(s.getId(),
                org.springframework.data.domain.PageRequest.of(0, 12)).getContent()).isEmpty();
        assertThat(row(f.storeId()).getRenewalFailures()).isZero();
    }

    @Test
    void 제한된_구독도_체크아웃하면_오늘부터_새_주기로_풀린다() {
        매장픽스처 f = 매장을_만든다("restricted-checkout@example.com");
        Instant longOverdue = ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()
                .minusDays(10).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant();
        구독을_만든다(f.storeId(), "ACTIVE", longOverdue, "old-key-" + System.nanoTime());

        billingService.checkout(f.ownerPublicId(), f.storePublicId(), checkout(issue(f.storeId())), "127.0.0.1", "junit");

        Subscription s = row(f.storeId());
        assertThat(s.serviceStateAt(Instant.now())).isEqualTo("ACTIVE");
        assertThat(s.getCurrentPeriodStart()).isAfter(Instant.now().minus(Duration.ofMinutes(1)));
    }

    // ── 자동결제 on/off ────────────────────────────────────────────────

    @Test
    void 자동결제를_끄면_다음_결제예정일까지는_그대로_쓰고_유예뒤_제한된다() {
        매장픽스처 f = 매장을_만든다("auto-renew-off@example.com");
        String key = issue(f.storeId());
        Subscription s = 구독을_만든다(f.storeId(), "ACTIVE", Instant.now().minus(Duration.ofMinutes(1)), key);

        BillingView view = billingService.setAutoRenew(f.ownerPublicId(), f.storePublicId(),
                new AutoRenewRequest(false), "127.0.0.1", "junit");

        assertThat(view.autoRenew()).isFalse();
        billingService.renew(f.storeId()); // 꺼졌으므로 청구되지 않는다
        assertThat(paymentRepository.findBySubscriptionIdOrderByCreatedAtDesc(s.getId(),
                org.springframework.data.domain.PageRequest.of(0, 12)).getContent()).isEmpty();
        assertThat(row(f.storeId()).serviceStateAt(Instant.now())).isEqualTo("GRACE");
    }

    // ── serviceStateAt 경계 ───────────────────────────────────────────

    // ── 자동결제 끄면 빌링키 즉시 삭제(2026-09-29 결정) ───────────────────

    @Test
    void 자동결제를_끄면_빌링키를_즉시_삭제하고_동의철회를_기록한다() {
        매장픽스처 f = 매장을_만든다("auto-renew-key-delete@example.com");
        String key = issue(f.storeId());
        구독을_만든다(f.storeId(), "ACTIVE", Instant.now().minus(Duration.ofMinutes(1)), key);

        BillingView view = billingService.setAutoRenew(f.ownerPublicId(), f.storePublicId(),
                new AutoRenewRequest(false), "127.0.0.1", "junit");

        assertThat(view.autoRenew()).isFalse();
        assertThat(view.hasCard()).isFalse();
        Subscription s = row(f.storeId());
        assertThat(s.getBillingKey()).isNull();
        assertThat(s.getBillingChannelKey()).isNull();
        assertThat(DELETED).contains(key);
    }

    @Test
    void 카드없이_자동결제를_다시_켤_수_없다() {
        매장픽스처 f = 매장을_만든다("auto-renew-reenable@example.com");
        구독을_만든다(f.storeId(), "ACTIVE", Instant.now().minus(Duration.ofMinutes(1)), null);

        ApiException e = (ApiException) assertThatThrownBy(() -> billingService.setAutoRenew(f.ownerPublicId(),
                f.storePublicId(), new AutoRenewRequest(true), "127.0.0.1", "junit"))
                .isInstanceOf(ApiException.class).actual();

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(e.getDetails().get("reason")).isEqualTo("카드를 다시 등록해 주세요");
    }

    @Test
    void ACTIVE에서_카드를_재등록하면_청구없이_자동결제가_다시_켜진다() {
        매장픽스처 f = 매장을_만든다("re-register-active@example.com");
        구독을_만든다(f.storeId(), "ACTIVE", Instant.now().plus(Duration.ofDays(10)), null);
        Subscription before = row(f.storeId());
        assertThat(before.isAutoRenew()).isFalse(); // 카드가 없었으므로 꺼져 있다
        int paidBefore = PAID_BODIES.size();

        BillingView view = billingService.checkout(f.ownerPublicId(), f.storePublicId(), checkout(issue(f.storeId())),
                "127.0.0.1", "junit");

        assertThat(view.chargeNowKrw()).isZero();
        assertThat(PAID_BODIES.size()).isEqualTo(paidBefore); // 새 청구가 없다
        Subscription after = row(f.storeId());
        assertThat(after.isAutoRenew()).isTrue();
        assertThat(after.getNextBillingAt()).isEqualTo(before.getNextBillingAt());
    }

    @Test
    void GRACE에서_카드를_재등록하면_청구한다() {
        매장픽스처 f = 매장을_만든다("re-register-grace@example.com");
        구독을_만든다(f.storeId(), "ACTIVE", Instant.now().minus(Duration.ofDays(1)), null);
        assertThat(row(f.storeId()).serviceStateAt(Instant.now())).isEqualTo("GRACE");
        int paidBefore = PAID_BODIES.size();

        BillingView view = billingService.checkout(f.ownerPublicId(), f.storePublicId(), checkout(issue(f.storeId())),
                "127.0.0.1", "junit");

        assertThat(PAID_BODIES.size()).isEqualTo(paidBefore + 1);
        assertThat(view.serviceState()).isEqualTo("ACTIVE");
        assertThat(row(f.storeId()).isAutoRenew()).isTrue();
    }

    // ── 이용 제한으로 보류된 답글의 재개(2026-09-29) ───────────────────────

    @Test
    void 보류답글_전체를_재개하면_예약되고_보류수가_0이_된다() {
        매장픽스처 f = 서비스가능_매장을_만든다("held-all@example.com");
        구독을_만든다(f.storeId(), "ACTIVE", Instant.now().plus(Duration.ofDays(10)), issue(f.storeId()));
        ReplyDraft d1 = 보류답글을_만든다(f.storeId(), f.ownerId());
        ReplyDraft d2 = 보류답글을_만든다(f.storeId(), f.ownerId());
        long auditBefore = auditLogRepository.findByActionOrderByCreatedAtAsc("DRAFT_RESUMED_BY_OWNER").size();

        HeldReplyResumeResponse res = billingService.resumeHeldReplies(f.ownerPublicId(), f.storePublicId(), null);

        assertThat(res.resumed()).isEqualTo(2);
        assertThat(res.view().heldReplyCount()).isZero();
        assertThat(replyDraftRepository.findById(d1.getId()).orElseThrow().getStatus()).isEqualTo("SCHEDULED");
        assertThat(replyDraftRepository.findById(d1.getId()).orElseThrow().getGuardrailFlags()).isEmpty();
        assertThat(replyDraftRepository.findById(d2.getId()).orElseThrow().getStatus()).isEqualTo("SCHEDULED");
        assertThat(auditLogRepository.findByActionOrderByCreatedAtAsc("DRAFT_RESUMED_BY_OWNER").size())
                .isEqualTo(auditBefore + 2);
    }

    @Test
    void 지정한_초안만_재개하고_나머지는_보류로_남긴다() {
        매장픽스처 f = 서비스가능_매장을_만든다("held-partial@example.com");
        구독을_만든다(f.storeId(), "ACTIVE", Instant.now().plus(Duration.ofDays(10)), issue(f.storeId()));
        ReplyDraft d1 = 보류답글을_만든다(f.storeId(), f.ownerId());
        ReplyDraft d2 = 보류답글을_만든다(f.storeId(), f.ownerId());

        HeldReplyResumeResponse res = billingService.resumeHeldReplies(f.ownerPublicId(), f.storePublicId(),
                List.of(d1.getPublicId()));

        assertThat(res.resumed()).isEqualTo(1);
        assertThat(res.view().heldReplyCount()).isEqualTo(1);
        assertThat(replyDraftRepository.findById(d1.getId()).orElseThrow().getStatus()).isEqualTo("SCHEDULED");
        assertThat(replyDraftRepository.findById(d2.getId()).orElseThrow().getStatus()).isEqualTo("BLOCKED");
    }

    /** 남의 매장 초안이 하나라도 섞이면 전부 거절한다 — 내 것만 골라 부분 반영하지 않는다. */
    @Test
    void 남의_매장_초안이_섞이면_전체를_거절하고_아무것도_바꾸지_않는다() {
        매장픽스처 f = 서비스가능_매장을_만든다("held-foreign@example.com");
        구독을_만든다(f.storeId(), "ACTIVE", Instant.now().plus(Duration.ofDays(10)), issue(f.storeId()));
        ReplyDraft mine = 보류답글을_만든다(f.storeId(), f.ownerId());
        매장픽스처 other = 매장을_만든다("held-foreign-other@example.com");
        ReplyDraft foreign = 보류답글을_만든다(other.storeId(), other.ownerId());

        assertThatThrownBy(() -> billingService.resumeHeldReplies(f.ownerPublicId(), f.storePublicId(),
                List.of(mine.getPublicId(), foreign.getPublicId())))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThat(replyDraftRepository.findById(mine.getId()).orElseThrow().getStatus()).isEqualTo("BLOCKED");
        assertThat(replyDraftRepository.findById(foreign.getId()).orElseThrow().getStatus()).isEqualTo("BLOCKED");
    }

    /** 서비스 불가(카드 미등록·미납) 매장은 결제 재개 전이므로 재개 자체를 열지 않는다 — 402. */
    @Test
    void 서비스불가한_매장은_보류답글을_재개할_수_없다() {
        매장픽스처 f = 매장을_만든다("held-unpaid@example.com"); // activatedAt 없음 → 서비스 불가
        ReplyDraft d = 보류답글을_만든다(f.storeId(), f.ownerId());

        assertThatThrownBy(() -> billingService.resumeHeldReplies(f.ownerPublicId(), f.storePublicId(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SUBSCRIPTION_PAYMENT_REQUIRED));
        assertThat(replyDraftRepository.findById(d.getId()).orElseThrow().getStatus()).isEqualTo("BLOCKED");
    }

    // ── 가맹 브랜드 구간 단가(V49) ─────────────────────────────────────────

    /** 약정 매장 수만 설정한다(스냅샷 없이) — unitPriceFor 가 committedOrDefault 로 떨어지는 경로. */
    private void 브랜드_약정을_만든다(Long storeId, String brand, int committedStoreCount) {
        franchiseBrandRepository.save(FranchiseBrand.builder().brandName(brand)
                .committedStoreCount(committedStoreCount).build());
        Store store = storeRepository.findById(storeId).orElseThrow();
        store.assignBrand(brand);
        storeRepository.save(store);
    }

    @Test
    void 약정_매장수가_50곳인_브랜드는_31900원을_청구한다() {
        매장픽스처 f = 매장을_만든다("brand-50@example.com");
        브랜드_약정을_만든다(f.storeId(), "리뷰브랜드50", 50);

        BillingView view = billingService.checkout(f.ownerPublicId(), f.storePublicId(), checkout(issue(f.storeId())),
                "127.0.0.1", "junit");

        assertThat(view.unitPriceKrw()).isEqualTo(29000);
        assertThat(PAID_BODIES.get(PAID_BODIES.size() - 1)).contains("\"total\":31900");
    }

    @Test
    void 브랜드가_없는_매장은_기본단가_33000원을_청구한다() {
        매장픽스처 f = 매장을_만든다("brand-none@example.com");

        BillingView view = billingService.checkout(f.ownerPublicId(), f.storePublicId(), checkout(issue(f.storeId())),
                "127.0.0.1", "junit");

        assertThat(view.unitPriceKrw()).isEqualTo(30000);
        assertThat(PAID_BODIES.get(PAID_BODIES.size() - 1)).contains("\"total\":33000");
    }

    /** UNKNOWN 재시도 사이 브랜드 단가가 바뀌어도, 이미 만든 Payment 행의 금액을 그대로 청구한다. */
    @Test
    void 재시도는_처음_확정된_금액을_그대로_청구한다() {
        매장픽스처 f = 매장을_만든다("retry-price@example.com");
        브랜드_약정을_만든다(f.storeId(), "리트라이브랜드", 50); // tier(50) = 29000
        String key = issue(f.storeId());
        구독을_만든다(f.storeId(), "ACTIVE", Instant.now().minus(Duration.ofMinutes(1)), key);

        upstream5xx = true;
        confirmStatus = "PENDING"; // 결과를 모른다 — Payment 행(29000+2900)만 만들고 아무것도 확정하지 않는다
        billingService.renew(f.storeId());

        // 재시도 사이 약정을 5곳으로 낮춘다 — 새로 계산하면 tier(5)=30000 이지만, 이미 만든 행이 있으므로 무시돼야 한다.
        FranchiseBrand brand = franchiseBrandRepository.findByBrandName("리트라이브랜드").orElseThrow();
        brand.changeCommittedStoreCount(5);
        franchiseBrandRepository.save(brand);

        upstream5xx = false; // 이번엔 정상 응답 — 실제로 전송한 금액이 PAID_BODIES 에 남는다
        billingService.renew(f.storeId());

        assertThat(row(f.storeId()).getStatus()).isEqualTo("ACTIVE");
        assertThat(PAID_BODIES.get(PAID_BODIES.size() - 1)).contains("\"total\":31900"); // 처음 금액 그대로
    }

    @Test
    void 유예는_D플러스2_23시59분59초까지이고_D플러스3_00시부터_제한이다() {
        java.time.ZoneId kst = java.time.ZoneId.of("Asia/Seoul");
        Instant nextBillingAt = ZonedDateTime.now(kst).toLocalDate().minusDays(3).atStartOfDay(kst).toInstant();
        Subscription s = Subscription.builder().storeId(1L).priceKrw(new java.math.BigDecimal("30000"))
                .status("ACTIVE").build();
        s.changeNextBillingAt(nextBillingAt);

        Instant graceEdge = nextBillingAt.atZone(kst).toLocalDate().plusDays(3).atStartOfDay(kst).toInstant()
                .minusSeconds(1);
        assertThat(s.serviceStateAt(graceEdge)).isEqualTo("GRACE");

        Instant restrictedStart = nextBillingAt.atZone(kst).toLocalDate().plusDays(3).atStartOfDay(kst).toInstant();
        assertThat(s.serviceStateAt(restrictedStart)).isEqualTo("RESTRICTED");
    }
}
