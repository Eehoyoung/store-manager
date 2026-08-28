package com.storemanager.api.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.storemanager.api.agreement.UserAgreement;
import com.storemanager.api.agreement.UserAgreementRepository;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.draft.ReplyDraft;
import com.storemanager.api.draft.ReplyDraftRepository;
import com.storemanager.api.draft.ReviewAnalysis;
import com.storemanager.api.draft.ReviewAnalysisRepository;
import com.storemanager.api.notify.NotificationLog;
import com.storemanager.api.notify.NotificationLogRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * WP-03 — privacy.md §7 "리뷰·분석·답글·검색 예시 및 그 복제·파생 데이터" 파기를 실제 Postgres 위에서 검증한다.
 * CollectResultIT 와 동일한 Testcontainers 패턴을 재사용한다. 스케줄러 메서드는 cron 을 기다리지 않고 직접 호출한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DataRetentionSchedulerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    DataRetentionScheduler scheduler;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    StoreRepository storeRepository;

    @Autowired
    CredentialService credentialService;

    @Autowired
    StorePlatformLinkRepository storePlatformLinkRepository;

    @Autowired
    UnifiedReviewRepository unifiedReviewRepository;

    @Autowired
    ReplyDraftRepository replyDraftRepository;

    @Autowired
    ReviewAnalysisRepository reviewAnalysisRepository;

    @Autowired
    ReplyStyleSampleRepository replyStyleSampleRepository;

    @Autowired
    NotificationLogRepository notificationLogRepository;

    @Autowired
    UserAgreementRepository userAgreementRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    private Long 매장을_만든다(Long ownerId, String name) {
        return storeRepository.save(Store.builder().ownerId(ownerId).name(name).build()).getId();
    }

    private Long 리뷰를_만든다(Long storeId, Long linkId, String platformReviewId, Instant purgeAfter) {
        UnifiedReview review = UnifiedReview.builder()
                .storeId(storeId)
                .linkId(linkId)
                .platform("BAEMIN")
                .platformReviewId(platformReviewId)
                .body("맛있어요")
                .writtenAt(Instant.now())
                .purgeAfter(purgeAfter)
                .build();
        return unifiedReviewRepository.save(review).getId();
    }

    @Test
    void 파기예정일이_지난_리뷰의_초안과_분석은_삭제되고_기간이_남은_것은_남는다() throws Exception {
        AppUser owner = appUserRepository.save(AppUser.builder().email("wp03-a@example.com")
                .passwordHash("dummy").name("사장A").build());
        Long storeId = 매장을_만든다(owner.getId(), "WP03매장A");
        PlatformAccount account = credentialService.save(owner.getId(), "BAEMIN", "wp03-a-login", "pw");
        Long linkId = storePlatformLinkRepository.save(StorePlatformLink.builder()
                .storeId(storeId).accountId(account.getId()).platform("BAEMIN")
                .platformStoreId("wp03-a-store").build()).getId();

        // 만료 대상 — purge_after 가 과거
        Long expiredReviewId = 리뷰를_만든다(storeId, linkId, "wp03-a-expired", Instant.now().minus(1, ChronoUnit.DAYS));
        Long expiredDraftId = replyDraftRepository.save(ReplyDraft.builder()
                .reviewId(expiredReviewId).storeId(storeId).content("감사합니다").generatedBy("AI").build()).getId();
        reviewAnalysisRepository.save(ReviewAnalysis.builder().reviewId(expiredReviewId).category("PRAISE")
                .sentiment(0.5f).riskLevel((short) 0).model("test").promptVersion("v1").build());
        Long notifId = notificationLogRepository.save(NotificationLog.builder()
                .userId(owner.getId()).storeId(storeId).channel("ALIMTALK").template("HIGH_RISK_REVIEW")
                .status("QUEUED").refType("UNIFIED_REVIEW").refId(expiredReviewId)
                .payload("{\"storeName\":\"WP03매장A\",\"body\":\"위험 리뷰 원문\"}").build()).getId();

        // 기간이 남은 대상 — purge_after 가 미래
        Long freshReviewId = 리뷰를_만든다(storeId, linkId, "wp03-a-fresh", Instant.now().plus(1000, ChronoUnit.DAYS));
        Long freshDraftId = replyDraftRepository.save(ReplyDraft.builder()
                .reviewId(freshReviewId).storeId(storeId).content("감사합니다").generatedBy("AI").build()).getId();
        reviewAnalysisRepository.save(ReviewAnalysis.builder().reviewId(freshReviewId).category("PRAISE")
                .sentiment(0.5f).riskLevel((short) 0).model("test").promptVersion("v1").build());

        // 계약 관련 증적 — 파기 대상이 아니다
        Long agreementId = userAgreementRepository.save(UserAgreement.builder().userId(owner.getId())
                .agreementCode("TERMS").docVersion("2026-08-28").agreed(true).agreedAt(Instant.now()).build())
                .getId();

        scheduler.purgeExpired();

        assertThat(replyDraftRepository.findById(expiredDraftId)).isEmpty();
        assertThat(reviewAnalysisRepository.findById(expiredReviewId)).isEmpty();
        NotificationLog clearedNotif = notificationLogRepository.findById(notifId).orElseThrow();
        assertThat(clearedNotif.getPayload()).isEqualTo("{}");

        assertThat(replyDraftRepository.findById(freshDraftId)).isPresent();
        assertThat(reviewAnalysisRepository.findById(freshReviewId)).isPresent();
        assertThat(userAgreementRepository.findById(agreementId)).isPresent();

        List<AuditLog> logs = auditLogRepository.findByActionOrderByCreatedAtAsc("REVIEW_PII_PURGED");
        assertThat(logs).isNotEmpty();
        String detail = logs.get(logs.size() - 1).getDetail();
        assertThat(detail).contains("\"replyDraft\": 1");
        assertThat(detail).contains("\"reviewAnalysis\": 1");
        assertThat(detail).contains("\"notificationLogCleared\": 1");
    }

    @Test
    void 삼년_지난_말투샘플은_삭제되고_최근_샘플은_남는다() {
        AppUser owner = appUserRepository.save(AppUser.builder().email("wp03-b@example.com")
                .passwordHash("dummy").name("사장B").build());
        Long storeId = 매장을_만든다(owner.getId(), "WP03매장B");

        Long oldSampleId = replyStyleSampleRepository.save(ReplyStyleSample.builder()
                .storeId(storeId).reviewText("옛날 리뷰").replyText("옛날 답글")
                .createdAt(Instant.now().minus(1100, ChronoUnit.DAYS)).build()).getId();
        Long recentSampleId = replyStyleSampleRepository.save(ReplyStyleSample.builder()
                .storeId(storeId).reviewText("최근 리뷰").replyText("최근 답글")
                .createdAt(Instant.now().minus(10, ChronoUnit.DAYS)).build()).getId();

        scheduler.purgeExpiredStyleSamples();

        assertThat(replyStyleSampleRepository.findById(oldSampleId)).isEmpty();
        assertThat(replyStyleSampleRepository.findById(recentSampleId)).isPresent();

        List<AuditLog> logs = auditLogRepository.findByActionOrderByCreatedAtAsc("REVIEW_PII_PURGED");
        assertThat(logs).anyMatch(l -> l.getDetail() != null && l.getDetail().contains("\"replyStyleSample\":"));
    }
}
