package com.storemanager.api.naver;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NaverReviewEventRepository extends JpaRepository<NaverReviewEvent, Long> {

    Optional<NaverReviewEvent> findByStoreIdAndReviewHash(Long storeId, String reviewHash);

    List<NaverReviewEvent> findByStoreIdAndReviewHashIn(Long storeId, Collection<String> reviewHashes);

    long countByStoreIdAndStatus(Long storeId, String status);
}
