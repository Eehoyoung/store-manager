package com.storemanager.api.notify;

import java.time.Instant;
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
    Optional<NotificationLog> findFirstByChannelAndStatusAndNextAttemptAtLessThanEqualOrderById(
            String channel, String status, Instant now);

    Optional<NotificationLog> findByProviderMessageId(String providerMessageId);

    /**
     * 보유기간 파기(DataRetentionScheduler) — payload 에 매장명·별점·리뷰 원문·차단 사유가 실려
     * 있으므로(CLAUDE.md 알림 채널 절) 리뷰 파생 데이터다. 발송 이력 자체(행)는 "언제 무엇을
     * 보냈는가"를 답하기 위해 남기고, 개인정보가 든 payload 만 비운다. HIGH_RISK_REVIEW 알림은
     * refType='UNIFIED_REVIEW', refId=review_id 로 발행되므로(LoggingNotifier/DraftService)
     * 리뷰와 같은 배치로 지울 수 있다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE notification_log SET payload = '{}'::jsonb
             WHERE ref_type = 'UNIFIED_REVIEW' AND ref_id IN (:reviewIds)
            """, nativeQuery = true)
    int clearPayloadForReviews(@Param("reviewIds") java.util.Collection<Long> reviewIds);
}
