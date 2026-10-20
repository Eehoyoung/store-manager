package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.reset;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 약관 9.4 제4항 - 선택형 요금 켜짐일 때 유료전환 사전고지에 선택 상품이 들어간다(꺼짐 본문은 TrialConversionNoticeIT). */
@SpringBootTest(properties = "app.pricing.tiered.enabled=true")
@ActiveProfiles("test")
@Testcontainers
class TrialConversionNoticeTieredIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

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

    @Test
    void conversionNoticeIncludesProductWhenOn() {
        AppUser owner = users.save(AppUser.builder().email("notice-tier@example.com").passwordHash("x").name("사장")
                .build());
        Store store = stores.save(Store.builder().ownerId(owner.getId()).name("체험매장").build());
        Instant end = Instant.now().plus(Duration.ofDays(3));
        Subscription s = Subscription.builder().storeId(store.getId()).priceKrw(new BigDecimal("30000"))
                .status("TRIAL").promotionCode("OPEN30").trialEndsAt(end).autoRenew(true).billingKey("key-tier")
                .activeDeliveryPlatforms(new String[] {"BAEMIN", "YOGIYO"}).build();
        s.changeNextBillingAt(end);
        subscriptions.save(s);

        assertThat(notices.sendDue(Instant.now())).isEqualTo(1);

        verify(mail).sendTrialConversionNotice(anyString(), anyString(),
                contains("- 선택 상품: 배달앱 2개(배달의민족, 요기요)\n- 결제 금액: 월 29,000원"));
    }
}
