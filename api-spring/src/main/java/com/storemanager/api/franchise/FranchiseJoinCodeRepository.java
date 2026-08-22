package com.storemanager.api.franchise;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FranchiseJoinCodeRepository extends JpaRepository<FranchiseJoinCode, Long> {

    Optional<FranchiseJoinCode> findByCodeHashAndActiveTrue(String codeHash);

    boolean existsByBrandName(String brandName);
}
