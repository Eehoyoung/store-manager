package com.storemanager.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.review.ReplyStyleSample;
import com.storemanager.api.review.ReplyStyleSampleRepository;
import com.storemanager.api.review.StorePlatformLink;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * POST /internal/collect-result 수신 → 정규화 → 적재 경로를 실제 Postgres 위에서 검증한다 (docs/13 §11.2).
 * CredentialServiceIT 와 동일한 Testcontainers 패턴을 재사용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CollectResultIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final String INTERNAL_TOKEN = "test-internal-token"; // application-test.yml 과 동일

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    CredentialService credentialService;

    @Autowired
    StoreRepository storeRepository;

    @Autowired
    StorePlatformLinkRepository storePlatformLinkRepository;

    @Autowired
    UnifiedReviewRepository unifiedReviewRepository;

    @Autowired
    ReplyStyleSampleRepository replyStyleSampleRepository;

    private Long 계약완료_매장을_만든다(Long ownerId, String name) {
        Store store = Store.builder()
                .ownerId(ownerId)
                .name(name)
                .activatedAt(Instant.now()) // 전자계약 서명 완료 상태여야 워커 결과를 처리한다(docs/11 §2.7)
                .build();
        return storeRepository.save(store).getId();
    }

    private StorePlatformLink 매장을_연동한다(Long storeId, Long accountId, String platform, String platformStoreId) {
        return storePlatformLinkRepository.save(StorePlatformLink.builder()
                .storeId(storeId)
                .accountId(accountId)
                .platform(platform)
                .platformStoreId(platformStoreId)
                .build());
    }

    private String 수집결과_요청바디(String jobId, String platform, String platformStoreId, String reviewId,
            String authorRaw, boolean withExistingReply) {
        String existingReplyField = withExistingReply
                ? (",\n                      \"existingReply\": {\"id\": \"rc-1\", \"contents\": \""
                        + authorRaw + "님, 감사합니다\"}")
                : "";
        return """
                {
                  "jobId": "%s",
                  "accountId": "acc-1",
                  "platform": "%s",
                  "status": "SUCCESS",
                  "stores": [{
                    "platformStoreId": "%s",
                    "storeName": "국수면회소",
                    "avgRating": 4.6,
                    "reviews": [{
                      "platformReviewId": "%s",
                      "rating": 5,
                      "body": "맛있게 잘 먹었습니다",
                      "authorRaw": "%s",
                      "orderedMenus": ["잔치국수"],
                      "imageUrls": [],
                      "platformExtra": {},
                      "reviewStatus": "0",
                      "writtenDate": "2024-03-31"%s
                    }]
                  }],
                  "stats": { "found": 1, "new": 1, "latencyMs": 100 }
                }
                """.formatted(jobId, platform, platformStoreId, reviewId, authorRaw, existingReplyField);
    }

    @Test
    void 같은_리뷰를_두번_수신해도_1행이고_collected_at이_변하지_않는다() throws Exception {
        AppUser owner = appUserRepository.save(AppUser.builder().email("f4owner@example.com")
                .passwordHash("dummy").name("사장1").build());
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "baemin-id-1", "pw");
        Long storeId = 계약완료_매장을_만든다(owner.getId(), "F4매장");
        매장을_연동한다(storeId, account.getId(), "BAEMIN", "store-f4");

        String body = 수집결과_요청바디("101", "BAEMIN", "store-f4", "review-f4", "aaa", false);

        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        UnifiedReview first = unifiedReviewRepository.findByPlatformAndPlatformReviewId("BAEMIN", "review-f4")
                .orElseThrow();
        Instant firstCollectedAt = first.getCollectedAt();

        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        List<UnifiedReview> all = unifiedReviewRepository.findAll().stream()
                .filter(r -> "review-f4".equals(r.getPlatformReviewId())).toList();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getCollectedAt()).isEqualTo(firstCollectedAt);
    }

    @Test
    void 배민_닉네임은_원문으로_저장되지_않고_마스킹과_해시로_저장된다() throws Exception {
        AppUser owner = appUserRepository.save(AppUser.builder().email("f5owner@example.com")
                .passwordHash("dummy").name("사장2").build());
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "baemin-id-2", "pw");
        Long storeId = 계약완료_매장을_만든다(owner.getId(), "F5매장");
        매장을_연동한다(storeId, account.getId(), "BAEMIN", "store-f5");

        String body = 수집결과_요청바디("102", "BAEMIN", "store-f5", "review-f5", "히리릴", false);

        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        UnifiedReview saved = unifiedReviewRepository.findByPlatformAndPlatformReviewId("BAEMIN", "review-f5")
                .orElseThrow();
        assertThat(saved.getAuthorMasked()).isEqualTo("히**");
        assertThat(saved.getAuthorMasked()).isNotEqualTo("히리릴");
        assertThat(saved.getAuthorHash()).hasSize(64);
        assertThat(saved.getAuthorHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void RC_LIST_기존답글은_스타일샘플로_적재되고_재수신시_중복적재되지_않는다() throws Exception {
        AppUser owner = appUserRepository.save(AppUser.builder().email("stylesample@example.com")
                .passwordHash("dummy").name("사장3").build());
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "baemin-id-3", "pw");
        Long storeId = 계약완료_매장을_만든다(owner.getId(), "F6매장");
        매장을_연동한다(storeId, account.getId(), "BAEMIN", "store-f6");

        String body = 수집결과_요청바디("103", "BAEMIN", "store-f6", "review-f6", "bbb", true);

        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        List<ReplyStyleSample> samples = replyStyleSampleRepository.findAll().stream()
                .filter(s -> storeId.equals(s.getStoreId())).toList();
        assertThat(samples).hasSize(1);
        assertThat(samples.get(0).getSource()).isEqualTo("RC_LIST");
        assertThat(samples.get(0).getReplyText()).isEqualTo("bbb님, 감사합니다");
    }

    @Test
    void 한_계정의_두_매장이_각각_자기_link에_적재된다() throws Exception {
        AppUser owner = appUserRepository.save(AppUser.builder().email("f7owner@example.com")
                .passwordHash("dummy").name("사장4").build());
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "baemin-id-4", "pw");
        Long storeAId = 계약완료_매장을_만든다(owner.getId(), "F7매장A");
        Long storeBId = 계약완료_매장을_만든다(owner.getId(), "F7매장B");
        StorePlatformLink linkA = 매장을_연동한다(storeAId, account.getId(), "BAEMIN", "store-f7-a");
        StorePlatformLink linkB = 매장을_연동한다(storeBId, account.getId(), "BAEMIN", "store-f7-b");

        String body = """
                {
                  "jobId": "104",
                  "accountId": "acc-1",
                  "platform": "BAEMIN",
                  "status": "SUCCESS",
                  "stores": [
                    {"platformStoreId": "store-f7-a", "storeName": "A매장", "avgRating": 4.5,
                     "reviews": [{"platformReviewId": "review-f7-a", "rating": 5, "body": "좋아요",
                       "authorRaw": "ccc", "orderedMenus": [], "imageUrls": [], "platformExtra": {},
                       "reviewStatus": "0", "writtenDate": "2024-03-31"}]},
                    {"platformStoreId": "store-f7-b", "storeName": "B매장", "avgRating": 3.9,
                     "reviews": [{"platformReviewId": "review-f7-b", "rating": 4, "body": "괜찮아요",
                       "authorRaw": "ddd", "orderedMenus": [], "imageUrls": [], "platformExtra": {},
                       "reviewStatus": "0", "writtenDate": "2024-03-31"}]}
                  ],
                  "stats": { "found": 2, "new": 2, "latencyMs": 200 }
                }
                """;

        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        UnifiedReview reviewA = unifiedReviewRepository.findByPlatformAndPlatformReviewId("BAEMIN", "review-f7-a")
                .orElseThrow();
        UnifiedReview reviewB = unifiedReviewRepository.findByPlatformAndPlatformReviewId("BAEMIN", "review-f7-b")
                .orElseThrow();
        assertThat(reviewA.getStoreId()).isEqualTo(storeAId);
        assertThat(reviewA.getLinkId()).isEqualTo(linkA.getId());
        assertThat(reviewB.getStoreId()).isEqualTo(storeBId);
        assertThat(reviewB.getLinkId()).isEqualTo(linkB.getId());
    }

    @Test
    void X_Internal_Token이_불일치하면_401을_반환한다() throws Exception {
        String body = 수집결과_요청바디("105", "BAEMIN", "store-none", "review-none", "eee", false);

        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }
}
