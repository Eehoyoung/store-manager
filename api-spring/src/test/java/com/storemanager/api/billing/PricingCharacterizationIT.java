package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/**
 * 선택형 요금 PR2 특성화 테스트(2026-10-19). TIERED_PRICING_ENABLED 꺼짐(기본) 상태에서 가격·청구 금액·결제 응답
 * JSON 키·사전고지 메일 금액이 지금과 같다는 것을 고정한다. 제품 코드를 바꾸기 전에 먼저 통과해야 하고,
 * 가격 정본으로 교체한 뒤에도 무수정으로 통과해야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PricingCharacterizationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired BillingService billingService;
    @Autowired TrialConversionNoticeService notices;
    @Autowired AppUserRepository users;
    @Autowired StoreRepository stores;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired PaymentRepository payments;
    @Autowired FranchiseBrandRepository brands;
    @MockitoBean PortOneClient portOne;
    @MockitoBean MailService mail;

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        Mockito.reset(portOne, mail);
        when(portOne.enabled()).thenReturn(true);
        when(portOne.channelKey()).thenReturn("ch-test");
        when(portOne.storeId()).thenReturn("store-test");
        when(portOne.pay(anyString(), anyString(), anyString(), anyLong(), anyMap()))
                .thenReturn(new PortOneClient.Charge(PortOneClient.Outcome.PAID, null));
    }

    private record F(AppUser owner, Store store) {}

    private F 매장(String email, String brand) {
        AppUser owner = users.save(AppUser.builder().email(email).passwordHash("x").name("사장").phone("01000000000")
                .build());
        Store store = stores.save(Store.builder().ownerId(owner.getId()).name("특성화-" + email).build());
        if (brand != null) {
            store.assignBrand(brand);
            store = stores.save(store);
        }
        return new F(owner, store);
    }

    private String 브랜드(int committed) {
        String name = "특성화브랜드-" + System.nanoTime();
        brands.save(FranchiseBrand.builder().brandName(name).committedStoreCount(committed).build());
        return name;
    }

    private void 빌링키를_허용한다(Store store, String key) throws Exception {
        JsonNode info = JSON.readTree("{\"status\":\"ISSUED\",\"customer\":{\"id\":\"store-" + store.getPublicId()
                + "\"},\"channels\":[{\"key\":\"ch-test\"}]}");
        when(portOne.billingKey(key)).thenReturn(info);
    }

    private long 청구액() {
        ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
        Mockito.verify(portOne, Mockito.atLeastOnce()).pay(anyString(), anyString(), anyString(), amount.capture(),
                anyMap());
        return amount.getAllValues().get(amount.getAllValues().size() - 1);
    }

    private BillingView 결제한다(F f, String key) throws Exception {
        빌링키를_허용한다(f.store(), key);
        return billingService.checkout(f.owner().getPublicId(), f.store().getPublicId(),
                new CheckoutRequest(key, true, AgreementService.CURRENT_VERSION), "127.0.0.1", "junit");
    }

    private Payment 마지막_결제(F f) {
        Subscription s = subscriptions.findByStoreIdAndStatusNot(f.store().getId(), "CANCELED").orElseThrow();
        List<Payment> list = new ArrayList<>(payments.findBySubscriptionIdOrderByCreatedAtDesc(s.getId(),
                org.springframework.data.domain.PageRequest.of(0, 12)).getContent());
        return list.get(0);
    }

    // ── 일반 매장: 30,000 + 3,000 = 33,000 ───────────────────────────────

    @Test
    void 일반매장_첫_결제는_공급가_30000_VAT_3000_합계_33000이다() throws Exception {
        F f = 매장("char-plain@example.com", null);
        결제한다(f, "key-plain");

        assertThat(청구액()).isEqualTo(33000L);
        Payment p = 마지막_결제(f);
        assertThat(p.getAmountKrw()).isEqualByComparingTo("30000");
        assertThat(p.getVatKrw()).isEqualByComparingTo("3000");
    }

    @Test
    void 결제응답_JSON은_지금_키_목록과_값을_유지한다() throws Exception {
        F f = 매장("char-view@example.com", null);
        // 결제 전(UNPAID) 화면: 지금 청구될 금액이 보인다.
        BillingView before = billingService.view(f.owner().getPublicId(), f.store().getPublicId());
        assertThat(before.amountKrw()).isEqualTo(33000L);
        assertThat(before.chargeNowKrw()).isEqualTo(33000L);
        assertThat(before.unitPriceKrw()).isEqualTo(30000);
        assertThat(before.brandPaidStoreCount()).isNull();

        BillingView after = 결제한다(f, "key-view");
        JsonNode json = JSON.valueToTree(after);
        List<String> keys = new ArrayList<>();
        json.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("enabled", "portoneStoreId", "channelKey", "customer", "serviceState",
                "trialPending", "trialDays", "trialEndsAt", "nextBillingAt", "restrictedFrom", "lastPaidAt",
                "hasCard", "autoRenew", "renewalFailures", "amountKrw", "chargeNowKrw", "consentVersion",
                "payments", "heldReplyCount", "unitPriceKrw", "brandPaidStoreCount");
        assertThat(json.get("serviceState").asText()).isEqualTo("ACTIVE");
        assertThat(json.get("amountKrw").asLong()).isEqualTo(33000L);
        assertThat(json.get("chargeNowKrw").asLong()).isZero();
        assertThat(json.get("unitPriceKrw").asInt()).isEqualTo(30000);
        assertThat(json.get("consentVersion").asText()).isEqualTo(AgreementService.CURRENT_VERSION);
        assertThat(json.get("payments").get(0).get("amountKrw").asLong()).isEqualTo(33000L);
    }

    // ── 가맹 브랜드 구간 3종 ────────────────────────────────────────────

    @Test
    void 가맹_50매장_약정은_29000_VAT_2900_합계_31900이다() throws Exception {
        F f = 매장("char-b50@example.com", 브랜드(50));
        BillingView v = 결제한다(f, "key-b50");
        assertThat(청구액()).isEqualTo(31900L);
        Payment p = 마지막_결제(f);
        assertThat(p.getAmountKrw()).isEqualByComparingTo("29000");
        assertThat(p.getVatKrw()).isEqualByComparingTo("2900");
        assertThat(v.unitPriceKrw()).isEqualTo(29000);
        assertThat(v.amountKrw()).isEqualTo(31900L);
    }

    @Test
    void 가맹_300매장_약정은_25000_VAT_2500_합계_27500이다() throws Exception {
        F f = 매장("char-b300@example.com", 브랜드(300));
        결제한다(f, "key-b300");
        assertThat(청구액()).isEqualTo(27500L);
        Payment p = 마지막_결제(f);
        assertThat(p.getAmountKrw()).isEqualByComparingTo("25000");
        assertThat(p.getVatKrw()).isEqualByComparingTo("2500");
    }

    @Test
    void 가맹_500매장_약정은_23000_VAT_2300_합계_25300이다() throws Exception {
        F f = 매장("char-b500@example.com", 브랜드(500));
        결제한다(f, "key-b500");
        assertThat(청구액()).isEqualTo(25300L);
        Payment p = 마지막_결제(f);
        assertThat(p.getAmountKrw()).isEqualByComparingTo("23000");
        assertThat(p.getVatKrw()).isEqualByComparingTo("2300");
    }

    // ── 정기 갱신 ────────────────────────────────────────────────────────

    @Test
    void 정기갱신도_같은_금액으로_청구한다() {
        F f = 매장("char-renew@example.com", 브랜드(100));
        Subscription s = Subscription.builder().storeId(f.store().getId()).priceKrw(new BigDecimal("30000"))
                .status("ACTIVE").autoRenew(true).billingKey("key-renew").billingChannelKey("ch-test").build();
        s.changeNextBillingAt(Instant.now().minus(Duration.ofHours(1)));
        subscriptions.save(s);

        billingService.renew(f.store().getId());

        assertThat(청구액()).isEqualTo(29700L); // 27,000 + 2,700
        Payment p = 마지막_결제(f);
        assertThat(p.getAmountKrw()).isEqualByComparingTo("27000");
        assertThat(p.getVatKrw()).isEqualByComparingTo("2700");
    }

    // ── 꺼짐이면 저장된 선택 명단을 무시한다 ──────────────────────────────

    @Test
    void 꺼짐이면_명단이_저장돼_있어도_금액은_예전과_같다() throws Exception {
        F plain = 매장("char-off-plain@example.com", null);
        F b50 = 매장("char-off-b50@example.com", 브랜드(50));
        for (F f : List.of(plain, b50)) {
            subscriptions.save(Subscription.builder().storeId(f.store().getId()).priceKrw(new BigDecimal("30000"))
                    .activeDeliveryPlatforms(new String[] {"BAEMIN"}).build());
        }
        assertThat(billingService.view(plain.owner().getPublicId(), plain.store().getPublicId()).amountKrw())
                .isEqualTo(33000L);
        assertThat(billingService.view(b50.owner().getPublicId(), b50.store().getPublicId()).amountKrw())
                .isEqualTo(31900L);
    }

    // ── 유료 전환 사전고지 메일 금액 ───────────────────────────────────

    @Test
    void 사전고지_메일은_일반_33000원_가맹_구간_합계를_적는다() {
        F plain = 매장("char-notice-plain@example.com", null);
        F b50 = 매장("char-notice-b50@example.com", 브랜드(50));
        Instant end = Instant.now().plus(Duration.ofDays(5));
        for (F f : List.of(plain, b50)) {
            Subscription s = Subscription.builder().storeId(f.store().getId()).priceKrw(new BigDecimal("30000"))
                    .status("TRIAL").promotionCode("OPEN30").trialEndsAt(end).autoRenew(true).billingKey("k").build();
            s.changeNextBillingAt(end);
            subscriptions.save(s);
        }
        Subscription sp = subscriptions.findByStoreIdAndStatusNot(plain.store().getId(), "CANCELED").orElseThrow();
        Subscription sb = subscriptions.findByStoreIdAndStatusNot(b50.store().getId(), "CANCELED").orElseThrow();

        assertThat(notices.body(plain.store(), sp)).contains("월 33,000원(부가세 포함)");
        assertThat(notices.body(b50.store(), sb)).contains("월 31,900원(부가세 포함)");
    }
}
