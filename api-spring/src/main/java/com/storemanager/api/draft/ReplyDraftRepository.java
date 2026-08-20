package com.storemanager.api.draft;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReplyDraftRepository extends JpaRepository<ReplyDraft, Long> {

    Page<ReplyDraft> findByStoreId(Long storeId, Pageable pageable);

    Page<ReplyDraft> findByStoreIdAndStatus(Long storeId, String status, Pageable pageable);

    /** 일괄승인 후보 조회용 — 페이징 없이 store 의 DRAFT 전체를 가져와 서비스 계층에서 필터링한다(S5 bulk-approve). */
    List<ReplyDraft> findByStoreIdAndStatus(Long storeId, String status);

    /** 게시 스케줄러(S9) — SCHEDULED 이고 예약 시각이 지난 것을 오래된 순으로 최대 100건. */
    @Query("SELECT d FROM ReplyDraft d WHERE d.status = 'SCHEDULED' AND d.scheduledAt <= :now ORDER BY d.scheduledAt ASC")
    List<ReplyDraft> findDueForPublish(@Param("now") Instant now, Pageable pageable);
}
