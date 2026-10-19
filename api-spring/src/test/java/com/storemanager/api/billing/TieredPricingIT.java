package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.agreement.AgreementService;
import com.storemanager.api.billing.BillingDtos.BillingView;
import com.storemanager.api.billing.BillingDtos.CheckoutRequest;
import com.storemanager.api.franchise.FranchiseBrand;
import com.storemanager.api.franchise.FranchiseBrandRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.sysauth.MailService;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
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

/** 선택형 요금 PR2 — 플래그 켜짐에서의 가격 정본·가맹 분기·청구 금액. */
@SpringBootTest(properties = "app.pricing.tiered.enabled=true")
@ActiveProfiles("test")
@Testcontainers
class TieredPricingIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired BillingService billingService;
    @Autowired AgreementService agreements;
    @Autowired BrandPricingService pricing;
    @Autowired TrialConversionNoticeService notices;
    @Autowired AppUserRepository users;
    @Autowired StoreRepository stores;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired PaymentRepository payments;
    @Autowired FranchiseBrandRepository brands;
    @MockitoBean PortOneClient portOne;
    @MockitoBean MailService mail;

    private static final YearMonth MONTH = YearMonth.now();

    @BeforeEach
    void setUp() {
        Mockito.reset(portOne, mail);
        when(portOne.enabled()).thenReturn(true);
        when(portOne.channelKey()).thenReturn("ch-test");
        when(portOne.storeId()).thenReturn("store-test");
        when(portOne.pay(anyString(), anyString(), anyString(), anyLong(), anyMap()))
                .thenReturn(new PortOneClient.Charge(PortOneClient.Outcome.PAID, null));
    }

    private Store 매장(String email, String brand) {
        AppUser owner = users.save(AppUser.builder().email(email).passwordHash("x").name("사장").phone("01000000000")
                .build());
        Store store = stores.save(Store.builder().ownerId(owner.getId()).name("선택형-" + email).build());
        if (brand != null) {
            store.assignBrand(brand);
            store = stores.save(store);
        }
        return store;
    }

    private String 브랜드(int committed) {
        String name = "선택형브랜드-" + System.nanoTime();
        brands.save(FranchiseBrand.builder().brandName(name).committedStoreCount(committed).build());
        return name;
    }

    private Subscription 구독(Store store, String status, String[] platforms) {
        Subscription s = Subscription.builder().storeId(store.getId()).priceKrw(new BigDecimal("30000"))
                .status(status).activeDeliveryPlatforms(platforms).build();
        s.changeNextBillingAt(Instant.now().plus(Duration.ofDays(10)));
        return subscriptions.save(s);
    }

    @Test
    void 일반매장은_선택_수대로_25000_29000_33000이고_명단이_null이면_3개다() {
        Store s = 매장("t-plain@example.com", null);
        assertThat(pricing.quoteFor(s, MONTH, 구독(s, "ACTIVE", new String[] {"BAEMIN"})).total()).isEqualTo(25000);
        Store s2 = 매장("t-plain2@example.com", null);
        assertThat(pricing.quoteFor(s2, MONTH, 구독(s2, "ACTIVE", new String[] {"BAEMIN", "YOGIYO"})).total())
                .isEqualTo(29000);
        Store s3 = 매장("t-plain3@example.com", null);
        PriceQuote three = pricing.quoteFor(s3, MONTH, 구독(s3, "ACTIVE", new String[] {"BAEMIN", "YOGIYO", "COUPANGEATS"}));
        assertThat(three).isEqualTo(new PriceQuote(30000, 3000, 33000));
        Store sn = 매장("t-plain-null@example.com", null);
        assertThat(pricing.quoteFor(sn, MONTH, 구독(sn, "ACTIVE", null))).isEqualTo(three); // nullPlatformsMeansThree
        assertThat(pricing.quoteFor(sn, MONTH, null)).isEqualTo(three); // 구독 행 없음
        Store st = 매장("t-trial@example.com", null);
        assertThat(pricing.quoteFor(st, MONTH, 구독(st, "TRIAL", null)).total()).isEqualTo(33000); // 체험 중 미선택
    }

    @Test
    void franchiseStore_3개나_명단_null이면_구간_단가_1_2개면_일반가이고_구간_할인이_없다() {
        Store b = 매장("t-fr3@example.com", 브랜드(300)); // 구간 25,000 + 2,500
        assertThat(pricing.quoteFor(b, MONTH, 구독(b, "ACTIVE", new String[] {"BAEMIN", "YOGIYO", "COUPANGEATS"})))
                .isEqualTo(new PriceQuote(25000, 2500, 27500));
        Store bn = 매장("t-frnull@example.com", 브랜드(300));
        assertThat(pricing.quoteFor(bn, MONTH, 구독(bn, "ACTIVE", null))).isEqualTo(new PriceQuote(25000, 2500, 27500));

        Store b1 = 매장("t-fr1@example.com", 브랜드(500)); // 가장 싼 구간이어도 1개면 25,000
        assertThat(pricing.quoteFor(b1, MONTH, 구독(b1, "ACTIVE", new String[] {"BAEMIN"})).total()).isEqualTo(25000);
        Store b2 = 매장("t-fr2@example.com", 브랜드(500));
        assertThat(pricing.quoteFor(b2, MONTH, 구독(b2, "ACTIVE", new String[] {"BAEMIN", "YOGIYO"})).total())
                .isEqualTo(29000);
    }

    @Test
    void paidStoreCount는_1_2개_가맹_매장도_센다() {
        String brand = 브랜드(1);
        Store a = 매장("t-cnt-a@example.com", brand);
        Store b = 매장("t-cnt-b@example.com", brand);
        Store c = 매장("t-cnt-c@example.com", brand);
        구독(a, "ACTIVE", new String[] {"BAEMIN"});
        구독(b, "ACTIVE", new String[] {"BAEMIN", "YOGIYO"});
        구독(c, "ACTIVE", null);
        assertThat(pricing.paidStoreCount(brand)).isEqualTo(3);
    }

    @Test
    void 청구는_공급가_22727_VAT_2273_합계_25000으로_포트원에_나간다() throws Exception {
        Store store = 매장("t-charge@example.com", null);
        AppUser owner = users.findById(store.getOwnerId()).orElseThrow();
        Subscription s = Subscription.builder().storeId(store.getId()).priceKrw(new BigDecimal("30000"))
                .activeDeliveryPlatforms(new String[] {"YOGIYO"}).build(); // 기본 TRIAL, 결제 전
        subscriptions.save(s);
        JsonNode info = JSON.readTree("{\"status\":\"ISSUED\",\"customer\":{\"id\":\"store-" + store.getPublicId()
                + "\"},\"channels\":[{\"key\":\"ch-test\"}]}");
        when(portOne.billingKey("key-tiered")).thenReturn(info);

        BillingView view = billingService.checkout(owner.getPublicId(), store.getPublicId(),
                new CheckoutRequest("key-tiered", true, agreements.currentVersion(), List.of("YOGIYO")), // D43A: 켜짐이면 선택 필수
                "127.0.0.1", "junit");

        ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
        Mockito.verify(portOne).pay(anyString(), anyString(), anyString(), amount.capture(), anyMap());
        assertThat(amount.getValue()).isEqualTo(25000L);
        Subscription row = subscriptions.findByStoreIdAndStatusNot(store.getId(), "CANCELED").orElseThrow();
        Payment p = payments.findBySubscriptionIdOrderByCreatedAtDesc(row.getId(),
                org.springframework.data.domain.PageRequest.of(0, 1)).getContent().get(0);
        assertThat(p.getAmountKrw()).isEqualByComparingTo("22727");
        assertThat(p.getVatKrw()).isEqualByComparingTo("2273");
        assertThat(p.totalKrw()).isEqualByComparingTo("25000");
        assertThat(view.amountKrw()).isEqualTo(25000L);
        assertThat(view.unitPriceKrw()).isEqualTo(22727);
    }

    @Test
    void 사전고지_메일도_선택_수_금액을_적는다() {
        Store store = 매장("t-notice@example.com", null);
        Instant end = Instant.now().plus(Duration.ofDays(5));
        Subscription s = Subscription.builder().storeId(store.getId()).priceKrw(new BigDecimal("30000"))
                .status("TRIAL").promotionCode("OPEN30").trialEndsAt(end).activeDeliveryPlatforms(
                        new String[] {"BAEMIN", "YOGIYO"}).build();
        s.changeNextBillingAt(end);
        s = subscriptions.save(s);
        assertThat(notices.body(store, s)).contains("월 29,000원(부가세 포함)");
        assertThat(List.of(s.getActiveDeliveryPlatforms())).containsExactly("BAEMIN", "YOGIYO"); // 배열 매핑 왕복
    }
}
