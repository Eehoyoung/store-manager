package com.storemanager.api.review;

import java.util.List;

/**
 * /stores/{storeId}/reviews, /reviews/{reviewId} 응답 DTO (docs/13 §5, Sprint 5 R1/R2).
 * ★ 리뷰는 읽기 전용이다 — 이 패키지에 리뷰 본문을 생성·수정하는 코드를 추가하지 않는다(절대규칙 1).
 * ★ author_hash 는 재식별 위험이 있어 응답에 절대 포함하지 않는다(절대규칙 6) — author_masked 만 노출한다.
 * ★ 외부에는 리뷰·초안의 public_id(UUID)만 노출한다. 내부 BIGSERIAL은 서비스 경계 밖으로 내보내지 않는다.
 */
final class ReviewDtos {

    private ReviewDtos() {
    }

    record AnalysisResponse(String category, Float sentiment, List<String> issueTags, Integer riskLevel,
            List<String> riskReasons) {
    }

    record DraftSummaryResponse(String id, String status, String content) {
    }

    record ReviewSummaryResponse(String id, String platform, Integer rating, String body, String authorMasked,
            List<String> orderedMenus, List<String> imageUrls, String writtenAt, boolean writtenDateOnly,
            String collectedAt, boolean hasOwnerReply, AnalysisResponse analysis, DraftSummaryResponse draft) {
    }

    record ReviewListResponse(List<ReviewSummaryResponse> items, String nextCursor, boolean hasMore) {
    }

    record ReviewDetailResponse(String id, String platform, Integer rating, String body, String authorMasked,
            List<String> orderedMenus, List<String> imageUrls, String writtenAt, boolean writtenDateOnly,
            String collectedAt, boolean hasOwnerReply, AnalysisResponse analysis,
            List<DraftSummaryResponse> drafts) {
    }
}
