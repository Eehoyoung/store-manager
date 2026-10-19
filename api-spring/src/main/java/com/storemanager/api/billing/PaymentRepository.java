package com.storemanager.api.billing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Page<Payment> findBySubscriptionIdOrderByCreatedAtDesc(Long subscriptionId, Pageable pageable);

    /** 이중청구 방지 — 같은 paymentId(pgTxId)로 다시 부르면 기존 PENDING 행을 재사용한다. */
    Optional<Payment> findByPgTxId(String pgTxId);

    /** 결과를 모르는(PENDING) 결제가 있는지 - 있으면 상품 변경을 막는다. */
    boolean existsBySubscriptionIdAndStatus(Long subscriptionId, String status);
}
