package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.crypto.PlatformAccountRepository;
import com.storemanager.api.review.StorePlatformLink;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 해지 후 이용 유예(약관 9.5)가 끝난 매장만 자격증명을 파기한다. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CancelCredentialPurgeIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired CancelCredentialPurgeService purge;
    @Autowired AppUserRepository users;
    @Autowired StoreRepository stores;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired PlatformAccountRepository accounts;
    @Autowired StorePlatformLinkRepository links;
    @Autowired AuditLogRepository auditLogs;

    private int seq;

    @BeforeEach
    void clean() {
        links.deleteAll();
        subscriptions.deleteAll();
        accounts.deleteAll();
    }

    private AppUser owner() {
        return users.save(AppUser.builder().email("purge-" + System.nanoTime() + "@example.com").passwordHash("x").name("사장").build());
    }

    private PlatformAccount account(AppUser o) {
        return accounts.save(PlatformAccount.builder().ownerId(o.getId()).platform("BAEMIN").loginId("id" + System.nanoTime())
                .encPassword(new byte[] {1}).encDek(new byte[] {1}).kmsKeyId("k").encNonce(new byte[] {1})
                .passwordFingerprint(new byte[] {(byte) (++seq), 7}).build());
    }

    /** nextBilling 을 기준으로 serviceUntil 이 정해진다(D+3 00:00 KST). */
    private Store store(AppUser o, PlatformAccount a, boolean autoRenew, Instant nextBilling) {
        Store st = stores.save(Store.builder().ownerId(o.getId()).name("매장" + (++seq)).build());
        Subscription s = Subscription.builder().storeId(st.getId()).priceKrw(new BigDecimal("30000"))
                .status("ACTIVE").autoRenew(autoRenew).billingKey(autoRenew ? "k" : null).build();
        s.changeNextBillingAt(nextBilling);
        subscriptions.save(s);
        links.save(StorePlatformLink.builder().storeId(st.getId()).accountId(a.getId()).platform("BAEMIN")
                .platformStoreId("ps" + seq).build());
        return st;
    }

    private boolean revoked(PlatformAccount a) {
        return accounts.findById(a.getId()).orElseThrow().getRevokedAt() != null;
    }

    @Test
    void 해지_유예_경과_파기하고_두번째는_멱등() {
        AppUser o = owner();
        PlatformAccount a = account(o);
        store(o, a, false, Instant.now().minus(Duration.ofDays(10)));

        purge.purgeEnded(Instant.now());
        long audits = auditLogs.count();
        purge.purgeEnded(Instant.now());

        assertThat(revoked(a)).isTrue();
        assertThat(auditLogs.count()).isEqualTo(audits);
    }

    @Test
    void 해지_유예_전이면_유지() {
        AppUser o = owner();
        PlatformAccount a = account(o);
        store(o, a, false, Instant.now().minus(Duration.ofDays(1)));

        purge.purgeEnded(Instant.now());

        assertThat(revoked(a)).isFalse();
    }

    @Test
    void 자동갱신_켜진_결제실패_제한은_유지() {
        AppUser o = owner();
        PlatformAccount a = account(o);
        store(o, a, true, Instant.now().minus(Duration.ofDays(10)));

        purge.purgeEnded(Instant.now());

        assertThat(revoked(a)).isFalse();
    }

    @Test
    void 다른_매장이_쓰는_계정은_보존() {
        AppUser o = owner();
        PlatformAccount a = account(o);
        store(o, a, false, Instant.now().minus(Duration.ofDays(10)));
        Store alive = store(o, a, true, Instant.now().plus(Duration.ofDays(10)));

        purge.purgeEnded(Instant.now());

        assertThat(revoked(a)).isFalse();
        assertThat(alive.getId()).isNotNull();
    }
}
