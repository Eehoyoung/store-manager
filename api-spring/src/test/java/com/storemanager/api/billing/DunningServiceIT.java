package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.crypto.PlatformAccountRepository;
import com.storemanager.api.review.StorePlatformLink;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.sysauth.MailService;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 미납 해지 절차(T-30, D120): 시정 요구 통지 -> 3일 전 재통지 -> 해지·빌링키 삭제·자격증명 파기. */
@SpringBootTest(properties = "app.billing.dunning-notify-enabled=true")
@ActiveProfiles("test")
@Testcontainers
class DunningServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired DunningService dunning;
    @Autowired AppUserRepository users;
    @Autowired StoreRepository stores;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired PlatformAccountRepository accounts;
    @Autowired StorePlatformLinkRepository links;
    @Autowired AuditLogRepository auditLogs;
    @Autowired PaymentRepository payments;
    @Autowired org.springframework.transaction.support.TransactionTemplate txTemplate;
    @MockitoBean MailService mail;
    @MockitoBean PortOneClient portOne;

    private int seq;

    @BeforeEach
    void clean() {
        payments.deleteAll();
        links.deleteAll();
        subscriptions.deleteAll();
        accounts.deleteAll();
        reset(mail, portOne);
        when(mail.sendDunningNotice(anyString(), anyString(), anyString())).thenReturn(true);
    }

    private AppUser owner() {
        return users.save(AppUser.builder().email("dun-" + System.nanoTime() + "@example.com").passwordHash("x")
                .name("사장").build());
    }

    private PlatformAccount account(AppUser o) {
        return accounts.save(PlatformAccount.builder().ownerId(o.getId()).platform("BAEMIN").loginId("id" + System.nanoTime())
                .encPassword(new byte[] {1}).encDek(new byte[] {1}).kmsKeyId("k").encNonce(new byte[] {1})
                .passwordFingerprint(new byte[] {(byte) (++seq), 9}).build());
    }

    /** nextBilling 이 10일 전 = 이용 제한 시각이 7일 전. failures>0 이라 미납 대상. */
    private Subscription store(AppUser o, PlatformAccount a, Instant nextBilling, boolean failed) {
        Store st = stores.save(Store.builder().ownerId(o.getId()).name("매장" + (++seq)).build());
        Subscription s = Subscription.builder().storeId(st.getId()).priceKrw(new BigDecimal("30000"))
                .status("ACTIVE").autoRenew(true).billingKey("bk").renewalFailures(failed ? 2 : 0).build();
        s.changeNextBillingAt(nextBilling);
        s = subscriptions.save(s);
        links.save(StorePlatformLink.builder().storeId(st.getId()).accountId(a.getId()).platform("BAEMIN")
                .platformStoreId("ps" + seq).build());
        return s;
    }

    private Subscription late(AppUser o, PlatformAccount a) {
        return store(o, a, Instant.now().minus(Duration.ofDays(10)), true);
    }

    private boolean revoked(PlatformAccount a) {
        return accounts.findById(a.getId()).orElseThrow().getRevokedAt() != null;
    }

    private Subscription reload(Subscription s) {
        return subscriptions.findById(s.getId()).orElseThrow();
    }

    @Test
    void 단계a_시정요구_통지하고_해지일을_발송일_14일_뒤로_저장() {
        AppUser o = owner();
        PlatformAccount a = account(o);
        Subscription s = late(o, a);

        dunning.run(Instant.now());

        Subscription after = reload(s);
        assertThat(after.getDunningNotifiedAt()).isNotNull();
        assertThat(after.getTerminationDueDate()).isEqualTo(LocalDate.now(KST).plusDays(14));
        assertThat(after.getStatus()).isEqualTo("ACTIVE");
        assertThat(revoked(a)).isFalse();
        verify(mail).sendDunningNotice(anyString(), contains("결제가 되지 않았어요"), argThat((String b) -> b.contains("청구하지 않아요") && b.contains("결제하면 이용이 유지되고") && b.contains("그 다음 날인") && b.contains("원(VAT 포함)")));
    }

    @Test
    void 단계a_메일이_안_나가면_기록하지_않고_다음날_재시도() {
        AppUser o = owner();
        Subscription s = late(o, account(o));
        when(mail.sendDunningNotice(anyString(), anyString(), anyString())).thenReturn(false);

        dunning.run(Instant.now());

        assertThat(reload(s).getDunningNotifiedAt()).isNull();
        assertThat(reload(s).getTerminationDueDate()).isNull();
    }

    @Test
    void 단계b_해지_3일_전_재통지는_한번만() {
        AppUser o = owner();
        Subscription s = late(o, account(o));
        s.markDunningNotified(Instant.now().minus(Duration.ofDays(12))); // 해지일 = 오늘 + 2
        subscriptions.save(s);

        dunning.run(Instant.now());
        dunning.run(Instant.now());

        assertThat(reload(s).getTerminationRemindedAt()).isNotNull();
        verify(mail, times(1)).sendDunningNotice(anyString(), anyString(), argThat((String b) -> b.contains("결제하지 않으면") && b.contains("해지돼요") && b.contains("원(VAT 포함)")));
    }

    @Test
    void 해지일_전이고_3일_밖이면_아무것도_안_보낸다() {
        AppUser o = owner();
        Subscription s = late(o, account(o));
        s.markDunningNotified(Instant.now().minus(Duration.ofDays(5)));
        subscriptions.save(s);

        dunning.run(Instant.now());

        verify(mail, never()).sendDunningNotice(anyString(), anyString(), anyString());
        assertThat(reload(s).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void 단계c_해지일_도래_해지하고_빌링키_삭제_자격증명_파기_감사() {
        AppUser o = owner();
        PlatformAccount a = account(o);
        Subscription s = late(o, a);
        s.markDunningNotified(Instant.now().minus(Duration.ofDays(15))); // 기한 = 어제
        s.markTerminationReminded(Instant.now().minus(Duration.ofDays(4)), s.getTerminationDueDate());
        subscriptions.save(s);

        dunning.run(Instant.now());

        Subscription after = reload(s);
        assertThat(after.getStatus()).isEqualTo("CANCELED");
        assertThat(after.getBillingKey()).isNull();
        assertThat(after.isAutoRenew()).isFalse();
        assertThat(revoked(a)).isTrue();
        verify(portOne).deleteBillingKey("bk");
        assertThat(auditLogs.findAll()).anyMatch(l -> "SUBSCRIPTION_TERMINATED_BY_COMPANY".equals(l.getAction())
                && l.getDetail().contains("terminationDate") && l.getDetail().matches("(?s).*\"billedUnpaid\":\\s*false.*")); // jsonb 는 콜론 뒤 공백을 넣는다
        assertThat(auditLogs.findAll()).anyMatch(l -> "CREDENTIAL_REVOKED_ON_TERMINATION".equals(l.getAction()));
        verify(mail).sendDunningNotice(anyString(), contains("해지되었어요"), anyString());
    }

    @Test
    void 다른_매장이_쓰는_계정은_해지해도_보존() {
        AppUser o = owner();
        PlatformAccount a = account(o);
        Subscription s = late(o, a);
        s.markDunningNotified(Instant.now().minus(Duration.ofDays(15)));
        s.markTerminationReminded(Instant.now().minus(Duration.ofDays(4)), s.getTerminationDueDate());
        subscriptions.save(s);
        store(o, a, Instant.now().plus(Duration.ofDays(10)), false); // 같은 계정을 쓰는 정상 매장

        dunning.run(Instant.now());

        assertThat(reload(s).getStatus()).isEqualTo("CANCELED");
        assertThat(revoked(a)).isFalse();
    }

    @Test
    void 해지_접수한_구독과_결제실패_기록_없는_구독은_대상이_아니다() {
        AppUser o = owner();
        Subscription canceled = late(o, account(o));
        canceled.disableAutoRenew();
        subscriptions.save(canceled);
        store(o, account(o), Instant.now().minus(Duration.ofDays(10)), false);

        dunning.run(Instant.now());

        verify(mail, never()).sendDunningNotice(anyString(), anyString(), anyString());
    }

    @Test
    void 결제_성공_카드_교체_운영자_활성화는_미납_기록을_초기화한다() {
        Subscription s = Subscription.builder().storeId(1L).priceKrw(BigDecimal.TEN).autoRenew(true).build();
        Instant now = Instant.now();
        s.markDunningNotified(now);
        s.markTerminationReminded(now, LocalDate.now());
        s.chargeSucceeded(now, now.plus(Duration.ofDays(30)), "k", "c");
        assertThat(s.getDunningNotifiedAt()).isNull();
        assertThat(s.getTerminationDueDate()).isNull();
        assertThat(s.getTerminationRemindedAt()).isNull();

        s.markDunningNotified(now);
        s.swapBillingKey("k2", "c");
        assertThat(s.getTerminationDueDate()).isNull();

        s.markDunningNotified(now);
        s.activateByOperator(now, now.plus(Duration.ofDays(30)));
        assertThat(s.getTerminationDueDate()).isNull();
    }

    @Test
    void 기한_당일은_해지하지_않고_다음날_해지한다() {
        AppUser o = owner();
        PlatformAccount a = account(o);
        Subscription s = late(o, a);
        Instant now = Instant.now();
        s.markDunningNotified(now.minus(Duration.ofDays(14))); // 기한 = 오늘
        s.markTerminationReminded(now.minus(Duration.ofDays(3)), s.getTerminationDueDate());
        subscriptions.save(s);

        dunning.run(now);
        assertThat(reload(s).getStatus()).isEqualTo("ACTIVE");
        assertThat(revoked(a)).isFalse();

        dunning.run(now.plus(Duration.ofDays(1)));
        assertThat(reload(s).getStatus()).isEqualTo("CANCELED");
        assertThat(revoked(a)).isTrue();
    }

    @Test
    void 재통지_없이_기한이_지나면_해지하지_않고_재통지_후_기한을_늦춘다() {
        AppUser o = owner();
        Subscription s = late(o, account(o));
        Instant now = Instant.now();
        s.markDunningNotified(now.minus(Duration.ofDays(15))); // 기한 = 어제, 재통지 기록 없음
        subscriptions.save(s);

        dunning.run(now);

        Subscription after = reload(s);
        assertThat(after.getStatus()).isEqualTo("ACTIVE");
        assertThat(after.getTerminationRemindedAt()).isNotNull();
        assertThat(after.getTerminationDueDate()).isEqualTo(LocalDate.now(KST).plusDays(3));
        verify(mail).sendDunningNotice(anyString(), contains("결제가 아직 확인되지 않았어요"), contains("까지 결제하면 이용이 유지되고"));

        dunning.run(now.plus(Duration.ofDays(4))); // 연장된 기한 다음 날
        assertThat(reload(s).getStatus()).isEqualTo("CANCELED");
    }

    @Test
    void 청구_잠금_중이면_해지하지_않는다() {
        AppUser o = owner();
        PlatformAccount a = account(o);
        Subscription s = late(o, a);
        Instant now = Instant.now();
        s.markDunningNotified(now.minus(Duration.ofDays(15)));
        s.markTerminationReminded(now.minus(Duration.ofDays(4)), s.getTerminationDueDate());
        subscriptions.save(s);
        txTemplate.executeWithoutResult(st ->
                subscriptions.lockBilling(s.getStoreId(), now, now.plus(Duration.ofMinutes(5))));

        dunning.run(now);

        assertThat(reload(s).getStatus()).isEqualTo("ACTIVE");
        assertThat(revoked(a)).isFalse();
    }

    @Test
    void 결제_성공_뒤_다시_미납되면_지난_기한으로_해지하지_않고_새_통지부터() {
        AppUser o = owner();
        PlatformAccount a = account(o);
        Subscription s = late(o, a);
        Instant now = Instant.now();
        s.markDunningNotified(now.minus(Duration.ofDays(15)));
        s.markTerminationReminded(now.minus(Duration.ofDays(4)), s.getTerminationDueDate());
        s.chargeSucceeded(now, now.plus(Duration.ofDays(30)), "bk", "c");
        subscriptions.save(s);
        assertThat(reload(s).getTerminationDueDate()).isNull();

        // 다시 미납: 결제예정일이 지나 실패가 쌓였다
        Subscription again = reload(s);
        again.changeNextBillingAt(now.minus(Duration.ofDays(10)));
        again.recordChargeFailure();
        subscriptions.save(again);
        dunning.run(now);

        Subscription after = reload(s);
        assertThat(after.getStatus()).isEqualTo("ACTIVE");
        assertThat(revoked(a)).isFalse();
        assertThat(after.getTerminationDueDate()).isEqualTo(LocalDate.now(KST).plusDays(14));
        assertThat(after.getTerminationRemindedAt()).isNull();
    }

    @Test
    void 결과를_모르는_PENDING_결제가_있으면_통지도_해지도_안_한다() {
        AppUser o = owner();
        Subscription s = late(o, account(o));
        payments.save(Payment.builder().subscriptionId(s.getId()).idempotencyKey("portone:unk-" + s.getId())
                .pgTxId("unk-" + s.getId()).amountKrw(new BigDecimal("30000")).vatKrw(new BigDecimal("3000"))
                .method("PORTONE_CARD").build());

        dunning.run(Instant.now());

        verify(mail, never()).sendDunningNotice(anyString(), anyString(), anyString());
        assertThat(reload(s).getDunningNotifiedAt()).isNull();
    }

    @Test
    void 플래그가_꺼져_있으면_아무것도_하지_않는다() {
        SubscriptionRepository repo = mock(SubscriptionRepository.class);
        MailService mailer = mock(MailService.class);
        PortOneClient pg = mock(PortOneClient.class);
        when(repo.findDunningTargets(any())).thenReturn(java.util.List.of(
                Subscription.builder().storeId(1L).priceKrw(BigDecimal.TEN).build()));
        DunningService off = new DunningService(repo, null, null, null, mailer, null, null, pg, null, null, null,
                null, null, false);

        assertThat(off.run(Instant.now())).isZero();

        verifyNoInteractions(mailer, pg);
        verify(repo, never()).save(any());
    }
}
