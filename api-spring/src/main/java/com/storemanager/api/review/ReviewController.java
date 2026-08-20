package com.storemanager.api.review;

import com.storemanager.api.review.ReviewDtos.ReviewDetailResponse;
import com.storemanager.api.review.ReviewDtos.ReviewListResponse;
import com.storemanager.api.security.CurrentUser;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** docs/13 §5 리뷰 조회 API(Sprint 5 R1/R2). 읽기 전용 — 리뷰 본문 생성 엔드포인트는 여기 없다(절대규칙 1). */
@RestController
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/api/v1/stores/{storeId}/reviews")
    public ReviewListResponse list(@PathVariable UUID storeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer minRating,
            @RequestParam(required = false) Integer maxRating,
            @RequestParam(required = false) Integer riskLevel,
            @RequestParam(required = false) Boolean hasReply,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return reviewService.listReviews(CurrentUser.publicId(), storeId, status, category, minRating, maxRating,
                riskLevel, hasReply, from, to, page, size);
    }

    @GetMapping("/api/v1/reviews/{reviewId}")
    public ReviewDetailResponse get(@PathVariable Long reviewId) {
        return reviewService.getReview(CurrentUser.publicId(), reviewId);
    }
}
