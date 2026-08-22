package com.storemanager.api.billing;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /** 매장당 CANCELED 가 아닌 구독은 최대 1건(uq_sub_store, docs/11 §2.7). */
    Optional<Subscription> findByStoreIdAndStatusNot(Long storeId, String status);

    /** 동시에 들어온 DELETE 요청도 최초 접수 한 건으로 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.storeId = :storeId and s.status <> 'CANCELED'")
    Optional<Subscription> findActiveishForUpdate(@Param("storeId") Long storeId);

    /** 청구 배치(B3) 대상 — 기간이 끝난 ACTIVE 구독. */
    List<Subscription> findByStatusAndCurrentPeriodEndLessThanEqual(String status, Instant now);
}
