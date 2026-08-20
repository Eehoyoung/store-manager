package com.storemanager.api.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmailIgnoreCaseAndDeletedAtIsNull(String email);

    Optional<AppUser> findByPublicId(UUID publicId);
}
