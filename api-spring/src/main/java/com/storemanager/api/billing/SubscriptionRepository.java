package com.storemanager.api.billing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /** 매장당 CANCELED 가 아닌 구독은 최대 1건(uq_sub_store, docs/11 §2.7). */
    Optional<Subscription> findByStoreIdAndStatusNot(Long storeId, String status);

    long countByPromotionCode(String promotionCode);

    /** 정기 갱신 스케줄러(B) 대상 — 결제예정일이 내일 이전인 구독. 실제 청구 여부는 서비스가 판정한다. */
    List<Subscription> findByNextBillingAtBefore(Instant tomorrow);

    /**
     * 청구 직전 매장 단위 잠금(조건부 UPDATE). PG 호출을 트랜잭션 밖에서 하므로 행 잠금(SELECT FOR UPDATE)
     * 대신 이 조건부 UPDATE로 동시 결제를 막는다 — 두 번 누르면 두 번 결제되는 사고를 막는다.
     */
    @Modifying
    @Query("update Subscription s set s.billingLockUntil = :until "
            + "where s.storeId = :storeId and (s.billingLockUntil is null or s.billingLockUntil < :now)")
    int lockBilling(@Param("storeId") Long storeId, @Param("now") Instant now, @Param("until") Instant until);

    @Modifying
    @Query("update Subscription s set s.billingLockUntil = null where s.storeId = :storeId")
    int unlockBilling(@Param("storeId") Long storeId);
}
