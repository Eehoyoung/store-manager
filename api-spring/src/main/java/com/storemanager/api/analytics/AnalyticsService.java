package com.storemanager.api.analytics;

import com.storemanager.api.analytics.AnalyticsDtos.CategoryBucket;
import com.storemanager.api.analytics.AnalyticsDtos.IssueTagItem;
import com.storemanager.api.analytics.AnalyticsDtos.IssuesResponse;
import com.storemanager.api.analytics.AnalyticsDtos.MenuItem;
import com.storemanager.api.analytics.AnalyticsDtos.MenusResponse;
import com.storemanager.api.analytics.AnalyticsDtos.RatingBucket;
import com.storemanager.api.analytics.AnalyticsDtos.ResponsePerformanceResponse;
import com.storemanager.api.analytics.AnalyticsDtos.SummaryResponse;
import com.storemanager.api.analytics.AnalyticsDtos.TrendPoint;
import com.storemanager.api.analytics.AnalyticsDtos.TrendResponse;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.draft.PublishScheduleCalculator;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /stores/{storeId}/analytics/summary, /trend (Sprint 5 A1/A2).
 * ★ 모든 집계는 AnalyticsQueryRepository 의 DB 쿼리 결과를 그대로 조립만 한다 — 전 리뷰를 메모리로 읽지 않는다.
 */
@Service
public class AnalyticsService {

    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final ZoneId KST = PublishScheduleCalculator.KST;

    private final AnalyticsQueryRepository analyticsQueryRepository;
    private final StoreRepository storeRepository;
    private final AppUserRepository appUserRepository;

    public AnalyticsService(AnalyticsQueryRepository analyticsQueryRepository, StoreRepository storeRepository,
            AppUserRepository appUserRepository) {
        this.analyticsQueryRepository = analyticsQueryRepository;
        this.storeRepository = storeRepository;
        this.appUserRepository = appUserRepository;
    }

    @Transactional(readOnly = true)
    public SummaryResponse summary(UUID ownerPublicId, UUID storePublicId, String fromStr, String toStr) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        LocalDate toDate = parseOrDefault(toStr, LocalDate.now(KST));
        LocalDate fromDate = parseOrDefault(fromStr, toDate.minusDays(DEFAULT_RANGE_DAYS - 1));
        Instant from = fromDate.atStartOfDay(KST).toInstant();
        Instant to = toDate.plusDays(1).atStartOfDay(KST).toInstant();

        Object[] countAvg = analyticsQueryRepository.reviewCountAndAvgRating(store.getId(), from, to).get(0);
        long total = ((Number) countAvg[0]).longValue();
        Double avgRating = countAvg[1] == null ? null : round1(((Number) countAvg[1]).doubleValue());

        List<RatingBucket> ratingDist = analyticsQueryRepository.ratingDistribution(store.getId(), from, to).stream()
                .map(row -> new RatingBucket(((Number) row[0]).intValue(), ((Number) row[1]).longValue()))
                .toList();
        List<CategoryBucket> categoryDist = analyticsQueryRepository.categoryDistribution(store.getId(), from, to)
                .stream().map(row -> new CategoryBucket((String) row[0], ((Number) row[1]).longValue())).toList();

        // 기간 지표: 답글 완료율은 "이 기간에 쓰인 리뷰" 기준을 유지한다.
        Map<String, Long> periodStatusCounts = new HashMap<>();
        for (Object[] row : analyticsQueryRepository.latestDraftStatusCounts(store.getId(), from, to)) {
            periodStatusCounts.put((String) row[0], ((Number) row[1]).longValue());
        }
        long publishedLike = periodStatusCounts.getOrDefault("PUBLISHED", 0L)
                + periodStatusCounts.getOrDefault("ALREADY_REPLIED", 0L);
        double completionRate = total == 0 ? 0.0 : round4((double) publishedLike / total);

        // ★ T-26: 현재 상태 지표(pendingCount/blockedCount/highRiskCount)는 기간 필터를 걷어내고 매장 전체의
        // 미처리 현황을 센다 — 40일 전에 쓰인 리뷰라도 아직 검수 대기라면 대시보드에서 사라지면 안 된다.
        Map<String, Long> allTimeStatusCounts = new HashMap<>();
        for (Object[] row : analyticsQueryRepository.latestDraftStatusCountsAllTime(store.getId())) {
            allTimeStatusCounts.put((String) row[0], ((Number) row[1]).longValue());
        }
        long pending = allTimeStatusCounts.getOrDefault("DRAFT", 0L);
        long blocked = allTimeStatusCounts.getOrDefault("BLOCKED", 0L);
        long highRisk = analyticsQueryRepository.highRiskPendingCount(store.getId());

        return new SummaryResponse(fromDate.toString(), toDate.toString(), total, avgRating, ratingDist,
                categoryDist, completionRate, pending, blocked, highRisk);
    }

    @Transactional(readOnly = true)
    public TrendResponse trend(UUID ownerPublicId, UUID storePublicId, String fromStr, String toStr) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        LocalDate toDate = parseOrDefault(toStr, LocalDate.now(KST));
        LocalDate fromDate = parseOrDefault(fromStr, toDate.minusDays(DEFAULT_RANGE_DAYS - 1));
        Instant from = fromDate.atStartOfDay(KST).toInstant();
        Instant to = toDate.plusDays(1).atStartOfDay(KST).toInstant();

        Map<LocalDate, Long> reviewCountByDay = new HashMap<>();
        Map<LocalDate, Double> avgRatingByDay = new HashMap<>();
        for (Object[] row : analyticsQueryRepository.dailyReviewStats(store.getId(), from, to)) {
            LocalDate day = toLocalDate(row[0]);
            reviewCountByDay.put(day, ((Number) row[1]).longValue());
            avgRatingByDay.put(day, row[2] == null ? null : round1(((Number) row[2]).doubleValue()));
        }
        Map<LocalDate, Long> publishedCountByDay = new HashMap<>();
        for (Object[] row : analyticsQueryRepository.dailyPublishedCounts(store.getId(), from, to)) {
            publishedCountByDay.put(toLocalDate(row[0]), ((Number) row[1]).longValue());
        }

        // ★ 데이터가 없는 날짜는 0으로 채운다(오케스트레이터 지시 A2) — 프론트 차트가 구멍 나지 않게.
        List<TrendPoint> items = new ArrayList<>();
        for (LocalDate d = fromDate; !d.isAfter(toDate); d = d.plusDays(1)) {
            items.add(new TrendPoint(d.toString(), reviewCountByDay.getOrDefault(d, 0L), avgRatingByDay.get(d),
                    publishedCountByDay.getOrDefault(d, 0L)));
        }
        return new TrendResponse(fromDate.toString(), toDate.toString(), items);
    }

    /** GET /stores/{storeId}/analytics/issues (B1). 기간 파라미터는 summary/trend 와 동일한 규약(기본 30일). */
    @Transactional(readOnly = true)
    public IssuesResponse issues(UUID ownerPublicId, UUID storePublicId, String fromStr, String toStr) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        LocalDate toDate = parseOrDefault(toStr, LocalDate.now(KST));
        LocalDate fromDate = parseOrDefault(fromStr, toDate.minusDays(DEFAULT_RANGE_DAYS - 1));
        Instant from = fromDate.atStartOfDay(KST).toInstant();
        Instant to = toDate.plusDays(1).atStartOfDay(KST).toInstant();

        List<IssueTagItem> items = analyticsQueryRepository.issueTagRanking(store.getId(), from, to).stream()
                .map(row -> new IssueTagItem((String) row[0], ((Number) row[1]).longValue(),
                        row[2] == null ? null : round1(((Number) row[2]).doubleValue()), toIso(row[3])))
                .toList();
        return new IssuesResponse(fromDate.toString(), toDate.toString(), items);
    }

    /** GET /stores/{storeId}/analytics/menus (B2). */
    @Transactional(readOnly = true)
    public MenusResponse menus(UUID ownerPublicId, UUID storePublicId, String fromStr, String toStr) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        LocalDate toDate = parseOrDefault(toStr, LocalDate.now(KST));
        LocalDate fromDate = parseOrDefault(fromStr, toDate.minusDays(DEFAULT_RANGE_DAYS - 1));
        Instant from = fromDate.atStartOfDay(KST).toInstant();
        Instant to = toDate.plusDays(1).atStartOfDay(KST).toInstant();

        List<MenuItem> items = analyticsQueryRepository.menuSatisfaction(store.getId(), from, to).stream()
                .map(row -> new MenuItem((String) row[0], ((Number) row[1]).longValue(),
                        row[2] == null ? null : round1(((Number) row[2]).doubleValue())))
                .toList();
        return new MenusResponse(fromDate.toString(), toDate.toString(), items);
    }

    /**
     * GET /stores/{storeId}/analytics/response (B3).
     * ★ 평균 응답시간은 published_at - collected_at 기준이다(written_at 은 시각정보가 없어 사용 불가).
     */
    @Transactional(readOnly = true)
    public ResponsePerformanceResponse response(UUID ownerPublicId, UUID storePublicId, String fromStr, String toStr) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        LocalDate toDate = parseOrDefault(toStr, LocalDate.now(KST));
        LocalDate fromDate = parseOrDefault(fromStr, toDate.minusDays(DEFAULT_RANGE_DAYS - 1));
        Instant from = fromDate.atStartOfDay(KST).toInstant();
        Instant to = toDate.plusDays(1).atStartOfDay(KST).toInstant();

        List<Object[]> rows = analyticsQueryRepository.responsePerformance(store.getId(), from, to);
        Object[] row = rows.isEmpty() ? new Object[] {0L, 0L, 0L, null, 0L} : rows.get(0);
        long total = row[0] == null ? 0L : ((Number) row[0]).longValue();
        long completed = row[1] == null ? 0L : ((Number) row[1]).longValue();
        long autoApproved = row[2] == null ? 0L : ((Number) row[2]).longValue();
        Double avgResponseMinutes = row[3] == null ? null : round1(((Number) row[3]).doubleValue());
        long retried = row[4] == null ? 0L : ((Number) row[4]).longValue();

        double completionRate = total == 0 ? 0.0 : round4((double) completed / total);
        double autoApprovalRate = completed == 0 ? 0.0 : round4((double) autoApproved / completed);

        return new ResponsePerformanceResponse(fromDate.toString(), toDate.toString(), total, completed,
                completionRate, autoApprovalRate, avgResponseMinutes, retried);
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────

    /** native 쿼리의 timestamptz 결과(java.sql.Timestamp) 를 ISO-8601 문자열로 변환한다. */
    private static String toIso(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant().toString();
        }
        if (v instanceof Instant instant) {
            return instant.toString();
        }
        return v.toString();
    }

    private static LocalDate toLocalDate(Object v) {
        if (v instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (v instanceof LocalDate localDate) {
            return localDate;
        }
        return LocalDate.parse(v.toString());
    }

    private static LocalDate parseOrDefault(String s, LocalDate fallback) {
        return s == null || s.isBlank() ? fallback : LocalDate.parse(s);
    }

    private static Double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private Store loadOwnedStore(AppUser owner, UUID storePublicId) {
        Store store = storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!store.getOwnerId().equals(owner.getId())) {
            // ★ X1: 403 이 아니라 404.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return store;
    }

    private AppUser resolveUser(UUID publicId) {
        return appUserRepository.findByPublicId(publicId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }
}
