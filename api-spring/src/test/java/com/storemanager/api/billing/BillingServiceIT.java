package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.billing.BillingDtos.CancellationRequestResponse;
import com.storemanager.api.billing.BillingDtos.ConfirmPaymentRequest;
import com.storemanager.api.billing.BillingDtos.ConfirmPaymentResponse;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.notify.NotificationLogRepository;
import com.storemanager.api.security.JwtTokenProvider;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 구독·청구 배치(B3)·미납 처리(B4)·운영자 입금 확인(B6) 을 실제 Postgres 위에서 검증한다 (docs/13 §9).
 * AnalyticsServiceIT 와 동일한 Testcontainers 패턴(Redis 불필요 — Billing 흐름은 Redis 를 쓰지 않는다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
class BillingServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired BillingService billingService;
    @Autowired AppUserRepository appUserRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired NotificationLogRepository notificationLogRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;

    private record 매장픽스처(UUID ownerPublicId, UUID storePublicId, Long storeId) {
    }

    private 매장픽스처 매장을_만든다(String email) {
        AppUser owner = appUserRepository.save(AppUser.builder().email(email).passwordHash("dummy").name("사장")
                .build());
        Store store = storeRepository.save(Store.builder().ownerId(owner.getId()).name("청구매장-" + email).build());
        return new 매장픽스처(owner.getPublicId(), store.getPublicId(), store.getId());
    }

    private Subscription 구독을_만든다(Long storeId, String status, Instant periodStart, Instant periodEnd) {
        return subscriptionRepository.save(Subscription.builder().storeId(storeId).priceKrw(BigDecimal.valueOf(30000))
                .status(status).currentPeriodStart(periodStart).currentPeriodEnd(periodEnd).build());
    }

    private Payment 청구를_만든다(Long subscriptionId, String idempotencyKey, String status, Instant dueAt) {
        // ★ deposit_code 는 PENDING 상태에서 유일해야 한다(V11 부분 유니크 인덱스) — 테스트마다 idempotencyKey 로 구분한다.
        return paymentRepository.save(Payment.builder().subscriptionId(subscriptionId)
                .idempotencyKey(idempotencyKey).amountKrw(BigDecimal.valueOf(30000)).vatKrw(BigDecimal.valueOf(3000))
                .status(status).method("BANK_TRANSFER").depositCode(idempotencyKey).dueAt(dueAt).build());
    }

    // ── (a) B3: 배치 두 번 실행해도 같은 달 청구는 1건만 ──────────────────

    @Test
    void invoiceBatch은_두번_실행해도_같은_달_청구가_1건만_생긴다() {
        매장픽스처 f = 매장을_만든다("invoice-owner@example.com");
        Instant pastEnd = Instant.now().minus(Duration.ofDays(1)); // 이미 기간이 끝난 구독
        Subscription sub = 구독을_만든다(f.storeId(), "ACTIVE", pastEnd.minus(Duration.ofDays(30)), pastEnd);

        billingService.runDailyInvoiceBatch();
        billingService.runDailyInvoiceBatch(); // ★ 두 번째 실행 — 청구가 또 생기면 안 된다

        List<Payment> payments = paymentRepository.findAll().stream()
                .filter(p -> p.getSubscriptionId().equals(sub.getId())).toList();
        assertThat(payments).hasSize(1);

        Subscription reloaded = subscriptionRepository.findById(sub.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPeriodEnd()).isAfter(pastEnd); // 기간은 정확히 한 번만 이월됐다
    }

    // ── (b) B6: 이미 PAID 인 건 재확인 — 200, 상태 유지 ───────────────────

    @Test
    void confirmPayment은_이미_PAID인_건을_재확인해도_상태가_유지된다() {
        매장픽스처 f = 매장을_만든다("confirm-idem@example.com");
        Subscription sub = 구독을_만든다(f.storeId(), "ACTIVE", Instant.now(), Instant.now().plus(Duration.ofDays(30)));
        Payment payment = 청구를_만든다(sub.getId(), "k-idem", "PENDING", Instant.now().plus(Duration.ofDays(7)));

        ConfirmPaymentRequest req = new ConfirmPaymentRequest("홍길동", Instant.now(), 33000L, null);
        ConfirmPaymentResponse first = billingService.confirmPayment(payment.getId(), req);
        assertThat(first.status()).isEqualTo("PAID");
        // ★ DB 왕복(TIMESTAMPTZ 마이크로초 절삭) 이후의 값을 기준값으로 삼는다 — Instant.now() 원본(나노초)과
        // 직접 비교하면 정밀도 차이로 항상 실패한다.
        Instant paidAtAfterFirst = paymentRepository.findById(payment.getId()).orElseThrow().getPaidAt();

        // ★ 운영자가 두 번 눌러도 안전해야 한다 — 예외 없이 그대로 200 이고 값이 바뀌지 않는다.
        ConfirmPaymentResponse second = billingService.confirmPayment(payment.getId(), req);
        assertThat(second.status()).isEqualTo("PAID");
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getPaidAt()).isEqualTo(paidAtAfterFirst);
    }

    // ── (c) B6: 입금액 불일치 — 400, 조용히 통과하지 않는다 ───────────────

    @Test
    void confirmPayment은_금액이_불일치하면_예외를_던지고_상태를_바꾸지_않는다() {
        매장픽스처 f = 매장을_만든다("confirm-mismatch@example.com");
        Subscription sub = 구독을_만든다(f.storeId(), "ACTIVE", Instant.now(), Instant.now().plus(Duration.ofDays(30)));
        Payment payment = 청구를_만든다(sub.getId(), "k-mismatch", "PENDING", Instant.now().plus(Duration.ofDays(7)));

        ConfirmPaymentRequest wrong = new ConfirmPaymentRequest("홍길동", Instant.now(), 1000L, null); // 청구액(33000)과 다름

        assertThatThrownBy(() -> billingService.confirmPayment(payment.getId(), wrong))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    // ── (d) B6: PAST_DUE/SUSPENDED 구독을 ACTIVE 로 복구 ──────────────────

    @Test
    void confirmPayment은_PAST_DUE와_SUSPENDED_구독을_ACTIVE로_복구한다() {
        매장픽스처 f1 = 매장을_만든다("restore-pastdue@example.com");
        Subscription pastDueSub = 구독을_만든다(f1.storeId(), "PAST_DUE", Instant.now(), Instant.now().plus(Duration.ofDays(30)));
        Payment p1 = 청구를_만든다(pastDueSub.getId(), "k-pastdue", "PENDING", Instant.now());
        billingService.confirmPayment(p1.getId(), new ConfirmPaymentRequest("가", Instant.now(), 33000L, null));
        assertThat(subscriptionRepository.findById(pastDueSub.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");

        매장픽스처 f2 = 매장을_만든다("restore-suspended@example.com");
        Subscription suspendedSub = 구독을_만든다(f2.storeId(), "SUSPENDED", Instant.now(), Instant.now().plus(Duration.ofDays(30)));
        Payment p2 = 청구를_만든다(suspendedSub.getId(), "k-suspended", "PENDING", Instant.now());
        billingService.confirmPayment(p2.getId(), new ConfirmPaymentRequest("나", Instant.now(), 33000L, null));
        assertThat(subscriptionRepository.findById(suspendedSub.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    // ── (e) B4: 같은 단계에서 알림이 중복 발송되지 않는다 ─────────────────

    @Test
    void overdueBatch은_같은_단계에서_알림을_중복발송하지_않는다() {
        매장픽스처 f = 매장을_만든다("overdue-owner@example.com");
        Subscription sub = 구독을_만든다(f.storeId(), "ACTIVE", Instant.now().minus(Duration.ofDays(60)),
                Instant.now().plus(Duration.ofDays(30)));
        // dueAt 을 8일 전으로 고정 — 이미 D+3, D+7 을 모두 지난 상태에서 배치를 반복 호출해 단계별 1회씩만 발송되는지 본다.
        Payment payment = 청구를_만든다(sub.getId(), "k-overdue", "PENDING", Instant.now().minus(Duration.ofDays(8)));

        billingService.runDailyOverdueBatch(); // 1회째 — D+3 발송
        assertThat(notificationLogRepository.countByRefTypeAndRefIdAndTemplate("PAYMENT", payment.getId(),
                "PAYMENT_OVERDUE")).isEqualTo(1);

        billingService.runDailyOverdueBatch(); // 2회째 — D+7 발송(이미 8일 지났으므로 조건 충족)
        assertThat(notificationLogRepository.countByRefTypeAndRefIdAndTemplate("PAYMENT", payment.getId(),
                "PAYMENT_OVERDUE")).isEqualTo(2);

        billingService.runDailyOverdueBatch(); // 3회째 — ★ 더 이상 늘어나면 안 된다(중복발송 방지)
        assertThat(notificationLogRepository.countByRefTypeAndRefIdAndTemplate("PAYMENT", payment.getId(),
                "PAYMENT_OVERDUE")).isEqualTo(2);
    }

    @Test
    void 해지요청은_소유자만_가능하고_반복해도_최초요청과_감사로그_한건만_남긴다() {
        매장픽스처 f = 매장을_만든다("cancel-owner@example.com");
        Subscription sub = 구독을_만든다(f.storeId(), "ACTIVE", Instant.now(), Instant.now().plus(Duration.ofDays(30)));

        CancellationRequestResponse first = billingService.requestCancellation(f.ownerPublicId(), f.storePublicId());
        CancellationRequestResponse second = billingService.requestCancellation(f.ownerPublicId(), f.storePublicId());

        assertThat(first.status()).isEqualTo("REQUESTED");
        assertThat(second.requestedAt()).isEqualTo(first.requestedAt());
        Subscription reloaded = subscriptionRepository.findById(sub.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("ACTIVE");
        assertThat(reloaded.getCancellationRequestedAt()).isNotNull();
        assertThat(auditLogRepository.findAll()).filteredOn(log ->
                "SUBSCRIPTION_CANCELLATION_REQUESTED".equals(log.getAction())
                        && sub.getId().equals(log.getTargetId()))
                .hasSize(1);

        매장픽스처 other = 매장을_만든다("cancel-other@example.com");
        assertThatThrownBy(() -> billingService.requestCancellation(other.ownerPublicId(), f.storePublicId()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void deleteSubscription은_해지요청을_202로_접수한다() throws Exception {
        매장픽스처 f = 매장을_만든다("cancel-api@example.com");
        구독을_만든다(f.storeId(), "ACTIVE", Instant.now(), Instant.now().plus(Duration.ofDays(30)));

        mockMvc.perform(delete("/api/v1/stores/{storeId}/subscription", f.storePublicId())
                        .header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(f.ownerPublicId().toString())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.requestedAt").isString());
    }

}
