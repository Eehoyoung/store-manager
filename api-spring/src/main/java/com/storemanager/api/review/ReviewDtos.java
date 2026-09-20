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

    /**
     * ★ guardrailFlags 를 내려보내는 이유 — 화면이 차단 사유를 사람이 읽을 문구로 보여주고,
     * 승인 가능 여부(RISK_LEVEL_TOO_HIGH 단독인가)를 누르기 전에 알려주기 위해서다.
     * 이 값이 없으면 사장님은 승인 버튼을 눌러 422 를 받고 나서야 승인이 안 된다는 것을 안다.
     *
     * <p>★ 최종 판정은 여전히 서버가 한다({@code RiskApprovalService.APPROVABLE_FLAG}).
     * 화면 검사는 사용자 편의일 뿐이며, 이 필드를 근거로 서버 검사를 줄이지 말 것.
     */
    /** ★ scheduledAt 은 SCHEDULED 초안의 게시 예정 시각이다. 화면이 "언제 나가는지" 를 보여주고
     *  게시 전에 멈출 수 있게 하는 데 쓴다(약관 제6조 제4항). 다른 상태에서는 null 이다. */
    record DraftSummaryResponse(String id, String status, String content, String generatedBy,
            List<String> guardrailFlags, String scheduledAt) {
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
