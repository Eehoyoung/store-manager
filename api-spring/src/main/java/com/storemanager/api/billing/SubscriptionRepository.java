package com.storemanager.api.billing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /** 매장당 CANCELED 가 아닌 구독은 최대 1건(uq_sub_store, docs/11 2.7장). */
    Optional<Subscription> findByStoreIdAndStatusNot(Long storeId, String status);

    /** 상품 변경 전용 - 행을 잠가 읽는다(갱신의 조건부 UPDATE 잠금과 순서가 갈린다). */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.storeId = :storeId and s.status <> 'CANCELED'")
    Optional<Subscription> findActiveForUpdate(@Param("storeId") Long storeId);

    /** 매장 기준 마지막 상품 변경 시각 - 해지 후 재가입(새 구독 행)이어도 월 1회가 이어진다. */
    @Query("select max(s.planChangedAt) from Subscription s where s.storeId = :storeId")
    Instant findLastPlanChangedAt(@Param("storeId") Long storeId);

    /** 해지(자동결제 꺼짐)했고 이용 유예(service_until)까지 끝난 구독 - 자격증명 파기 대상. */
    @Query("select s from Subscription s where s.cancellationRequestedAt is not null and s.autoRenew = false "
            + "and s.status not in ('CANCELED', 'SUSPENDED') "
            + "and s.serviceUntil is not null and s.serviceUntil <= :now")
    List<Subscription> findCancelEnded(@Param("now") Instant now);

    /** 미납 해지 절차 대상(D120): 자동결제가 켜진 채 결제 실패로 이용 제한 시각이 지난 구독. 해지 접수·SUSPENDED·CANCELED 제외. */
    @Query("select s from Subscription s where s.cancellationRequestedAt is null and s.autoRenew = true "
            + "and s.renewalFailures > 0 and s.status not in ('CANCELED', 'SUSPENDED') "
            + "and s.serviceUntil is not null and s.serviceUntil <= :now")
    List<Subscription> findDunningTargets(@Param("now") Instant now);

    long countByPromotionCode(String promotionCode);

    /** 정기 갱신 스케줄러(B) 대상 — 결제예정일이 내일 이전인 구독. 실제 청구 여부는 서비스가 판정한다. */
    List<Subscription> findByNextBillingAtBefore(Instant tomorrow);

    /**
     * 유료 전환 사전고지 대상 — 체험 중이고, 자동결제가 켜져 있고(해지했으면 전환이 없다), 체험 종료가
     * {@code noticeFrom} 이전이며 아직 지나지 않았고, 아직 고지하지 않은 구독.
     */
    @Query("select s from Subscription s where s.status = 'TRIAL' and s.autoRenew = true "
            + "and s.billingKey is not null and s.trialNoticeSentAt is null "
            + "and s.trialEndsAt > :now and s.trialEndsAt <= :noticeFrom")
    List<Subscription> findTrialConversionNoticeDue(@Param("now") Instant now, @Param("noticeFrom") Instant noticeFrom);

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
