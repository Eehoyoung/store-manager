package com.storemanager.api.review;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import java.time.Instant;
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
 * <p>★ 왜 익명화이지 삭제가 아닌가 — {@code reply_draft}, {@code review_analysis} 가
 * 이 행을 FK 로 참조한다. 삭제하면 게시 이력과 감사 근거가 함께 사라진다.
 * 개인정보보호법이 요구하는 것은 '식별할 수 없게 하는 것' 이지 행 제거가 아니다.
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.retention.enabled", havingValue = "true")
public class DataRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionScheduler.class);
    private static final int BATCH_SIZE = 500;

    private final UnifiedReviewRepository unifiedReviewRepository;
    private final AuditLogRepository auditLogRepository;
    private final int retentionDays;

    public DataRetentionScheduler(UnifiedReviewRepository unifiedReviewRepository,
            AuditLogRepository auditLogRepository,
            @Value("${app.privacy.retention-days:1095}") int retentionDays) {
        this.unifiedReviewRepository = unifiedReviewRepository;
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
     * 보유기간이 지난 리뷰의 개인정보를 비운다. 매일 새벽 4시 30분.
     *
     * <p>★ 한 번에 {@value #BATCH_SIZE} 건씩만 처리한다. 최초 도입 시 수년치가 한꺼번에
     * 걸릴 수 있는데, 한 트랜잭션에 다 넣으면 락이 오래 잡혀 서비스가 멈춘다.
     * 남은 건은 다음 날 이어서 처리된다.
     */
    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void purgeExpired() {
        int purged = unifiedReviewRepository.anonymizeExpired(Instant.now(), BATCH_SIZE);
        if (purged == 0) {
            return;
        }
        // ★ 무엇을 언제 지웠는지 남긴다. 파기 사실 자체는 개인정보가 아니며,
        //   '보유기간을 지켰다' 를 증명할 유일한 근거다.
        auditLogRepository.save(AuditLog.builder()
                .actorType("SYSTEM")
                .action("REVIEW_PII_PURGED")
                .targetType("UNIFIED_REVIEW")
                .detail("{\"count\":" + purged + ",\"retentionDays\":" + retentionDays + "}")
                .build());
        log.info("리뷰 개인정보 파기 {}건", purged);
    }
}
