package com.storemanager.api.franchise;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FranchiseBrandRepository extends JpaRepository<FranchiseBrand, String> {

    Optional<FranchiseBrand> findByBrandName(String brandName);

    List<FranchiseBrand> findAllByOrderByBrandNameAsc();
}
