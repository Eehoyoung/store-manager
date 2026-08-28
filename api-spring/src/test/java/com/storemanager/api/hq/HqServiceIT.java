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
    @Autowired HqAccessGuard hqAccessGuard;
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

    // ── 본부 조회 소급 기간 상한 ──────────────────────────────────────
    // ★ WP-01(2026-08-28) — FR-803 개별 리뷰 조회(listReviews)를 제거하면서 이 조회에 대한
    //   90일 상한 테스트 2건도 함께 제거했다. 집계(analytics)의 90일 상한은 아래
    //   "집계_조회도_90일로_당겨지고_적용기간을_응답에_담는다" 가 계속 검증한다.

    /** 집계도 같은 창을 쓴다. 거절하지 않고 당기며, 응답의 from 에 실제 적용 기간이 담긴다. */
    @Test
    void 집계_조회도_90일로_당겨지고_적용기간을_응답에_담는다() {
        UUID hqUser = 본부사용자를_만든다("hq-lookback3@test.com", "소급브랜드3");
        매장을_만든다("소급브랜드3", "store-lb3@test.com");
        LocalDate today = LocalDate.now(KST);

        HqDtos.HqAnalyticsResponse res = hqService.analytics(hqUser, "소급브랜드3",
                today.minusDays(400).toString(), today.toString());

        assertThat(LocalDate.parse(res.from()))
                .isEqualTo(today.minusDays(HqService.HQ_MAX_LOOKBACK_DAYS - 1L));
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

    // ── (b) 다른 브랜드 매장 storeId 로 접근 → 404 (H6-2) ────────
    // ★ WP-01(2026-08-28) — 이 재확인은 원래 FR-803 개별 리뷰 조회(listReviews)를 통해서만
    //   검증됐다. listReviews 를 제거했지만 HqAccessGuard.requireStoreInBrand 자체는 그대로
    //   남겨뒀으므로(do_not_touch) 가드를 직접 호출해 404 차단을 검증한다.

    @Test
    void 다른_브랜드_매장_storeId로_접근하면_404() {
        매장픽스처 storeB = 매장을_만든다("브랜드B", "b-owner@example.com");

        assertThatThrownBy(() -> hqAccessGuard.requireStoreInBrand(storeB.storePublicId(), "브랜드A"))
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

    // ★ WP-01(2026-08-28) — "리뷰_조회시에도_감사로그가_적재된다" 와
    //   "브랜드_리뷰조회에서_riskLevel_필터가_동작한다" 는 제거한 FR-803 개별 리뷰 조회
    //   (listReviews) 전용 테스트였다. 본부 조회 감사 로그 자체는 위
    //   "매장목록_조회시_감사로그가_HQ_액터로_적재된다" 와 아래 analytics 테스트가 계속 검증한다.

    @Test
    void 본부_권한이_없으면_브랜드목록은_빈배열이지_예외가_아니다() {
        AppUser 사용자 = appUserRepository.save(AppUser.builder().email("nobrand@example.com").passwordHash("d")
                .name("아무개").build());

        List<HqDtos.HqBrandResponse> res = hqService.listBrands(사용자.getPublicId());

        assertThat(res).isEmpty();
    }

    /**
     * ★ WP-02(2026-08-28) — 최소 집계 기준(=5)을 넘는 값만 이 테스트가 검증한다. 기준 미만
     * 항목이 가려지는 동작은 별도로 아래 "최소_집계_기준_미만인_항목은..." 테스트가 검증한다.
     * (숫자를 전부 5 이상으로 올려 재구성했다 — 원래 값(1·3건)은 새 기준으로는 전부 가려진다.)
     */
    @Test
    void 이상징후_레이더는_동일기간_발생률과_영향매장_고위험_메뉴근거를_집계한다() {
        매장픽스처 store1 = 매장을_만든다("레이더브랜드", "radar-owner1@example.com");
        매장픽스처 store2 = 매장을_만든다("레이더브랜드", "radar-owner2@example.com");
        UUID hqUser = 본부사용자를_만든다("radar-hq@example.com", "레이더브랜드");

        // 직전 기간: 분석 20건 중 배달지연 5건(25.0건/100건) — 기준(5) 이상이라 가려지지 않는다.
        for (int i = 0; i < 20; i++) {
            리뷰를_만든다(store1, "radar-prev-" + i, 4, 0, LocalDate.of(2026, 8, 7),
                    i < 5 ? new String[] {"배달지연"} : new String[0], new String[0], "[]");
        }
        // 현재 기간: 분석 30건. 배달지연 8건(순수 불만) + 5건(고위험·FOREIGN_OBJECT 동반) = 13건,
        // 2개 매장에 걸쳐 발생. 고위험 5건은 모두 store1.
        for (int i = 0; i < 8; i++) {
            매장픽스처 target = i == 1 ? store2 : store1;
            리뷰를_만든다(target, "radar-now-delay-" + i, 2, 0, LocalDate.of(2026, 8, 10),
                    new String[] {"배달지연"}, new String[0], "[\"치킨세트\"]");
        }
        for (int i = 0; i < 5; i++) {
            리뷰를_만든다(store1, "radar-now-risk-" + i, 1, 3, LocalDate.of(2026, 8, 10),
                    new String[] {"배달지연"}, new String[] {"FOREIGN_OBJECT"}, "[\"치킨세트\"]");
        }
        for (int i = 0; i < 17; i++) {
            리뷰를_만든다(store1, "radar-now-filler-" + i, 5, 0, LocalDate.of(2026, 8, 10),
                    new String[0], new String[0], "[]");
        }

        HqDtos.HqAnalyticsResponse result = hqService.analytics(hqUser, "레이더브랜드", "2026-08-08", "2026-08-10");

        assertThat(result.analysisCoverageRate()).isEqualTo(1.0);
        assertThat(result.highRiskReviews()).isEqualTo(5);
        assertThat(result.highRiskAffectedStores()).isEqualTo(1);
        assertThat(result.issueTagRanking()).filteredOn(i -> i.tag().equals("배달지연")).singleElement()
                .satisfies(i -> {
                    assertThat(i.belowThreshold()).isFalse();
                    assertThat(i.count()).isEqualTo(13);
                    assertThat(i.previousCount()).isEqualTo(5);
                    assertThat(i.ratePer100()).isEqualTo(43.3);
                    assertThat(i.previousRatePer100()).isEqualTo(25.0);
                    assertThat(i.deltaRatePoints()).isEqualTo(18.3);
                    assertThat(i.affectedStoreCount()).isEqualTo(2);
                    assertThat(i.signal()).isEqualTo("RISING");
                });
        assertThat(result.riskClusters()).anySatisfy(r -> {
            assertThat(r.reason()).isEqualTo("FOREIGN_OBJECT");
            assertThat(r.belowThreshold()).isFalse();
            assertThat(r.count()).isEqualTo(5);
        });
        assertThat(result.menuIssues()).anySatisfy(m -> {
            assertThat(m.menu()).isEqualTo("치킨세트");
            assertThat(m.tag()).isEqualTo("배달지연");
            assertThat(m.belowThreshold()).isFalse();
            assertThat(m.count()).isEqualTo(13);
        });
        assertThat(result.issueTagsBelowThreshold()).isEqualTo(0);
        assertThat(result.riskClustersBelowThreshold()).isEqualTo(0);
        assertThat(result.menuIssuesBelowThreshold()).isEqualTo(0);
    }

    /**
     * ★ WP-02(2026-08-28) 핵심 회귀 — count=1~4 짜리 이슈 태그·위험 사유·메뉴 이슈·일자별
     * 고위험 건수는 특정 리뷰(작성자)를 다시 알아볼 수 있게 한다(hq-data-sharing.md "최소
     * 집계 기준"). 항목을 목록에서 지우면 "그런 문제가 아예 없다"로 오독되므로(T-3),
     * 항목은 남기고 수치만 null 로 가리며 belowThreshold 플래그와 전체 가려진 건수를 함께 낸다.
     */
    @Test
    void 최소_집계_기준_미만인_항목은_수치가_가려지고_목록에는_남는다() {
        매장픽스처 store = 매장을_만든다("임계값브랜드", "threshold-owner@example.com");
        UUID hqUser = 본부사용자를_만든다("threshold-hq@example.com", "임계값브랜드");
        LocalDate day = LocalDate.of(2026, 8, 10);

        // 기준(5) 미만 — 이슈 태그 "손톱" 2건, 메뉴(떡볶이,손톱) 2건, 위험 사유는 없음.
        for (int i = 0; i < 2; i++) {
            리뷰를_만든다(store, "th-nail-" + i, 1, 0, day, new String[] {"손톱"}, new String[0], "[\"떡볶이\"]");
        }
        // 기준(5) 이상 — 이슈 태그 "맛" 5건. 위험은 아니다.
        for (int i = 0; i < 5; i++) {
            리뷰를_만든다(store, "th-taste-" + i, 4, 0, day, new String[] {"맛"}, new String[0], "[]");
        }
        // 기준(5) 미만 — 고위험 사유 "FOOD_POISONING" 2건.
        for (int i = 0; i < 2; i++) {
            리뷰를_만든다(store, "th-risk-" + i, 1, 3, day, new String[0], new String[] {"FOOD_POISONING"}, "[]");
        }

        HqDtos.HqAnalyticsResponse result = hqService.analytics(hqUser, "임계값브랜드", "2026-08-10", "2026-08-10");

        // ── 이슈 태그: "손톱"은 가려지고, "맛"은 그대로 보인다 ──
        assertThat(result.issueTagRanking()).extracting(HqDtos.IssueTagItem::tag)
                .containsExactlyInAnyOrder("손톱", "맛");
        assertThat(result.issueTagRanking()).filteredOn(i -> i.tag().equals("손톱")).singleElement().satisfies(i -> {
            assertThat(i.belowThreshold()).isTrue();
            assertThat(i.count()).isNull();
            assertThat(i.ratePer100()).isNull();
            assertThat(i.affectedStoreCount()).isNull();
            assertThat(i.signal()).isEqualTo("BELOW_THRESHOLD");
        });
        assertThat(result.issueTagRanking()).filteredOn(i -> i.tag().equals("맛")).singleElement().satisfies(i -> {
            assertThat(i.belowThreshold()).isFalse();
            assertThat(i.count()).isEqualTo(5);
        });
        assertThat(result.issueTagsBelowThreshold()).isEqualTo(1);

        // ── 위험 사유: "FOOD_POISONING" 은 목록에 남지만 수치는 가려진다 ──
        assertThat(result.riskClusters()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo("FOOD_POISONING");
            assertThat(r.belowThreshold()).isTrue();
            assertThat(r.count()).isNull();
            assertThat(r.affectedStoreCount()).isNull();
        });
        assertThat(result.riskClustersBelowThreshold()).isEqualTo(1);
        // ★ 최상위 highRiskReviews 요약치는 WP-02 범위 밖이다(개별 목록 항목만 가린다) — 그대로 노출된다.
        assertThat(result.highRiskReviews()).isEqualTo(2);

        // ── 메뉴×이슈: (떡볶이,손톱) 조합도 남지만 수치는 가려진다 ──
        assertThat(result.menuIssues()).singleElement().satisfies(m -> {
            assertThat(m.menu()).isEqualTo("떡볶이");
            assertThat(m.tag()).isEqualTo("손톱");
            assertThat(m.belowThreshold()).isTrue();
            assertThat(m.count()).isNull();
        });
        assertThat(result.menuIssuesBelowThreshold()).isEqualTo(1);

        // ── 일자별 위험 흐름: 그 날 고위험 2건(<5)은 가려지지만, 분석 총량은 가리지 않는다 ──
        assertThat(result.dailyRiskTrend()).singleElement().satisfies(d -> {
            assertThat(d.analyzedCount()).isEqualTo(9);
            assertThat(d.issueReviewCount()).isEqualTo(7); // 손톱(2)+맛(5) — 기준 이상이라 안 가려짐
            assertThat(d.highRiskCount()).isNull(); // 2건 — 기준 미만이라 가려짐
            assertThat(d.belowThreshold()).isTrue();
        });
        assertThat(result.dailyRiskBelowThreshold()).isEqualTo(1);
    }
}
