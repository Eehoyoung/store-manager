package com.storemanager.api.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.storemanager.api.billing.Subscription;
import com.storemanager.api.billing.SubscriptionRepository;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StorePersona;
import com.storemanager.api.store.StorePersonaRepository;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 구독이 서비스 가능한 매장만 답글 초안 대상이어야 한다(2026-09-29 자동결제 전환).
 *
 * <p>★ findNeedingDraft 의 구독 조건은 Subscription.isServiceableAt 과 같아야 한다
 * (status NOT IN ('SUSPENDED','CANCELED') AND service_until > now). 여기서 갈라지면
 * 결제 안 한 매장에 LLM 비용을 쓰거나, 결제한 매장의 초안이 영영 안 만들어진다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TrialDraftEligibilityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired AppUserRepository appUserRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired StorePersonaRepository storePersonaRepository;
    @Autowired StorePlatformLinkRepository storePlatformLinkRepository;
    @Autowired UnifiedReviewRepository unifiedReviewRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired CredentialService credentialService;

    private Long 리뷰가_있는_매장(String key, String status, Instant serviceUntil) {
        AppUser owner = appUserRepository.save(AppUser.builder().email(key + "@example.com")
                .passwordHash("dummy").name("사장").build());
        Store store = Store.builder().ownerId(owner.getId()).name("체험-" + key).build();
        store.activateByCredentialConsent(Instant.now());
        store = storeRepository.save(store);
        storePersonaRepository.save(StorePersona.builder().storeId(store.getId()).tone("FRIENDLY")
                .delayHours((short) 0).publishWindows("[]").personaSeed(1).build());
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "id-" + key, "pw");
        StorePlatformLink link = storePlatformLinkRepository.save(StorePlatformLink.builder()
                .storeId(store.getId()).accountId(account.getId()).platform("BAEMIN")
                .platformStoreId("ps-" + key).build());
        subscriptionRepository.save(Subscription.builder().storeId(store.getId())
                .priceKrw(BigDecimal.valueOf(30000)).status(status).serviceUntil(serviceUntil).build());
        return unifiedReviewRepository.save(UnifiedReview.builder().storeId(store.getId()).linkId(link.getId())
                .platform("BAEMIN").platformReviewId("r-" + key).rating((short) 5).body("맛있어요")
                .writtenAt(Instant.now()).build()).getId();
    }

    @Test
    void 서비스_가능한_구독의_매장만_초안_대상이다() {
        Instant later = Instant.now().plus(10, ChronoUnit.DAYS);
        Long active = 리뷰가_있는_매장("active", "ACTIVE", later);
        Long expired = 리뷰가_있는_매장("expired", "ACTIVE", Instant.now().minusSeconds(60));
        Long unpaid = 리뷰가_있는_매장("unpaid", "TRIAL", null);
        Long suspended = 리뷰가_있는_매장("suspended", "SUSPENDED", later);

        var ids = unifiedReviewRepository.findNeedingDraft(Instant.now(), PageRequest.of(0, 50)).stream()
                .map(UnifiedReview::getId).toList();

        assertThat(ids).contains(active).doesNotContain(expired, unpaid, suspended);
    }
}
