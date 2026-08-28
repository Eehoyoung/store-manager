package com.storemanager.api.draft;

import java.time.Instant;
import java.util.List;
import java.util.Collection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReplyDraftRepository extends JpaRepository<ReplyDraft, Long> {

    java.util.Optional<ReplyDraft> findByPublicId(java.util.UUID publicId);

    boolean existsByReviewIdAndStatusIn(Long reviewId, Collection<String> statuses);

    /** 게시 스케줄러(S9) — SCHEDULED 이고 예약 시각이 지난 것을 오래된 순으로 최대 100건. */
    @Query("SELECT d FROM ReplyDraft d WHERE d.status = 'SCHEDULED' AND d.scheduledAt <= :now ORDER BY d.scheduledAt ASC")
    List<ReplyDraft> findDueForPublish(@Param("now") Instant now, Pageable pageable);

    /**
     * 보유기간 파기(DataRetentionScheduler) — 파생 데이터라 리뷰와 같은 기준(purge_after)으로 지운다.
     * privacy.md §7 "리뷰·분석·답글 및 그 복제·파생 데이터".
     */
    long deleteByReviewIdIn(Collection<Long> reviewIds);
}
