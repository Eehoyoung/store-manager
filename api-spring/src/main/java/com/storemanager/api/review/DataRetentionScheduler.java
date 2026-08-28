package com.storemanager.api.review;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.draft.ReplyDraftRepository;
import com.storemanager.api.draft.ReviewAnalysisRepository;
import com.storemanager.api.notify.NotificationLogRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개인정보 보유기간 경과분 파기 (개인정보보호법 제21조).
 *
 * <p>★ 지금까지 {@code unified_review.purge_after} 컬럼만 있고 <b>채우는 곳도 지우는 곳도
 * 없었다.</b> 보유기간을 정해 두고 실제로는 영구 보관하고 있었던 셈이다.
 *
 * <p>★ 무엇을 지우는가 — 행 전체가 아니라 <b>개인정보 항목만</b> 비운다.
 * <ul>
 *   <li>{@code author_masked}, {@code author_hash} — 작성자 식별 가능 정보
 *   <li>{@code body}, {@code existing_reply} — 본문에 이름·전화번호가 섞여 들어올 수 있다
 *   <li>{@code image_urls} — 사진에 얼굴·주소가 찍혀 있을 수 있다
 * </ul>
 * 별점·작성일·이슈태그 같은 <b>통계 항목은 남긴다.</b> 행을 통째로 지우면 그 기간의 매장
 * 통계가 소급해서 바뀌고, 사장님이 보던 숫자가 어느 날 달라진다.
 *
 * <p>★ 왜 익명화이지 삭제가 아닌가 — {@code unified_review} 행 자체는 매장 통계(별점·작성일·
 * 이슈태그)의 근거라 남긴다. 다만 privacy.md §7 은 "리뷰·분석·답글·검색 예시 및 그 복제·파생
 * 데이터"를 파기 대상으로 명시하므로, 그 파생 테이블({@code reply_draft}, {@code review_analysis},
 * {@code reply_style_sample})은 이 배치에서 <b>행 자체를 삭제</b>한다. {@code notification_log}
 * 는 "언제 무엇을 보냈는가"를 답해야 하는 발송 이력이라 행은 남기고 개인정보가 담긴
 * {@code payload} 만 빈 객체로 비운다.
 *
 * <p>★ {@code reply_style_sample} 한계 — V6 스키마에 review_id 가 없다(store_id·review_text·
 * reply_text·created_at 만 존재). 리뷰 단위 삭제 요청과 연결할 수단이 없으므로 created_at 기준
 * 3년 경과로만 처리한다. 즉 회원이 특정 리뷰의 즉시 삭제를 요청해도, 그 리뷰 본문을 학습
 * 코퍼스로 복제해 둔 스타일 샘플은 어떤 샘플이 그 리뷰에서 왔는지 식별할 수 없어 3년이
 * 지나야 지워진다. 텍스트 비교로 억지 매칭하지 않는다(오탐 위험).
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.retention.enabled", havingValue = "true")
/** 동의 증적 user_agreement 는 계약 관련 법정 보존 대상이라 파기 배치에서 제외한다. */
public class DataRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionScheduler.class);
    private static final int BATCH_SIZE = 500;

    private final UnifiedReviewRepository unifiedReviewRepository;
    private final ReplyDraftRepository replyDraftRepository;
    private final ReviewAnalysisRepository reviewAnalysisRepository;
    private final ReplyStyleSampleRepository replyStyleSampleRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final AuditLogRepository auditLogRepository;
    private final int retentionDays;

    public DataRetentionScheduler(UnifiedReviewRepository unifiedReviewRepository,
            ReplyDraftRepository replyDraftRepository,
            ReviewAnalysisRepository reviewAnalysisRepository,
            ReplyStyleSampleRepository replyStyleSampleRepository,
            NotificationLogRepository notificationLogRepository,
            AuditLogRepository auditLogRepository,
            @Value("${app.privacy.retention-days:1095}") int retentionDays) {
        this.unifiedReviewRepository = unifiedReviewRepository;
        this.replyDraftRepository = replyDraftRepository;
        this.reviewAnalysisRepository = reviewAnalysisRepository;
        this.replyStyleSampleRepository = replyStyleSampleRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.auditLogRepository = auditLogRepository;
        this.retentionDays = retentionDays;
    }

    /**
     * 새로 들어온 리뷰에 파기 예정일을 채운다. 매일 새벽 4시.
     *
     * <p>★ 적재 시점이 아니라 배치로 채우는 이유: 보유기간 정책이 바뀌면(예: 3년 → 1년)
     * 이미 쌓인 행에도 적용돼야 한다. 적재 시점에 박아 두면 과거 행은 영영 옛 정책을 따른다.
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void stampPurgeDates() {
        int updated = unifiedReviewRepository.stampMissingPurgeAfter(retentionDays);
        if (updated > 0) {
            log.info("파기 예정일 설정 {}건 (보유 {}일)", updated, retentionDays);
        }
    }

    /**
     * 보유기간이 지난 리뷰의 개인정보와 그 파생 데이터를 비운다. 매일 새벽 4시 30분.
     *
     * <p>★ 한 번에 {@value #BATCH_SIZE} 건씩만 처리한다. 최초 도입 시 수년치가 한꺼번에
     * 걸릴 수 있는데, 한 트랜잭션에 다 넣으면 락이 오래 잡혀 서비스가 멈춘다.
     * 남은 건은 다음 날 이어서 처리된다.
     *
     * <p>★ 순서가 중요하다 — 먼저 대상 리뷰 id 를 확정한 뒤 파생 테이블을 지우고, 마지막에
     * {@link UnifiedReviewRepository#anonymizeExpired}로 리뷰 자체(및 purge_after)를 처리한다.
     * anonymizeExpired 가 purge_after 를 먼저 지워버리면 같은 배치를 다시 찾을 수 없다.
     */
    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void purgeExpired() {
        Instant now = Instant.now();
        List<Long> reviewIds = unifiedReviewRepository.findIdsDueForPurge(now, BATCH_SIZE);
        if (reviewIds.isEmpty()) {
            return;
        }
        long draftsDeleted = replyDraftRepository.deleteByReviewIdIn(reviewIds);
        long analysisDeleted = reviewAnalysisRepository.deleteByReviewIdIn(reviewIds);
        int notificationsCleared = notificationLogRepository.clearPayloadForReviews(reviewIds);
        int reviewsPurged = unifiedReviewRepository.anonymizeExpired(now, BATCH_SIZE);

        // ★ 무엇을 언제 지웠는지 남긴다. 파기 사실 자체는 개인정보가 아니며,
        //   '보유기간을 지켰다' 를 증명할 유일한 근거다. 테이블별 건수를 구분해 남긴다.
        auditLogRepository.save(AuditLog.builder()
                .actorType("SYSTEM")
                .action("REVIEW_PII_PURGED")
                .targetType("UNIFIED_REVIEW")
                .detail(("{\"unifiedReview\":%d,\"replyDraft\":%d,\"reviewAnalysis\":%d,"
                        + "\"notificationLogCleared\":%d,\"retentionDays\":%d}")
                        .formatted(reviewsPurged, draftsDeleted, analysisDeleted, notificationsCleared,
                                retentionDays))
                .build());
        log.info("리뷰 파생데이터 파기 - unified_review {}건, reply_draft {}건, review_analysis {}건, "
                + "notification_log payload {}건", reviewsPurged, draftsDeleted, analysisDeleted,
                notificationsCleared);
    }

    /**
     * 보유기간이 지난 말투 학습 샘플({@code reply_style_sample})을 지운다. 매일 새벽 4시 35분.
     *
     * <p>★ review_id 가 없어(V6) 위 {@link #purgeExpired} 배치와 연결할 수 없다. 클래스 주석의
     * 한계를 그대로 따라 created_at 기준 {@value #BATCH_SIZE}건씩, retentionDays 경과분만 지운다.
     */
    @Scheduled(cron = "0 35 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void purgeExpiredStyleSamples() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int purged = replyStyleSampleRepository.deleteExpired(cutoff, BATCH_SIZE);
        if (purged == 0) {
            return;
        }
        auditLogRepository.save(AuditLog.builder()
                .actorType("SYSTEM")
                .action("REVIEW_PII_PURGED")
                .targetType("REPLY_STYLE_SAMPLE")
                .detail("{\"replyStyleSample\":" + purged + ",\"retentionDays\":" + retentionDays + "}")
                .build());
        log.info("말투 학습 샘플 파기 {}건", purged);
    }
}
