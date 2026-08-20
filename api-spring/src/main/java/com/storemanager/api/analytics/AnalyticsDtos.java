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
     * avgResponseMinutes 는 published_at - collected_at 기준(written_at 은 시각정보가 없어 사용 불가, CLAUDE.md 데이터처리 1번).
     */
    record ResponsePerformanceResponse(String from, String to, long totalReviews, long completedCount,
            double completionRate, double autoApprovalRate, Double avgResponseMinutes, long retriedCount) {
    }
}
