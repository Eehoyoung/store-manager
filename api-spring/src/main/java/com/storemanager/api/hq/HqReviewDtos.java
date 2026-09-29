package com.storemanager.api.hq;

import java.util.List;

/**
 * 가맹본부 개별 리뷰 조회 응답 DTO (docs/26 §4, docs/26a endpoints.hq.reviewForbiddenFields).
 *
 * <p>★ 일부러 {@link HqDtos} 와 분리했다 — {@code HqDtoFieldsTest} 는 "본부는 개별 리뷰를 볼 수
 * 없다"던 옛 설계(WP-01)를 지키는 테스트라 {@code body}·{@code writtenAt} 같은 이름 자체를 금지어로
 * 스캔한다. 지금은 동의·플래그로 통제된 개별 리뷰 조회가 의도된 기능이므로, 이 파일은
 * {@code HqReviewDtoFieldsTest} 가 실제 금지 목록(reviewForbiddenFields)으로 따로 검증한다.
 */
final class HqReviewDtos {

    private HqReviewDtos() {
    }

    record ReviewItem(String reviewId, String storeName, String platform, String writtenAt, Integer rating,
            String excerpt, String authorDisplay, String category, List<String> issueTags, Integer riskLevel,
            List<String> riskReasons, String replyStatus) {
    }

    record ReviewListResponse(List<ReviewItem> items, String nextCursor) {
    }

    record ReviewDetailResponse(String reviewId, String storeName, String platform, String writtenAt,
            Integer rating, String excerpt, String authorDisplay, String category, List<String> issueTags,
            Integer riskLevel, List<String> riskReasons, String replyStatus, String body) {
    }
}
