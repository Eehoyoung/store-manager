package com.storemanager.api.franchise;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FranchiseAffiliationRequestRepository extends JpaRepository<FranchiseAffiliationRequest, Long> {
    List<FranchiseAffiliationRequest> findByStatusOrderByRequestedAtAsc(String status);

    List<FranchiseAffiliationRequest> findAllByOrderByRequestedAtDesc();

    Optional<FranchiseAffiliationRequest> findByPublicId(UUID publicId);

    @Query("SELECT COUNT(r) FROM FranchiseAffiliationRequest r JOIN FranchiseJoinCode c ON c.id = r.joinCodeId "
            + "WHERE c.brandName = :brandName AND r.status = 'PENDING'")
    long countPendingByBrand(@Param("brandName") String brandName);

    @Query("SELECT r FROM FranchiseAffiliationRequest r WHERE r.storeId = :storeId AND r.status = 'APPROVED' "
            + "ORDER BY r.decidedAt DESC")
    List<FranchiseAffiliationRequest> findApprovedByStoreIdOrderByDecidedAtDesc(@Param("storeId") Long storeId);
}
