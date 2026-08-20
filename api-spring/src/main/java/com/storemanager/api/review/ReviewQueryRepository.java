package com.storemanager.api.review;

import com.storemanager.api.draft.ReplyDraft;
import com.storemanager.api.draft.ReviewAnalysis;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 리뷰 목록/상세 조회용 신규 저장소 (Sprint 5 R1/R2). 기존 UnifiedReviewRepository(review 패키지) 는
 * dedupe 전용 단순 조회만 갖고 있어 손대지 않고, 조인 조회는 이 저장소를 새로 만들어 담당한다.
 * ★ N+1 방지: 목록 조회는 필터링에 review_analysis/reply_draft 를 조인하되 엔티티는 UnifiedReview 만 페이징으로
 * 가져오고, 분석·최신초안 데이터는 이 저장소의 배치 조회 메서드 2개로 한 번에 채운다(ReviewService 참고).
 */
public interface ReviewQueryRepository extends JpaRepository<UnifiedReview, Long> {

    @Query(value = """
            SELECT r FROM UnifiedReview r
            LEFT JOIN ReviewAnalysis a ON a.reviewId = r.id
            LEFT JOIN ReplyDraft d ON d.id = (
                SELECT MAX(d2.id) FROM ReplyDraft d2 WHERE d2.reviewId = r.id)
            WHERE r.storeId = :storeId
              AND (:status IS NULL OR d.status = :status)
              AND (:category IS NULL OR a.category = :category)
              AND (:minRating IS NULL OR r.rating >= :minRating)
              AND (:maxRating IS NULL OR r.rating <= :maxRating)
              AND (:riskLevel IS NULL OR a.riskLevel >= :riskLevel)
              AND (:hasReply IS NULL OR r.hasOwnerReply = :hasReply)
              AND r.writtenAt >= :from
              AND r.writtenAt < :to
            ORDER BY r.writtenAt DESC, r.id DESC
            """,
            countQuery = """
            SELECT COUNT(r) FROM UnifiedReview r
            LEFT JOIN ReviewAnalysis a ON a.reviewId = r.id
            LEFT JOIN ReplyDraft d ON d.id = (
                SELECT MAX(d2.id) FROM ReplyDraft d2 WHERE d2.reviewId = r.id)
            WHERE r.storeId = :storeId
              AND (:status IS NULL OR d.status = :status)
              AND (:category IS NULL OR a.category = :category)
              AND (:minRating IS NULL OR r.rating >= :minRating)
              AND (:maxRating IS NULL OR r.rating <= :maxRating)
              AND (:riskLevel IS NULL OR a.riskLevel >= :riskLevel)
              AND (:hasReply IS NULL OR r.hasOwnerReply = :hasReply)
              AND r.writtenAt >= :from
              AND r.writtenAt < :to
            """)
    Page<UnifiedReview> search(@Param("storeId") Long storeId, @Param("status") String status,
            @Param("category") String category, @Param("minRating") Short minRating,
            @Param("maxRating") Short maxRating, @Param("riskLevel") Short riskLevel,
            @Param("hasReply") Boolean hasReply, @Param("from") Instant from, @Param("to") Instant to,
            Pageable pageable);

    @Query("SELECT a FROM ReviewAnalysis a WHERE a.reviewId IN :reviewIds")
    List<ReviewAnalysis> findAnalysesByReviewIds(@Param("reviewIds") List<Long> reviewIds);

    /** 목록 화면용 — 리뷰당 최신 초안 1개씩 배치로 가져온다(id 최댓값 = 최신, BIGSERIAL 특성 이용). */
    @Query("""
            SELECT d FROM ReplyDraft d
            WHERE d.id IN (SELECT MAX(d2.id) FROM ReplyDraft d2 WHERE d2.reviewId IN :reviewIds GROUP BY d2.reviewId)
            """)
    List<ReplyDraft> findLatestDraftsByReviewIds(@Param("reviewIds") List<Long> reviewIds);

    /** 상세 화면용 — 해당 리뷰의 초안 전체(재생성 이력 포함)를 최신순으로. */
    @Query("SELECT d FROM ReplyDraft d WHERE d.reviewId = :reviewId ORDER BY d.id DESC")
    List<ReplyDraft> findDraftsByReviewId(@Param("reviewId") Long reviewId);
}
