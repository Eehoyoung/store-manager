package com.storemanager.api.agreement;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAgreementRepository extends JpaRepository<UserAgreement, Long> {
    List<UserAgreement> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 매장별 최신 유효 동의 조회 — HQ_DATA_SHARING(집계) 처럼 storeId 가 있는 동의용. */
    Optional<UserAgreement> findTopByUserIdAndStoreIdAndAgreementCodeOrderByCreatedAtDesc(Long userId, Long storeId,
            String agreementCode);

    /** 사용자 단위 최신 유효 동의 조회 — HQ_REVIEW_SHARING 처럼 storeId 가 없는 동의용. */
    Optional<UserAgreement> findTopByUserIdAndAgreementCodeOrderByCreatedAtDesc(Long userId, String agreementCode);
}
