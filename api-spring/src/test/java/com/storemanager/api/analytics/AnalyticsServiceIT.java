package com.storemanager.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.storemanager.api.analytics.AnalyticsDtos.IssuesResponse;
import com.storemanager.api.analytics.AnalyticsDtos.MenusResponse;
import com.storemanager.api.analytics.AnalyticsDtos.ResponsePerformanceResponse;
import com.storemanager.api.analytics.AnalyticsDtos.SummaryResponse;
import com.storemanager.api.analytics.AnalyticsDtos.TrendResponse;
import com.storemanager.api.draft.PublishScheduleCalculator;
import com.storemanager.api.draft.ReplyDraft;
import com.storemanager.api.draft.ReplyDraftRepository;
import com.storemanager.api.draft.ReviewAnalysis;
import com.storemanager.api.draft.ReviewAnalysisRepository;
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

/**
 * AnalyticsService 집계 정확성을 실제 Postgres 위에서 검증한다(Sprint 5 X2-d).
 * ★ DB 의 GROUP BY/COUNT/AVG 결과가 그대로 응답에 반영되는지 확인한다 — 자바에서 재계산하지 않는다.
 * CredentialServiceIT 와 동일한 Testcontainers 패턴(Redis 불필요)을 재사용한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AnalyticsServiceIT {

    private static final ZoneId KST = PublishScheduleCalculator.KST;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired AnalyticsService analyticsService;
    @Autowired AppUserRepository appUserRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired UnifiedReviewRepository unifiedReviewRepository;
    @Autowired ReviewAnalysisRepository reviewAnalysisRepository;
    @Autowired ReplyDraftRepository replyDraftRepository;
    @Autowired CredentialService credentialService;
    @Autowired StorePlatformLinkRepository storePlatformLinkRepository;

    private record 매장픽스처(UUID ownerPublicId, UUID storePublicId, Long storeId, Long linkId) {
    }

    private 매장픽스처 매장을_만든다(String email) {
        AppUser owner = appUserRepository.save(AppUser.builder().email(email).passwordHash("dummy").name("사장")
                .build());
        Store store = storeRepository.save(Store.builder().ownerId(owner.getId()).name("집계매장-" + email).build());
        // unified_review.link_id 는 store_platform_link FK 이므로 계정·연동을 먼저 만든다(docs/11 §2.3~2.4).
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "id-" + email, "pw");
        StorePlatformLink link = storePlatformLinkRepository.save(StorePlatformLink.builder()
                .storeId(store.getId()).accountId(account.getId()).platform("BAEMIN")
                .platformStoreId("ps-" + email).build());
        return new 매장픽스처(owner.getPublicId(), store.getPublicId(), store.getId(), link.getId());
    }

    private Long 리뷰를_만든다(매장픽스처 f, String platformReviewId, int rating, LocalDate writtenDate) {
        UnifiedReview review = unifiedReviewRepository.save(UnifiedReview.builder()
                .storeId(f.storeId()).linkId(f.linkId()).platform("BAEMIN").platformReviewId(platformReviewId)
                .rating((short) rating).body("리뷰본문").writtenAt(writtenDate.atStartOfDay(KST).toInstant())
                .build());
        return review.getId();
    }

    /** B2 용 — 주문 메뉴(JSONB 문자열배열)를 지정해 리뷰를 만든다. */
    private Long 리뷰를_만든다_메뉴(매장픽스처 f, String platformReviewId, int rating, LocalDate writtenDate,
            String orderedMenusJson) {
        UnifiedReview review = unifiedReviewRepository.save(UnifiedReview.builder()
                .storeId(f.storeId()).linkId(f.linkId()).platform("BAEMIN").platformReviewId(platformReviewId)
                .rating((short) rating).body("리뷰본문").writtenAt(writtenDate.atStartOfDay(KST).toInstant())
                .orderedMenus(orderedMenusJson)
                .build());
        return review.getId();
    }

    /** B3 용 — written_at 과 별도로 collected_at(응답시간 계산 기준)을 지정해 리뷰를 만든다. */
    private Long 리뷰를_만든다_수집시각(매장픽스처 f, String platformReviewId, LocalDate writtenDate, Instant collectedAt) {
        UnifiedReview review = unifiedReviewRepository.save(UnifiedReview.builder()
                .storeId(f.storeId()).linkId(f.linkId()).platform("BAEMIN").platformReviewId(platformReviewId)
                .rating((short) 5).body("리뷰본문").writtenAt(writtenDate.atStartOfDay(KST).toInstant())
                .collectedAt(collectedAt)
                .build());
        return review.getId();
    }

    private void 분석을_만든다(Long reviewId, String category, int riskLevel, String... issueTags) {
        reviewAnalysisRepository.save(ReviewAnalysis.builder().reviewId(reviewId).category(category)
                .sentiment(0f).riskLevel((short) riskLevel).issueTags(issueTags).model("m").promptVersion("v1")
                .build());
    }

    private void 초안을_만든다(Long storeId, Long reviewId, String status, Instant publishedAt) {
        replyDraftRepository.save(ReplyDraft.builder().storeId(storeId).reviewId(reviewId).content("답글")
                .status(status).generatedBy("AI").publishedAt(publishedAt).build());
    }

    /** B3 용 — 승인자(approvedBy, null=자동승인)와 재시도 횟수를 지정해 초안을 만든다. */
    private void 초안을_만든다(Long storeId, Long reviewId, String status, Instant publishedAt, Long approvedBy,
            int retryCount) {
        replyDraftRepository.save(ReplyDraft.builder().storeId(storeId).reviewId(reviewId).content("답글")
                .status(status).generatedBy("AI").publishedAt(publishedAt).approvedBy(approvedBy)
                .retryCount((short) retryCount).build());
    }

    @Test
    void summary는_DB_집계값을_그대로_반환한다() {
        매장픽스처 f = 매장을_만든다("summary-owner@example.com");
        LocalDate day = LocalDate.of(2026, 8, 10);

        Long r1 = 리뷰를_만든다(f, "s-1", 5, day);
        분석을_만든다(r1, "PRAISE", 0);
        초안을_만든다(f.storeId(), r1, "PUBLISHED", day.atStartOfDay(KST).toInstant());

        Long r2 = 리뷰를_만든다(f, "s-2", 5, day);
        분석을_만든다(r2, "PRAISE", 0);
        초안을_만든다(f.storeId(), r2, "PUBLISHED", day.atStartOfDay(KST).toInstant());

        Long r3 = 리뷰를_만든다(f, "s-3", 4, day);
        분석을_만든다(r3, "POSITIVE", 0);
        초안을_만든다(f.storeId(), r3, "DRAFT", null);

        Long r4 = 리뷰를_만든다(f, "s-4", 1, day);
        분석을_만든다(r4, "COMPLAINT", 3); // 고위험(절대규칙 3)
        초안을_만든다(f.storeId(), r4, "BLOCKED", null);

        Long r5 = 리뷰를_만든다(f, "s-5", 2, day);
        분석을_만든다(r5, "COMPLAINT", 1);
        초안을_만든다(f.storeId(), r5, "SCHEDULED", null);

        SummaryResponse res = analyticsService.summary(f.ownerPublicId(), f.storePublicId(), "2026-08-01",
                "2026-08-20");

        assertThat(res.totalReviews()).isEqualTo(5);
        assertThat(res.avgRating()).isCloseTo(3.4, within(0.01));
        assertThat(res.ratingDistribution()).hasSize(4); // rating 5(2건 합산), 4, 1, 2 → 4개 버킷
        assertThat(res.ratingDistribution().stream().filter(b -> b.rating() == 5).findFirst().orElseThrow().count())
                .isEqualTo(2);
        assertThat(res.categoryDistribution().stream().filter(b -> "COMPLAINT".equals(b.category())).findFirst()
                .orElseThrow().count()).isEqualTo(2);
        assertThat(res.replyCompletionRate()).isCloseTo(0.4, within(0.001)); // PUBLISHED 2건 / 전체 5건
        assertThat(res.pendingCount()).isEqualTo(1); // DRAFT
        assertThat(res.blockedCount()).isEqualTo(1); // BLOCKED
        assertThat(res.highRiskCount()).isEqualTo(1); // risk_level>=3
    }

    @Test
    void trend는_리뷰가_없는_날짜도_0으로_채운다() {
        매장픽스처 f = 매장을_만든다("trend-owner@example.com");
        LocalDate day1 = LocalDate.of(2026, 8, 5);
        LocalDate day3 = LocalDate.of(2026, 8, 7);

        리뷰를_만든다(f, "t-1", 5, day1);
        리뷰를_만든다(f, "t-2", 3, day1);
        리뷰를_만든다(f, "t-3", 4, day3);

        TrendResponse res = analyticsService.trend(f.ownerPublicId(), f.storePublicId(), "2026-08-05", "2026-08-07");

        assertThat(res.items()).hasSize(3); // 8/5, 8/6, 8/7
        assertThat(res.items().get(0).date()).isEqualTo("2026-08-05");
        assertThat(res.items().get(0).reviewCount()).isEqualTo(2);
        assertThat(res.items().get(0).avgRating()).isCloseTo(4.0, within(0.01));
        assertThat(res.items().get(1).date()).isEqualTo("2026-08-06");
        assertThat(res.items().get(1).reviewCount()).isEqualTo(0); // ★ 데이터 없는 날짜도 0으로 채워진다
        assertThat(res.items().get(1).avgRating()).isNull();
        assertThat(res.items().get(2).reviewCount()).isEqualTo(1);
    }

    // ── B1: 이슈 태그 랭킹 ─────────────────────────────────────────────

    @Test
    void issues는_태그별_빈도와_평균별점_최근발생일을_DB에서_집계한다() {
        매장픽스처 f = 매장을_만든다("issues-owner@example.com");
        LocalDate day1 = LocalDate.of(2026, 8, 10);
        LocalDate day2 = LocalDate.of(2026, 8, 12);

        Long r1 = 리뷰를_만든다(f, "i-1", 2, day1);
        분석을_만든다(r1, "COMPLAINT", 1, "간", "청결");
        Long r2 = 리뷰를_만든다(f, "i-2", 4, day2);
        분석을_만든다(r2, "IMPROVEMENT", 0, "간");

        AnalyticsDtos.IssuesResponse res = analyticsService.issues(f.ownerPublicId(), f.storePublicId(),
                "2026-08-01", "2026-08-20");

        assertThat(res.items()).hasSize(2);
        var ganTag = res.items().stream().filter(i -> "간".equals(i.tag())).findFirst().orElseThrow();
        assertThat(ganTag.count()).isEqualTo(2);
        assertThat(ganTag.avgRating()).isCloseTo(3.0, within(0.01)); // (2+4)/2
        assertThat(ganTag.lastOccurredAt()).startsWith("2026-08-1"); // day2(8/12) 기준 최신값
        var cleanTag = res.items().stream().filter(i -> "청결".equals(i.tag())).findFirst().orElseThrow();
        assertThat(cleanTag.count()).isEqualTo(1);

        // 기간 파라미터 없이도(★ IS NULL 패턴 회귀 방지) 500 없이 200 응답이어야 한다.
        AnalyticsDtos.IssuesResponse noRange = analyticsService.issues(f.ownerPublicId(), f.storePublicId(), null, null);
        assertThat(noRange).isNotNull();
    }

    // ── B2: 메뉴별 만족도 ─────────────────────────────────────────────

    @Test
    void menus는_메뉴별_리뷰수와_평균별점을_집계하고_메뉴없는_리뷰는_제외한다() {
        매장픽스처 f = 매장을_만든다("menus-owner@example.com");
        LocalDate day = LocalDate.of(2026, 8, 10);

        리뷰를_만든다_메뉴(f, "m-1", 5, day, "[\"김치찌개\",\"공기밥\"]");
        리뷰를_만든다_메뉴(f, "m-2", 3, day, "[\"김치찌개\"]");
        리뷰를_만든다_메뉴(f, "m-3", 4, day, "[]"); // 주문 메뉴 없음 — 제외되어야 한다

        AnalyticsDtos.MenusResponse res = analyticsService.menus(f.ownerPublicId(), f.storePublicId(),
                "2026-08-01", "2026-08-20");

        assertThat(res.items()).hasSize(2); // m-3 은 집계에서 빠진다
        var kimchi = res.items().stream().filter(i -> "김치찌개".equals(i.menu())).findFirst().orElseThrow();
        assertThat(kimchi.count()).isEqualTo(2);
        assertThat(kimchi.avgRating()).isCloseTo(4.0, within(0.01)); // (5+3)/2
        var rice = res.items().stream().filter(i -> "공기밥".equals(i.menu())).findFirst().orElseThrow();
        assertThat(rice.count()).isEqualTo(1);

        AnalyticsDtos.MenusResponse noRange = analyticsService.menus(f.ownerPublicId(), f.storePublicId(), null, null);
        assertThat(noRange).isNotNull();
    }

    // ── B3: 응답 성과 ────────────────────────────────────────────────

    @Test
    void response는_collected_at_기준_응답시간과_완료율_자동승인율_재시도건수를_집계한다() {
        매장픽스처 f = 매장을_만든다("response-owner@example.com");
        LocalDate day = LocalDate.of(2026, 8, 10);

        // r1: 수동승인, 수집 10:00 → 게시 10:30 (30분)
        Instant collected1 = day.atTime(10, 0).atZone(KST).toInstant();
        Long r1 = 리뷰를_만든다_수집시각(f, "p-1", day, collected1);
        초안을_만든다(f.storeId(), r1, "PUBLISHED", day.atTime(10, 30).atZone(KST).toInstant(), 1L, 0);

        // r2: 자동승인(approvedBy=null), 수집 09:00 → 게시 09:10 (10분), 재시도 1회
        Instant collected2 = day.atTime(9, 0).atZone(KST).toInstant();
        Long r2 = 리뷰를_만든다_수집시각(f, "p-2", day, collected2);
        초안을_만든다(f.storeId(), r2, "PUBLISHED", day.atTime(9, 10).atZone(KST).toInstant(), null, 1);

        // r3: 아직 미완료(DRAFT, published_at 없음) — ★ 모집단이 collected_at 기준이므로 이 건도 분모에 들어간다.
        // 모집단을 published_at 으로 잡으면 분모가 '이미 게시된 것' 이 되어 완료율이 항상 100% 가 된다.
        Instant collected3 = day.atTime(11, 0).atZone(KST).toInstant();
        Long r3 = 리뷰를_만든다_수집시각(f, "p-3", day, collected3);
        초안을_만든다(f.storeId(), r3, "DRAFT", null, null, 0);

        AnalyticsDtos.ResponsePerformanceResponse res = analyticsService.response(f.ownerPublicId(),
                f.storePublicId(), "2026-08-01", "2026-08-20");

        assertThat(res.totalReviews()).isEqualTo(3); // ★ 이 기간에 수집된 일감 전체(r1,r2,r3)
        assertThat(res.completedCount()).isEqualTo(2); // r1, r2 만 PUBLISHED
        // ★ 완료율이 100% 가 아니라 2/3 로 나와야 한다. 미처리 1건이 지표에 드러나는 것이 이 지표의 존재 이유다.
        assertThat(res.completionRate()).isCloseTo(2.0 / 3.0, within(0.001));
        assertThat(res.autoApprovalRate()).isCloseTo(0.5, within(0.001)); // 완료 2건 중 자동승인 1건(r2)
        assertThat(res.avgResponseMinutes()).isCloseTo(20.0, within(0.01)); // (30+10)/2, written_at 이 아니라 collected_at 기준
        assertThat(res.retriedCount()).isEqualTo(1); // r2 만 retry_count>0

        AnalyticsDtos.ResponsePerformanceResponse noRange = analyticsService.response(f.ownerPublicId(),
                f.storePublicId(), null, null);
        assertThat(noRange).isNotNull();
    }

    // ── T-26: 현재 상태 지표는 기간 필터를 걷어낸다 ──────────────────────

    @Test
    void summary는_기간_밖_리뷰의_미처리_초안도_pendingCount에_잡고_totalReviews에는_잡지_않는다() {
        매장픽스처 f = 매장을_만든다("t26-owner@example.com");
        LocalDate today = LocalDate.of(2026, 8, 20);
        LocalDate oldDay = today.minusDays(40); // 조회 기간(from~to) 밖 — 40일 전 작성

        // 기간 밖(40일 전) 리뷰: 아직 DRAFT(검수 대기) — 일감이므로 절대 화면에서 사라지면 안 된다.
        Long oldPendingReview = 리뷰를_만든다(f, "old-1", 3, oldDay);
        분석을_만든다(oldPendingReview, "IMPROVEMENT", 0);
        초안을_만든다(f.storeId(), oldPendingReview, "DRAFT", null);

        // 기간 밖(40일 전) 고위험 리뷰: 아직 BLOCKED(미처리) — 마찬가지로 화면에서 사라지면 안 된다.
        Long oldBlockedReview = 리뷰를_만든다(f, "old-2", 1, oldDay);
        분석을_만든다(oldBlockedReview, "COMPLAINT", 3);
        초안을_만든다(f.storeId(), oldBlockedReview, "BLOCKED", null);

        // 기간 안(오늘) 리뷰 1건 — totalReviews 는 이것만 세야 한다.
        Long recentReview = 리뷰를_만든다(f, "recent-1", 5, today);
        분석을_만든다(recentReview, "PRAISE", 0);
        초안을_만든다(f.storeId(), recentReview, "PUBLISHED", today.atStartOfDay(KST).toInstant());

        AnalyticsDtos.SummaryResponse res = analyticsService.summary(f.ownerPublicId(), f.storePublicId(),
                today.toString(), today.toString());

        assertThat(res.totalReviews()).isEqualTo(1); // ★ 기간 지표 — 오늘 작성된 리뷰 1건만
        assertThat(res.pendingCount()).isEqualTo(1); // ★ 전체 기준 — 40일 전 DRAFT 도 잡힌다
        assertThat(res.blockedCount()).isEqualTo(1); // ★ 전체 기준 — 40일 전 BLOCKED 도 잡힌다
        assertThat(res.highRiskCount()).isEqualTo(1); // ★ 전체 기준 — risk_level>=3 이면서 아직 BLOCKED 인 건
    }
}
