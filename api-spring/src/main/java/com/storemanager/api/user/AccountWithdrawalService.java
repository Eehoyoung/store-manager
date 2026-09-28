package com.storemanager.api.user;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.billing.PortOneClient;
import com.storemanager.api.billing.Subscription;
import com.storemanager.api.billing.SubscriptionRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.crypto.PlatformAccountRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 회원 탈퇴 (개인정보보호법 제37조 — 처리정지·파기 요구권).
 *
 * <p>★ 탈퇴는 행 하나를 지우는 일이 아니다. 남겨두면 각각 다른 사고가 된다.
 * <ul>
 *   <li>구독을 안 끊으면 → 탈퇴한 사람에게 요금이 청구된다
 *   <li>매장 activated_at 을 안 지우면 → 수집·게시가 계속 돌아 비용이 나가고,
 *       탈퇴한 사람의 매장에 답글이 달린다
 *   <li>플랫폼 계정을 안 지우면 → 배달앱 자격증명이 그대로 남는다. 가장 위험하다
 *   <li>이메일을 안 풀면 → 같은 주소로 다시 가입할 수 없다
 * </ul>
 *
 * <p>★ 리뷰 데이터는 <b>여기서 지우지 않는다.</b> 작성자는 우리 회원이 아니라 배달앱 고객이고,
 * 이미 가명처리(author_hash)돼 있다. 보유기간이 지나면 {@code DataRetentionScheduler} 가
 * 파기한다 — 탈퇴 즉시 지우면 그 매장의 통계·감사 근거가 함께 사라진다.
 *
 * <p>★ 감사로그는 남긴다. "언제 누가 탈퇴했는가" 는 분쟁 시 우리를 지키는 기록이며,
 * 개인정보가 아니라 처리 이력이다.
 */
@Service
public class AccountWithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(AccountWithdrawalService.class);

    private final AppUserRepository appUserRepository;
    private final StoreRepository storeRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlatformAccountRepository platformAccountRepository;
    private final CredentialService credentialService;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final PortOneClient portOneClient;
    private final TransactionTemplate writes;

    public AccountWithdrawalService(AppUserRepository appUserRepository, StoreRepository storeRepository,
            SubscriptionRepository subscriptionRepository, PlatformAccountRepository platformAccountRepository,
            CredentialService credentialService, AuditLogRepository auditLogRepository,
            PasswordEncoder passwordEncoder, PortOneClient portOneClient,
            PlatformTransactionManager transactionManager) {
        this.appUserRepository = appUserRepository;
        this.storeRepository = storeRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.platformAccountRepository = platformAccountRepository;
        this.credentialService = credentialService;
        this.auditLogRepository = auditLogRepository;
        this.passwordEncoder = passwordEncoder;
        this.portOneClient = portOneClient;
        this.writes = new TransactionTemplate(transactionManager);
    }

    /**
     * 탈퇴 처리.
     *
     * @param password 본인 확인용. 탈퇴는 되돌릴 수 없으므로 비밀번호를 다시 받는다 —
     *                 세션이 탈취된 상태에서 계정이 삭제되면 복구할 방법이 없다.
     *
     *                 <p>★ 포트원 빌링키 삭제는 PG 호출이라 트랜잭션 커밋 뒤에 한다(BillingService 와
     *                 같은 규율 — PG 호출을 {@code @Transactional} 안에 넣지 않는다). 실패해도 로그만
     *                 남긴다 — 탈퇴 자체를 막을 이유는 아니다. 응답 본문·빌링키는 로그에 남기지 않는다.
     */
    public void withdraw(UUID publicId, String password) {
        List<String> billingKeysToDelete = writes.execute(status -> withdrawTx(publicId, password));
        for (String billingKey : billingKeysToDelete) {
            try {
                portOneClient.deleteBillingKey(billingKey);
            } catch (RuntimeException e) {
                log.warn("탈퇴 시 포트원 빌링키 삭제 실패: {}", e.getClass().getSimpleName());
            }
        }
    }

    private List<String> withdrawTx(UUID publicId, String password) {
        AppUser user = appUserRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }

        Instant now = Instant.now();

        // 1) 배달앱 자격증명부터 파기한다. 다른 단계가 실패해도 이것만은 먼저 지운다.
        List<PlatformAccount> accounts =
                platformAccountRepository.findByOwnerIdAndRevokedAtIsNullOrderByCreatedAtDesc(user.getId());
        for (PlatformAccount account : accounts) {
            credentialService.revoke(account);
        }

        // 2) 구독 해지 — 안 하면 탈퇴한 사람에게 청구된다. 빌링키는 커밋 뒤 삭제하도록 모아 둔다.
        List<String> billingKeys = new ArrayList<>();
        List<Store> stores = storeRepository.findByOwnerIdAndDeletedAtIsNull(user.getId());
        for (Store store : stores) {
            subscriptionRepository.findByStoreIdAndStatusNot(store.getId(), "CANCELED").ifPresent(sub -> {
                if (sub.getBillingKey() != null) {
                    billingKeys.add(sub.getBillingKey());
                }
                sub.cancelImmediately();
            });
            // 3) 매장 정지 — activated_at 이 남아 있으면 수집·게시가 계속 돈다.
            // user_agreement 는 계약 관련 증적이라 삭제하지 않는다. 재가입자는 새 user_id 로 다시 동의한다.
            store.softDeleteForWithdrawal(now);
        }

        // 4) 회원 탈퇴. deleted_at 을 세워야 uq_user_email 이 풀려 같은 주소로 재가입할 수 있다.
        user.withdraw(now);

        auditLogRepository.save(AuditLog.builder()
                .actorType("USER")
                .actorId(user.getId())
                .action("ACCOUNT_WITHDRAWN")
                .targetType("APP_USER")
                .targetId(user.getId())
                .build());

        log.info("회원 탈퇴 userId={} 매장={} 플랫폼계정={}", user.getId(), stores.size(), accounts.size());
        return billingKeys;
    }
}
