package com.storemanager.api.draft;

import java.time.Instant;
import java.util.List;
import java.util.Collection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReplyDraftRepository extends JpaRepository<ReplyDraft, Long> {

    boolean existsByReviewIdAndStatusIn(Long reviewId, Collection<String> statuses);

    /** 게시 스케줄러(S9) — SCHEDULED 이고 예약 시각이 지난 것을 오래된 순으로 최대 100건. */
    @Query("SELECT d FROM ReplyDraft d WHERE d.status = 'SCHEDULED' AND d.scheduledAt <= :now ORDER BY d.scheduledAt ASC")
    List<ReplyDraft> findDueForPublish(@Param("now") Instant now, Pageable pageable);
}
