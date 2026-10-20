package com.storemanager.api.crypto;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformAccountRepository extends JpaRepository<PlatformAccount, Long> {

    List<PlatformAccount> findByOwnerIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long ownerId);

    Optional<PlatformAccount> findByPublicIdAndOwnerIdAndRevokedAtIsNull(UUID publicId, Long ownerId);

    Optional<PlatformAccount> findByIdAndOwnerIdAndRevokedAtIsNull(Long id, Long ownerId);

    List<PlatformAccount> findByIntendedStoreIdAndPlatformInAndRevokedAtIsNull(Long storeId,
            java.util.Collection<String> platforms);

    Optional<PlatformAccount> findByIdAndRevokedAtIsNull(Long id);

    boolean existsByPlatformAndLoginIdAndRevokedAtIsNull(String platform, String loginId);
}
