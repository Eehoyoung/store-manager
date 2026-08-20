package com.storemanager.api.analytics;

import com.storemanager.api.review.UnifiedReview;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 대시보드 집계 전용 저장소 (Sprint 5 A1/A2).
 * ★ 전 리뷰를 메모리로 읽어와 세지 않는다 — 모든 집계는 DB 에서 GROUP BY/COUNT/AVG 로 계산한다(오케스트레이터 지시 A1).
 * ★ '최신 초안 상태' 판정은 리뷰당 reply_draft.id 최댓값(=최신, BIGSERIAL) 으로 상관 서브쿼리를 쓴다
 * (ReviewQueryRepository 의 목록 조회와 동일한 패턴).
 */
public interface AnalyticsQueryRepository extends JpaRepository<UnifiedReview, Long> {

    @Query("SELECT COUNT(r), AVG(r.rating) FROM UnifiedReview r "
            + "WHERE r.storeId = :storeId AND r.writtenAt >= :from AND r.writtenAt < :to")
    List<Object[]> reviewCountAndAvgRating(@Param("storeId") Long storeId, @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("SELECT r.rating, COUNT(r) FROM UnifiedReview r "
            + "WHERE r.storeId = :storeId AND r.writtenAt >= :from AND r.writtenAt < :to AND r.rating IS NOT NULL "
            + "GROUP BY r.rating")
    List<Object[]> ratingDistribution(@Param("storeId") Long storeId, @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("SELECT a.category, COUNT(a) FROM ReviewAnalysis a JOIN UnifiedReview r ON r.id = a.reviewId "
            + "WHERE r.storeId = :storeId AND r.writtenAt >= :from AND r.writtenAt < :to GROUP BY a.category")
    List<Object[]> categoryDistribution(@Param("storeId") Long storeId, @Param("from") Instant from,
            @Param("to") Instant to);

    /** 리뷰당 최신 초안의 상태별 건수 (게시완료율 집계용 — 기간 기준, T-26). */
    @Query("""
            SELECT d.status, COUNT(d) FROM ReplyDraft d JOIN UnifiedReview r ON r.id = d.reviewId
            WHERE r.storeId = :storeId AND r.writtenAt >= :from AND r.writtenAt < :to
              AND d.id IN (SELECT MAX(d2.id) FROM ReplyDraft d2 WHERE d2.reviewId = d.reviewId)
            GROUP BY d.status
            """)
    List<Object[]> latestDraftStatusCounts(@Param("storeId") Long storeId, @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * 리뷰당 최신 초안의 상태별 건수 — 기간 필터 없음, 매장 전체(T-26 현재 상태 지표: pendingCount/blockedCount).
     * 40일 전에 작성된 리뷰라도 아직 검수 대기(DRAFT)/차단(BLOCKED)이면 반드시 잡혀야 한다 — 기간으로 잘라내면
     * 일감이 화면에서 사라진다.
     */
    @Query("""
            SELECT d.status, COUNT(d) FROM ReplyDraft d JOIN UnifiedReview r ON r.id = d.reviewId
            WHERE r.storeId = :storeId
              AND d.id IN (SELECT MAX(d2.id) FROM ReplyDraft d2 WHERE d2.reviewId = d.reviewId)
            GROUP BY d.status
            """)
    List<Object[]> latestDraftStatusCountsAllTime(@Param("storeId") Long storeId);

    /**
     * 고위험(risk_level>=3)이면서 아직 처리되지 않은 리뷰 수 — 기간 필터 없음, 매장 전체(T-26).
     * ★ "초안이 BLOCKED 인 것" 이 아니라 "종결되지 않은 것" 을 센다. 초안이 아예 없는 고위험 리뷰
     * (AI 호출 실패·미생성)가 가장 위험한 미처리 건인데, BLOCKED 존재를 조건으로 걸면 그게 0 으로
     * 보인다. 종결(PUBLISHED/ALREADY_REPLIED)된 건만 빼고 나머지는 전부 일감으로 센다.
     */
    @Query("""
            SELECT COUNT(a) FROM ReviewAnalysis a JOIN UnifiedReview r ON r.id = a.reviewId
            WHERE r.storeId = :storeId AND a.riskLevel >= 3
              AND NOT EXISTS (
                SELECT d FROM ReplyDraft d
                WHERE d.reviewId = r.id
                  AND d.id = (SELECT MAX(d2.id) FROM ReplyDraft d2 WHERE d2.reviewId = r.id)
                  AND d.status IN ('PUBLISHED', 'ALREADY_REPLIED')
              )
            """)
    long highRiskPendingCount(@Param("storeId") Long storeId);

    /** 일자별 리뷰수·평균별점. KST 달력일 기준(written_at 은 REVIEWDATE 기준 KST 자정, docs/11 §2.4). */
    @Query(value = "SELECT (written_at AT TIME ZONE 'Asia/Seoul')::date AS day, COUNT(*), AVG(rating) "
            + "FROM unified_review WHERE store_id = :storeId AND written_at >= :from AND written_at < :to "
            + "GROUP BY day ORDER BY day", nativeQuery = true)
    List<Object[]> dailyReviewStats(@Param("storeId") Long storeId, @Param("from") Instant from,
            @Param("to") Instant to);

    /** 일자별 게시 완료 건수. published_at(실제 게시 시각) 의 KST 달력일 기준. */
    @Query(value = "SELECT (published_at AT TIME ZONE 'Asia/Seoul')::date AS day, COUNT(*) "
            + "FROM reply_draft WHERE store_id = :storeId AND status = 'PUBLISHED' "
            + "AND published_at >= :from AND published_at < :to GROUP BY day ORDER BY day", nativeQuery = true)
    List<Object[]> dailyPublishedCounts(@Param("storeId") Long storeId, @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * 이슈 태그 랭킹 (B1). review_analysis.issue_tags(TEXT[]) 를 unnest 로 펼쳐 DB 에서 빈도순 집계한다.
     * 태그별 평균 별점·최근 발생일도 함께 낸다(unified_review 조인).
     */
    @Query(value = """
            SELECT tag, COUNT(*) AS cnt, AVG(r.rating) AS avg_rating, MAX(r.written_at) AS last_at
            FROM review_analysis a
            JOIN unified_review r ON r.id = a.review_id
            CROSS JOIN LATERAL unnest(a.issue_tags) AS tag
            WHERE r.store_id = :storeId AND r.written_at >= :from AND r.written_at < :to
            GROUP BY tag
            ORDER BY cnt DESC
            """, nativeQuery = true)
    List<Object[]> issueTagRanking(@Param("storeId") Long storeId, @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * 메뉴별 만족도 (B2). unified_review.ordered_menus(JSONB 문자열배열) 를 jsonb_array_elements_text 로 펼쳐 집계한다.
     * ordered_menus 가 빈 배열('[]')인 리뷰는 CROSS JOIN LATERAL 특성상 자동으로 결과에서 빠진다
     * (주문 메뉴가 없는 리뷰 제외 — 별도 필터 불필요).
     */
    @Query(value = """
            SELECT menu, COUNT(*) AS cnt, AVG(r.rating) AS avg_rating
            FROM unified_review r
            CROSS JOIN LATERAL jsonb_array_elements_text(r.ordered_menus) AS menu
            WHERE r.store_id = :storeId AND r.written_at >= :from AND r.written_at < :to
            GROUP BY menu
            ORDER BY cnt DESC
            """, nativeQuery = true)
    List<Object[]> menuSatisfaction(@Param("storeId") Long storeId, @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * 응답 성과 (B3). 리뷰당 최신 초안(id 최댓값) 기준 단일 행 집계.
     * ★ T-26: 기간 필터를 written_at 이 아니라 d.published_at(그 기간에 실제로 게시한 답글) 기준으로 바꿨다 —
     * "리뷰가 언제 쓰였는지"가 아니라 "우리가 언제 답글을 게시했는지"로 성과를 잰다. 리뷰가 40일 전에 쓰였어도
     * 이번 기간에 막 게시됐다면 이번 기간 실적으로 잡힌다. 결과적으로 published_at IS NULL 인 초안(DRAFT 등
     * ★ 모집단은 "이 기간에 수집된 리뷰"(collected_at)다. published_at 으로 모집단을 잡으면
     * 분모가 '이미 게시된 것' 이 되어 완료율이 구조적으로 항상 100% 가 된다 — 지표가 죽는다.
     * collected_at 은 일감이 들어온 시각이고 실제 시각 정보를 가진다.
     * 평균 응답시간은 여전히 published_at - collected_at 으로 계산한다 — written_at(REVIEWDATE) 은
     * 시각 정보가 없어 기준으로 쓸 수 없다(CLAUDE.md 데이터처리 1번).
     */
    @Query(value = """
            SELECT
              COUNT(*) AS total_reviews,
              COUNT(*) FILTER (WHERE d.status IN ('PUBLISHED','ALREADY_REPLIED')) AS completed,
              COUNT(*) FILTER (WHERE d.status = 'PUBLISHED') AS auto_published,
              AVG(EXTRACT(EPOCH FROM (d.published_at - r.collected_at)) / 60.0)
                  FILTER (WHERE d.status = 'PUBLISHED' AND d.published_at IS NOT NULL) AS avg_response_minutes,
              COUNT(*) FILTER (WHERE d.retry_count > 0) AS retried
            FROM unified_review r
            LEFT JOIN reply_draft d ON d.id = (SELECT MAX(d2.id) FROM reply_draft d2 WHERE d2.review_id = r.id)
            WHERE r.store_id = :storeId AND r.collected_at >= :from AND r.collected_at < :to
            """, nativeQuery = true)
    List<Object[]> responsePerformance(@Param("storeId") Long storeId, @Param("from") Instant from,
            @Param("to") Instant to);
}
