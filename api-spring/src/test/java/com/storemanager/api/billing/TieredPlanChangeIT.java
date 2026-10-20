package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.admin.AdminSubscriptionService;
import com.storemanager.api.agreement.AgreementService;
import com.storemanager.api.billing.BillingDtos.BillingView;
import com.storemanager.api.billing.BillingDtos.CheckoutRequest;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.draft.ReplyDraft;
import com.storemanager.api.draft.ReplyDraftRepository;
import com.storemanager.api.review.StorePlatformLink;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StorePersona;
import com.storemanager.api.store.StorePersonaRepository;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 선택형 요금(D38) PR5 - 변경 예약·월 1회·잠금·다음 결제 승격(켜짐, 실제 Postgres·Redis, 포트원은 목). */
@SpringBootTest(properties = "app.pricing.tiered.enabled=true")
@ActiveProfiles("test")
@Testcontainers
class TieredPlanChangeIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired BillingService billingService;
    @Autowired AgreementService agreements;
    @Autowired AdminSubscriptionService adminSubscriptions;
    @Autowired AppUserRepository users;
    @Autowired StoreRepository stores;
    @Autowired StorePersonaRepository personas;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired PaymentRepository payments;
    @Autowired StorePlatformLinkRepository links;
    @Autowired UnifiedReviewRepository reviews;
    @Autowired ReplyDraftRepository drafts;
    @Autowired CredentialService credentialService;
    @Autowired StringRedisTemplate stringRedis;
    @MockitoBean PortOneClient portOne;

    @BeforeEach
    void setUp() {
        Mockito.reset(portOne);
        when(portOne.enabled()).thenReturn(true);
        when(portOne.channelKey()).thenReturn("ch-test");
        when(portOne.storeId()).thenReturn("store-test");
        when(portOne.pay(anyString(), anyString(), anyString(), anyLong(), anyMap()))
                .thenReturn(new PortOneClient.Charge(PortOneClient.Outcome.PAID, null));
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private Store 매장() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        AppUser owner = users.save(AppUser.builder().email("plan-" + key + "@example.com").passwordHash("x")
                .name("사장").phone("01000000000").build());
        Store store = Store.builder().ownerId(owner.getId()).name("변경-" + key).build();
        store.activateByCredentialConsent(Instant.now());
        store = stores.save(store);
        personas.save(StorePersona.builder().storeId(store.getId()).tone("FRIENDLY").delayHours((short) 0)
                .publishWindows("[]").personaSeed(1).build());
        return store;
    }

    /** 오늘 안에 결제예정일이 와 있는(갱신 대상) 한편 아직 ACTIVE 인 구독 - 변경과 갱신을 모두 시험할 수 있다. */
    private static Instant dueLaterToday() {
        return LocalDate.now(KST).plusDays(1).atStartOfDay(KST).minusSeconds(30).toInstant();
    }

    private Subscription 구독(Store store, String status, String[] active) {
        Subscription s = Subscription.builder().storeId(store.getId()).priceKrw(new BigDecimal("30000"))
                .status(status).activeDeliveryPlatforms(active).billingKey("bk-" + store.getId())
                .billingChannelKey("ch-test").autoRenew(true).build();
        s.changeNextBillingAt(dueLaterToday());
        return subscriptions.save(s);
    }

    private Subscription 다시읽기(Store store) {
        return subscriptions.findByStoreIdAndStatusNot(store.getId(), "CANCELED").orElseThrow();
    }

    private UUID owner(Store s) {
        return users.findById(s.getOwnerId()).orElseThrow().getPublicId();
    }

    private BillingView 변경(Store s, String... platforms) {
        return billingService.changePlan(owner(s), s.getPublicId(), List.of(platforms));
    }

    private static ErrorCode code(Throwable t) {
        return ((ApiException) t).getErrorCode();
    }

    private long lastPaidTotal() {
        ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
        verify(portOne, atLeastOnce()).pay(anyString(), anyString(), anyString(), amount.capture(), anyMap());
        return amount.getValue();
    }

    private UnifiedReview 리뷰(Store store, String platform) {
        String id = UUID.randomUUID().toString();
        PlatformAccount account = credentialService.save(store.getOwnerId(), "BAEMIN", id, "pw");
        StorePlatformLink link = links.save(StorePlatformLink.builder().storeId(store.getId())
                .accountId(account.getId()).platform("BAEMIN").platformStoreId(id).build());
        return reviews.save(UnifiedReview.builder().storeId(store.getId()).linkId(link.getId()).platform(platform)
                .platformReviewId(id).rating((short) 5).body("맛있어요").writtenAt(Instant.now()).build());
    }

    private ReplyDraft 예약초안(Store store, UnifiedReview r) {
        return drafts.save(ReplyDraft.builder().reviewId(r.getId()).storeId(store.getId()).status("SCHEDULED")
                .content("답글").generatedBy("AI").scheduledAt(Instant.now().plusSeconds(3600)).build());
    }

    private static String draftKey(Long id) {
        return "publish:blocked:draft:" + id;
    }

    // ── 변경 예약 ─────────────────────────────────────────────────────────

    @Test
    void changeWritesPendingOnly() {
        Store s = 매장();
        구독(s, "ACTIVE", null);

        BillingView v = 변경(s, "BAEMIN");

        Subscription row = 다시읽기(s);
        assertThat(row.getActiveDeliveryPlatforms()).isNull(); // 현재 명단은 그대로
        assertThat(row.getPendingDeliveryPlatforms()).containsExactly("BAEMIN");
        assertThat(row.getPlanChangedAt()).isNotNull();
        assertThat(v.amountKrw()).isEqualTo(33000L); // 이번 결제는 그대로
        assertThat(v.tiered().current().totalKrw()).isEqualTo(33000);
        assertThat(v.tiered().next().totalKrw()).isEqualTo(25000);
        assertThat(v.tiered().next().supplyKrw() + v.tiered().next().vatKrw()).isEqualTo(25000);
        assertThat(v.tiered().next().deliveryPlatforms()).containsExactly("BAEMIN");
        assertThat(v.tiered().changedThisMonth()).isTrue();
        verify(portOne, never()).pay(anyString(), anyString(), anyString(), anyLong(), anyMap());
    }

    @Test
    void changeRejectsInvalidSelections() {
        Store s = 매장();
        구독(s, "ACTIVE", null);
        for (List<String> bad : List.of(List.<String>of(), List.of("BAEMIN", "BAEMIN"), List.of("NAVER"),
                List.of("baemin"), List.of("BAEMIN", "YOGIYO", "COUPANGEATS", "BAEMIN"),
                java.util.Arrays.asList("BAEMIN", null))) {
            assertThatThrownBy(() -> billingService.changePlan(owner(s), s.getPublicId(), bad))
                    .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThatThrownBy(() -> billingService.changePlan(owner(s), s.getPublicId(), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void changeOfAnotherOwnersStoreIsNotFound() {
        Store mine = 매장();
        Store other = 매장();
        구독(other, "ACTIVE", null);
        assertThatThrownBy(() -> billingService.changePlan(owner(mine), other.getPublicId(), List.of("BAEMIN")))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void changeRejectedSecondTimeInSameKstMonth() {
        Store s = 매장();
        구독(s, "ACTIVE", null);
        변경(s, "BAEMIN");

        assertThatThrownBy(() -> 변경(s, "YOGIYO"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.PLAN_CHANGE_LIMIT));
        assertThat(다시읽기(s).getPendingDeliveryPlatforms()).containsExactly("BAEMIN");
    }

    @Test
    void cancelByResavingCurrentListClearsPending() {
        Store s = 매장();
        구독(s, "ACTIVE", new String[] {"BAEMIN", "COUPANGEATS", "YOGIYO"});
        변경(s, "BAEMIN");

        BillingView v = 변경(s, "BAEMIN", "COUPANGEATS", "YOGIYO"); // 현재 명단과 같은 값 = 취소(월 1회 소모 없이 허용)

        assertThat(다시읽기(s).getPendingDeliveryPlatforms()).isNull();
        assertThat(v.tiered().next()).isNull();
    }

    @Test
    void planChangedAtKstMonthBoundary() {
        Instant octLast = Instant.parse("2026-10-31T14:59:59Z"); // 10/31 23:59:59 KST
        Instant novFirst = Instant.parse("2026-10-31T15:00:00Z"); // 11/01 00:00:00 KST
        assertThat(BillingService.changedInMonth(octLast, Instant.parse("2026-10-01T00:00:00Z"))).isTrue();
        assertThat(BillingService.changedInMonth(octLast, novFirst)).isFalse();
        assertThat(BillingService.changedInMonth(novFirst, Instant.parse("2026-11-30T14:59:59Z"))).isTrue();
        assertThat(BillingService.changedInMonth(novFirst, Instant.parse("2026-11-30T15:00:00Z"))).isFalse();
        assertThat(BillingService.changedInMonth(null, novFirst)).isFalse();
    }

    @Test
    void lastMonthChangeDoesNotBlockThisMonth() {
        Store s = 매장();
        Subscription sub = 구독(s, "ACTIVE", null);
        sub.reservePlan(new java.util.TreeSet<>(List.of("YOGIYO")), Instant.now().minus(Duration.ofDays(40)));
        subscriptions.save(sub);

        변경(s, "BAEMIN");

        assertThat(다시읽기(s).getPendingDeliveryPlatforms()).containsExactly("BAEMIN");
    }

    @Test
    void oncePerMonthSurvivesResignup() {
        Store s = 매장();
        Subscription old = 구독(s, "ACTIVE", null);
        변경(s, "BAEMIN");
        old = 다시읽기(s);
        old.cancelImmediately(); // 해지
        subscriptions.save(old);
        구독(s, "ACTIVE", null); // 재가입 = 새 구독 행

        assertThatThrownBy(() -> 변경(s, "YOGIYO"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.PLAN_CHANGE_LIMIT));
    }

    @Test
    void changeRejectedWhilePaymentPendingOrUnknown() {
        Store s = 매장();
        Subscription sub = 구독(s, "ACTIVE", null);
        payments.save(Payment.builder().subscriptionId(sub.getId()).idempotencyKey("portone:unk-" + sub.getId())
                .pgTxId("unk-" + sub.getId()).amountKrw(new BigDecimal("30000")).vatKrw(new BigDecimal("3000"))
                .method("PORTONE_CARD").build()); // 기본 상태 PENDING = 결과 모름

        assertThatThrownBy(() -> 변경(s, "BAEMIN"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.PLAN_CHANGE_NOT_ALLOWED));
        assertThat(다시읽기(s).getPendingDeliveryPlatforms()).isNull();
    }

    @Test
    void changeDuringGraceOrFailedPayment() {
        Store grace = 매장();
        Subscription g = 구독(grace, "ACTIVE", null);
        g.changeNextBillingAt(Instant.now().minus(Duration.ofHours(30))); // 결제예정일 지남 = 유예
        subscriptions.save(g);
        assertThatThrownBy(() -> 변경(grace, "BAEMIN"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.PLAN_CHANGE_NOT_ALLOWED));

        Store failed = 매장();
        Subscription f = 구독(failed, "ACTIVE", null);
        f.recordChargeFailure();
        subscriptions.save(f);
        assertThatThrownBy(() -> 변경(failed, "BAEMIN"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.PLAN_CHANGE_NOT_ALLOWED));

        Store unpaid = 매장();
        subscriptions.save(Subscription.builder().storeId(unpaid.getId()).priceKrw(new BigDecimal("30000")).build());
        assertThatThrownBy(() -> 변경(unpaid, "BAEMIN"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.PLAN_CHANGE_NOT_ALLOWED));
    }

    @Test
    void changeRejectedWhileRenewalInFlight() {
        Store s = 매장();
        Subscription sub = Subscription.builder().storeId(s.getId()).priceKrw(new BigDecimal("30000"))
                .status("ACTIVE").billingLockUntil(Instant.now().plusSeconds(120)).build(); // 갱신 중 흉내
        sub.changeNextBillingAt(dueLaterToday());
        subscriptions.save(sub);
        assertThatThrownBy(() -> 변경(s, "BAEMIN"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.BILLING_BUSY));
    }

    // ── 갱신(자동결제) ────────────────────────────────────────────────────

    @Test
    void renewalChargesPendingAndPromotesOnSuccess() {
        Store s = 매장();
        구독(s, "ACTIVE", null);
        변경(s, "BAEMIN");

        billingService.renew(s.getId());

        assertThat(lastPaidTotal()).isEqualTo(25000L); // 대기 명단(1개) 가격으로 청구
        Subscription row = 다시읽기(s);
        assertThat(row.getActiveDeliveryPlatforms()).containsExactly("BAEMIN");
        assertThat(row.getPendingDeliveryPlatforms()).isNull();
        assertThat(row.getNextBillingAt()).isAfter(dueLaterToday());
    }

    @Test
    void renewalWithoutPendingChargesCurrentAndKeepsNull() {
        Store s = 매장();
        구독(s, "ACTIVE", null);

        billingService.renew(s.getId());

        assertThat(lastPaidTotal()).isEqualTo(33000L);
        assertThat(다시읽기(s).getActiveDeliveryPlatforms()).isNull();
    }

    @Test
    void renewalFailureKeepsActive() {
        Store s = 매장();
        구독(s, "ACTIVE", null);
        변경(s, "BAEMIN");
        when(portOne.pay(anyString(), anyString(), anyString(), anyLong(), anyMap()))
                .thenReturn(new PortOneClient.Charge(PortOneClient.Outcome.DECLINED, "잔액부족"));

        billingService.renew(s.getId());

        Subscription row = 다시읽기(s);
        assertThat(row.getActiveDeliveryPlatforms()).isNull(); // 승격 안 함
        assertThat(row.getPendingDeliveryPlatforms()).containsExactly("BAEMIN");
        assertThat(row.getRenewalFailures()).isEqualTo(1);
    }

    @Test
    void changeDuringTrialConfirmsAtTrialEnd() {
        Store s = 매장();
        Subscription sub = 구독(s, "TRIAL", null);
        sub.beginTrial(Instant.now().minus(Duration.ofDays(29)), dueLaterToday(), "bk-trial", "ch-test");
        subscriptions.save(sub);

        변경(s, "BAEMIN", "YOGIYO"); // 체험 중 변경 = pending 에만
        assertThat(다시읽기(s).getActiveDeliveryPlatforms()).isNull();
        assertThat(다시읽기(s).getPendingDeliveryPlatforms()).containsExactly("BAEMIN", "YOGIYO");

        billingService.renew(s.getId()); // 체험 종료 첫 결제

        assertThat(lastPaidTotal()).isEqualTo(29000L);
        assertThat(다시읽기(s).getActiveDeliveryPlatforms()).containsExactly("BAEMIN", "YOGIYO");
        assertThat(다시읽기(s).getPendingDeliveryPlatforms()).isNull();
    }

    @Test
    void unknownRetryKeepsFrozenAmount() {
        Store s = 매장();
        구독(s, "ACTIVE", null);
        변경(s, "BAEMIN");
        when(portOne.pay(anyString(), anyString(), anyString(), anyLong(), anyMap()))
                .thenReturn(new PortOneClient.Charge(PortOneClient.Outcome.UNKNOWN, null));
        billingService.renew(s.getId()); // 결과 모름 - 아무것도 바뀌지 않는다

        Subscription mid = 다시읽기(s);
        assertThat(mid.getActiveDeliveryPlatforms()).isNull();
        assertThat(mid.getPendingDeliveryPlatforms()).containsExactly("BAEMIN");
        // 결과를 모르는 동안 변경은 막혀 금액이 바뀔 수 없다.
        assertThatThrownBy(() -> 변경(s, "BAEMIN", "COUPANGEATS", "YOGIYO"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.PLAN_CHANGE_NOT_ALLOWED));

        when(portOne.pay(anyString(), anyString(), anyString(), anyLong(), anyMap()))
                .thenReturn(new PortOneClient.Charge(PortOneClient.Outcome.PAID, null));
        billingService.renew(s.getId());

        ArgumentCaptor<Long> amounts = ArgumentCaptor.forClass(Long.class);
        verify(portOne, Mockito.times(2)).pay(anyString(), anyString(), anyString(), amounts.capture(), anyMap());
        assertThat(amounts.getAllValues()).containsExactly(25000L, 25000L);
        assertThat(다시읽기(s).getActiveDeliveryPlatforms()).containsExactly("BAEMIN");
        assertThat(다시읽기(s).getPendingDeliveryPlatforms()).isNull();
    }

    // ── 동시성(실 Postgres) ───────────────────────────────────────────────

    @Test
    void parallelChangeAndRenewalOnlyOneSucceeds() throws Exception {
        Store s = 매장();
        구독(s, "ACTIVE", null);
        List<List<String>> choices = List.of(List.of("BAEMIN"), List.of("YOGIYO"), List.of("COUPANGEATS"),
                List.of("BAEMIN", "YOGIYO"), List.of("BAEMIN", "COUPANGEATS"), List.of("COUPANGEATS", "YOGIYO"),
                List.of("BAEMIN"), List.of("YOGIYO"));
        int n = choices.size() + 1;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (List<String> c : choices) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    billingService.changePlan(owner(s), s.getPublicId(), c);
                    ok.incrementAndGet();
                } catch (ApiException expected) {
                    // 월 1회 초과 / 갱신 중 / 결제 상태 - 모두 정상 거절
                }
                return null;
            }));
        }
        futures.add(pool.submit(() -> {
            start.await();
            billingService.renew(s.getId());
            return null;
        }));
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(ok.get()).isLessThanOrEqualTo(1); // 변경은 많아야 한 건
        Subscription row = 다시읽기(s);
        long charged = lastPaidTotal();
        if (charged == 33000L) { // 갱신이 먼저 읽었으면 현재(3개) 가격, 변경은 그 뒤의 다음 결제 몫
            assertThat(row.getActiveDeliveryPlatforms()).isNull();
        } else { // 변경이 먼저였으면 그 명단으로 청구·승격, 대기는 비어 있다
            assertThat(row.getPendingDeliveryPlatforms()).isNull();
            assertThat(row.getActiveDeliveryPlatforms()).hasSize(charged == 25000L ? 1 : 2);
        }
    }

    @Test
    void parallelChangesOnlyOneSucceeds() throws Exception {
        Store s = 매장();
        구독(s, "ACTIVE", null);
        List<List<String>> choices = List.of(List.of("BAEMIN"), List.of("YOGIYO"), List.of("COUPANGEATS"),
                List.of("BAEMIN", "YOGIYO"), List.of("BAEMIN", "COUPANGEATS"), List.of("COUPANGEATS", "YOGIYO"));
        ExecutorService pool = Executors.newFixedThreadPool(choices.size());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (List<String> c : choices) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    billingService.changePlan(owner(s), s.getPublicId(), c);
                    ok.incrementAndGet();
                } catch (ApiException e) {
                    if (e.getErrorCode() == ErrorCode.PLAN_CHANGE_LIMIT) {
                        limited.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(1);
        assertThat(limited.get()).isEqualTo(choices.size() - 1);
    }

    // ── 하향·재선택 초안 처리 ─────────────────────────────────────────────

    @Test
    void downgradeBlocksScheduledAndWritesRedisKeys() {
        Store s = 매장();
        구독(s, "ACTIVE", null);
        ReplyDraft keep = 예약초안(s, 리뷰(s, "BAEMIN"));
        ReplyDraft removed = 예약초안(s, 리뷰(s, "YOGIYO"));
        ReplyDraft inQueue = 예약초안(s, 리뷰(s, "COUPANGEATS"));
        stringRedis.opsForValue().set("dispatch:draft:" + inQueue.getId(), "tok"); // 이미 큐에 들어간 잡
        변경(s, "BAEMIN");

        billingService.renew(s.getId()); // 승격

        ReplyDraft r1 = drafts.findById(keep.getId()).orElseThrow();
        assertThat(r1.getStatus()).isEqualTo("SCHEDULED");
        assertThat(stringRedis.hasKey(draftKey(keep.getId()))).isFalse();
        ReplyDraft r2 = drafts.findById(removed.getId()).orElseThrow();
        assertThat(r2.getStatus()).isEqualTo("BLOCKED");
        assertThat(r2.getGuardrailFlags()).containsExactly("PLATFORM_NOT_SELECTED");
        assertThat(stringRedis.opsForValue().get(draftKey(removed.getId()))).isEqualTo("PLATFORM_NOT_SELECTED");
        ReplyDraft r3 = drafts.findById(inQueue.getId()).orElseThrow();
        assertThat(r3.getStatus()).isEqualTo("SCHEDULED"); // 큐에 있던 잡은 DB 를 건드리지 않고 키로만 멈춘다
        assertThat(stringRedis.opsForValue().get(draftKey(inQueue.getId()))).isEqualTo("PLATFORM_NOT_SELECTED");
    }

    @Test
    void downgradeNoRefund() {
        Store s = 매장();
        Subscription sub = 구독(s, "ACTIVE", null);
        변경(s, "BAEMIN");

        billingService.renew(s.getId());

        List<Payment> all = payments.findBySubscriptionIdOrderByCreatedAtDesc(sub.getId(), PageRequest.of(0, 20))
                .getContent();
        assertThat(all).hasSize(1); // 환불·취소 결제 없음, 새 청구 한 건뿐
        assertThat(all.get(0).totalKrw()).isEqualByComparingTo("25000");
        verify(portOne, never()).deleteBillingKey(anyString());
    }

    @Test
    void redisKeyClearedOnReselect() {
        Store s = 매장();
        Subscription sub = 구독(s, "ACTIVE", new String[] {"BAEMIN"});
        ReplyDraft sched = 예약초안(s, 리뷰(s, "YOGIYO"));
        stringRedis.opsForValue().set(draftKey(sched.getId()), "PLATFORM_NOT_SELECTED");
        변경(s, "BAEMIN", "YOGIYO");

        billingService.renew(s.getId());

        assertThat(stringRedis.hasKey(draftKey(sched.getId()))).isFalse();
        assertThat(drafts.findById(sched.getId()).orElseThrow().getStatus()).isEqualTo("SCHEDULED");
        assertThat(sub.getId()).isNotNull();
    }

    @Test
    void reselectAllowsRedraftWithoutAutoPublish() {
        Store s = 매장();
        구독(s, "ACTIVE", null);
        UnifiedReview yogiyoReview = 리뷰(s, "YOGIYO");
        ReplyDraft d = 예약초안(s, yogiyoReview);
        변경(s, "BAEMIN");
        billingService.renew(s.getId()); // 하향: YOGIYO 초안 BLOCKED + 키
        assertThat(drafts.findById(d.getId()).orElseThrow().getStatus()).isEqualTo("BLOCKED");
        assertThat(stringRedis.hasKey(draftKey(d.getId()))).isTrue();
        assertThat(reviews.findNeedingDraft(Instant.now(), true, PageRequest.of(0, 500)).stream()
                .map(UnifiedReview::getId)).doesNotContain(yogiyoReview.getId()); // 미선택 + BLOCKED 초안

        // 다음 달: 월 1회 초기화, 다시 3개 선택, 다음 결제일 도래
        Subscription row = 다시읽기(s);
        // 지난달 변경 기록을 만든다(현재 명단과 다른 값 - 같은 값은 취소라 기록이 남지 않는다).
        row.reservePlan(new java.util.TreeSet<>(List.of("YOGIYO")), Instant.now().minus(Duration.ofDays(40)));
        row.changeNextBillingAt(dueLaterToday());
        subscriptions.save(row);
        변경(s, "BAEMIN", "COUPANGEATS", "YOGIYO");
        billingService.renew(s.getId());

        ReplyDraft after = drafts.findById(d.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("FAILED"); // 재생성 가능한 가장 보수적인 전이 - SCHEDULED 아님
        assertThat(after.getStatus()).isNotEqualTo("SCHEDULED");
        assertThat(stringRedis.hasKey(draftKey(d.getId()))).isFalse();
        assertThat(reviews.findNeedingDraft(Instant.now(), true, PageRequest.of(0, 500)).stream()
                .map(UnifiedReview::getId)).contains(yogiyoReview.getId()); // 새로 만들 수 있다
    }

    // ── 카드 등록(D43A) ───────────────────────────────────────────────────

    private void billingKeyOk(Store store, String key) throws Exception {
        JsonNode info = JSON.readTree("{\"status\":\"ISSUED\",\"customer\":{\"id\":\"store-" + store.getPublicId()
                + "\"},\"channels\":[{\"key\":\"ch-test\"}]}");
        when(portOne.billingKey(key)).thenReturn(info);
    }

    @Test
    void checkoutRequiresSelectionWhenOn() throws Exception {
        Store s = 매장();
        subscriptions.save(Subscription.builder().storeId(s.getId()).priceKrw(new BigDecimal("30000")).build());
        billingKeyOk(s, "k1");
        for (List<String> bad : java.util.Arrays.<List<String>>asList(null, List.of(), List.of("NAVER"),
                List.of("BAEMIN", "BAEMIN"))) {
            assertThatThrownBy(() -> billingService.checkout(owner(s), s.getPublicId(),
                    new CheckoutRequest("k1", true, agreements.currentVersion(), bad), "127.0.0.1", "junit"))
                    .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        verify(portOne, never()).pay(anyString(), anyString(), anyString(), anyLong(), anyMap());

        BillingView v = billingService.checkout(owner(s), s.getPublicId(),
                new CheckoutRequest("k1", true, agreements.currentVersion(), List.of("COUPANGEATS", "BAEMIN")),
                "127.0.0.1", "junit");

        assertThat(lastPaidTotal()).isEqualTo(29000L);
        assertThat(다시읽기(s).getActiveDeliveryPlatforms()).containsExactly("BAEMIN", "COUPANGEATS"); // active 로 저장
        assertThat(다시읽기(s).getPendingDeliveryPlatforms()).isNull();
        assertThat(v.tiered().current().totalKrw()).isEqualTo(29000);
    }

    @Test
    void trialStartStoresSelectionAsActive() throws Exception {
        Store s = 매장();
        subscriptions.save(Subscription.builder().storeId(s.getId()).priceKrw(new BigDecimal("30000"))
                .status("TRIAL").promotionCode("OPEN30").build()); // 쿠폰 예약(카드 전)
        billingKeyOk(s, "k2");

        billingService.checkout(owner(s), s.getPublicId(),
                new CheckoutRequest("k2", true, agreements.currentVersion(), List.of("YOGIYO")), "127.0.0.1", "junit");

        assertThat(다시읽기(s).getActiveDeliveryPlatforms()).containsExactly("YOGIYO");
        verify(portOne, never()).pay(anyString(), anyString(), anyString(), anyLong(), anyMap()); // 체험은 청구 없음
    }

    @Test
    void adminActivationKeepsNullSelection() {
        Store s = 매장();
        adminSubscriptions.activate(s.getPublicId(), "입금 확인", "admin");
        Subscription row = 다시읽기(s);
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
        assertThat(row.getActiveDeliveryPlatforms()).isNull(); // 명단 null = 3개 간주
        assertThat(row.selectedDeliveryCount()).isEqualTo(3);
    }

    // ── 리뷰 반영 ─────────────────────────────────────────────────────────

    @Test
    void cardSwapNeedsNoSelectionAndIgnoresIt() throws Exception {
        Store s = 매장();
        구독(s, "ACTIVE", new String[] {"BAEMIN"});
        billingKeyOk(s, "k-swap");
        billingKeyOk(s, "k-swap2");

        billingService.checkout(owner(s), s.getPublicId(),
                new CheckoutRequest("k-swap", true, agreements.currentVersion()), "127.0.0.1", "junit");
        billingService.checkout(owner(s), s.getPublicId(),
                new CheckoutRequest("k-swap2", true, agreements.currentVersion(), List.of("YOGIYO")), "127.0.0.1", "junit");

        verify(portOne, never()).pay(anyString(), anyString(), anyString(), anyLong(), anyMap());
        assertThat(다시읽기(s).getActiveDeliveryPlatforms()).containsExactly("BAEMIN"); // 카드 교체는 명단을 바꾸지 않는다
        assertThat(다시읽기(s).getPendingDeliveryPlatforms()).isNull();
    }

    @Test
    void changeRejectedWhenAutoRenewOff() {
        Store s = 매장();
        Subscription sub = Subscription.builder().storeId(s.getId()).priceKrw(new BigDecimal("30000"))
                .status("ACTIVE").billingKey("bk-" + s.getId()).billingChannelKey("ch-test").autoRenew(false).build();
        sub.changeNextBillingAt(dueLaterToday());
        subscriptions.save(sub);

        assertThatThrownBy(() -> 변경(s, "BAEMIN"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.PLAN_CHANGE_NOT_ALLOWED));
    }

    @Test
    void cancelDoesNotConsumeMonthlyChange() {
        Store s = 매장();
        // 지난달 예약이 대기 중인 상태에서 이번 달 취소.
        Instant lastMonth = LocalDate.now(KST).withDayOfMonth(1).atStartOfDay(KST).minusDays(1).toInstant();
        Subscription sub = Subscription.builder().storeId(s.getId()).priceKrw(new BigDecimal("30000"))
                .status("ACTIVE").billingKey("bk-" + s.getId()).billingChannelKey("ch-test").autoRenew(true)
                .pendingDeliveryPlatforms(new String[] {"BAEMIN"}).planChangedAt(lastMonth).build();
        sub.changeNextBillingAt(dueLaterToday());
        subscriptions.save(sub);

        변경(s, "BAEMIN", "COUPANGEATS", "YOGIYO"); // 취소
        assertThat(다시읽기(s).getPlanChangedAt()).isEqualTo(lastMonth);

        변경(s, "YOGIYO"); // 이번 달 변경권은 남아 있다
        assertThat(다시읽기(s).getPendingDeliveryPlatforms()).containsExactly("YOGIYO");
    }
}
