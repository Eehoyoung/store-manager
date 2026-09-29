package com.storemanager.api.hq;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FranchiseHqMemberRepository extends JpaRepository<FranchiseHqMember, Long> {

    boolean existsByUserIdAndBrandName(Long userId, String brandName);

    Optional<FranchiseHqMember> findByUserIdAndBrandName(Long userId, String brandName);

    Optional<FranchiseHqMember> findByPublicId(UUID publicId);

    List<FranchiseHqMember> findByBrandNameOrderByInvitedAtAsc(String brandName);

    long countByBrandName(String brandName);

    long countByBrandNameAndStatus(String brandName, String status);

    List<FranchiseHqMember> findByUserId(Long userId);

    /** 로그인 자격 판정(docs/26a auth.hqEligible) — 브랜드까지 ACTIVE 인 멤버십만. */
    @Query("SELECT m FROM FranchiseHqMember m JOIN FranchiseBrand b ON b.brandName = m.brandName "
            + "WHERE m.userId = :userId AND m.status = 'ACTIVE' AND b.status = 'ACTIVE'")
    List<FranchiseHqMember> findActiveEligibleMemberships(@Param("userId") Long userId);

    @Query("SELECT f.brandName FROM FranchiseHqMember f WHERE f.userId = :userId ORDER BY f.brandName")
    List<String> findBrandNamesByUserId(@Param("userId") Long userId);

    /** 브랜드 목록 화면의 "최근 본부 로그인" — 그 브랜드 담당자 전체 중 최댓값. */
    @Query("SELECT MAX(m.lastLoginAt) FROM FranchiseHqMember m WHERE m.brandName = :brandName")
    Instant maxLastLoginAtByBrand(@Param("brandName") String brandName);
}
