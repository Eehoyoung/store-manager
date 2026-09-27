package com.storemanager.api.user;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmailIgnoreCaseAndDeletedAtIsNull(String email);

    Optional<AppUser> findByPublicId(UUID publicId);

    Optional<AppUser> findByPublicIdAndDeletedAtIsNull(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.publicId = :publicId and u.deletedAt is null")
    Optional<AppUser> findActiveByPublicIdForUpdate(@Param("publicId") UUID publicId);
}
