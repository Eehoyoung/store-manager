package com.storemanager.api.hq;

import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.store.Store;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 가맹본부 조회용 집계·검색 저장소 (Sprint 8, FR-802~804).
 * ★ N+1 방지: 매장이 여러 개여도 매장별 반복 쿼리 없이 storeId 목록을 IN 절로 한 번에 조회한다.
 * ReviewQueryRepository/AnalyticsQueryRepository 와 같은 관례로, 기존 review/draft/billing 엔티티를
 * 새 저장소에서 조인해 쓴다(그 패키지 파일은 건드리지 않는다).
 */
public interface HqQueryRepository extends JpaRepository<Store, Long> {

    @Query("SELECT s.brandName, COUNT(s) FROM Store s WHERE s.brandName IN :brandNames AND s.deletedAt IS NULL "
            + "GROUP BY s.brandName")
    List<Object[]> countStoresByBrandNames(@Param("brandNames") List<String> brandNames);

    @Query("SELECT s FROM Store s WHERE s.brandName = :brandName AND s.deletedAt IS NULL ORDER BY s.name")
    List<Store> findStoresByBrandName(@Param("brandName") String brandName);

    /** 매장별 플랫폼 연동 상태(1매장 N플랫폼 가능, docs/11 §2.3). */
    @Query("SELECT spl.storeId, spl.platform, pa.linkStatus FROM StorePlatformLink spl "
            + "JOIN PlatformAccount pa ON pa.id = spl.accountId WHERE spl.storeId IN :storeIds")
    List<Object[]> platformLinkStatuses(@Param("storeIds") List<Long> storeIds);

    /** 매장 상태는 coarse 한 구독 status 만 본다 — 청구·입금 상세는 절대 노출하지 않는다(H9). */
    @Query("SELECT sub.storeId, sub.status FROM Subscription sub "
            + "WHERE sub.storeId IN :storeIds AND sub.status <> 'CANCELED'")
    List<Object[]> subscriptionStatuses(@Param("storeIds") List<Long> storeIds);

    @Query("SELECT r.storeId, MAX(r.collectedAt) FROM UnifiedReview r WHERE r.storeId IN :storeIds "
            + "GROUP BY r.storeId")
    List<Object[]> lastCollectedAtByStore(@Param("storeIds") List<Long> storeIds);

    @Query("SELECT r.storeId, COUNT(r), AVG(r.rating) FROM UnifiedReview r "
            + "WHERE r.storeId IN :storeIds AND r.writtenAt >= :from GROUP BY r.storeId")
    List<Object[]> recentReviewStatsByStore(@Param("storeIds") List<Long> storeIds, @Param("from") Instant from);

    /**
     * 매장별 · 리뷰당 최신 초안 상태 건수 — 기간 필터 없음(현재 미처리 현황, AnalyticsQueryRepository T-26 관례와 동일).
     */
    @Query("""
            SELECT d.storeId, d.status, COUNT(d) FROM ReplyDraft d
            WHERE d.storeId IN :storeIds
              AND d.id IN (SELECT MAX(d2.id) FROM ReplyDraft d2 WHERE d2.reviewId = d.reviewId)
            GROUP BY d.storeId, d.status
            """)
    List<Object[]> latestDraftStatusCountsByStore(@Param("storeIds") List<Long> storeIds);

    /** 매장별 고위험(risk_level>=3) 미종결 리뷰 수 — 기간 필터 없음(AnalyticsQueryRepository.highRiskPendingCount 관례). */
    @Query("""
            SELECT r.storeId, COUNT(a) FROM ReviewAnalysis a JOIN UnifiedReview r ON r.id = a.reviewId
            WHERE r.storeId IN :storeIds AND a.riskLevel >= 3
              AND NOT EXISTS (
                SELECT d FROM ReplyDraft d
                WHERE d.reviewId = r.id
                  AND d.id = (SELECT MAX(d2.id) FROM ReplyDraft d2 WHERE d2.reviewId = r.id)
                  AND d.status IN ('PUBLISHED', 'ALREADY_REPLIED')
              )
            GROUP BY r.storeId
            """)
    List<Object[]> highRiskPendingCountsByStore(@Param("storeIds") List<Long> storeIds);

    // ── FR-803: 브랜드 통합 리뷰 조회 ──────────────────────────────────

    @Query(value = """
            SELECT r FROM UnifiedReview r
            LEFT JOIN ReviewAnalysis a ON a.reviewId = r.id
            LEFT JOIN ReplyDraft d ON d.id = (
                SELECT MAX(d2.id) FROM ReplyDraft d2 WHERE d2.reviewId = r.id)
            WHERE r.storeId IN :storeIds
              AND (:status IS NULL OR d.status = :status)
              AND (:category IS NULL OR a.category = :category)
              AND (:minRating IS NULL OR r.rating >= :minRating)
              AND (:maxRating IS NULL OR r.rating <= :maxRating)
              AND (:riskLevel IS NULL OR a.riskLevel >= :riskLevel)
              AND r.writtenAt >= :from
              AND r.writtenAt < :to
            ORDER BY r.writtenAt DESC, r.id DESC
            """,
            countQuery = """
            SELECT COUNT(r) FROM UnifiedReview r
            LEFT JOIN ReviewAnalysis a ON a.reviewId = r.id
            LEFT JOIN ReplyDraft d ON d.id = (
                SELECT MAX(d2.id) FROM ReplyDraft d2 WHERE d2.reviewId = r.id)
            WHERE r.storeId IN :storeIds
              AND (:status IS NULL OR d.status = :status)
              AND (:category IS NULL OR a.category = :category)
              AND (:minRating IS NULL OR r.rating >= :minRating)
              AND (:maxRating IS NULL OR r.rating <= :maxRating)
              AND (:riskLevel IS NULL OR a.riskLevel >= :riskLevel)
              AND r.writtenAt >= :from
              AND r.writtenAt < :to
            """)
    Page<UnifiedReview> searchBrandReviews(@Param("storeIds") List<Long> storeIds, @Param("status") String status,
            @Param("category") String category, @Param("minRating") Short minRating,
            @Param("maxRating") Short maxRating, @Param("riskLevel") Short riskLevel, @Param("from") Instant from,
            @Param("to") Instant to, Pageable pageable);

    /** issueTag 는 null 이 아닌 경우에만 이 쿼리를 호출한다. PostgreSQL 배열 조건의 null 타입 추론을 피한다. */
    @Query(value = """
            SELECT r.* FROM unified_review r
            LEFT JOIN review_analysis a ON a.review_id = r.id
            LEFT JOIN reply_draft d ON d.id = (
                SELECT MAX(d2.id) FROM reply_draft d2 WHERE d2.review_id = r.id)
            WHERE r.store_id IN (:storeIds)
              AND :issueTag = ANY(a.issue_tags)
              AND (:status IS NULL OR d.status = :status)
              AND (:category IS NULL OR a.category = :category)
              AND (:minRating IS NULL OR r.rating >= :minRating)
              AND (:maxRating IS NULL OR r.rating <= :maxRating)
              AND (:riskLevel IS NULL OR a.risk_level >= :riskLevel)
              AND r.written_at >= :from
              AND r.written_at < :to
            ORDER BY r.written_at DESC, r.id DESC
            """,
            countQuery = """
            SELECT COUNT(r.id) FROM unified_review r
            LEFT JOIN review_analysis a ON a.review_id = r.id
            LEFT JOIN reply_draft d ON d.id = (
                SELECT MAX(d2.id) FROM reply_draft d2 WHERE d2.review_id = r.id)
            WHERE r.store_id IN (:storeIds)
              AND :issueTag = ANY(a.issue_tags)
              AND (:status IS NULL OR d.status = :status)
              AND (:category IS NULL OR a.category = :category)
              AND (:minRating IS NULL OR r.rating >= :minRating)
              AND (:maxRating IS NULL OR r.rating <= :maxRating)
              AND (:riskLevel IS NULL OR a.risk_level >= :riskLevel)
              AND r.written_at >= :from
              AND r.written_at < :to
            """, nativeQuery = true)
    Page<UnifiedReview> searchBrandReviewsByIssueTag(@Param("storeIds") List<Long> storeIds,
            @Param("issueTag") String issueTag, @Param("status") String status,
            @Param("category") String category, @Param("minRating") Short minRating,
            @Param("maxRating") Short maxRating, @Param("riskLevel") Short riskLevel, @Param("from") Instant from,
            @Param("to") Instant to, Pageable pageable);

    // ── FR-804: 브랜드 집계 ────────────────────────────────────────────

    @Query("SELECT COUNT(r), AVG(r.rating) FROM UnifiedReview r "
            + "WHERE r.storeId IN :storeIds AND r.writtenAt >= :from AND r.writtenAt < :to")
    List<Object[]> brandReviewCountAndAvgRating(@Param("storeIds") List<Long> storeIds, @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("SELECT r.rating, COUNT(r) FROM UnifiedReview r "
            + "WHERE r.storeId IN :storeIds AND r.writtenAt >= :from AND r.writtenAt < :to AND r.rating IS NOT NULL "
            + "GROUP BY r.rating")
    List<Object[]> brandRatingDistribution(@Param("storeIds") List<Long> storeIds, @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("SELECT a.category, COUNT(a) FROM ReviewAnalysis a JOIN UnifiedReview r ON r.id = a.reviewId "
            + "WHERE r.storeId IN :storeIds AND r.writtenAt >= :from AND r.writtenAt < :to GROUP BY a.category")
    List<Object[]> brandCategoryDistribution(@Param("storeIds") List<Long> storeIds, @Param("from") Instant from,
            @Param("to") Instant to);

    /** 브랜드 공통 이슈 태그 랭킹 — AnalyticsQueryRepository.issueTagRanking 을 다매장(IN)으로 확장한 버전. */
    @Query(value = """
            SELECT tag, COUNT(*) AS cnt
            FROM review_analysis a
            JOIN unified_review r ON r.id = a.review_id
            CROSS JOIN LATERAL unnest(a.issue_tags) AS tag
            WHERE r.store_id IN (:storeIds) AND r.written_at >= :from AND r.written_at < :to
            GROUP BY tag
            ORDER BY cnt DESC
            """, nativeQuery = true)
    List<Object[]> brandIssueTagRanking(@Param("storeIds") List<Long> storeIds, @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("SELECT COUNT(a) FROM ReviewAnalysis a JOIN UnifiedReview r ON r.id = a.reviewId "
            + "WHERE r.storeId IN :storeIds AND r.writtenAt >= :from AND r.writtenAt < :to")
    long analyzedReviewCount(@Param("storeIds") List<Long> storeIds, @Param("from") Instant from,
            @Param("to") Instant to);

    /** 이슈별 건수·영향 매장·평균 별점. 기간 비교는 같은 쿼리를 현재/직전 기간에 각각 실행한다. */
    @Query(value = """
            SELECT tag, COUNT(*) AS cnt, COUNT(DISTINCT r.store_id) AS stores, AVG(r.rating) AS avg_rating
            FROM review_analysis a
            JOIN unified_review r ON r.id = a.review_id
            CROSS JOIN LATERAL unnest(a.issue_tags) AS tag
            WHERE r.store_id IN (:storeIds) AND r.written_at >= :from AND r.written_at < :to
            GROUP BY tag
            """, nativeQuery = true)
    List<Object[]> brandIssueTagStats(@Param("storeIds") List<Long> storeIds, @Param("from") Instant from,
            @Param("to") Instant to);

    /** 고위험 사유 군집. 모델 태그가 아니라 저장된 risk_reasons만 사용한다. */
    @Query(value = """
            SELECT reason, COUNT(*) AS cnt, COUNT(DISTINCT r.store_id) AS stores
            FROM review_analysis a
            JOIN unified_review r ON r.id = a.review_id
            CROSS JOIN LATERAL unnest(a.risk_reasons) AS reason
            WHERE r.store_id IN (:storeIds) AND r.written_at >= :from AND r.written_at < :to
              AND a.risk_level >= 3
            GROUP BY reason
            ORDER BY cnt DESC, reason
            """, nativeQuery = true)
    List<Object[]> brandRiskClusters(@Param("storeIds") List<Long> storeIds, @Param("from") Instant from,
            @Param("to") Instant to);

    @Query(value = """
            SELECT COUNT(*) AS cnt, COUNT(DISTINCT r.store_id) AS stores
            FROM review_analysis a
            JOIN unified_review r ON r.id = a.review_id
            WHERE r.store_id IN (:storeIds) AND r.written_at >= :from AND r.written_at < :to
              AND a.risk_level >= 3
            """, nativeQuery = true)
    List<Object[]> brandHighRiskSummary(@Param("storeIds") List<Long> storeIds, @Param("from") Instant from,
            @Param("to") Instant to);

    /** 주문 메뉴와 이슈의 동시 출현 상위 10개. 별도 AI 호출 없이 기존 JSONB/태그를 집계한다. */
    @Query(value = """
            SELECT menu, tag, COUNT(*) AS cnt, COUNT(DISTINCT r.store_id) AS stores, AVG(r.rating) AS avg_rating
            FROM review_analysis a
            JOIN unified_review r ON r.id = a.review_id
            CROSS JOIN LATERAL jsonb_array_elements_text(r.ordered_menus) AS menu
            CROSS JOIN LATERAL unnest(a.issue_tags) AS tag
            WHERE r.store_id IN (:storeIds) AND r.written_at >= :from AND r.written_at < :to
              AND menu <> ''
            GROUP BY menu, tag
            ORDER BY cnt DESC, stores DESC, menu, tag
            LIMIT 10
            """, nativeQuery = true)
    List<Object[]> brandMenuIssues(@Param("storeIds") List<Long> storeIds, @Param("from") Instant from,
            @Param("to") Instant to);

    @Query(value = """
            SELECT (r.written_at AT TIME ZONE 'Asia/Seoul')::date AS day,
                   COUNT(*) AS analyzed,
                   COUNT(*) FILTER (WHERE cardinality(a.issue_tags) > 0) AS issue_reviews,
                   COUNT(*) FILTER (WHERE a.risk_level >= 3) AS high_risk
            FROM review_analysis a
            JOIN unified_review r ON r.id = a.review_id
            WHERE r.store_id IN (:storeIds) AND r.written_at >= :from AND r.written_at < :to
            GROUP BY day
            ORDER BY day
            """, nativeQuery = true)
    List<Object[]> brandDailyRiskTrend(@Param("storeIds") List<Long> storeIds, @Param("from") Instant from,
            @Param("to") Instant to);

    /** 매장별 비교표용 — 기간 내 리뷰수·평균별점. */
    @Query("SELECT r.storeId, COUNT(r), AVG(r.rating) FROM UnifiedReview r "
            + "WHERE r.storeId IN :storeIds AND r.writtenAt >= :from AND r.writtenAt < :to GROUP BY r.storeId")
    List<Object[]> perStorePeriodReviewStats(@Param("storeIds") List<Long> storeIds, @Param("from") Instant from,
            @Param("to") Instant to);

    /** 매장별 비교표용 — 기간 내 리뷰당 최신 초안 상태 건수(답글 완료율 산출용, AnalyticsService.summary 와 동일 기준). */
    @Query("""
            SELECT d.storeId, d.status, COUNT(d) FROM ReplyDraft d JOIN UnifiedReview r ON r.id = d.reviewId
            WHERE r.storeId IN :storeIds AND r.writtenAt >= :from AND r.writtenAt < :to
              AND d.id IN (SELECT MAX(d2.id) FROM ReplyDraft d2 WHERE d2.reviewId = d.reviewId)
            GROUP BY d.storeId, d.status
            """)
    List<Object[]> perStorePeriodDraftStatusCounts(@Param("storeIds") List<Long> storeIds, @Param("from") Instant from,
            @Param("to") Instant to);
}
