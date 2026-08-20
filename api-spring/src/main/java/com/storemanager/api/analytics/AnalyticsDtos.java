package com.storemanager.api.analytics;

import java.util.List;

/** GET /stores/{storeId}/analytics/summary, /trend 응답 DTO (Sprint 5 A1/A2). */
final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    record RatingBucket(int rating, long count) {
    }

    record CategoryBucket(String category, long count) {
    }

    /**
     * GET /analytics/summary 응답.
     * ★ T-26: 필드마다 기준이 다르다 — 프론트는 이 차이를 반드시 구분해서 보여줘야 한다.
     * <ul>
     *   <li><b>기간 기준</b>(from~to, written_at) — totalReviews, avgRating, ratingDistribution,
     *       categoryDistribution, replyCompletionRate. "이 기간에 쓰인 리뷰"에 대한 지표다.</li>
     *   <li><b>전체 기준</b>(기간 무시, 매장 전체 현재 상태) — pendingCount, blockedCount, highRiskCount.
     *       "지금 검수해야 할 일감"이므로 40일 전 리뷰라도 미처리면 잡힌다.</li>
     * </ul>
     */
    record SummaryResponse(String from, String to, long totalReviews, Double avgRating,
            List<RatingBucket> ratingDistribution, List<CategoryBucket> categoryDistribution,
            double replyCompletionRate, long pendingCount, long blockedCount, long highRiskCount) {
    }

    record TrendPoint(String date, long reviewCount, Double avgRating, long publishedCount) {
    }

    record TrendResponse(String from, String to, List<TrendPoint> items) {
    }

    /** GET /analytics/issues 항목 (Sprint 5 B1). */
    record IssueTagItem(String tag, long count, Double avgRating, String lastOccurredAt) {
    }

    record IssuesResponse(String from, String to, List<IssueTagItem> items) {
    }

    /** GET /analytics/menus 항목 (Sprint 5 B2). */
    record MenuItem(String menu, long count, Double avgRating) {
    }

    record MenusResponse(String from, String to, List<MenuItem> items) {
    }

    /**
     * GET /analytics/response (Sprint 5 B3).
     * ★ T-26: 기간(from~to) 필터 기준이 written_at 이 아니라 published_at 이다 — "이 기간에 실제로 게시한 답글"의
     * 성과를 잰다(리뷰가 언제 쓰였는지는 무관). avgResponseMinutes 는 published_at - collected_at 기준
     * (written_at 은 시각정보가 없어 사용 불가, CLAUDE.md 데이터처리 1번).
     */
    record ResponsePerformanceResponse(String from, String to, long totalReviews, long completedCount,
            double completionRate, double autoApprovalRate, Double avgResponseMinutes, long retriedCount) {
    }
}
