package com.storemanager.api.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, Long> {

    List<Store> findByOwnerIdAndDeletedAtIsNull(Long ownerId);

    Optional<Store> findByPublicIdAndDeletedAtIsNull(UUID publicId);
}
