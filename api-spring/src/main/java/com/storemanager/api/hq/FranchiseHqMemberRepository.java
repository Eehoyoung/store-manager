package com.storemanager.api.hq;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FranchiseHqMemberRepository extends JpaRepository<FranchiseHqMember, Long> {

    boolean existsByUserIdAndBrandName(Long userId, String brandName);

    @Query("SELECT f.brandName FROM FranchiseHqMember f WHERE f.userId = :userId ORDER BY f.brandName")
    List<String> findBrandNamesByUserId(@Param("userId") Long userId);
}
