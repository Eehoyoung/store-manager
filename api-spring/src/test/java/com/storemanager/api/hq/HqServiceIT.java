package com.storemanager.api.hq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.draft.PublishScheduleCalculator;
import com.storemanager.api.draft.ReviewAnalysis;
import com.storemanager.api.draft.ReviewAnalysisRepository;
import com.storemanager.api.review.StorePlatformLink;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

/**
 * 가맹본부 조회(Sprint 8, FR-800) 접근통제·감사로그·필터를 실제 Postgres 위에서 검증한다.
 * AnalyticsServiceIT/CredentialServiceIT 와 동일한 Testcontainers 패턴을 재사용한다.
 * H10 (a)~(c),(e) 를 이 파일이, (d) 는 HqDtoFieldsTest, (f) 는 HqNoWriteEndpointTest 가 담당한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class HqServiceIT {

    private static final ZoneId KST = PublishScheduleCalculator.KST;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired HqService hqService;
    @Autowired AppUserRepository appUserRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired FranchiseHqMemberRepository hqMemberRepository;
    @Autowired UnifiedReviewRepository unifiedReviewRepository;
    @Autowired ReviewAnalysisRepository reviewAnalysisRepository;
    @Autowired CredentialService credentialService;
    @Autowired StorePlatformLinkRepository storePlatformLinkRepository;
    @Autowired AuditLogRepository auditLogRepository;

    private record 매장픽스처(Long storeId, UUID storePublicId, Long linkId) {
    }

    private 매장픽스처 매장을_만든다(String brandName, String email) {
        AppUser owner = appUserRepository.save(AppUser.builder().email(email).passwordHash("dummy").name("사장")
                .build());
        Store store = storeRepository.save(Store.builder().ownerId(owner.getId()).name("매장-" + email)
                .brandName(brandName).build());
        // unified_review.link_id 는 store_platform_link FK 이므로 계정·연동을 먼저 만든다(docs/11 §2.3~2.4).
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "id-" + email, "pw");
        StorePlatformLink link = storePlatformLinkRepository.save(StorePlatformLink.builder()
                .storeId(store.getId()).accountId(account.getId()).platform("BAEMIN")
                .platformStoreId("ps-" + email).build());
        return new 매장픽스처(store.getId(), store.getPublicId(), link.getId());
    }

    /** 본부 사용자를 만들고 franchise_hq_member 로 brandName 에 연결한다. */
    private UUID 본부사용자를_만든다(String email, String brandName) {
        AppUser hqUser = appUserRepository.save(AppUser.builder().email(email).passwordHash("dummy")
                .name("본부담당자").build());
        hqMemberRepository.save(FranchiseHqMember.builder().userId(hqUser.getId()).brandName(brandName).build());
        return hqUser.getPublicId();
    }

    private Long 리뷰를_만든다(매장픽스처 f, String platformReviewId, int rating, int riskLevel, LocalDate writtenDate) {
        return 리뷰를_만든다(f, platformReviewId, rating, riskLevel, writtenDate, new String[0], new String[0], "[]");
    }

    private Long 리뷰를_만든다(매장픽스처 f, String platformReviewId, int rating, int riskLevel,
            LocalDate writtenDate, String[] issueTags, String[] riskReasons, String orderedMenus) {
        UnifiedReview review = unifiedReviewRepository.save(UnifiedReview.builder()
                .storeId(f.storeId()).linkId(f.linkId()).platform("BAEMIN").platformReviewId(platformReviewId)
                .rating((short) rating).body("리뷰본문").writtenAt(writtenDate.atStartOfDay(KST).toInstant())
                .orderedMenus(orderedMenus)
                .build());
        reviewAnalysisRepository.save(ReviewAnalysis.builder().reviewId(review.getId())
                .category(riskLevel >= 3 ? "COMPLAINT" : "PRAISE").sentiment(0f).riskLevel((short) riskLevel)
                .issueTags(issueTags).riskReasons(riskReasons).model("m").promptVersion("v1").build());
        return review.getId();
    }

    // ── (a) 본부 권한 없는 사용자 → 404 ─────────────────────────────────

    @Test
    void 본부_권한이_없는_사용자가_매장목록을_조회하면_404() {
        매장을_만든다("브랜드A", "a-owner@example.com");
        AppUser 무권한사용자 = appUserRepository.save(AppUser.builder().email("no-hq@example.com")
                .passwordHash("dummy").name("아무개").build());

        assertThatThrownBy(() -> hqService.listStores(무권한사용자.getPublicId(), "브랜드A"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void 존재하지_않는_브랜드명으로_조회해도_404() {
        AppUser 사용자 = appUserRepository.save(AppUser.builder().email("ghost@example.com").passwordHash("d")
                .name("아무개").build());

        assertThatThrownBy(() -> hqService.listStores(사용자.getPublicId(), "존재안하는브랜드"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // ── (b) A브랜드 본부가 B브랜드 매장 storeId 로 조회 → 404 (H6-2) ────────

    @Test
    void A브랜드_본부가_B브랜드_매장_storeId로_리뷰조회시_404() {
        매장픽스처 storeB = 매장을_만든다("브랜드B", "b-owner@example.com");
        UUID hqUserA = 본부사용자를_만든다("hq-a@example.com", "브랜드A");
        매장을_만든다("브랜드A", "a-owner2@example.com"); // 브랜드A 본부가 실제 권한을 갖도록 매장 1개는 A로 만들어둔다

        assertThatThrownBy(() -> hqService.listReviews(hqUserA, "브랜드A", storeB.storePublicId(), null, null, null,
                null, null, null, null, 0, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // ── (c) 조회 시 감사로그 적재 ────────────────────────────────────────

    @Test
    void 매장목록_조회시_감사로그가_HQ_액터로_적재된다() {
        매장을_만든다("브랜드C", "c-owner@example.com");
        UUID hqUser = 본부사용자를_만든다("hq-c@example.com", "브랜드C");

        long before = auditLogRepository.count();
        hqService.listStores(hqUser, "브랜드C");

        List<AuditLog> logs = auditLogRepository.findAll();
        assertThat(logs.size()).isGreaterThan((int) before);
        assertThat(logs).anySatisfy(l -> {
            assertThat(l.getActorType()).isEqualTo("HQ");
            assertThat(l.getAction()).isEqualTo("HQ_STORES_VIEW");
            assertThat(l.getTargetType()).isEqualTo("BRAND");
        });
    }

    @Test
    void 리뷰_조회시에도_감사로그가_적재된다() {
        매장픽스처 store = 매장을_만든다("브랜드E", "e-owner@example.com");
        UUID hqUser = 본부사용자를_만든다("hq-e@example.com", "브랜드E");

        hqService.listReviews(hqUser, "브랜드E", null, null, null, null, null, null, null, null, 0, 20);

        assertThat(auditLogRepository.findAll()).anySatisfy(l -> {
            assertThat(l.getActorType()).isEqualTo("HQ");
            assertThat(l.getAction()).isEqualTo("HQ_REVIEWS_VIEW");
        });
        // ★ 감사로그에는 리뷰 본문·작성자 정보를 절대 넣지 않는다(H7).
        boolean leaksReviewBody = auditLogRepository.findAll().stream()
                .anyMatch(l -> l.getDetail() != null && l.getDetail().contains("리뷰본문"));
        assertThat(leaksReviewBody).isFalse();
    }

    // ── (e) riskLevel 필터 동작 ─────────────────────────────────────────

    @Test
    void 브랜드_리뷰조회에서_riskLevel_필터가_동작한다() {
        매장픽스처 store = 매장을_만든다("브랜드D", "d-owner@example.com");
        UUID hqUser = 본부사용자를_만든다("hq-d@example.com", "브랜드D");
        LocalDate day = LocalDate.of(2026, 8, 10);
        리뷰를_만든다(store, "r-low", 5, 0, day);
        리뷰를_만든다(store, "r-high", 1, 3, day);

        HqDtos.HqReviewListResponse res = hqService.listReviews(hqUser, "브랜드D", null, null, null, null, 3, null,
                null, null, 0, 20);

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).analysis().riskLevel()).isEqualTo(3);
        assertThat(res.items().get(0).storeName()).isEqualTo("매장-d-owner@example.com");
    }

    @Test
    void 본부_권한이_없으면_브랜드목록은_빈배열이지_예외가_아니다() {
        AppUser 사용자 = appUserRepository.save(AppUser.builder().email("nobrand@example.com").passwordHash("d")
                .name("아무개").build());

        List<HqDtos.HqBrandResponse> res = hqService.listBrands(사용자.getPublicId());

        assertThat(res).isEmpty();
    }

    @Test
    void 이상징후_레이더는_동일기간_발생률과_영향매장_고위험_메뉴근거를_집계한다() {
        매장픽스처 store1 = 매장을_만든다("레이더브랜드", "radar-owner1@example.com");
        매장픽스처 store2 = 매장을_만든다("레이더브랜드", "radar-owner2@example.com");
        UUID hqUser = 본부사용자를_만든다("radar-hq@example.com", "레이더브랜드");

        // 직전 3일: 분석 10건 중 배달지연 1건(10.0건/100건).
        for (int i = 0; i < 10; i++) {
            리뷰를_만든다(store1, "radar-prev-" + i, 4, 0, LocalDate.of(2026, 8, 7),
                    i == 0 ? new String[] {"배달지연"} : new String[0], new String[0], "[]");
        }
        // 현재 3일: 분석 10건 중 배달지연 3건(30.0건/100건), 2개 매장에 걸쳐 발생.
        for (int i = 0; i < 10; i++) {
            매장픽스처 target = i == 1 ? store2 : store1;
            boolean issue = i < 3;
            리뷰를_만든다(target, "radar-now-" + i, issue ? 2 : 5, issue && i == 2 ? 3 : 0,
                    LocalDate.of(2026, 8, 10), issue ? new String[] {"배달지연"} : new String[0],
                    issue && i == 2 ? new String[] {"FOREIGN_OBJECT"} : new String[0],
                    issue ? "[\"치킨세트\"]" : "[]");
        }

        HqDtos.HqAnalyticsResponse result = hqService.analytics(hqUser, "레이더브랜드", "2026-08-08", "2026-08-10");

        assertThat(result.analysisCoverageRate()).isEqualTo(1.0);
        assertThat(result.highRiskReviews()).isEqualTo(1);
        assertThat(result.highRiskAffectedStores()).isEqualTo(1);
        assertThat(result.issueTagRanking()).filteredOn(i -> i.tag().equals("배달지연")).singleElement()
                .satisfies(i -> {
                    assertThat(i.count()).isEqualTo(3);
                    assertThat(i.previousCount()).isEqualTo(1);
                    assertThat(i.ratePer100()).isEqualTo(30.0);
                    assertThat(i.previousRatePer100()).isEqualTo(10.0);
                    assertThat(i.deltaRatePoints()).isEqualTo(20.0);
                    assertThat(i.affectedStoreCount()).isEqualTo(2);
                    assertThat(i.signal()).isEqualTo("RISING");
                });
        assertThat(result.riskClusters()).anySatisfy(r -> {
            assertThat(r.reason()).isEqualTo("FOREIGN_OBJECT");
            assertThat(r.count()).isEqualTo(1);
        });
        assertThat(result.menuIssues()).anySatisfy(m -> {
            assertThat(m.menu()).isEqualTo("치킨세트");
            assertThat(m.tag()).isEqualTo("배달지연");
            assertThat(m.count()).isEqualTo(3);
        });
    }

    @Test
    void 레이더_이슈를_클릭하면_해당_태그의_근거리뷰만_조회한다() {
        매장픽스처 store = 매장을_만든다("드릴다운브랜드", "drill-owner@example.com");
        UUID hqUser = 본부사용자를_만든다("drill-hq@example.com", "드릴다운브랜드");
        LocalDate day = LocalDate.of(2026, 8, 10);
        리뷰를_만든다(store, "drill-delay", 2, 1, day, new String[] {"배달지연"}, new String[0], "[]");
        리뷰를_만든다(store, "drill-taste", 2, 1, day, new String[] {"맛"}, new String[0], "[]");

        HqDtos.HqReviewListResponse result = hqService.listReviews(hqUser, "드릴다운브랜드", null, null, null,
                null, null, null, "배달지연", "2026-08-10", "2026-08-10", 0, 20);

        assertThat(result.items()).singleElement()
                .satisfies(item -> assertThat(item.analysis().issueTags()).containsExactly("배달지연"));
    }
}
