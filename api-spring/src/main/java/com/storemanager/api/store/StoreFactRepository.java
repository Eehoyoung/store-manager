package com.storemanager.api.store;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreFactRepository extends JpaRepository<StoreFact, StoreFact.Key> {
    List<StoreFact> findByStoreId(Long storeId);
}
