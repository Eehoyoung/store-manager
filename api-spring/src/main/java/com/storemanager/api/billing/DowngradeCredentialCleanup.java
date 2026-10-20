package com.storemanager.api.billing;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.crypto.PlatformAccountRepository;
import com.storemanager.api.review.StorePlatformLink;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.store.StoreServiceGate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 상품을 낮춘 적용일(승격)에 빠진 배달앱의 연동·자격증명을 지운다(약관 9.1의3 제6항).
 * 호출자(결제 확정·체험 중 변경) 트랜잭션 안에서 DB 만 만진다. 리뷰·답글 기록은 그대로 둔다
 * <p>★ store_platform_link 행은 지우지 않는다 - unified_review.link_id 가 NOT NULL FK(ON DELETE 없음)라 리뷰가 있는
 * 연동은 지울 수 없다. 자격증명을 파기(CredentialService.revoke: revoked_at + 암호문 비움)하면 워커가 그 계정을 더 쓰지
 * 않고, 수집 적재는 StoreServiceGate 가 막는다.
 * <p>한 계정(PlatformAccount)을 여러 매장이 쓸 수 있다 - 다른 매장 중 그 배달앱을 선택한 서비스 가능 매장이
 * 있으면 자격증명은 남긴다.
 */
@Component
public class DowngradeCredentialCleanup {

    private final StorePlatformLinkRepository links;
    private final PlatformAccountRepository accounts;
    private final CredentialService credentialService;
    private final StoreRepository stores;
    private final StoreServiceGate serviceGate;
    private final AuditLogRepository auditLogs;

    public DowngradeCredentialCleanup(StorePlatformLinkRepository links, PlatformAccountRepository accounts,
            CredentialService credentialService, StoreRepository stores, StoreServiceGate serviceGate,
            AuditLogRepository auditLogs) {
        this.links = links;
        this.accounts = accounts;
        this.credentialService = credentialService;
        this.stores = stores;
        this.serviceGate = serviceGate;
        this.auditLogs = auditLogs;
    }

    public void onRemoved(Long storeId, Set<String> removed) {
        if (removed.isEmpty()) {
            return;
        }
        List<StorePlatformLink> mine = links.findByStoreIdAndPlatformIn(storeId, removed);
        Map<Long, PlatformAccount> targets = new LinkedHashMap<>();
        for (StorePlatformLink l : mine) {
            accounts.findByIdAndRevokedAtIsNull(l.getAccountId()).ifPresent(a -> targets.put(a.getId(), a));
        }
        for (PlatformAccount a : accounts.findByIntendedStoreIdAndPlatformInAndRevokedAtIsNull(storeId, removed)) {
            targets.putIfAbsent(a.getId(), a);
        }
        for (PlatformAccount a : targets.values()) {
            boolean usedElsewhere = links.findByAccountIdOrderByCreatedAtAsc(a.getId()).stream()
                    .filter(l -> !l.getStoreId().equals(storeId))
                    .anyMatch(l -> stores.findById(l.getStoreId())
                            .map(s -> serviceGate.isServiceable(s, a.getPlatform())).orElse(false));
            if (usedElsewhere) {
                continue;
            }
            credentialService.revoke(a);
            auditLogs.save(AuditLog.builder().actorType("SYSTEM").action("CREDENTIAL_REVOKED_ON_DOWNGRADE")
                    .targetType("PLATFORM_ACCOUNT").targetId(a.getId()).build());
        }
    }
}
