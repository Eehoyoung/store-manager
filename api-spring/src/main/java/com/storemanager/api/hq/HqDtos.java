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

    /** FR-802. 매장별 운영 상태. */
    record HqStoreResponse(String storeId, String name, String address, boolean activated, String serviceStatus,
            List<PlatformLinkStatus> platformLinks, String lastCollectedAt, long pendingCount, long blockedCount,
            long highRiskCount, long recentReviewCount, Double recentAvgRating) {
    }

    record HqAnalysisResponse(String category, Float sentiment, List<String> issueTags, Integer riskLevel,
            List<String> riskReasons) {
    }

    record HqDraftSummaryResponse(String id, String status, String content) {
    }

    /** FR-803. 어느 매장의 리뷰인지 storeId/storeName 을 함께 내려준다. */
    record HqReviewItem(String id, String storeId, String storeName, String platform, Integer rating, String body,
            String authorMasked, List<String> orderedMenus, List<String> imageUrls, String writtenAt,
            boolean writtenDateOnly, String collectedAt, boolean hasOwnerReply, HqAnalysisResponse analysis,
            HqDraftSummaryResponse draft) {
    }

    record HqReviewListResponse(List<HqReviewItem> items, boolean hasMore) {
    }

    record RatingBucket(int rating, long count) {
    }

    record CategoryBucket(String category, long count) {
    }

    record IssueTagItem(String tag, long count) {
    }

    /** FR-804 매장별 비교. 미처리 건수는 pendingCount+blockedCount+highRiskCount 합계(현재 기준, 기간 무관). */
    record StoreComparisonItem(String storeId, String storeName, long reviewCount, Double avgRating,
            double replyCompletionRate, long unprocessedCount) {
    }

    record HqAnalyticsResponse(String from, String to, long totalReviews, Double avgRating,
            List<RatingBucket> ratingDistribution, List<CategoryBucket> categoryDistribution,
            List<IssueTagItem> issueTagRanking, List<StoreComparisonItem> storeComparison) {
    }
}
