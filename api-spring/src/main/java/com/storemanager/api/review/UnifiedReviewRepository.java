package com.storemanager.api.review;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnifiedReviewRepository extends JpaRepository<UnifiedReview, Long> {

    Optional<UnifiedReview> findByPlatformAndPlatformReviewId(String platform, String platformReviewId);

    Optional<UnifiedReview> findByPublicId(UUID publicId);
}
