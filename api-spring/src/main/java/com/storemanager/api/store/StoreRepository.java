package com.storemanager.api.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, Long> {

    List<Store> findByOwnerIdAndDeletedAtIsNull(Long ownerId);

    /** 운영자 화면용 전체 조회. 매장 수가 100 단위라 페이징 없이 둔다.
     *  ponytail: 매장이 수천이 되면 페이징으로 바꾼다. */
    List<Store> findByDeletedAtIsNullOrderByIdAsc();

    Optional<Store> findByPublicIdAndDeletedAtIsNull(UUID publicId);
}
