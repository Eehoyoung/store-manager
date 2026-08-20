package com.storemanager.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.draft.ReplyDraft;
import com.storemanager.api.draft.ReplyDraftRepository;
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
import org.testcontainers.containers.GenericContainer;
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

    // ★ 게시 결과 경로는 dispatch:draft:{id} 키를 지우려고 Redis 에 접속한다.
    // 컨테이너를 띄우지 않으면 application.yml 기본값(localhost:6380)의 실제 개발용 Redis 에 붙는다 —
    // 테스트가 개발 데이터를 건드리게 되므로 전용 컨테이너로 격리한다.
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

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

    @Autowired
    ReplyDraftRepository replyDraftRepository;

    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

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

    // ── 게시 결과 수신 (Sprint 4 S10) ───────────────────────────────────────
    // 상태 전이 자체는 ReplyDraftTest 가 단위로 덮지만, "워커가 실제로 보내는 JSON 이
    // 올바른 전이를 호출하는가"는 여기서만 검증된다. 워커가 보내는 형태를 실제로 실행해
    // 확인한 값 그대로 사용한다.

    private record 게시픽스처(Long draftId, Long reviewId) {
    }

    private 게시픽스처 게시대기_초안을_만든다(String email, String platformReviewId) throws Exception {
        AppUser owner = appUserRepository.save(AppUser.builder().email(email)
                .passwordHash("dummy").name("사장P").build());
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "baemin-" + email, "pw");
        Long storeId = 계약완료_매장을_만든다(owner.getId(), "게시매장-" + platformReviewId);
        매장을_연동한다(storeId, account.getId(), "BAEMIN", "store-" + platformReviewId);

        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(수집결과_요청바디("job-" + platformReviewId, "BAEMIN", "store-" + platformReviewId,
                                platformReviewId, "손님", false)))
                .andExpect(status().isOk());
        UnifiedReview review = unifiedReviewRepository
                .findByPlatformAndPlatformReviewId("BAEMIN", platformReviewId).orElseThrow();

        ReplyDraft draft = replyDraftRepository.save(ReplyDraft.builder()
                .reviewId(review.getId()).storeId(storeId).content("고객님 감사합니다").generatedBy("AI")
                .build());
        draft.approve(owner.getId(), Instant.now());
        replyDraftRepository.save(draft);
        stringRedisTemplate.opsForValue().set("dispatch:draft:" + draft.getId(), "1");
        return new 게시픽스처(draft.getId(), review.getId());
    }

    private void 게시결과를_보낸다(String json) throws Exception {
        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    private String 게시결과_바디(Long draftId, String status, String action, String ecode,
            String platformCommentId, String failReason) {
        return """
                {"jobId":"pub-%d","accountId":"1","platform":"BAEMIN","status":"%s","ecode":%s,
                 "action":"%s","publish":{"draftId":%d,"platformCommentId":%s,"failReason":%s}}
                """.formatted(draftId, status, ecode == null ? "null" : "\"" + ecode + "\"", action, draftId,
                platformCommentId == null ? "null" : "\"" + platformCommentId + "\"",
                failReason == null ? "null" : "\"" + failReason + "\"");
    }

    @Test
    void 게시성공이면_PUBLISHED_로_전이하고_dispatch키가_지워진다() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub1@example.com", "rv-pub-1");

        게시결과를_보낸다(게시결과_바디(f.draftId(), "SUCCESS", "PUBLISHED", null, "2024033103186049", null));

        ReplyDraft after = replyDraftRepository.findById(f.draftId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("PUBLISHED");
        assertThat(after.getPlatformCommentId()).isEqualTo("2024033103186049");
        assertThat(after.getPublishedAt()).isNotNull();
        assertThat(stringRedisTemplate.hasKey("dispatch:draft:" + f.draftId())).isFalse();
    }

    @Test
    void 댓글중복은_실패가_아니라_ALREADY_REPLIED_로_정상종료된다() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub2@example.com", "rv-pub-2");

        // 워커는 ERR_MDCOM_MSG00009 를 status=SUCCESS 로 보낸다(직접 실행해 확인한 형태).
        게시결과를_보낸다(게시결과_바디(f.draftId(), "SUCCESS", "ALREADY_REPLIED", "ERR_MDCOM_MSG00009", null, null));

        ReplyDraft after = replyDraftRepository.findById(f.draftId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("ALREADY_REPLIED");
        // 사장님이 앱에서 직접 답글을 단 상태이므로 리뷰에도 반영돼야 한다.
        assertThat(unifiedReviewRepository.findById(f.reviewId()).orElseThrow().isHasOwnerReply()).isTrue();
    }

    @Test
    void 일반실패는_재시도를_위해_SCHEDULED_로_되돌아가고_retry_count가_증가한다() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub3@example.com", "rv-pub-3");

        게시결과를_보낸다(게시결과_바디(f.draftId(), "FAILED", "FAIL", "ERR_UNKNOWN_9999", null, "일시 오류"));

        ReplyDraft after = replyDraftRepository.findById(f.draftId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("SCHEDULED");
        assertThat(after.getRetryCount()).isEqualTo((short) 1);
        assertThat(after.getScheduledAt()).isAfter(Instant.now());
    }

    @Test
    void 위험도차단은_재시도하지_않고_BLOCKED_로_종결된다() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub4@example.com", "rv-pub-4");

        // ★ 절대규칙 3: 워커가 riskLevel>=3 이중검증으로 거절한 경우다.
        // action 은 FAIL 이지만 재시도 대상이 아니라 사람 검수 대상이다.
        게시결과를_보낸다(게시결과_바디(f.draftId(), "FAILED", "FAIL", null, null, "RISK_LEVEL_TOO_HIGH"));

        ReplyDraft after = replyDraftRepository.findById(f.draftId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("BLOCKED");
        assertThat(after.getRetryCount()).isEqualTo((short) 0);
        // scheduled_at 은 남아 있어도 무해하다 — 스케줄러 조회가 status=SCHEDULED 만 보므로 재디스패치되지 않는다.
        assertThat(after.getFailCode()).isEqualTo("RISK_LEVEL_TOO_HIGH");
    }
}
