package com.storemanager.api.billing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /** 매장당 CANCELED 가 아닌 구독은 최대 1건(uq_sub_store, docs/11 §2.7). */
    Optional<Subscription> findByStoreIdAndStatusNot(Long storeId, String status);

    /** 청구 배치(B3) 대상 — 기간이 끝난 ACTIVE 구독. */
    List<Subscription> findByStatusAndCurrentPeriodEndLessThanEqual(String status, Instant now);
}
