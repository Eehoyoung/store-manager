package com.storemanager.api.notify;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    /** 미납 알림(B4) 단계별 중복발송 방지 — 같은 대상(refType/refId)에 같은 템플릿이 몇 번 발송됐는지 센다. */
    long countByRefTypeAndRefIdAndTemplate(String refType, Long refId, String template);

    @Modifying
    @Query(value = """
            INSERT INTO notification_log
                (user_id, store_id, channel, template, status, payload, ref_type, ref_id, next_attempt_at)
            VALUES
                (:userId, :storeId, 'ALIMTALK', 'HIGH_RISK_REVIEW', 'QUEUED', CAST(:payload AS jsonb),
                 :refType, :refId, now())
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int enqueueHighRiskIfAbsent(@Param("userId") Long userId, @Param("storeId") Long storeId,
            @Param("refType") String refType, @Param("refId") Long refId, @Param("payload") String payload);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<NotificationLog> findTop20ByChannelAndStatusAndNextAttemptAtLessThanEqualOrderById(
            String channel, String status, Instant now);

    Optional<NotificationLog> findByProviderMessageId(String providerMessageId);
}
