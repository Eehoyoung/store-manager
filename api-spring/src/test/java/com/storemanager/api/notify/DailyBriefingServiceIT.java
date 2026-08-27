package com.storemanager.api.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.storemanager.api.draft.ReplyDraft;
import com.storemanager.api.draft.ReplyDraftRepository;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.review.StorePlatformLink;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.billing.Subscription;
import com.storemanager.api.billing.SubscriptionRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 매일 정오 브리핑 집계 통합테스트.
 *
 * <p>★ 목이 아니라 실제 DB 로 돌린다 — 집계 SQL 이 이 기능의 전부이고, 목은 SQL 의
 * 오류를 재현하지 못한다(핸드오프 §2.1 에서 같은 교훈을 얻었다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DailyBriefingServiceIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired DailyBriefingService service;
    @Autowired AppUserRepository appUserRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired UnifiedReviewRepository unifiedReviewRepository;
    @Autowired ReplyDraftRepository replyDraftRepository;
    @Autowired NotificationLogRepository notificationLogRepository;
    @Autowired CredentialService credentialService;
    @Autowired StorePlatformLinkRepository storePlatformLinkRepository;

    /** unified_review.link_id 는 FK 다 — 실제 링크를 만들어야 리뷰를 넣을 수 있다. */
    private Long linkIdOf(Long storeId, Long ownerId, String email) {
        PlatformAccount account = credentialService.save(ownerId, "BAEMIN", "id-" + email, "pw");
        return storePlatformLinkRepository.save(StorePlatformLink.builder()
                .storeId(storeId).accountId(account.getId()).platform("BAEMIN")
                .platformStoreId("ps-" + email).build()).getId();
    }

    private record 픽스처(Long storeId, Long linkId) {
    }

    private 픽스처 매장픽스처를_만든다(String email, String name, boolean activated, String subStatus) {
        Long storeId = 매장을_만든다(email, name, activated, subStatus);
        Long ownerId = storeRepository.findById(storeId).orElseThrow().getOwnerId();
        return new 픽스처(storeId, linkIdOf(storeId, ownerId, email));
    }

    private Long 매장을_만든다(String email, String name, boolean activated, String subStatus) {
        AppUser owner = appUserRepository.save(AppUser.builder()
                .email(email).name("사장").passwordHash("x").build());
        Store store = storeRepository.save(Store.builder()
                .ownerId(owner.getId()).name(name).status("ACTIVE")
                .activatedAt(activated ? Instant.now().minusSeconds(86400) : null).build());
        if (subStatus != null) {
            subscriptionRepository.save(Subscription.builder()
                    .storeId(store.getId()).status(subStatus)
                    .priceKrw(new java.math.BigDecimal("30000"))
                    .currentPeriodStart(Instant.now().minusSeconds(86400))
                    .currentPeriodEnd(Instant.now().plusSeconds(86400 * 29)).build());
        }
        return store.getId();
    }

    private Long 리뷰를_만든다(픽스처 f, String platformReviewId, Instant collectedAt) {
        return unifiedReviewRepository.save(UnifiedReview.builder()
                .storeId(f.storeId()).linkId(f.linkId()).platform("BAEMIN").platformReviewId(platformReviewId)
                .rating((short) 5).body("본문").writtenAt(collectedAt).collectedAt(collectedAt)
                .build()).getId();
    }

    private void 초안을_만든다(Long storeId, Long reviewId, String status, String... flags) {
        replyDraftRepository.save(ReplyDraft.builder()
                .storeId(storeId).reviewId(reviewId).content("답글").status(status)
                .generatedBy("AI").guardrailFlags(flags).build());
    }

    @Test
    void 오늘_수집_답글_확인필요를_매장별로_집계한다() {
        Instant now = Instant.now();
        픽스처 f = 매장픽스처를_만든다("brief1@t.com", "브리핑점", true, "ACTIVE");
        Long storeId = f.storeId();
        Long r1 = 리뷰를_만든다(f, "BR-1", now);
        Long r2 = 리뷰를_만든다(f, "BR-2", now);
        // 어제 수집분 — 오늘 집계에 들어가면 안 된다
        리뷰를_만든다(f, "BR-OLD", now.minusSeconds(86400 * 2));
        초안을_만든다(storeId, r1, "PUBLISHED");
        초안을_만든다(storeId, r2, "BLOCKED", "RISK_LEVEL_TOO_HIGH");

        List<DailyBriefingService.Briefing> rows = service.collect(LocalDate.now(KST));
        DailyBriefingService.Briefing b = rows.stream()
                .filter(x -> x.storeId().equals(storeId)).findFirst().orElseThrow();

        assertThat(b.storeName()).isEqualTo("브리핑점");
        assertThat(b.collected()).isEqualTo(2);
        assertThat(b.replied()).isEqualTo(1);
        assertThat(b.needsReview()).isEqualTo(1);
    }

    /**
     * ★ 밀린 검수는 오늘의 일이다. 기간 필터를 걸면 오래된 위험 리뷰가 브리핑에서 사라지고,
     * 밀릴수록 숨는 구조가 된다(CLAUDE.md 대시보드 집계 기준과 같은 원칙).
     */
    @Test
    void 오래된_확인필요_건도_계속_센다() {
        픽스처 f = 매장픽스처를_만든다("brief2@t.com", "묵은점", true, "ACTIVE");
        Long storeId = f.storeId();
        Long r = 리뷰를_만든다(f, "BR2-OLD", Instant.now().minusSeconds(86400L * 60));
        초안을_만든다(storeId, r, "BLOCKED", "RISK_LEVEL_TOO_HIGH");

        DailyBriefingService.Briefing b = service.collect(LocalDate.now(KST)).stream()
                .filter(x -> x.storeId().equals(storeId)).findFirst().orElseThrow();

        assertThat(b.collected()).isZero();
        assertThat(b.needsReview()).isEqualTo(1);
    }

    /** 사장님이 게시하지 않기로 한 건은 더 이상 '확인 필요' 가 아니다. */
    @Test
    void 사람이_거절한_건은_확인필요에서_빠진다() {
        픽스처 f = 매장픽스처를_만든다("brief3@t.com", "거절점", true, "ACTIVE");
        Long storeId = f.storeId();
        Long r = 리뷰를_만든다(f, "BR3-1", Instant.now());
        초안을_만든다(storeId, r, "BLOCKED", "RISK_LEVEL_TOO_HIGH", "HUMAN_REJECTED");

        DailyBriefingService.Briefing b = service.collect(LocalDate.now(KST)).stream()
                .filter(x -> x.storeId().equals(storeId)).findFirst().orElseThrow();

        assertThat(b.needsReview()).isZero();
    }

    /** 해지·정지된 매장에 알림이 계속 가면 그건 광고다. */
    @Test
    void 구독이_없거나_미활성인_매장은_대상이_아니다() {
        Long noSub = 매장을_만든다("brief4@t.com", "구독없음", true, null);
        Long inactive = 매장을_만든다("brief5@t.com", "미활성", false, "ACTIVE");
        Long canceled = 매장을_만든다("brief6@t.com", "해지", true, "CANCELED");

        List<Long> ids = service.collect(LocalDate.now(KST)).stream()
                .map(DailyBriefingService.Briefing::storeId).toList();

        assertThat(ids).doesNotContain(noSub, inactive, canceled);
    }

    /** ★ 조용한 날에도 보낸다. 소식이 없으면 서비스가 멈춘 것인지 알 수 없다. */
    @Test
    void 아무_일도_없는_날에도_대상에_포함된다() {
        Long storeId = 매장을_만든다("brief7@t.com", "조용한점", true, "ACTIVE");

        DailyBriefingService.Briefing b = service.collect(LocalDate.now(KST)).stream()
                .filter(x -> x.storeId().equals(storeId)).findFirst().orElseThrow();

        assertThat(b.collected()).isZero();
        assertThat(b.replied()).isZero();
        assertThat(b.needsReview()).isZero();
    }

    @Test
    void 발송하면_변수와_함께_알림로그에_남는다() {
        픽스처 f = 매장픽스처를_만든다("brief8@t.com", "발송점", true, "ACTIVE");
        Long storeId = f.storeId();
        리뷰를_만든다(f, "BR8-1", Instant.now());

        service.sendDailyBriefings();

        NotificationLog logRow = notificationLogRepository.findAll().stream()
                .filter(n -> DailyBriefingService.TEMPLATE.equals(n.getTemplate())
                        && storeId.equals(n.getStoreId()))
                .findFirst().orElseThrow();
        assertThat(logRow.getChannel()).isEqualTo(DailyBriefingService.CHANNEL);
        // JSONB 는 저장 시 키 순서와 공백을 정규화한다 — 원문 문자열을 그대로 기대하지 않는다.
        assertThat(logRow.getPayload().replace(" ", ""))
                .contains("\"storeName\":\"발송점\"")
                .contains("\"collected\":\"1\"");
    }

    /**
     * ★ 배치가 두 번 돌아도 브리핑은 하루 한 건이다. 유니크 인덱스가 구조적으로 막는다.
     * "배치가 두 번 돌 리 없다" 에 의존하지 않는다 — 청구 배치에서 같은 교훈을 얻었다.
     */
    @Test
    void 배치를_두_번_돌려도_같은_날_두_번_보내지_않는다() {
        Long storeId = 매장을_만든다("brief9@t.com", "중복점", true, "ACTIVE");

        service.sendDailyBriefings();
        service.sendDailyBriefings();

        long count = notificationLogRepository.findAll().stream()
                .filter(n -> DailyBriefingService.TEMPLATE.equals(n.getTemplate())
                        && storeId.equals(n.getStoreId()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    /** DB 고유 인덱스와 ON CONFLICT가 같은 고위험 원인의 중복 큐 적재를 함께 막는다. */
    @Test
    @org.springframework.transaction.annotation.Transactional
    void 생성과_게시실패_경로가_같은_리뷰를_알려도_한_번만_큐에_쌓인다() {
        Long storeId = 매장을_만든다("risk-queue@t.com", "위험알림점", true, "ACTIVE");
        Long ownerId = storeRepository.findById(storeId).orElseThrow().getOwnerId();

        assertThat(notificationLogRepository.enqueueHighRiskIfAbsent(
                ownerId, storeId, "UNIFIED_REVIEW", 991L, "{}")).isEqualTo(1);
        assertThat(notificationLogRepository.enqueueHighRiskIfAbsent(
                ownerId, storeId, "UNIFIED_REVIEW", 991L, "{}")).isZero();
        assertThat(notificationLogRepository.enqueueHighRiskIfAbsent(
                ownerId, storeId, "UNIFIED_REVIEW", 992L, "{}")).isEqualTo(1);
        assertThat(notificationLogRepository.countByRefTypeAndRefIdAndTemplate(
                "UNIFIED_REVIEW", 991L, "HIGH_RISK_REVIEW")).isEqualTo(1);
        assertThat(notificationLogRepository.countByRefTypeAndRefIdAndTemplate(
                "UNIFIED_REVIEW", 992L, "HIGH_RISK_REVIEW")).isEqualTo(1);
    }
}
