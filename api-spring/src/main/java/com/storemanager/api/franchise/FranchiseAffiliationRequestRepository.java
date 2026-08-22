package com.storemanager.api.franchise;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FranchiseAffiliationRequestRepository extends JpaRepository<FranchiseAffiliationRequest, Long> {
    List<FranchiseAffiliationRequest> findByStatusOrderByRequestedAtAsc(String status);
    Optional<FranchiseAffiliationRequest> findByPublicId(UUID publicId);
}
