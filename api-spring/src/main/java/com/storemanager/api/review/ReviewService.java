package com.storemanager.api.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.draft.PublishScheduleCalculator;
import com.storemanager.api.draft.ReplyDraft;
import com.storemanager.api.draft.ReviewAnalysis;
import com.storemanager.api.review.ReviewDtos.AnalysisResponse;
import com.storemanager.api.review.ReviewDtos.DraftSummaryResponse;
import com.storemanager.api.review.ReviewDtos.ReviewDetailResponse;
import com.storemanager.api.review.ReviewDtos.ReviewListResponse;
import com.storemanager.api.review.ReviewDtos.ReviewSummaryResponse;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /stores/{storeId}/reviews, GET /reviews/{reviewId} (docs/13 §5, Sprint 5 R1/R2).
 * ★ 읽기 전용이다. 리뷰 본문·답글을 생성·수정하는 코드는 이 클래스에 절대 추가하지 않는다(절대규칙 1).
 * ★ 소유권 검사는 DraftService/StoreService 와 달리 403 이 아니라 404 를 던진다 — 오케스트레이터 지시(X1):
 * 남의 매장 storeId 로 조회 시 존재 여부를 흘리지 않기 위함이다.
 */
@Service
public class ReviewService {

    private static final ZoneId KST = PublishScheduleCalculator.KST;

    private final ReviewQueryRepository reviewQueryRepository;
    private final StoreRepository storeRepository;
    private final AppUserRepository appUserRepository;
    private final ObjectMapper objectMapper;

    public ReviewService(ReviewQueryRepository reviewQueryRepository, StoreRepository storeRepository,
            AppUserRepository appUserRepository, ObjectMapper objectMapper) {
        this.reviewQueryRepository = reviewQueryRepository;
        this.storeRepository = storeRepository;
        this.appUserRepository = appUserRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ReviewListResponse listReviews(UUID ownerPublicId, UUID storePublicId, String status, String category,
            Integer minRating, Integer maxRating, Integer riskLevel, Boolean hasReply, String from, String to,
            int page, int size) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);

        Page<UnifiedReview> result = reviewQueryRepository.search(store.getId(), blankToNull(status),
                blankToNull(category), toShort(minRating), toShort(maxRating), toShort(riskLevel), hasReply,
                parseFromDate(from), parseToDateExclusive(to), PageRequest.of(page, size));

        List<UnifiedReview> reviews = result.getContent();
        List<Long> reviewIds = reviews.stream().map(UnifiedReview::getId).toList();
        Map<Long, ReviewAnalysis> analysisByReviewId = new HashMap<>();
        Map<Long, ReplyDraft> latestDraftByReviewId = new HashMap<>();
        if (!reviewIds.isEmpty()) {
            for (ReviewAnalysis a : reviewQueryRepository.findAnalysesByReviewIds(reviewIds)) {
                analysisByReviewId.put(a.getReviewId(), a);
            }
            for (ReplyDraft d : reviewQueryRepository.findLatestDraftsByReviewIds(reviewIds)) {
                latestDraftByReviewId.put(d.getReviewId(), d);
            }
        }

        List<ReviewSummaryResponse> items = reviews.stream()
                .map(r -> toSummary(r, analysisByReviewId.get(r.getId()), latestDraftByReviewId.get(r.getId())))
                .toList();
        return new ReviewListResponse(items, result.hasNext());
    }

    @Transactional(readOnly = true)
    public ReviewDetailResponse getReview(UUID ownerPublicId, Long reviewId) {
        AppUser owner = resolveUser(ownerPublicId);
        UnifiedReview review = reviewQueryRepository.findById(reviewId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        loadOwnedStore(owner, review.getStoreId());

        ReviewAnalysis analysis = reviewQueryRepository.findAnalysesByReviewIds(List.of(reviewId)).stream()
                .findFirst().orElse(null);
        List<DraftSummaryResponse> drafts = reviewQueryRepository.findDraftsByReviewId(reviewId).stream()
                .map(ReviewService::toDraftSummary).toList();

        return new ReviewDetailResponse(String.valueOf(review.getId()), review.getPlatform(),
                toInt(review.getRating()), review.getBody(), review.getAuthorMasked(),
                parseStringList(review.getOrderedMenus()), parseStringList(review.getImageUrls()),
                toIso(review.getWrittenAt()), review.isWrittenDateOnly(), toIso(review.getCollectedAt()),
                review.isHasOwnerReply(), toAnalysisResponse(analysis), drafts);
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────

    private ReviewSummaryResponse toSummary(UnifiedReview r, ReviewAnalysis analysis, ReplyDraft latestDraft) {
        return new ReviewSummaryResponse(String.valueOf(r.getId()), r.getPlatform(), toInt(r.getRating()),
                r.getBody(), r.getAuthorMasked(), parseStringList(r.getOrderedMenus()),
                parseStringList(r.getImageUrls()), toIso(r.getWrittenAt()), r.isWrittenDateOnly(),
                toIso(r.getCollectedAt()), r.isHasOwnerReply(), toAnalysisResponse(analysis),
                toDraftSummary(latestDraft));
    }

    private static DraftSummaryResponse toDraftSummary(ReplyDraft d) {
        if (d == null) {
            return null;
        }
        return new DraftSummaryResponse(String.valueOf(d.getId()), d.getStatus(), d.getContent());
    }

    private static AnalysisResponse toAnalysisResponse(ReviewAnalysis a) {
        if (a == null) {
            return null;
        }
        return new AnalysisResponse(a.getCategory(), a.getSentiment(), List.of(a.getIssueTags()),
                (int) a.getRiskLevel(), List.of(a.getRiskReasons()));
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private Store loadOwnedStore(AppUser owner, UUID storePublicId) {
        Store store = storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!store.getOwnerId().equals(owner.getId())) {
            // ★ X1: 403 이 아니라 404 — 존재 여부를 흘리지 않는다.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return store;
    }

    private Store loadOwnedStore(AppUser owner, Long storeId) {
        Store store = storeRepository.findById(storeId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!store.getOwnerId().equals(owner.getId())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return store;
    }

    private AppUser resolveUser(UUID publicId) {
        return appUserRepository.findByPublicId(publicId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Short toShort(Integer v) {
        return v == null ? null : v.shortValue();
    }

    private static Integer toInt(Short v) {
        return v == null ? null : v.intValue();
    }

    private static String toIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    // ★ 기간 필터는 null 을 넘기지 않고 넓은 경계값으로 대체한다.
    // JPQL 의 (:from IS NULL OR ...) 는 Postgres 가 타임스탬프 바인드 파라미터의 타입을
    // 추론하지 못해 "could not determine data type of parameter" 로 500 이 난다(실기동에서 확인).
    // 경계값으로 바꾸면 IS NULL 검사 자체가 사라져 문제가 없어지고 의미도 동일하다.
    private static final Instant OPEN_START = Instant.EPOCH;
    private static final Instant OPEN_END = LocalDate.of(9999, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();

    /** yyyy-MM-dd 를 KST 자정 기준 Instant 로 변환한다(written_at 저장 규약과 동일, docs/11 §2.4). */
    private static Instant parseFromDate(String date) {
        if (date == null || date.isBlank()) {
            return OPEN_START;
        }
        return LocalDate.parse(date).atStartOfDay(KST).toInstant();
    }

    /** to 는 포함(inclusive) 의미이므로 다음날 자정을 배타적 상한으로 사용한다. */
    private static Instant parseToDateExclusive(String date) {
        if (date == null || date.isBlank()) {
            return OPEN_END;
        }
        return LocalDate.parse(date).plusDays(1).atStartOfDay(KST).toInstant();
    }
}
