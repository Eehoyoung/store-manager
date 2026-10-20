package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.agreement.AgreementService;
import com.storemanager.api.billing.BillingDtos.BillingView;
import com.storemanager.api.billing.BillingDtos.CheckoutRequest;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 선택형 요금(D38) PR5 - 플래그 꺼짐(기본)에서는 변경 API 거부, 카드 등록은 선택 필드 무시, 킬 스위치 갱신 거부. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TieredPlanChangeFlagOffIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired BillingService billingService;
    @Autowired AgreementService agreements;
    @Autowired AppUserRepository users;
    @Autowired StoreRepository stores;
    @Autowired SubscriptionRepository subscriptions;
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

    private Store 매장() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        AppUser owner = users.save(AppUser.builder().email("off-" + key + "@example.com").passwordHash("x")
                .name("사장").phone("01000000000").build());
        return stores.save(Store.builder().ownerId(owner.getId()).name("꺼짐-" + key).build());
    }

    private UUID owner(Store s) {
        return users.findById(s.getOwnerId()).orElseThrow().getPublicId();
    }

    private Subscription 구독(Store store, String[] active, String[] pending) {
        Subscription s = Subscription.builder().storeId(store.getId()).priceKrw(new BigDecimal("30000"))
                .status("ACTIVE").activeDeliveryPlatforms(active).pendingDeliveryPlatforms(pending)
                .billingKey("bk-" + store.getId()).billingChannelKey("ch-test").autoRenew(true).build();
        s.changeNextBillingAt(LocalDate.now(KST).plusDays(1).atStartOfDay(KST).minusSeconds(30).toInstant());
        return subscriptions.save(s);
    }

    @Test
    void changeEndpointRejectedWhenFlagOff() {
        Store s = 매장();
        구독(s, null, null);
        assertThatThrownBy(() -> billingService.changePlan(owner(s), s.getPublicId(), List.of("BAEMIN")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        Subscription row = subscriptions.findByStoreIdAndStatusNot(s.getId(), "CANCELED").orElseThrow();
        assertThat(row.getPendingDeliveryPlatforms()).isNull();
        assertThat(row.getPlanChangedAt()).isNull();
    }

    @Test
    void renewalRefusedWhenFlagOffAndSelectionExists() {
        Store active = 매장();
        구독(active, new String[] {"BAEMIN"}, null);
        Store pending = 매장();
        구독(pending, null, new String[] {"BAEMIN"});

        billingService.renew(active.getId());
        billingService.renew(pending.getId());

        verify(portOne, never()).pay(anyString(), anyString(), anyString(), anyLong(), anyMap());
        assertThat(subscriptions.findByStoreIdAndStatusNot(active.getId(), "CANCELED").orElseThrow()
                .getRenewalFailures()).isZero();
    }

    @Test
    void renewalWithoutSelectionIsUnchangedWhenFlagOff() {
        Store s = 매장();
        구독(s, null, null);
        billingService.renew(s.getId());
        ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
        verify(portOne).pay(anyString(), anyString(), anyString(), amount.capture(), anyMap());
        assertThat(amount.getValue()).isEqualTo(33000L);
    }

    @Test
    void checkoutIgnoresSelectionWhenOff() throws Exception {
        Store s = 매장();
        subscriptions.save(Subscription.builder().storeId(s.getId()).priceKrw(new BigDecimal("30000")).build());
        JsonNode info = JSON.readTree("{\"status\":\"ISSUED\",\"customer\":{\"id\":\"store-" + s.getPublicId()
                + "\"},\"channels\":[{\"key\":\"ch-test\"}]}");
        when(portOne.billingKey("k-off")).thenReturn(info);

        BillingView v = billingService.checkout(owner(s), s.getPublicId(),
                new CheckoutRequest("k-off", true, agreements.currentVersion(), List.of("YOGIYO")), "127.0.0.1",
                "junit");

        ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
        verify(portOne).pay(anyString(), anyString(), anyString(), amount.capture(), anyMap());
        assertThat(amount.getValue()).isEqualTo(33000L); // 선택 필드 무시
        assertThat(subscriptions.findByStoreIdAndStatusNot(s.getId(), "CANCELED").orElseThrow()
                .getActiveDeliveryPlatforms()).isNull();
        assertThat(v.tiered()).isNull();
    }

    @Test
    void viewJsonHasNoTieredKeyWhenFlagOff() throws Exception {
        Store s = 매장();
        구독(s, null, null);
        BillingView v = billingService.view(owner(s), s.getPublicId());
        String json = JSON.writeValueAsString(v);
        assertThat(json).doesNotContain("tiered").doesNotContain("deliveryPlatforms");
        assertThat(json).contains("\"brandPaidStoreCount\":null"); // 기존 null 키는 그대로 나간다(바이트 동일)
    }

    // ── 리뷰 반영: 킬 스위치 범위(3개 미만만)와 카드 등록 경로 ─────────────

    @Test
    void renewalWithThreeSelectionProceedsWhenFlagOff() {
        Store s = 매장();
        구독(s, new String[] {"BAEMIN", "COUPANGEATS", "YOGIYO"}, null); // 3개 = 꺼짐 가격과 같다
        billingService.renew(s.getId());
        ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
        verify(portOne).pay(anyString(), anyString(), anyString(), amount.capture(), anyMap());
        assertThat(amount.getValue()).isEqualTo(33000L);
    }

    @Test
    void checkoutRefusedWhenFlagOffAndSelectionBelowThree() throws Exception {
        Store s = 매장();
        // UNPAID(결제 이력 없음) + 1개 명단 - 꺼짐 가격 33,000원으로 청구하면 안 된다.
        subscriptions.save(Subscription.builder().storeId(s.getId()).priceKrw(new BigDecimal("30000"))
                .activeDeliveryPlatforms(new String[] {"YOGIYO"}).build());
        JsonNode info = JSON.readTree("{\"status\":\"ISSUED\",\"customer\":{\"id\":\"store-" + s.getPublicId()
                + "\"},\"channels\":[{\"key\":\"ch-test\"}]}");
        when(portOne.billingKey("k-kill")).thenReturn(info);

        assertThatThrownBy(() -> billingService.checkout(owner(s), s.getPublicId(),
                new CheckoutRequest("k-kill", true, agreements.currentVersion()), "127.0.0.1", "junit"))
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        verify(portOne, never()).pay(anyString(), anyString(), anyString(), anyLong(), anyMap());
    }
}
