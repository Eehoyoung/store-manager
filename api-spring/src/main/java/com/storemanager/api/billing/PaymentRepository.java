package com.storemanager.api.billing;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** 이중청구 방지(B2/B3) — idempotency_key 존재 여부만 확인한다. */
    boolean existsByIdempotencyKey(String idempotencyKey);

    /** 입금코드 생성 시 PENDING 건과의 충돌만 피하면 된다(V11: 부분 유니크 인덱스와 동일 조건). */
    boolean existsByDepositCode(String depositCode);

    Page<Payment> findBySubscriptionIdOrderByCreatedAtDesc(Long subscriptionId, Pageable pageable);

    /** 미납 처리 배치(B4) 대상. */
    List<Payment> findByStatusAndDueAtBefore(String status, Instant now);
}
