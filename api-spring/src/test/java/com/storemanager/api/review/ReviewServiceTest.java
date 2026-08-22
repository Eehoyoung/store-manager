package com.storemanager.api.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.draft.ReplyDraft;
import com.storemanager.api.draft.ReviewAnalysis;
import com.storemanager.api.review.ReviewDtos.ReviewDetailResponse;
import com.storemanager.api.review.ReviewDtos.ReviewListResponse;
import com.storemanager.api.review.ReviewDtos.ReviewSummaryResponse;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ReviewService 단위테스트(Sprint 5 X2-c). Repository 는 전부 목으로 대체한다.
 * ★ 남의 매장 storeId 접근은 403 이 아니라 404 여야 한다(오케스트레이터 X1 지시).
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock private ReviewQueryRepository reviewQueryRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private AppUserRepository appUserRepository;

    private ReviewService reviewService;

    private final UUID ownerPublicId = UUID.randomUUID();
    private final AppUser owner = AppUser.builder().id(1L).publicId(ownerPublicId).email("owner@store.com")
            .name("사장").build();
    private final UUID storePublicId = UUID.randomUUID();
    private final Store myStore = Store.builder().id(100L).publicId(storePublicId).ownerId(1L).name("가게").build();

    @BeforeEach
    void setUp() {
        reviewService = new ReviewService(reviewQueryRepository, storeRepository, appUserRepository,
                new ObjectMapper());
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(owner));
    }

    @Test
    void 남의_매장_리뷰목록_조회는_403이_아니라_404다() {
        Store othersStore = Store.builder().id(999L).publicId(storePublicId).ownerId(2L).name("남의가게").build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(othersStore));

        ApiException ex = assertThrows(ApiException.class,
                () -> reviewService.listReviews(ownerPublicId, storePublicId, null, null, null, null, null, null,
                        null, null, null, 20));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 존재하지_않는_매장_리뷰목록_조회도_404다() {
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> reviewService.listReviews(ownerPublicId, storePublicId, null, null, null, null, null, null,
                        null, null, null, 20));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 남의_매장_리뷰의_상세조회도_404다() {
        UUID reviewPublicId = UUID.randomUUID();
        UnifiedReview review = UnifiedReview.builder().id(50L).publicId(reviewPublicId).storeId(999L).linkId(1L).platform("BAEMIN")
                .platformReviewId("r-50").rating((short) 5).body("맛있어요").writtenAt(Instant.now()).build();
        when(reviewQueryRepository.findByPublicId(reviewPublicId)).thenReturn(Optional.of(review));
        Store othersStore = Store.builder().id(999L).ownerId(2L).name("남의가게").build();
        when(storeRepository.findById(999L)).thenReturn(Optional.of(othersStore));

        ApiException ex = assertThrows(ApiException.class, () -> reviewService.getReview(ownerPublicId, reviewPublicId));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 목록_응답은_분석과_최신초안을_리뷰별로_채워_반환한다() {
        UUID reviewPublicId = UUID.randomUUID();
        UnifiedReview r1 = UnifiedReview.builder().id(10L).publicId(reviewPublicId).storeId(100L).linkId(1L).platform("BAEMIN")
                .platformReviewId("r-10").rating((short) 1).body("머리카락이 나왔어요").authorMasked("히**")
                .writtenAt(Instant.now()).collectedAt(Instant.now()).build();
        when(reviewQueryRepository.searchAfter(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(r1));
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(myStore));

        ReviewAnalysis analysis = ReviewAnalysis.builder().reviewId(10L).category("COMPLAINT").sentiment(-0.8f)
                .riskLevel((short) 3).riskReasons(new String[] {"FOREIGN_OBJECT"}).model("m").promptVersion("v1")
                .build();
        when(reviewQueryRepository.findAnalysesByReviewIds(anyList())).thenReturn(List.of(analysis));
        ReplyDraft draft = ReplyDraft.builder().id(7L).reviewId(10L).storeId(100L).content("").status("BLOCKED")
                .generatedBy("AI").build();
        when(reviewQueryRepository.findLatestDraftsByReviewIds(anyList())).thenReturn(List.of(draft));

        ReviewListResponse result = reviewService.listReviews(ownerPublicId, storePublicId, null, null, null, null,
                null, null, null, null, null, 20);

        assertThat(result.items()).hasSize(1);
        var item = result.items().get(0);
        assertThat(item.authorMasked()).isEqualTo("히**");
        assertThat(item.id()).isEqualTo(reviewPublicId.toString());
        assertThat(item.analysis().riskLevel()).isEqualTo(3);
        assertThat(item.draft().status()).isEqualTo("BLOCKED");
        assertThat(item.draft().id()).isEqualTo(draft.getPublicId().toString());
    }

    @Test
    void 커서_목록은_size보다_한건_더_조회해_nextCursor를_반환한다() {
        UnifiedReview r1 = review(30L);
        UnifiedReview r2 = review(29L);
        UnifiedReview r3 = review(28L);
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(myStore));
        when(reviewQueryRepository.searchAfter(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(List.of(r1, r2, r3));
        when(reviewQueryRepository.findAnalysesByReviewIds(anyList())).thenReturn(List.of());
        when(reviewQueryRepository.findLatestDraftsByReviewIds(anyList())).thenReturn(List.of());

        ReviewListResponse result = reviewService.listReviews(ownerPublicId, storePublicId, null, null, null, null,
                null, null, null, null, null, 2);

        assertThat(result.items()).extracting(ReviewSummaryResponse::id)
                .containsExactly(r1.getPublicId().toString(), r2.getPublicId().toString());
        assertThat(result.nextCursor()).isEqualTo(r2.getPublicId().toString());
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void 상세_응답은_초안_전체_이력을_최신순으로_담는다() {
        UUID reviewPublicId = UUID.randomUUID();
        UnifiedReview review = UnifiedReview.builder().id(20L).publicId(reviewPublicId).storeId(100L).linkId(1L).platform("BAEMIN")
                .platformReviewId("r-20").rating((short) 5).body("맛있어요").writtenAt(Instant.now()).build();
        when(reviewQueryRepository.findByPublicId(reviewPublicId)).thenReturn(Optional.of(review));
        when(storeRepository.findById(100L)).thenReturn(Optional.of(myStore));
        when(reviewQueryRepository.findAnalysesByReviewIds(List.of(20L))).thenReturn(List.of());
        ReplyDraft d1 = ReplyDraft.builder().id(2L).reviewId(20L).storeId(100L).content("두번째").status("DRAFT")
                .generatedBy("AI").build();
        ReplyDraft d2 = ReplyDraft.builder().id(1L).reviewId(20L).storeId(100L).content("첫번째").status("BLOCKED")
                .generatedBy("AI").build();
        when(reviewQueryRepository.findDraftsByReviewId(20L)).thenReturn(List.of(d1, d2));

        ReviewDetailResponse result = reviewService.getReview(ownerPublicId, reviewPublicId);

        assertThat(result.drafts()).hasSize(2);
        assertThat(result.drafts().get(0).id()).isEqualTo(d1.getPublicId().toString());
        assertThat(result.analysis()).isNull();
    }

    private static UnifiedReview review(long id) {
        return UnifiedReview.builder().id(id).storeId(100L).linkId(1L).platform("BAEMIN")
                .platformReviewId("r-" + id).rating((short) 5).body("맛있어요").writtenAt(Instant.now()).build();
    }
}
