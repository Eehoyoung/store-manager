package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.sysauth.MailService;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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

/** 무료체험 → 유료 전환 사전고지(약관 9.4조 4항). 체험 종료 7일 전부터 한 번, 성공했을 때만 기록한다. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TrialConversionNoticeIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired TrialConversionNoticeService notices;
    @Autowired AppUserRepository users;
    @Autowired StoreRepository stores;
    @Autowired SubscriptionRepository subscriptions;
    @MockitoBean MailService mail;

    @BeforeEach
    void clean() {
        subscriptions.deleteAll();
        reset(mail);
        when(mail.sendTrialConversionNotice(anyString(), anyString(), anyString())).thenReturn(true);
    }

    private Subscription 체험중(String email, Duration untilEnd, boolean autoRenew) {
        AppUser owner = users.save(AppUser.builder().email(email).passwordHash("x").name("사장").build());
        Store store = stores.save(Store.builder().ownerId(owner.getId()).name("체험매장").build());
        Instant end = Instant.now().plus(untilEnd);
        Subscription s = Subscription.builder().storeId(store.getId()).priceKrw(new BigDecimal("30000"))
                .status("TRIAL").promotionCode("OPEN30").trialEndsAt(end).autoRenew(autoRenew)
                .billingKey(autoRenew ? "key-" + email : null).build();
        s.changeNextBillingAt(end);
        return subscriptions.save(s);
    }

    @Test
    void 체험_종료_7일_이내면_한_번만_보낸다() {
        Subscription s = 체험중("notice-1@example.com", Duration.ofDays(5), true);

        assertThat(notices.sendDue(Instant.now())).isEqualTo(1);
        assertThat(notices.sendDue(Instant.now())).isZero();

        verify(mail, times(1)).sendTrialConversionNotice(eq("notice-1@example.com"), anyString(),
                contains("33,000원"));
        assertThat(subscriptions.findById(s.getId()).orElseThrow().getTrialNoticeSentAt()).isNotNull();
    }

    @Test
    void 본문에_전환일_금액_결제방법_해지방법이_있다() {
        체험중("notice-body@example.com", Duration.ofDays(3), true);
        notices.sendDue(Instant.now());
        verify(mail).sendTrialConversionNotice(anyString(), anyString(), contains("유료 전환일"));
        verify(mail).sendTrialConversionNotice(anyString(), anyString(), contains("자동결제"));
        verify(mail).sendTrialConversionNotice(anyString(), anyString(), contains("해지 방법"));
    }

    @Test
    void 아직_7일보다_멀면_보내지_않는다() {
        체험중("notice-far@example.com", Duration.ofDays(10), true);
        assertThat(notices.sendDue(Instant.now())).isZero();
        verify(mail, never()).sendTrialConversionNotice(anyString(), anyString(), anyString());
    }

    @Test
    void 자동결제를_해지했으면_전환이_없으므로_보내지_않는다() {
        체험중("notice-off@example.com", Duration.ofDays(5), false);
        assertThat(notices.sendDue(Instant.now())).isZero();
    }

    @Test
    void 발송에_실패하면_기록하지_않고_다음날_다시_보낸다() {
        Subscription s = 체험중("notice-fail@example.com", Duration.ofDays(5), true);
        when(mail.sendTrialConversionNotice(anyString(), anyString(), anyString())).thenReturn(false);

        assertThat(notices.sendDue(Instant.now())).isZero();
        assertThat(subscriptions.findById(s.getId()).orElseThrow().getTrialNoticeSentAt()).isNull();

        when(mail.sendTrialConversionNotice(anyString(), anyString(), anyString())).thenReturn(true);
        assertThat(notices.sendDue(Instant.now())).isEqualTo(1);
    }
}
