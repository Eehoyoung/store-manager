package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    @MockitoBean MailService mail;
    @MockitoBean PortOneClient portOne;

    private int seq;

    @BeforeEach
    void clean() {
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
        verify(mail).sendDunningNotice(anyString(), contains("결제가 되지 않았어요"), contains("청구하지 않아요"));
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
        verify(mail, times(1)).sendDunningNotice(anyString(), contains("해지될 예정"), anyString());
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
        s.markDunningNotified(Instant.now().minus(Duration.ofDays(15))); // 해지일 = 어제
        subscriptions.save(s);

        dunning.run(Instant.now());

        Subscription after = reload(s);
        assertThat(after.getStatus()).isEqualTo("CANCELED");
        assertThat(after.getBillingKey()).isNull();
        assertThat(after.isAutoRenew()).isFalse();
        assertThat(revoked(a)).isTrue();
        verify(portOne).deleteBillingKey("bk");
        assertThat(auditLogs.findAll()).anyMatch(l -> "SUBSCRIPTION_TERMINATED_BY_COMPANY".equals(l.getAction())
                && l.getDetail().contains("terminationDate") && l.getDetail().contains("\"billedUnpaid\":false"));
        assertThat(auditLogs.findAll()).anyMatch(l -> "CREDENTIAL_REVOKED_ON_TERMINATION".equals(l.getAction()));
        verify(mail).sendDunningNotice(anyString(), contains("해지되었어요"), anyString());
    }

    @Test
    void 다른_매장이_쓰는_계정은_해지해도_보존() {
        AppUser o = owner();
        PlatformAccount a = account(o);
        Subscription s = late(o, a);
        s.markDunningNotified(Instant.now().minus(Duration.ofDays(15)));
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
        s.markTerminationReminded(now);
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
    void 플래그가_꺼져_있으면_아무것도_하지_않는다() {
        SubscriptionRepository repo = mock(SubscriptionRepository.class);
        MailService mailer = mock(MailService.class);
        PortOneClient pg = mock(PortOneClient.class);
        when(repo.findDunningTargets(any())).thenReturn(java.util.List.of(
                Subscription.builder().storeId(1L).priceKrw(BigDecimal.TEN).build()));
        DunningService off = new DunningService(repo, null, null, null, mailer, null, null, pg, null, null, null,
                null, false);

        assertThat(off.run(Instant.now())).isZero();

        verifyNoInteractions(mailer, pg);
        verify(repo, never()).save(any());
    }
}
