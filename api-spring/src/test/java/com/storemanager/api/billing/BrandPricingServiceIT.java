package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storemanager.api.billing.PricingDtos.AdminBrandPricingRow;
import com.storemanager.api.franchise.FranchiseBrand;
import com.storemanager.api.franchise.FranchiseBrandRepository;
import com.storemanager.api.hq.FranchiseHqMember;
import com.storemanager.api.hq.FranchiseHqMemberRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.sysauth.MailService;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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

/** 가맹 브랜드 구간 단가(V49) — paidStoreCount 집계, 스냅샷 멱등성, 단가 변경 안내 발송. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class BrandPricingServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired BrandPricingService service;
    @Autowired FranchiseBrandRepository brands;
    @Autowired FranchiseHqMemberRepository hqMembers;
    @Autowired StoreRepository stores;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired AppUserRepository users;
    @Autowired BrandMonthlyPriceRepository monthlyPrices;
    @MockitoBean MailService mail;

    @BeforeEach
    void setUp() {
        reset(mail);
        when(mail.sendBrandPriceNotice(anyString(), anyString(), anyString())).thenReturn(true);
        // ★ 이 클래스의 테스트는 같은 Testcontainers DB 를 공유한다(트랜잭션 롤백 없음). 이전 테스트가
        //   만든 브랜드의 미확인(notified_at IS NULL) 스냅샷 행이 남아 있으면 sendPriceNotices() 가
        //   전역으로 그 행까지 함께 처리해 이번 테스트의 발송 건수 단언이 흔들린다 — 먼저 비운다.
        service.sendPriceNotices(Instant.now());
        reset(mail);
        when(mail.sendBrandPriceNotice(anyString(), anyString(), anyString())).thenReturn(true);
    }

    private AppUser 사장을_만든다(String email) {
        return users.save(AppUser.builder().email(email).passwordHash("x").name("사장").build());
    }

    /** status 는 ACTIVE|GRACE|TRIAL|UNPAID|RESTRICTED|SUSPENDED. UNPAID 는 구독 행 자체를 만들지 않는다. */
    private void 매장을_만든다(String brand, Long ownerId, String status) {
        Store store = stores.save(Store.builder().ownerId(ownerId).name("매장-" + System.nanoTime()).build());
        store.assignBrand(brand);
        stores.save(store);
        if ("UNPAID".equals(status)) {
            return;
        }
        Instant now = Instant.now();
        Subscription.SubscriptionBuilder b = Subscription.builder().storeId(store.getId())
                .priceKrw(new BigDecimal("30000"));
        Instant nextBillingAt;
        switch (status) {
            case "ACTIVE" -> {
                b.status("ACTIVE");
                nextBillingAt = now.plus(Duration.ofDays(10));
            }
            case "GRACE" -> {
                b.status("ACTIVE");
                nextBillingAt = now.minus(Duration.ofDays(1));
            }
            case "TRIAL" -> {
                b.status("TRIAL").trialEndsAt(now.plus(Duration.ofDays(10)));
                nextBillingAt = now.plus(Duration.ofDays(10));
            }
            case "RESTRICTED" -> {
                b.status("ACTIVE");
                nextBillingAt = now.minus(Duration.ofDays(10));
            }
            case "SUSPENDED" -> {
                b.status("SUSPENDED");
                nextBillingAt = now.plus(Duration.ofDays(10));
            }
            default -> throw new IllegalArgumentException(status);
        }
        Subscription s = b.build();
        s.changeNextBillingAt(nextBillingAt);
        subscriptions.save(s);
    }

    // ── paidStoreCount ───────────────────────────────────────────────────

    @Test
    void ACTIVE_GRACE만_유료매장으로_세고_나머지는_제외한다() {
        String brand = "카운트브랜드-" + System.nanoTime();
        brands.save(FranchiseBrand.builder().brandName(brand).build());
        Long ownerId = 사장을_만든다("count-owner@example.com").getId();
        매장을_만든다(brand, ownerId, "ACTIVE");
        매장을_만든다(brand, ownerId, "GRACE");
        매장을_만든다(brand, ownerId, "TRIAL");
        매장을_만든다(brand, ownerId, "UNPAID");
        매장을_만든다(brand, ownerId, "RESTRICTED");
        매장을_만든다(brand, ownerId, "SUSPENDED");

        assertThat(service.paidStoreCount(brand)).isEqualTo(2);
    }

    // ── unitPriceFor 우선순위: 스냅샷 > 약정 > 기본값 ────────────────────────

    @Test
    void 스냅샷이_있으면_그_단가를_쓰고_약정이_바뀌어도_영향받지_않는다() {
        String brand = "스냅샷브랜드-" + System.nanoTime();
        brands.save(FranchiseBrand.builder().brandName(brand).committedStoreCount(50).build());
        YearMonth month = YearMonth.now(KST).plusMonths(1);
        monthlyPrices.save(BrandMonthlyPrice.builder().brandName(brand).billingMonth(month.atDay(1))
                .paidStoreCount(120).unitPrice(27000).build());
        Store store = stores.save(Store.builder().ownerId(사장을_만든다("snap-owner@example.com").getId())
                .name("스냅샷매장").build());
        store.assignBrand(brand);
        stores.save(store);

        assertThat(service.unitPriceFor(store, month)).isEqualTo(27000); // 약정(50→29000)이 아니라 스냅샷 값
    }

    @Test
    void 스냅샷이_없으면_약정_매장수로_계산하고_약정도_없으면_기본값이다() {
        String committedBrand = "약정브랜드-" + System.nanoTime();
        brands.save(FranchiseBrand.builder().brandName(committedBrand).committedStoreCount(100).build());
        Store withCommitted = stores.save(Store.builder()
                .ownerId(사장을_만든다("committed-owner@example.com").getId()).name("약정매장").build());
        withCommitted.assignBrand(committedBrand);
        stores.save(withCommitted);

        String bareBrand = "약정없는브랜드-" + System.nanoTime();
        brands.save(FranchiseBrand.builder().brandName(bareBrand).build());
        Store bare = stores.save(Store.builder().ownerId(사장을_만든다("bare-owner@example.com").getId())
                .name("약정없는매장").build());
        bare.assignBrand(bareBrand);
        stores.save(bare);

        Store noBrand = stores.save(Store.builder().ownerId(사장을_만든다("nobrand-owner@example.com").getId())
                .name("브랜드없는매장").build());

        YearMonth thisMonth = YearMonth.now(KST);
        assertThat(service.unitPriceFor(withCommitted, thisMonth)).isEqualTo(27000); // tier(100)
        assertThat(service.unitPriceFor(bare, thisMonth)).isEqualTo(30000); // 기본값
        assertThat(service.unitPriceFor(noBrand, thisMonth)).isEqualTo(30000); // 브랜드 없음
    }

    // ── snapshotNextMonth ────────────────────────────────────────────────

    @Test
    void 유료매장이_0인_브랜드는_스냅샷_행을_만들지_않는다() {
        String brand = "빈브랜드-" + System.nanoTime();
        brands.save(FranchiseBrand.builder().brandName(brand).build());
        Long ownerId = 사장을_만든다("empty-owner@example.com").getId();
        매장을_만든다(brand, ownerId, "UNPAID");

        service.snapshotNextMonth(Instant.now());

        LocalDate nextMonth = YearMonth.now(KST).plusMonths(1).atDay(1);
        assertThat(monthlyPrices.findByBrandNameAndBillingMonth(brand, nextMonth)).isEmpty();
    }

    @Test
    void 스냅샷은_두번_실행해도_한_행만_남는다() {
        String brand = "멱등브랜드-" + System.nanoTime();
        brands.save(FranchiseBrand.builder().brandName(brand).build());
        Long ownerId = 사장을_만든다("idem-owner@example.com").getId();
        매장을_만든다(brand, ownerId, "ACTIVE");
        Instant now = Instant.now();

        service.snapshotNextMonth(now);
        service.snapshotNextMonth(now);

        LocalDate nextMonth = YearMonth.now(KST).plusMonths(1).atDay(1);
        assertThat(monthlyPrices.findByBrandNameAndBillingMonth(brand, nextMonth)).isPresent();
        assertThat(monthlyPrices.findByBrandNameAndBillingMonth(brand, nextMonth).orElseThrow().getUnitPrice())
                .isEqualTo(30000); // tier(1)
    }

    // ── summary ──────────────────────────────────────────────────────────

    @Test
    void summary는_다음달_확정_전에는_LIVE_ESTIMATE로_어림한다() {
        String brand = "요약브랜드-" + System.nanoTime();
        brands.save(FranchiseBrand.builder().brandName(brand).build());
        Long ownerId = 사장을_만든다("summary-owner@example.com").getId();
        for (int i = 0; i < 55; i++) {
            매장을_만든다(brand, ownerId, "ACTIVE");
        }

        AdminBrandPricingRow row = service.summary(brand, Instant.now());

        assertThat(row.paidStoreCount()).isEqualTo(55);
        assertThat(row.nextMonth().basis()).isEqualTo("LIVE_ESTIMATE");
        assertThat(row.nextMonth().confirmed()).isFalse();
        assertThat(row.nextMonth().unitPriceKrw()).isEqualTo(29000); // tier(55)
        assertThat(row.thisMonth().confirmed()).isTrue();
    }

    // ── sendPriceNotices ─────────────────────────────────────────────────

    @Test
    void 단가가_바뀐_브랜드에만_메일을_보내고_확인처리한다() {
        String changedBrand = "변경브랜드-" + System.nanoTime();
        brands.save(FranchiseBrand.builder().brandName(changedBrand).build());
        AppUser owner = 사장을_만든다("notice-owner@example.com");
        매장을_만든다(changedBrand, owner.getId(), "ACTIVE");
        YearMonth nextMonth = YearMonth.now(KST).plusMonths(1);
        // 이번 달은 기본값(30000, 매장 1곳→committed 없음), 다음 달 스냅샷은 27000 — 값이 다르다.
        monthlyPrices.save(BrandMonthlyPrice.builder().brandName(changedBrand).billingMonth(nextMonth.atDay(1))
                .paidStoreCount(120).unitPrice(27000).build());

        String sameBrand = "동일브랜드-" + System.nanoTime();
        brands.save(FranchiseBrand.builder().brandName(sameBrand).build());
        매장을_만든다(sameBrand, owner.getId(), "ACTIVE");
        monthlyPrices.save(BrandMonthlyPrice.builder().brandName(sameBrand).billingMonth(nextMonth.atDay(1))
                .paidStoreCount(1).unitPrice(30000).build()); // 이번 달 기본값과 같다 — 발송 없이 확인 처리

        int sent = service.sendPriceNotices(Instant.now());

        assertThat(sent).isEqualTo(1);
        verify(mail, times(1)).sendBrandPriceNotice(anyString(), anyString(), anyString());
        assertThat(monthlyPrices.findByBrandNameAndBillingMonth(changedBrand, nextMonth.atDay(1))
                .orElseThrow().getNotifiedAt()).isNotNull();
        assertThat(monthlyPrices.findByBrandNameAndBillingMonth(sameBrand, nextMonth.atDay(1))
                .orElseThrow().getNotifiedAt()).isNotNull();
    }

    @Test
    void 대상이_없으면_발송없이_확인처리하고_이미_처리한_행은_다시_보내지_않는다() {
        String brand = "대상없음브랜드-" + System.nanoTime();
        brands.save(FranchiseBrand.builder().brandName(brand).build());
        // 매장도 담당자도 없다 — 유료 매장 0인데 스냅샷만 직접 심는다(운영자가 수동으로 넣은 상황을 흉내).
        YearMonth nextMonth = YearMonth.now(KST).plusMonths(1);
        monthlyPrices.save(BrandMonthlyPrice.builder().brandName(brand).billingMonth(nextMonth.atDay(1))
                .paidStoreCount(0).unitPrice(27000).build());

        int sent = service.sendPriceNotices(Instant.now());
        assertThat(sent).isZero();
        assertThat(monthlyPrices.findByBrandNameAndBillingMonth(brand, nextMonth.atDay(1))
                .orElseThrow().getNotifiedAt()).isNotNull();

        reset(mail);
        assertThat(service.sendPriceNotices(Instant.now())).isZero();
        verify(mail, never()).sendBrandPriceNotice(anyString(), anyString(), anyString());
    }
}
