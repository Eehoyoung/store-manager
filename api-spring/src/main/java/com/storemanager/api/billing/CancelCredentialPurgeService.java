package com.storemanager.api.billing;

import java.time.Instant;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사장님이 해지(cancellation_requested_at 기록)하고 이용 유예(약관 9.5, D+3 00:00)가 끝난 매장의 배달앱 자격증명을 파기한다.
 * 유예 기준은 Subscription.serviceUntil(restrictedFrom) 을 그대로 쓴다. 운영자 활성화(자동결제 꺼짐)·SUSPENDED·
 * 자동결제가 켜진 미납 제한은 해지가 아니므로 대상이 아니다.
 * 이미 파기된 계정은 DowngradeCredentialCleanup 이 건너뛰므로 매일 돌려도 멱등이다. 매장별 트랜잭션이라 한 건 실패가 전체를 되돌리지 않는다.
 */
@Service
public class CancelCredentialPurgeService {

    private static final Logger log = LoggerFactory.getLogger(CancelCredentialPurgeService.class);

    private final SubscriptionRepository subscriptions;
    private final DowngradeCredentialCleanup cleanup;
    private final TransactionTemplate tx;

    public CancelCredentialPurgeService(SubscriptionRepository subscriptions, DowngradeCredentialCleanup cleanup,
            TransactionTemplate tx) {
        this.subscriptions = subscriptions;
        this.cleanup = cleanup;
        this.tx = tx;
    }

    public int purgeEnded(Instant now) {
        var ended = subscriptions.findCancelEnded(now);
        // ponytail: 매일 해지 만료 전건을 훑는다(파기 후엔 계정 조회가 비어 가볍다). 건수가 커지면 purged_at 표식을 둔다.
        for (Subscription s : ended) {
            Long storeId = s.getStoreId();
            try {
                tx.executeWithoutResult(st -> cleanup.onRemoved(storeId,
                        new HashSet<>(Subscription.DELIVERY_PLATFORMS), "CREDENTIAL_REVOKED_ON_CANCEL"));
            } catch (RuntimeException e) {
                log.error("해지 자격증명 파기 실패 storeId={}", storeId); // 예외 본문은 값을 담을 수 있어 남기지 않는다
            }
        }
        return ended.size();
    }
}
