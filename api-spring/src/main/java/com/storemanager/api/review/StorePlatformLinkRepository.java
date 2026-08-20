package com.storemanager.api.review;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorePlatformLinkRepository extends JpaRepository<StorePlatformLink, Long> {

    Optional<StorePlatformLink> findByPlatformAndPlatformStoreId(String platform, String platformStoreId);
}
