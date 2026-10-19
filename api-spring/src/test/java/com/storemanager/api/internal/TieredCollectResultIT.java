package com.storemanager.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storemanager.api.billing.Subscription;
import com.storemanager.api.billing.SubscriptionRepository;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.review.StorePlatformLink;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 선택형 요금(D38) 켜짐에서 수집 결과 적재·자동 연결이 고르지 않은 배달앱 몫을 버리는지(실제 Postgres).
 * 꺼짐 동작은 CollectResultIT 가 그대로 잠근다.
 */
@SpringBootTest(properties = "app.pricing.tiered.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class TieredCollectResultIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static final String INTERNAL_TOKEN = "test-internal-token";

    @Autowired MockMvc mockMvc;
    @Autowired AppUserRepository appUserRepository;
    @Autowired CredentialService credentialService;
    @Autowired StoreRepository storeRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired StorePlatformLinkRepository storePlatformLinkRepository;
    @Autowired UnifiedReviewRepository unifiedReviewRepository;

    private Long store(Long ownerId, String name, String... selected) {
        Long storeId = storeRepository.save(Store.builder().ownerId(ownerId).name(name)
                .activatedAt(Instant.now()).build()).getId();
        subscriptionRepository.save(Subscription.builder().storeId(storeId).status("ACTIVE")
                .priceKrw(new BigDecimal("30000")).serviceUntil(Instant.now().plusSeconds(86400 * 29))
                .activeDeliveryPlatforms(selected.length == 0 ? null : selected).build());
        return storeId;
    }

    private static String body(Long accountId, String platform, String... storeAndReview) {
        StringBuilder stores = new StringBuilder();
        for (int i = 0; i < storeAndReview.length; i += 2) {
            if (i > 0) {
                stores.append(',');
            }
            stores.append("""
                    {"platformStoreId": "%s", "storeName": "매장", "avgRating": 4.5,
                     "reviews": [{"platformReviewId": "%s", "rating": 5, "body": "좋아요",
                       "authorRaw": "abc", "orderedMenus": [], "imageUrls": [], "platformExtra": {},
                       "reviewStatus": "0", "writtenDate": "2024-03-31"}]}
                    """.formatted(storeAndReview[i], storeAndReview[i + 1]));
        }
        return """
                {"jobId": "t-%s", "accountId": "%d", "platform": "%s", "status": "SUCCESS",
                 "stores": [%s], "stats": {"found": 1, "new": 1, "latencyMs": 10}}
                """.formatted(storeAndReview[1], accountId, platform, stores);
    }

    @Test
    void collectResultSkipsUnselectedStoreInSharedAccount() throws Exception {
        AppUser owner = appUserRepository.save(AppUser.builder().email("tier-shared@example.com")
                .passwordHash("dummy").name("사장").build());
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "tier-baemin-1", "pw");
        Long selectedStore = store(owner.getId(), "배민 고른 매장", "BAEMIN");
        Long unselectedStore = store(owner.getId(), "요기요만 고른 매장", "YOGIYO");
        storePlatformLinkRepository.save(StorePlatformLink.builder().storeId(selectedStore)
                .accountId(account.getId()).platform("BAEMIN").platformStoreId("tier-a").build());
        StorePlatformLink kept = storePlatformLinkRepository.save(StorePlatformLink.builder().storeId(unselectedStore)
                .accountId(account.getId()).platform("BAEMIN").platformStoreId("tier-b").build());

        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(account.getId(), "BAEMIN", "tier-a", "rv-tier-a", "tier-b", "rv-tier-b")))
                .andExpect(status().isOk());

        assertThat(unifiedReviewRepository.findByPlatformAndPlatformReviewId("BAEMIN", "rv-tier-a")).isPresent();
        assertThat(unifiedReviewRepository.findByPlatformAndPlatformReviewId("BAEMIN", "rv-tier-b")).isEmpty();
        // existingLinkKeptOnDowngrade — 연결은 지우지 않는다(상품을 다시 올리면 그대로 쓴다).
        assertThat(storePlatformLinkRepository.findById(kept.getId())).isPresent();
    }

    @Test
    void autoLinkSkipsUnselected() throws Exception {
        AppUser owner = appUserRepository.save(AppUser.builder().email("tier-autolink@example.com")
                .passwordHash("dummy").name("사장").build());
        Long storeId = store(owner.getId(), "배민만 고른 매장", "BAEMIN");
        PlatformAccount account = credentialService.save(owner.getId(), "YOGIYO", "tier-yogi-1", "pw", storeId);

        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(account.getId(), "YOGIYO", "tier-yogi-store", "rv-tier-yogi")))
                .andExpect(status().isOk());

        assertThat(storePlatformLinkRepository.findByPlatformAndPlatformStoreId("YOGIYO", "tier-yogi-store")).isEmpty();
        assertThat(unifiedReviewRepository.findByPlatformAndPlatformReviewId("YOGIYO", "rv-tier-yogi")).isEmpty();
    }
}
