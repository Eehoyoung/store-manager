package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.storemanager.api.billing.BillingDtos.HeldReplyResumeResponse;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.draft.PublishBlockKeys;
import com.storemanager.api.draft.ReplyDraft;
import com.storemanager.api.draft.ReplyDraftRepository;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.review.StorePlatformLink;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StorePersona;
import com.storemanager.api.store.StorePersonaRepository;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 선택형 요금(D38) 켜짐에서 초안 대상 SQL·보류 재개·Redis 차단 키(실제 Postgres·Redis).
 * 꺼짐 동작은 기존 테스트가 기대값 변경 없이 잠근다.
 */
@SpringBootTest(properties = "app.pricing.tiered.enabled=true")
@ActiveProfiles("test")
@Testcontainers
class TieredPlatformGateIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired AppUserRepository appUserRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired StorePersonaRepository storePersonaRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired StorePlatformLinkRepository storePlatformLinkRepository;
    @Autowired UnifiedReviewRepository unifiedReviewRepository;
    @Autowired ReplyDraftRepository replyDraftRepository;
    @Autowired CredentialService credentialService;
    @Autowired BillingService billingService;
    @Autowired PublishBlockKeys publishBlockKeys;
    @Autowired StringRedisTemplate stringRedisTemplate;

    private record Fx(AppUser owner, Store store) {}

    /** 배달앱 BAEMIN 만 고른 서비스 가능 매장. */
    private Fx 배민만_고른_매장(String key) {
        AppUser owner = appUserRepository.save(AppUser.builder().email(key + "@example.com")
                .passwordHash("dummy").name("사장").build());
        Store store = Store.builder().ownerId(owner.getId()).name("선택-" + key).build();
        store.activateByCredentialConsent(Instant.now());
        store = storeRepository.save(store);
        storePersonaRepository.save(StorePersona.builder().storeId(store.getId()).tone("FRIENDLY")
                .delayHours((short) 0).publishWindows("[]").personaSeed(1).build());
        subscriptionRepository.save(Subscription.builder().storeId(store.getId()).status("ACTIVE")
                .priceKrw(new BigDecimal("30000")).serviceUntil(Instant.now().plusSeconds(86400 * 10))
                .activeDeliveryPlatforms(new String[] {"BAEMIN"}).build());
        return new Fx(owner, store);
    }

    private UnifiedReview review(Fx f, String platform) {
        String id = UUID.randomUUID().toString();
        PlatformAccount account = credentialService.save(f.owner().getId(), "BAEMIN", id, "pw");
        StorePlatformLink link = storePlatformLinkRepository.save(StorePlatformLink.builder()
                .storeId(f.store().getId()).accountId(account.getId()).platform("BAEMIN").platformStoreId(id).build());
        return unifiedReviewRepository.save(UnifiedReview.builder().storeId(f.store().getId()).linkId(link.getId())
                .platform(platform).platformReviewId(id).rating((short) 5).body("맛있어요")
                .writtenAt(Instant.now()).build());
    }

    private ReplyDraft heldDraft(Fx f, UnifiedReview r, String flag) {
        return replyDraftRepository.save(ReplyDraft.builder().reviewId(r.getId()).storeId(f.store().getId())
                .status("BLOCKED").content("보류된 답글").generatedBy("AI").guardrailFlags(new String[] {flag}).build());
    }

    @Test
    void findNeedingDraftExcludesUnselected() {
        Fx f = 배민만_고른_매장("sql-gate");
        Long baemin = review(f, "BAEMIN").getId();
        Long yogiyo = review(f, "YOGIYO").getId();
        Long naver = review(f, "NAVER").getId();

        List<Long> on = unifiedReviewRepository.findNeedingDraft(Instant.now(), true, PageRequest.of(0, 100))
                .stream().map(UnifiedReview::getId).toList();
        assertThat(on).contains(baemin, naver).doesNotContain(yogiyo);

        // 꺼짐(2-인자 = 플래그 꺼짐과 같은 SQL 결과)이면 지금과 같다 - 미선택도 그대로 대상이다.
        List<Long> off = unifiedReviewRepository.findNeedingDraft(Instant.now(), PageRequest.of(0, 100))
                .stream().map(UnifiedReview::getId).toList();
        assertThat(off).contains(baemin, naver, yogiyo);
    }

    @Test
    void publishBlockKeysWriteAndClear() {
        publishBlockKeys.block(List.of(9101L, 9102L));

        assertThat(stringRedisTemplate.opsForValue().get("publish:blocked:draft:9101"))
                .isEqualTo("PLATFORM_NOT_SELECTED");
        Long ttl = stringRedisTemplate.getExpire("publish:blocked:draft:9102");
        assertThat(ttl).isBetween(86000L, 86400L);

        publishBlockKeys.unblock(List.of(9101L, 9102L));
        assertThat(stringRedisTemplate.hasKey("publish:blocked:draft:9101")).isFalse();
        assertThat(stringRedisTemplate.hasKey("publish:blocked:draft:9102")).isFalse();
        publishBlockKeys.unblock(List.of()); // 빈 목록도 안전하다
    }

    @Test
    void resumeSkipsUnselectedWithSeparateMarker() {
        Fx f = 배민만_고른_매장("resume-skip");
        ReplyDraft ok = heldDraft(f, review(f, "BAEMIN"), "STORE_INACTIVE");
        ReplyDraft unselected = heldDraft(f, review(f, "YOGIYO"), "STORE_INACTIVE");

        HeldReplyResumeResponse res = billingService.resumeHeldReplies(f.owner().getPublicId(),
                f.store().getPublicId(), null);

        assertThat(res.resumed()).isEqualTo(1);
        assertThat(replyDraftRepository.findById(ok.getId()).orElseThrow().getStatus()).isEqualTo("SCHEDULED");
        ReplyDraft after = replyDraftRepository.findById(unselected.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("BLOCKED");
        assertThat(after.getGuardrailFlags()).containsExactly("PLATFORM_NOT_SELECTED");
    }

    @Test
    void heldCountExcludesPlatformNotSelected() {
        Fx f = 배민만_고른_매장("held-count");
        heldDraft(f, review(f, "BAEMIN"), "STORE_INACTIVE");
        heldDraft(f, review(f, "YOGIYO"), "STORE_INACTIVE"); // 보류였지만 미선택 플랫폼
        heldDraft(f, review(f, "COUPANGEATS"), "PLATFORM_NOT_SELECTED"); // 이미 별도 표식

        assertThat(billingService.view(f.owner().getPublicId(), f.store().getPublicId()).heldReplyCount())
                .isEqualTo(1);
    }

    @Test
    void resumeDoesNotRepublishAfterPaymentRecovery() {
        Fx f = 배민만_고른_매장("no-republish");
        ReplyDraft d = heldDraft(f, review(f, "YOGIYO"), "PLATFORM_NOT_SELECTED");

        // 결제는 회복(ACTIVE)돼 있다. 그래도 재개 대상이 아니고 지정해도 거절된다.
        HeldReplyResumeResponse res = billingService.resumeHeldReplies(f.owner().getPublicId(),
                f.store().getPublicId(), null);
        assertThat(res.resumed()).isZero();
        assertThatThrownBy(() -> billingService.resumeHeldReplies(f.owner().getPublicId(),
                f.store().getPublicId(), List.of(d.getPublicId())))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        ReplyDraft after = replyDraftRepository.findById(d.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("BLOCKED");
        assertThat(after.getGuardrailFlags()).containsExactly("PLATFORM_NOT_SELECTED");
    }
}
