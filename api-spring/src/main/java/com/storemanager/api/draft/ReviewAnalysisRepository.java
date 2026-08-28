package com.storemanager.api.draft;

import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewAnalysisRepository extends JpaRepository<ReviewAnalysis, Long> {

    /**
     * 보유기간 파기(DataRetentionScheduler) — PK 가 review_id 이므로 그대로 삭제 조건이 된다.
     * privacy.md §7 "리뷰·분석·답글 및 그 복제·파생 데이터".
     */
    long deleteByReviewIdIn(Collection<Long> reviewIds);
}
