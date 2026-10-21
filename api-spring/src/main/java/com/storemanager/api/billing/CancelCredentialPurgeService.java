package com.storemanager.api.billing;

import java.time.Instant;
import java.util.HashSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 해지 후 이용 유예(약관 9.5, D+3 00:00)가 끝난 매장의 배달앱 자격증명을 파기한다.
 * 유예 기준은 Subscription.serviceUntil(restrictedFrom) 을 그대로 쓴다. 자동결제가 켜진 미납 제한은 대상이 아니다.
 * 이미 파기된 계정은 DowngradeCredentialCleanup 이 건너뛰므로 매일 돌려도 멱등이다.
 */
@Service
public class CancelCredentialPurgeService {

    private final SubscriptionRepository subscriptions;
    private final DowngradeCredentialCleanup cleanup;

    public CancelCredentialPurgeService(SubscriptionRepository subscriptions, DowngradeCredentialCleanup cleanup) {
        this.subscriptions = subscriptions;
        this.cleanup = cleanup;
    }

    @Transactional
    public int purgeEnded(Instant now) {
        var ended = subscriptions.findCancelEnded(now);
        // ponytail: 매일 해지 만료 전건을 훑는다(파기 후엔 계정 조회가 비어 가볍다). 건수가 커지면 purged_at 표식을 둔다.
        for (Subscription s : ended) {
            cleanup.onRemoved(s.getStoreId(), new HashSet<>(Subscription.DELIVERY_PLATFORMS),
                    "CREDENTIAL_REVOKED_ON_CANCEL");
        }
        return ended.size();
    }
}
