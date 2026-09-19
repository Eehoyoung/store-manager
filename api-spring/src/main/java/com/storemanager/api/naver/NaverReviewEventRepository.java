package com.storemanager.api.naver;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface NaverReviewEventRepository extends JpaRepository<NaverReviewEvent, Long> {

    Optional<NaverReviewEvent> findByStoreIdAndReviewHash(Long storeId, String reviewHash);

    List<NaverReviewEvent> findByStoreIdAndReviewHashIn(Long storeId, Collection<String> reviewHashes);

    long countByStoreIdAndStatus(Long storeId, String status);

    /**
     * G7 비교용 네이버 자체 게시 이력. ReplyDraftRepository.findRecentPublishedContents 와 같은
     * 기준(최근 30일, 최대 20건, 최신순)이지만 배달 게시 이력을 참조하지 않고 naver_review_event
     * 자신의 POSTED 건만 본다 — 네이버 패키지가 draft 패키지에 의존하지 않게 하기 위함이다.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    @Query("SELECT e.draftContent FROM NaverReviewEvent e WHERE e.storeId = :storeId AND e.status = 'POSTED' "
            + "AND e.postedAt >= :since AND e.draftContent IS NOT NULL ORDER BY e.postedAt DESC, e.id DESC")
    List<String> findRecentPostedContents(@Param("storeId") Long storeId, @Param("since") Instant since,
            Pageable pageable);
}
