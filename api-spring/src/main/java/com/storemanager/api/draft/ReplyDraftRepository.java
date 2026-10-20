package com.storemanager.api.draft;

import java.time.Instant;
import java.util.List;
import java.util.Collection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface ReplyDraftRepository extends JpaRepository<ReplyDraft, Long> {

    java.util.Optional<ReplyDraft> findByPublicId(java.util.UUID publicId);

    boolean existsByReviewIdAndStatusIn(Long reviewId, Collection<String> statuses);

    /** 결제 재개 후 보류 답글을 찾는다(BillingService). 매장의 BLOCKED 전체를 가져와 가드레일 플래그로 가른다. */
    List<ReplyDraft> findByStoreIdAndStatus(Long storeId, String status);

    /** G7 비교용 게시 이력. 조회 장애가 생성 트랜잭션을 롤백시키지 않도록 분리한다. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    @Query("SELECT d.content FROM ReplyDraft d WHERE d.storeId = :storeId AND d.status = 'PUBLISHED' "
            + "AND d.publishedAt >= :since ORDER BY d.publishedAt DESC, d.id DESC")
    List<String> findRecentPublishedContents(@Param("storeId") Long storeId, @Param("since") Instant since,
            Pageable pageable);

    /** 게시 스케줄러(S9) — SCHEDULED 이고 예약 시각이 지난 것을 오래된 순으로 최대 100건. */
    @Query("SELECT d FROM ReplyDraft d WHERE d.status = 'SCHEDULED' AND d.scheduledAt <= :now ORDER BY d.scheduledAt ASC")
    List<ReplyDraft> findDueForPublish(@Param("now") Instant now, Pageable pageable);

    /**
     * 보유기간 파기(DataRetentionScheduler) — 파생 데이터라 리뷰와 같은 기준(purge_after)으로 지운다.
     * privacy.md 7장 "리뷰·분석·답글 및 그 복제·파생 데이터".
     */
    long deleteByReviewIdIn(Collection<Long> reviewIds);

    /** 선택형 요금 하향 승격 - 빠진 배달앱의 SCHEDULED 초안. */
    @Query(value = """
            SELECT d.* FROM reply_draft d JOIN unified_review r ON r.id = d.review_id
             WHERE d.store_id = :storeId AND d.status = 'SCHEDULED' AND r.platform IN (:platforms)
            """, nativeQuery = true)
    List<ReplyDraft> findScheduledByStoreAndPlatforms(@Param("storeId") Long storeId,
            @Param("platforms") Collection<String> platforms);

    /** 선택형 요금 재선택 승격 - 다시 열린 배달앱의 SCHEDULED 초안과 PLATFORM_NOT_SELECTED 로 막힌 초안. */
    @Query(value = """
            SELECT d.* FROM reply_draft d JOIN unified_review r ON r.id = d.review_id
             WHERE d.store_id = :storeId AND r.platform IN (:platforms)
               AND (d.status = 'SCHEDULED'
                    OR (d.status = 'BLOCKED' AND 'PLATFORM_NOT_SELECTED' = ANY(d.guardrail_flags)))
            """, nativeQuery = true)
    List<ReplyDraft> findReselectCandidates(@Param("storeId") Long storeId,
            @Param("platforms") Collection<String> platforms);
}
