package com.storemanager.api.agreement;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAgreementRepository extends JpaRepository<UserAgreement, Long> {
    List<UserAgreement> findByUserIdOrderByCreatedAtDesc(Long userId);
}
