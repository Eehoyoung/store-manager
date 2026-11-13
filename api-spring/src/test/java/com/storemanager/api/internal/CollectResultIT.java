package com.storemanager.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.draft.ReplyDraft;
import com.storemanager.api.draft.ReplyDraftRepository;
import com.storemanager.api.draft.ReviewAnalysis;
import com.storemanager.api.draft.ReviewAnalysisRepository;
import com.storemanager.api.notify.NotificationLogRepository;
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
 * POST /internal/collect-result 수신 → 정규화 → 적재 경로를 실제 Postgres 위에서 검증한다 (docs/13 11.2장).
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
    com.storemanager.api.billing.SubscriptionRepository subscriptionRepository;

    @Autowired
    StorePlatformLinkRepository storePlatformLinkRepository;

    @Autowired
    UnifiedReviewRepository unifiedReviewRepository;

    @Autowired
    ReplyStyleSampleRepository replyStyleSampleRepository;

    @Autowired
    NotificationLogRepository notificationLogRepository;

    @Autowired
    ReplyDraftRepository replyDraftRepository;

    @Autowired
    ReviewAnalysisRepository reviewAnalysisRepository;

    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Autowired
    com.storemanager.api.crypto.PlatformAccountRepository platformAccountRepository;

    @Autowired
    com.storemanager.api.audit.AuditLogRepository auditLogRepository;

    @Autowired
    com.storemanager.api.collect.CollectionJobRepository collectionJobRepository;

    @Autowired
    com.storemanager.api.collect.DataApiCallLogRepository dataApiCallLogRepository;

    // 알림 호출은 스파이로 확인한다. 실체는 LoggingNotifier(기록만) — PLATFORM_LINK_ERROR 는 발송 대기열에 넣지 않고,
    // 발송기(AlimtalkDispatcher)는 app.alimtalk.enabled=true 일 때만 뜬다. 다른 시험의 notification_log 확인도 그대로 동작한다.
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.storemanager.api.notify.Notifier notifier;

    private Long 계약완료_매장을_만든다(Long ownerId, String name) {
        Store store = Store.builder()
                .ownerId(ownerId)
                .name(name)
                .activatedAt(Instant.now()) // 자격증명 위탁 동의 완료 상태여야 워커 결과를 처리한다.
                .build();
        Long storeId = storeRepository.save(store).getId();
        // ★ 구독도 있어야 수집 결과를 적재한다(StoreServiceGate). 계약만으로는 비용 드는 작업을
        //   하지 않는다 — 무료로 DataAPI 호출과 LLM 토큰을 태우지 않기 위한 게이트다.
        // ★ 자동결제 전환(2026-09-29) — isServiceableAt 이 service_until 을 본다. 없으면
        //   ACTIVE 라도 서비스 불가로 판정돼 이 픽스처의 매장이 전부 수집 결과 적재에서 빠진다.
        subscriptionRepository.save(com.storemanager.api.billing.Subscription.builder()
                .storeId(storeId)
                .status("ACTIVE")
                .priceKrw(new java.math.BigDecimal("30000"))
                .serviceUntil(Instant.now().plusSeconds(86400 * 29))
                .build());
        return storeId;
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
        Long accountId = storePlatformLinkRepository.findByPlatformAndPlatformStoreId(platform, platformStoreId)
                .orElseThrow().getAccountId();
        String existingReplyField = withExistingReply
                ? (",\n                      \"existingReply\": {\"id\": \"rc-1\", \"contents\": \""
                        + authorRaw + "님, 감사합니다\"}")
                : "";
        return """
                {
                  "jobId": "%s",
                  "accountId": "%d",
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
                """.formatted(jobId, accountId, platform, platformStoreId, reviewId, authorRaw, existingReplyField);
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

    // ── 연동 검증 표시(markVerified) ─────────────────────────────────────────
    // 빠지면 사장님 화면이 영원히 '검증 보류'로 남고, 연동 끊김 뒤 수집이 다시 성공해도 ERROR 가 풀리지 않는다.

    private void 수집결과를_보낸다(String body) throws Exception {
        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void 정상수집이면_연동완료로_표시하고_연동끊김도_풀며_최초_검증시각은_유지한다() throws Exception {
        AppUser owner = appUserRepository.save(AppUser.builder().email("verify-ok@example.com")
                .passwordHash("dummy").name("사장V").build());
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "baemin-verify-ok", "pw");
        account.markLinkError("ERR_MLCOM_MSG50059");
        platformAccountRepository.save(account);
        매장을_연동한다(계약완료_매장을_만든다(owner.getId(), "검증매장"), account.getId(), "BAEMIN", "store-verify-ok");

        수집결과를_보낸다(수집결과_요청바디("verify-1", "BAEMIN", "store-verify-ok", "rv-verify-1", "손님", false));

        PlatformAccount first = platformAccountRepository.findById(account.getId()).orElseThrow();
        assertThat(first.getLinkStatus()).isEqualTo("LINKED");
        assertThat(first.getLastErrorCode()).isNull();
        assertThat(first.getVerifiedAt()).isNotNull();
        assertThat(first.getLastSyncedAt()).isNotNull();

        수집결과를_보낸다(수집결과_요청바디("verify-2", "BAEMIN", "store-verify-ok", "rv-verify-2", "손님", false));

        PlatformAccount second = platformAccountRepository.findById(account.getId()).orElseThrow();
        assertThat(second.getVerifiedAt()).isEqualTo(first.getVerifiedAt());
        assertThat(second.getLastSyncedAt()).isAfterOrEqualTo(first.getLastSyncedAt());
    }

    @Test
    void 적재한_매장이_없으면_자격증명이_맞아도_연동완료로_표시하지_않는다() throws Exception {
        AppUser owner = appUserRepository.save(AppUser.builder().email("verify-none@example.com")
                .passwordHash("dummy").name("사장N").build());
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "baemin-verify-none", "pw");
        // 구독이 없는 매장 — 서비스 게이트에서 건너뛰어 processed 가 0 이다.
        Long storeId = storeRepository.save(Store.builder().ownerId(owner.getId()).name("미구독매장")
                .activatedAt(Instant.now()).build()).getId();
        매장을_연동한다(storeId, account.getId(), "BAEMIN", "store-verify-none");

        수집결과를_보낸다(수집결과_요청바디("verify-none", "BAEMIN", "store-verify-none", "rv-verify-none", "손님", false));

        PlatformAccount after = platformAccountRepository.findById(account.getId()).orElseThrow();
        assertThat(after.getVerifiedAt()).isNull();
        assertThat(after.getLinkStatus()).isNotEqualTo("LINKED");
    }

    @Test
    void 실패한_수집이면_연동완료로_표시하지_않고_연동끊김도_그대로_둔다() throws Exception {
        AppUser owner = appUserRepository.save(AppUser.builder().email("verify-fail@example.com")
                .passwordHash("dummy").name("사장F").build());
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "baemin-verify-fail", "pw");
        account.markLinkError("ERR_MLCOM_MSG50059");
        platformAccountRepository.save(account);
        매장을_연동한다(계약완료_매장을_만든다(owner.getId(), "실패매장"), account.getId(), "BAEMIN", "store-verify-fail");

        수집결과를_보낸다(수집결과_요청바디("verify-fail", "BAEMIN", "store-verify-fail", "rv-verify-fail", "손님", false)
                .replace("\"status\": \"SUCCESS\"", "\"status\": \"FAILED\""));

        PlatformAccount after = platformAccountRepository.findById(account.getId()).orElseThrow();
        assertThat(after.getVerifiedAt()).isNull();
        assertThat(after.getLinkStatus()).isEqualTo("ERROR");
        assertThat(after.getLastErrorCode()).isEqualTo("ERR_MLCOM_MSG50059");
    }

    // ── 수집 중 연동 끊김(LINK_ERROR) ────────────────────────────────────────
    // 빠지면 비밀번호가 바뀐 매장이 계속 LINKED 로 보여 사장님이 재연동할 줄 모르고, 수집은 매번 조용히 실패한다.

    private String 수집_연동끊김_바디(String jobId, String accountId) {
        return """
                {"jobId":"%s","accountId":"%s","platform":"BAEMIN","status":"FAILED",
                 "ecode":"ERR_MLCOM_MSG50059","action":"LINK_ERROR","stores":[]}
                """.formatted(jobId, accountId);
    }

    @Test
    void 수집중_연동끊김이면_연동된_계정을_ERROR로_바꾸고_오류코드를_남긴다() throws Exception {
        AppUser owner = appUserRepository.save(AppUser.builder().email("collect-link@example.com")
                .passwordHash("dummy").name("사장L").build());
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "baemin-collect-link", "pw");
        매장을_연동한다(계약완료_매장을_만든다(owner.getId(), "끊김매장"), account.getId(), "BAEMIN", "store-collect-link");
        수집결과를_보낸다(수집결과_요청바디("link-ok", "BAEMIN", "store-collect-link", "rv-collect-link", "손님", false));
        assertThat(platformAccountRepository.findById(account.getId()).orElseThrow().getLinkStatus()).isEqualTo("LINKED");

        수집결과를_보낸다(수집_연동끊김_바디("link-err", String.valueOf(account.getId())));

        PlatformAccount after = platformAccountRepository.findById(account.getId()).orElseThrow();
        assertThat(after.getLinkStatus()).isEqualTo("ERROR");
        assertThat(after.getLastErrorCode()).isEqualTo("ERR_MLCOM_MSG50059");
    }

    @Test
    void 계정번호가_숫자가_아닌_연동끊김도_결과를_받고_다른_계정은_건드리지_않는다() throws Exception {
        AppUser owner = appUserRepository.save(AppUser.builder().email("collect-link-nan@example.com")
                .passwordHash("dummy").name("사장X").build());
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "baemin-collect-nan", "pw");
        매장을_연동한다(계약완료_매장을_만든다(owner.getId(), "멀쩡매장"), account.getId(), "BAEMIN", "store-collect-nan");
        수집결과를_보낸다(수집결과_요청바디("nan-ok", "BAEMIN", "store-collect-nan", "rv-collect-nan", "손님", false));

        // 500 으로 거절하면 워커 결과가 유실된다 — 계정을 특정할 수 없으면 상태 전이만 건너뛴다.
        수집결과를_보낸다(수집_연동끊김_바디("nan-err", "not-a-number"));

        PlatformAccount after = platformAccountRepository.findById(account.getId()).orElseThrow();
        assertThat(after.getLinkStatus()).isEqualTo("LINKED");
        assertThat(after.getLastErrorCode()).isNull();
    }

    // ── 수집 이력(collection_job)·호출 기록(data_api_call_log) ────────────────
    // T-5 수집 성공률의 근거다. 예전에 jobId 해석 실패로 한 행도 쌓이지 않은 적이 있다.

    private List<com.storemanager.api.collect.CollectionJob> 수집이력(Long accountId) {
        return collectionJobRepository.findAll().stream().filter(j -> accountId.equals(j.getAccountId())).toList();
    }

    private List<com.storemanager.api.collect.DataApiCallLog> 호출기록(Long accountId) {
        return dataApiCallLogRepository.findAll().stream().filter(l -> accountId.equals(l.getAccountId())).toList();
    }

    private PlatformAccount 이력용_연동계정을_만든다(String key) {
        AppUser owner = appUserRepository.save(AppUser.builder().email(key + "@example.com")
                .passwordHash("dummy").name("사장H").build());
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "baemin-" + key, "pw");
        매장을_연동한다(계약완료_매장을_만든다(owner.getId(), "이력매장-" + key), account.getId(), "BAEMIN", "store-" + key);
        return account;
    }

    @Test
    void 기간이_있는_정기수집_성공은_수집이력과_호출기록을_한_행씩_남긴다() throws Exception {
        PlatformAccount account = 이력용_연동계정을_만든다("hist-poll");

        수집결과를_보낸다(수집결과_요청바디("job-hist-poll", "BAEMIN", "store-hist-poll", "rv-hist-poll", "손님", false)
                .replace("\"status\": \"SUCCESS\",",
                        "\"status\": \"SUCCESS\", \"jobType\": \"POLL\", \"startDate\": \"2026-11-01\", \"endDate\": \"2026-11-07\","));

        List<com.storemanager.api.collect.CollectionJob> jobs = 수집이력(account.getId());
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getJobKey()).isEqualTo("job-hist-poll");
        assertThat(jobs.get(0).getJobType()).isEqualTo("POLL");
        assertThat(jobs.get(0).getStatus()).isEqualTo("SUCCESS");
        assertThat(jobs.get(0).getReviewsFound()).isEqualTo(1);
        assertThat(jobs.get(0).getReviewsNew()).isEqualTo(1);
        assertThat(jobs.get(0).getStartDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 1));
        assertThat(jobs.get(0).getEndDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 7));
        List<com.storemanager.api.collect.DataApiCallLog> calls = 호출기록(account.getId());
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getResult()).isEqualTo("SUCCESS");
    }

    @Test
    void 백필_실패는_BACKFILL_FAILED와_오류코드로_남긴다() throws Exception {
        PlatformAccount account = 이력용_연동계정을_만든다("hist-bf");

        수집결과를_보낸다("""
                {"jobId":"job-hist-bf","accountId":"%d","platform":"BAEMIN","status":"FAILED",
                 "ecode":"ERR_HIST_9999","action":"FAIL","jobType":"BACKFILL",
                 "startDate":"2026-10-01","endDate":"2026-10-31","stores":[],
                 "stats":{"found":0,"new":0,"latencyMs":50}}
                """.formatted(account.getId()));

        List<com.storemanager.api.collect.CollectionJob> jobs = 수집이력(account.getId());
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getJobType()).isEqualTo("BACKFILL");
        assertThat(jobs.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(jobs.get(0).getEcode()).isEqualTo("ERR_HIST_9999");
        assertThat(jobs.get(0).getReviewsNew()).isZero();
        List<com.storemanager.api.collect.DataApiCallLog> calls = 호출기록(account.getId());
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getEcode()).isEqualTo("ERR_HIST_9999");
    }

    @Test
    void 기간이_없는_구버전_수집결과는_받되_수집이력은_만들지_않고_호출기록은_남긴다() throws Exception {
        PlatformAccount account = 이력용_연동계정을_만든다("hist-legacy");

        수집결과를_보낸다(수집결과_요청바디("job-hist-legacy", "BAEMIN", "store-hist-legacy", "rv-hist-legacy", "손님", false));

        assertThat(수집이력(account.getId())).isEmpty();
        // 기간이 없을 때 호출 기록까지 사라지면 DataAPI 원가 집계가 빈다.
        assertThat(호출기록(account.getId())).hasSize(1);
    }

    // ── 자동 연결(autoLink) ──────────────────────────────────────────────────
    // 틀리면 남의 매장에 리뷰가 적재되거나, 사장님 첫 연동이 영원히 '매핑 대기'로 남는다.

    private String 자동연결_바디(Long accountId, String platformStoreId, String reviewId) {
        return """
                {"jobId":"job-%s","accountId":"%d","platform":"BAEMIN","status":"SUCCESS",
                 "stores":[{"platformStoreId":"%s","storeName":"자동연결매장","avgRating":4.5,
                   "reviews":[{"platformReviewId":"%s","rating":5,"body":"맛있어요","authorRaw":"손님",
                     "orderedMenus":[],"imageUrls":[],"platformExtra":{},"reviewStatus":"0",
                     "writtenDate":"2026-11-01"}]}],
                 "stats":{"found":1,"new":1,"latencyMs":50}}
                """.formatted(reviewId, accountId, platformStoreId, reviewId);
    }

    private AppUser 사장을_만든다(String key) {
        return appUserRepository.save(AppUser.builder().email(key + "@example.com")
                .passwordHash("dummy").name("사장A").build());
    }

    @Test
    void 지정매장이_있는_계정의_첫수집은_그_매장에_자동연결하고_리뷰를_적재한다() throws Exception {
        AppUser owner = 사장을_만든다("auto-ok");
        Long storeId = 계약완료_매장을_만든다(owner.getId(), "자동연결-성공");
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "baemin-auto-ok", "pw", storeId);

        수집결과를_보낸다(자동연결_바디(account.getId(), "store-auto-ok", "rv-auto-ok"));

        StorePlatformLink link = storePlatformLinkRepository
                .findByPlatformAndPlatformStoreId("BAEMIN", "store-auto-ok").orElseThrow();
        assertThat(link.getStoreId()).isEqualTo(storeId);
        assertThat(link.getAccountId()).isEqualTo(account.getId());
        assertThat(link.getStoreNameSnapshot()).isEqualTo("자동연결매장");
        assertThat(unifiedReviewRepository.findByPlatformAndPlatformReviewId("BAEMIN", "rv-auto-ok")
                .orElseThrow().getStoreId()).isEqualTo(storeId);
        assertThat(platformAccountRepository.findById(account.getId()).orElseThrow().getLinkStatus())
                .isEqualTo("LINKED");
    }

    @Test
    void 파기된_계정은_지정매장이_있어도_자동연결하지_않고_리뷰도_적재하지_않는다() throws Exception {
        AppUser owner = 사장을_만든다("auto-revoked");
        Long storeId = 계약완료_매장을_만든다(owner.getId(), "자동연결-파기");
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "baemin-auto-rv", "pw", storeId);
        credentialService.revoke(platformAccountRepository.findById(account.getId()).orElseThrow());

        수집결과를_보낸다(자동연결_바디(account.getId(), "store-auto-revoked", "rv-auto-revoked"));

        assertThat(storePlatformLinkRepository.findByPlatformAndPlatformStoreId("BAEMIN", "store-auto-revoked"))
                .isEmpty();
        assertThat(unifiedReviewRepository.findByPlatformAndPlatformReviewId("BAEMIN", "rv-auto-revoked"))
                .isEmpty();
    }

    @Test
    void 같은_계정의_둘째_플랫폼매장은_자동연결하지_않고_사람_매핑으로_남긴다() throws Exception {
        AppUser owner = 사장을_만든다("auto-second");
        Long storeId = 계약완료_매장을_만든다(owner.getId(), "자동연결-둘째");
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "baemin-auto-2", "pw", storeId);
        수집결과를_보낸다(자동연결_바디(account.getId(), "store-auto-first", "rv-auto-first"));

        수집결과를_보낸다(자동연결_바디(account.getId(), "store-auto-second", "rv-auto-second"));

        assertThat(storePlatformLinkRepository.findByPlatformAndPlatformStoreId("BAEMIN", "store-auto-first"))
                .isPresent();
        assertThat(storePlatformLinkRepository.findByPlatformAndPlatformStoreId("BAEMIN", "store-auto-second"))
                .isEmpty();
        assertThat(unifiedReviewRepository.findByPlatformAndPlatformReviewId("BAEMIN", "rv-auto-second"))
                .isEmpty();
    }

    @Test
    void 지정매장이_다른_사장님_매장이면_자동연결하지_않고_리뷰도_적재하지_않는다() throws Exception {
        AppUser owner = 사장을_만든다("auto-owner");
        AppUser other = 사장을_만든다("auto-other");
        Long othersStoreId = 계약완료_매장을_만든다(other.getId(), "남의매장");
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "baemin-auto-own", "pw",
                othersStoreId);

        수집결과를_보낸다(자동연결_바디(account.getId(), "store-auto-other", "rv-auto-other"));

        assertThat(storePlatformLinkRepository.findByPlatformAndPlatformStoreId("BAEMIN", "store-auto-other"))
                .isEmpty();
        assertThat(unifiedReviewRepository.findByPlatformAndPlatformReviewId("BAEMIN", "rv-auto-other"))
                .isEmpty();
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

    /**
     * ★ 말투 코퍼스는 ai-python 이 pgvector 로 직접 조회해 few-shot 예시로 Anthropic 에 보낸다
     * (ai-python/rag.py). DraftService 의 마스킹이 덮지 못하는 두 번째 국외 이전 경로이므로
     * 적재 시점에 지운다 — 개인정보처리방침 6.3장은 "개인을 알아볼 수 없도록 처리된 정보만
     * 외부 AI 에 전송한다" 고 약속한다. 이 검증을 지우면 그 약속이 코드에서 풀린다.
     */
    @Test
    void 기존_답글은_식별자를_지우고_말투_코퍼스에_적재된다() throws Exception {
        AppUser owner = appUserRepository.save(AppUser.builder().email("mask-owner@example.com")
                .passwordHash("dummy").name("사장M").build());
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "baemin-id-mask", "pw");
        Long storeId = 계약완료_매장을_만든다(owner.getId(), "마스킹매장");
        매장을_연동한다(storeId, account.getId(), "BAEMIN", "mask-store-1");

        // 픽스처가 기존 답글을 "{authorRaw}님, 감사합니다" 로 만든다. 사장님이 답글에 연락처를
        // 적어 두는 사례를 재현하려고 authorRaw 자리에 전화번호를 넣는다.
        String body = 수집결과_요청바디("job-mask", "BAEMIN", "mask-store-1", "rv-mask", "010-1234-5678", true);
        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        List<ReplyStyleSample> samples = replyStyleSampleRepository.findAll().stream()
                .filter(s -> storeId.equals(s.getStoreId())).toList();
        assertThat(samples).hasSize(1);
        assertThat(samples.get(0).getReplyText()).doesNotContain("010-1234-5678");
        assertThat(samples.get(0).getReplyText()).endsWith("님, 감사합니다");
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
                  "accountId": "%d",
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
                """.formatted(account.getId());

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
        String body = """
                {"jobId":"105","accountId":"1","platform":"BAEMIN","status":"SUCCESS","stores":[]}
                """;

        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 다른_계정의_수집결과는_매장에_적재하지_않는다() throws Exception {
        AppUser owner = appUserRepository.save(AppUser.builder().email("cross-account@example.com")
                .passwordHash("dummy").name("사장").build());
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "cross-account", "pw");
        Long storeId = 계약완료_매장을_만든다(owner.getId(), "교차계정매장");
        매장을_연동한다(storeId, account.getId(), "BAEMIN", "cross-store");
        String body = 수집결과_요청바디("cross-job", "BAEMIN", "cross-store", "cross-review", "손님", false)
                .replace("\"accountId\": \"" + account.getId() + "\"", "\"accountId\": \"999999\"");

        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        assertThat(unifiedReviewRepository.findByPlatformAndPlatformReviewId("BAEMIN", "cross-review")).isEmpty();
    }

    // ── 게시 결과 수신 (Sprint 4 S10) ───────────────────────────────────────
    // 상태 전이 자체는 ReplyDraftTest 가 단위로 덮지만, "워커가 실제로 보내는 JSON 이
    // 올바른 전이를 호출하는가"는 여기서만 검증된다. 워커가 보내는 형태를 실제로 실행해
    // 확인한 값 그대로 사용한다.

    private record 게시픽스처(Long draftId, Long reviewId, Long accountId, String dispatchToken) {
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
        reviewAnalysisRepository.save(ReviewAnalysis.builder().reviewId(review.getId()).category("PRAISE")
                .sentiment(0.8f).riskLevel((short) 0).model("test").promptVersion("v1").build());

        ReplyDraft draft = replyDraftRepository.save(ReplyDraft.builder()
                .reviewId(review.getId()).storeId(storeId).content("고객님 감사합니다").generatedBy("AI")
                .build());
        draft.scheduleAutomatically(Instant.now());
        replyDraftRepository.save(draft);
        String dispatchToken = "dispatch-token-" + draft.getId();
        stringRedisTemplate.opsForValue().set("dispatch:draft:" + draft.getId(), dispatchToken);
        return new 게시픽스처(draft.getId(), review.getId(), account.getId(), dispatchToken);
    }

    private void 게시결과를_보낸다(String json) throws Exception {
        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    private String 게시결과_바디(게시픽스처 fixture, String status, String action, String ecode,
            String platformCommentId, String failReason) {
        return """
                {"jobId":"pub-%d","accountId":"%d","platform":"BAEMIN","status":"%s","ecode":%s,
                 "action":"%s","publish":{"draftId":%d,"platformCommentId":%s,"failReason":%s,
                 "dispatchToken":"%s"}}
                """.formatted(fixture.draftId(), fixture.accountId(), status,
                ecode == null ? "null" : "\"" + ecode + "\"", action, fixture.draftId(),
                platformCommentId == null ? "null" : "\"" + platformCommentId + "\"",
                failReason == null ? "null" : "\"" + failReason + "\"", fixture.dispatchToken());
    }

    @Test
    void 게시성공이면_PUBLISHED_로_전이하고_dispatch키가_지워진다() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub1@example.com", "rv-pub-1");

        게시결과를_보낸다(게시결과_바디(f, "SUCCESS", "PUBLISHED", null, "2024033103186049", null));

        ReplyDraft after = replyDraftRepository.findById(f.draftId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("PUBLISHED");
        assertThat(after.getPlatformCommentId()).isEqualTo("2024033103186049");
        assertThat(after.getPublishedAt()).isNotNull();
        assertThat(stringRedisTemplate.hasKey("dispatch:draft:" + f.draftId())).isFalse();
    }

    @Test
    void 발행작업과_다른_디스패치토큰은_상태를_변경하지_않는다() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub-token@example.com", "rv-pub-token");
        String body = 게시결과_바디(f, "SUCCESS", "PUBLISHED", null, "forged-comment", null)
                .replace(f.dispatchToken(), "wrong-dispatch-token");

        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        assertThat(replyDraftRepository.findById(f.draftId()).orElseThrow().getStatus()).isEqualTo("SCHEDULED");
        assertThat(stringRedisTemplate.hasKey("dispatch:draft:" + f.draftId())).isTrue();
    }

    @Test
    void 댓글중복은_실패가_아니라_ALREADY_REPLIED_로_정상종료된다() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub2@example.com", "rv-pub-2");

        // 워커는 ERR_MDCOM_MSG00009 를 status=SUCCESS 로 보낸다(직접 실행해 확인한 형태).
        게시결과를_보낸다(게시결과_바디(f, "SUCCESS", "ALREADY_REPLIED", "ERR_MDCOM_MSG00009", null, null));

        ReplyDraft after = replyDraftRepository.findById(f.draftId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("ALREADY_REPLIED");
        // 사장님이 앱에서 직접 답글을 단 상태이므로 리뷰에도 반영돼야 한다.
        assertThat(unifiedReviewRepository.findById(f.reviewId()).orElseThrow().isHasOwnerReply()).isTrue();
    }

    @Test
    void 일반실패는_재시도를_위해_SCHEDULED_로_되돌아가고_retry_count가_증가한다() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub3@example.com", "rv-pub-3");

        게시결과를_보낸다(게시결과_바디(f, "FAILED", "FAIL", "ERR_UNKNOWN_9999", null, "일시 오류"));

        ReplyDraft after = replyDraftRepository.findById(f.draftId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("SCHEDULED");
        assertThat(after.getRetryCount()).isEqualTo((short) 1);
        assertThat(after.getScheduledAt()).isAfter(Instant.now());
    }

    @Test
    void publishFailPlatformNotSelectedIsBlockedNotRetried() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub-pns@example.com", "rv-pub-pns");

        // 워커가 Redis 차단 키를 보고 게시 직전에 거절한 경우 - 재시도하지 않고 별도 표식으로 종결한다.
        게시결과를_보낸다(게시결과_바디(f, "FAILED", "FAIL", null, null, "PLATFORM_NOT_SELECTED"));

        ReplyDraft after = replyDraftRepository.findById(f.draftId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("BLOCKED");
        assertThat(after.getRetryCount()).isEqualTo((short) 0);
        assertThat(after.getFailCode()).isEqualTo("PLATFORM_NOT_SELECTED");
        assertThat(after.getGuardrailFlags()).containsExactly("PLATFORM_NOT_SELECTED");
    }

    @Test
    void 재시도를_3회_소진한_일반실패는_FAILED로_확정되고_더_재예약되지_않는다() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub-exhaust@example.com", "rv-pub-exhaust");
        ReplyDraft draft = replyDraftRepository.findById(f.draftId()).orElseThrow();
        for (int i = 0; i < 3; i++) {
            draft.retryLater(Instant.now(), "일시 오류");
        }
        replyDraftRepository.save(draft);

        게시결과를_보낸다(게시결과_바디(f, "FAILED", "FAIL", "ERR_UNKNOWN_9999", null, "일시 오류"));

        ReplyDraft after = replyDraftRepository.findById(f.draftId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("FAILED");
        assertThat(after.getRetryCount()).isEqualTo((short) 3);
        assertThat(after.getFailCode()).isEqualTo("ERR_UNKNOWN_9999");
    }

    @Test
    void 매장비활성으로_게시직전_거절되면_재시도하지_않고_BLOCKED로_종결하고_위험알림은_없다() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub-si@example.com", "rv-pub-si");

        게시결과를_보낸다(게시결과_바디(f, "FAILED", "FAIL", null, null, "STORE_INACTIVE"));

        ReplyDraft after = replyDraftRepository.findById(f.draftId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("BLOCKED");
        assertThat(after.getRetryCount()).isEqualTo((short) 0);
        assertThat(after.getFailCode()).isEqualTo("STORE_INACTIVE");
        assertThat(auditLogRepository.findAll()).anyMatch(a -> "DRAFT_BLOCKED_AT_PUBLISH_GUARD".equals(a.getAction())
                && f.draftId().equals(a.getTargetId()));
        // 위험 리뷰 알림은 RISK_LEVEL_TOO_HIGH 일 때만이다.
        assertThat(notificationLogRepository.countByRefTypeAndRefIdAndTemplate(
                "UNIFIED_REVIEW", f.reviewId(), "HIGH_RISK_REVIEW")).isZero();
    }

    @Test
    void 위험도차단은_재시도하지_않고_BLOCKED_로_종결된다() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub4@example.com", "rv-pub-4");

        // ★ 절대규칙 3: 워커가 riskLevel>=3 이중검증으로 거절한 경우다.
        // action 은 FAIL 이지만 재시도 대상이 아니라 사람 검수 대상이다.
        게시결과를_보낸다(게시결과_바디(f, "FAILED", "FAIL", null, null, "RISK_LEVEL_TOO_HIGH"));

        ReplyDraft after = replyDraftRepository.findById(f.draftId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("BLOCKED");
        assertThat(after.getRetryCount()).isEqualTo((short) 0);
        // scheduled_at 은 남아 있어도 무해하다 — 스케줄러 조회가 status=SCHEDULED 만 보므로 재디스패치되지 않는다.
        assertThat(after.getFailCode()).isEqualTo("RISK_LEVEL_TOO_HIGH");
        assertThat(notificationLogRepository.countByRefTypeAndRefIdAndTemplate(
                "UNIFIED_REVIEW", f.reviewId(), "HIGH_RISK_REVIEW")).isEqualTo(1);
    }

    private void 게시성공_보고가_거절된다(게시픽스처 f) throws Exception {
        mockMvc.perform(post("/internal/collect-result").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(게시결과_바디(f, "SUCCESS", "PUBLISHED", null, "c-1", null)))
                .andExpect(status().isConflict());
        ReplyDraft after = replyDraftRepository.findById(f.draftId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("SCHEDULED");
        assertThat(after.getPublishedAt()).isNull();
    }

    @Test
    void 위험도_3이상인데_게시성공이_오면_PUBLISHED로_기록하지_않는다() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub-risk3@example.com", "rv-pub-risk3");
        // ★ 절대규칙 3 서버측 마지막 확인 — 큐에 실린 뒤 재분석으로 위험도가 올라간 경우다.
        reviewAnalysisRepository.save(ReviewAnalysis.builder().reviewId(f.reviewId()).category("COMPLAINT")
                .sentiment(-0.9f).riskLevel((short) 3).model("test").promptVersion("v1").build());

        게시성공_보고가_거절된다(f);
    }

    @Test
    void 매장이_비활성인데_게시성공이_오면_PUBLISHED로_기록하지_않는다() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub-inactive@example.com", "rv-pub-inactive");
        Store store = storeRepository.findById(replyDraftRepository.findById(f.draftId()).orElseThrow().getStoreId())
                .orElseThrow();
        store.clearCredentialConsent(Instant.now());
        storeRepository.save(store);

        게시성공_보고가_거절된다(f);
    }

    @Test
    void 모르는_action은_FAIL로_기록되어_재시도로_돌아간다() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub-unknown@example.com", "rv-pub-unknown");

        // 400 으로 거절하면 결과가 유실되고 초안이 SCHEDULED 로 남아 영원히 재디스패치된다(실기동 결함).
        게시결과를_보낸다(게시결과_바디(f, "FAILED", "CONFIG_ERROR", "ERR_CONFIG", null, "설정 오류"));

        ReplyDraft after = replyDraftRepository.findById(f.draftId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("SCHEDULED");
        assertThat(after.getRetryCount()).isEqualTo((short) 1);
        assertThat(stringRedisTemplate.hasKey("dispatch:draft:" + f.draftId())).isFalse();
    }

    @Test
    void 쓰기_스위치가_꺼져_거절되면_retry_count를_올리지_않고_미룬다() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub-wd@example.com", "rv-pub-wd");

        게시결과를_보낸다(게시결과_바디(f, "FAILED", "FAIL", null, null, "DATAAPI_WRITE_DISABLED"));

        ReplyDraft after = replyDraftRepository.findById(f.draftId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("SCHEDULED");
        assertThat(after.getRetryCount()).isEqualTo((short) 0);
        assertThat(after.getFailReason()).isEqualTo("DATAAPI_WRITE_DISABLED");
        assertThat(after.getScheduledAt()).isAfter(Instant.now().plusSeconds(9 * 60));
    }

    @Test
    void 연동끊김이면_재시도하지_않고_FAILED로_확정하고_계정을_ERROR로_바꾸고_사장님께_알린다() throws Exception {
        게시픽스처 f = 게시대기_초안을_만든다("pub-link@example.com", "rv-pub-link");

        게시결과를_보낸다(게시결과_바디(f, "FAILED", "LINK_ERROR", "ERR_MLCOM_MSG50059", null, "로그인 실패"));

        ReplyDraft after = replyDraftRepository.findById(f.draftId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("FAILED");
        assertThat(after.getRetryCount()).isEqualTo((short) 0);
        assertThat(after.getFailCode()).isEqualTo("ERR_MLCOM_MSG50059");
        assertThat(stringRedisTemplate.hasKey("dispatch:draft:" + f.draftId())).isFalse();
        com.storemanager.api.crypto.PlatformAccount account =
                platformAccountRepository.findById(f.accountId()).orElseThrow();
        assertThat(account.getLinkStatus()).isEqualTo("ERROR");
        assertThat(account.getLastErrorCode()).isEqualTo("ERR_MLCOM_MSG50059");
        org.mockito.Mockito.verify(notifier, org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("ALIMTALK"),
                org.mockito.ArgumentMatchers.eq("PLATFORM_LINK_ERROR"), org.mockito.ArgumentMatchers.eq("REPLY_DRAFT"),
                org.mockito.ArgumentMatchers.eq(f.draftId()));
    }
}
