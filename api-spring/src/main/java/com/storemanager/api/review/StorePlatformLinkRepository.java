package com.storemanager.api.review;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorePlatformLinkRepository extends JpaRepository<StorePlatformLink, Long> {

    Optional<StorePlatformLink> findByPlatformAndPlatformStoreId(String platform, String platformStoreId);

    List<StorePlatformLink> findByAccountIdOrderByCreatedAtAsc(Long accountId);

    boolean existsByPlatformAndPlatformStoreId(String platform, String platformStoreId);

    void deleteByAccountId(Long accountId);
}
