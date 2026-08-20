package com.storemanager.api.review;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnifiedReviewRepository extends JpaRepository<UnifiedReview, Long> {

    Optional<UnifiedReview> findByPlatformAndPlatformReviewId(String platform, String platformReviewId);
}
