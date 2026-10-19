package com.storemanager.api.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.storemanager.api.billing.Subscription;
import com.storemanager.api.billing.SubscriptionRepository;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.review.StorePlatformLink;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 선택형 요금 켜짐에서 브리핑 집계가 미선택 배달앱 리뷰를 세지 않는다(SQL 복제 조건). 꺼짐은 DailyBriefingServiceIT. */
@SpringBootTest(properties = "app.pricing.tiered.enabled=true")
@ActiveProfiles("test")
@Testcontainers
class DailyBriefingTieredIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired DailyBriefingService service;
    @Autowired AppUserRepository appUserRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired StorePlatformLinkRepository storePlatformLinkRepository;
    @Autowired UnifiedReviewRepository unifiedReviewRepository;
    @Autowired CredentialService credentialService;

    @Test
    void briefingCountsOnlySelectedPlatforms() {
        AppUser owner = appUserRepository.save(AppUser.builder().email("brief-tier@example.com")
                .passwordHash("dummy").name("사장").build());
        Store store = Store.builder().ownerId(owner.getId()).name("브리핑-선택").build();
        store.activateByCredentialConsent(Instant.now());
        store = storeRepository.save(store);
        subscriptionRepository.save(Subscription.builder().storeId(store.getId()).status("ACTIVE")
                .priceKrw(new BigDecimal("30000")).serviceUntil(Instant.now().plusSeconds(86400 * 10))
                .activeDeliveryPlatforms(new String[] {"BAEMIN"}).build());
        for (String platform : new String[] {"BAEMIN", "YOGIYO", "NAVER"}) {
            String id = UUID.randomUUID().toString();
            PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", id, "pw");
            StorePlatformLink link = storePlatformLinkRepository.save(StorePlatformLink.builder()
                    .storeId(store.getId()).accountId(account.getId()).platform("BAEMIN").platformStoreId(id).build());
            unifiedReviewRepository.save(UnifiedReview.builder().storeId(store.getId()).linkId(link.getId())
                    .platform(platform).platformReviewId(id).rating((short) 5).body("좋아요")
                    .writtenAt(Instant.now()).collectedAt(Instant.now()).build());
        }

        Long storeId = store.getId();
        var row = service.collect(LocalDate.now(ZoneId.of("Asia/Seoul"))).stream()
                .filter(b -> b.storeId().equals(storeId)).findFirst().orElseThrow();

        assertThat(row.collected()).isEqualTo(2); // BAEMIN + NAVER, YOGIYO 는 제외
    }
}
