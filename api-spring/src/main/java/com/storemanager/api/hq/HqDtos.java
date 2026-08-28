package com.storemanager.api.hq;

import java.util.List;

/**
 * 가맹본부 조회 API 응답 DTO (Sprint 8, FR-802~804).
 * ★ 본부는 조회 전용이다 — 이 파일에 요청 바디(쓰기용) 레코드를 추가하지 않는다(H8).
 * ★ H9 비노출 항목 — 구독/청구/입금 상세, 플랫폼 계정 자격증명(login_id 포함), author_hash, 가맹점주 개인정보는
 * 어떤 레코드에도 필드로 넣지 않는다. 매장 상태는 serviceStatus(이용중/정지) 수준의 coarse 값만 노출한다.
 */
final class HqDtos {

    private HqDtos() {
    }

    record HqBrandResponse(String brandName, long storeCount) {
    }

    record PlatformLinkStatus(String platform, String linkStatus) {
    }

    /**
     * FR-802. 매장별 운영 상태.
     *
     * <p>★ WP-03(2026-08-28) — pendingCount·blockedCount·highRiskCount·recentReviewCount 는
     * analytics 와 같은 최소 집계 기준(HqService.MIN_AGGREGATION_THRESHOLD)을 적용한다. 기준
     * 미만(1~4)이면 null 로 가리고 belowThreshold 가 true 다. 0 은 "그 상태가 없다"는 뜻이라
     * 가리지 않는다. recentAvgRating 은 recentReviewCount 가 가려지면 함께 가린다 — 적은
     * 표본의 평균은 그 표본을 좁힌다.
     */
    record HqStoreResponse(String storeId, String name, String address, boolean activated, String serviceStatus,
            List<PlatformLinkStatus> platformLinks, String lastCollectedAt, Long pendingCount, Long blockedCount,
            Long highRiskCount, Long recentReviewCount, Double recentAvgRating, boolean belowThreshold) {
    }

    record RatingBucket(int rating, long count) {
    }

    record CategoryBucket(String category, long count) {
    }

    /**
     * 브랜드 이슈 추이. 발생률 분모는 전체 리뷰가 아니라 분석 완료 리뷰다.
     * analysisCoverageRate 를 함께 내려 분석 누락을 정상으로 오해하지 않게 한다.
     *
     * <p>★ 최소 집계 기준(HqService.MIN_AGGREGATION_THRESHOLD) 미만이면 count 이하 수치 필드는
     * 전부 null 로 가려지고 belowThreshold 가 true 다. tag 자체는 남긴다 — 항목을 목록에서
     * 빼면 "그런 이슈가 아예 없다"로 오독되기 때문이다(T-3).
     */
    record IssueTagItem(String tag, Long count, Long previousCount, Double ratePer100, Double previousRatePer100,
            Double deltaRatePoints, Long affectedStoreCount, Double avgRating, String signal,
            boolean belowThreshold) {
    }

    /** ★ 최소 집계 기준 미만이면 count·previousCount·affectedStoreCount 가 null 이 된다(WP-02). */
    record RiskClusterItem(String reason, Long count, Long previousCount, Long affectedStoreCount,
            boolean belowThreshold) {
    }

    /** ★ 최소 집계 기준 미만이면 count·affectedStoreCount·avgRating 이 null 이 된다(WP-02). */
    record MenuIssueItem(String menu, String tag, Long count, Long affectedStoreCount, Double avgRating,
            boolean belowThreshold) {
    }

    /**
     * ★ 하루 단위는 표본이 가장 작다 — 이슈·고위험 건수가 각각 최소 집계 기준 미만이면 그 필드만
     * null 로 가린다(analyzedCount 는 이슈와 무관한 리뷰 총량이라 가리지 않는다).
     */
    record DailyRiskItem(String date, long analyzedCount, Long issueReviewCount, Long highRiskCount,
            boolean belowThreshold) {
    }

    /**
     * FR-804 매장별 비교. 미처리 건수는 pendingCount+blockedCount+highRiskCount 합계(현재 기준, 기간 무관).
     *
     * <p>★ 최소 집계 기준 미만이면 reviewCount·avgRating·replyCompletionRate·unprocessedCount 가
     * 전부 null 이 되고 belowThreshold 가 true 다. 매장명은 남긴다 — 항목을 목록에서 빼면
     * 본부가 '문제 없음' 으로 읽는다(WP-02, T-3).
     *
     * <p>★ 완료율까지 가리는 이유 — 리뷰 2건 중 1건 완료면 0.5 다. 분모가 작으면 비율이
     * 곧 원본 건수를 알려준다. 건수만 가리고 비율을 남기면 가린 의미가 없다.
     */
    record StoreComparisonItem(String storeId, String storeName, Long reviewCount, Double avgRating,
            Double replyCompletionRate, Long unprocessedCount, boolean belowThreshold) {
    }

    /**
     * FR-804 브랜드 집계 응답.
     * ★ issueTagsBelowThreshold 등 4개 필드는 최소 집계 기준 미만이라 수치를 가린 항목 수다(WP-02, T-3).
     * 조용히 숨기면 본부가 "문제 없음"으로 읽으므로 가려진 사실과 건수를 항상 함께 내려준다.
     */
    record HqAnalyticsResponse(String from, String to, String previousFrom, String previousTo, String dataAsOf,
            long totalReviews, long analyzedReviews, double analysisCoverageRate, Double avgRating,
            long highRiskReviews, long highRiskAffectedStores,
            long issueTagsBelowThreshold, long riskClustersBelowThreshold, long menuIssuesBelowThreshold,
            long dailyRiskBelowThreshold,
            List<RatingBucket> ratingDistribution, List<CategoryBucket> categoryDistribution,
            List<IssueTagItem> issueTagRanking, List<RiskClusterItem> riskClusters,
            List<MenuIssueItem> menuIssues, List<DailyRiskItem> dailyRiskTrend,
            List<StoreComparisonItem> storeComparison) {
    }
}
