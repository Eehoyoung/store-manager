package com.storemanager.api.admin;

import com.storemanager.api.billing.PortOneClient;
import com.storemanager.api.sysauth.MailService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 출시 준비 점검 입력을 모아 {@link ReadinessEvaluator} 에 넘긴다. 조회 전용. */
@Service
public class ReadinessService {

    static final String WORKER_KEY = "readiness:worker";

    private final EntityManager em;
    private final StringRedisTemplate redis;
    private final PortOneClient portOne;
    private final MailService mail;
    private final boolean billingScheduler;
    private final DeployHeadChecker deployHead;
    private final String deploySha;
    private final Instant startedAt = Instant.now();
    private final String rotateStatusFile;
    private final String deployStatusFile;
    private final String backupStatusFile;
    private final String restoreTestStatusFile;

    public ReadinessService(EntityManager em, StringRedisTemplate redis, PortOneClient portOne, MailService mail,
            @Value("${app.scheduler.billing.enabled:false}") boolean billingScheduler,
            DeployHeadChecker deployHead, @Value("${app.deploy.sha:}") String deploySha,
            @Value("${READINESS_ROTATE_STATUS_FILE:}") String rotateStatusFile,
            @Value("${READINESS_DEPLOY_STATUS_FILE:}") String deployStatusFile,
            @Value("${READINESS_BACKUP_STATUS_FILE:}") String backupStatusFile,
            @Value("${READINESS_RESTORE_TEST_STATUS_FILE:}") String restoreTestStatusFile) {
        this.backupStatusFile = backupStatusFile == null ? "" : backupStatusFile.trim();
        this.restoreTestStatusFile = restoreTestStatusFile == null ? "" : restoreTestStatusFile.trim();
        this.rotateStatusFile = rotateStatusFile == null ? "" : rotateStatusFile.trim();
        this.deployStatusFile = deployStatusFile == null ? "" : deployStatusFile.trim();
        this.deployHead = deployHead;
        this.deploySha = deploySha == null ? "" : deploySha.trim();
        this.em = em;
        this.redis = redis;
        this.portOne = portOne;
        this.mail = mail;
        this.billingScheduler = billingScheduler;
    }

    public record Report(Instant generatedAt, String mode, List<ReadinessEvaluator.Item> items) {}

    @Transactional(readOnly = true)
    public Report report() {
        Instant now = Instant.now();
        String worker = null;
        try {
            worker = redis.opsForValue().get(WORKER_KEY);
        } catch (RuntimeException e) {
            // Redis 장애는 UNKNOWN 으로 흡수한다.
        }
        ReadinessEvaluator.Inputs in = new ReadinessEvaluator.Inputs(worker,
                instant("SELECT max(finished_at) FROM collection_job WHERE status = 'SUCCESS'"),
                instant("SELECT max(published_at) FROM reply_draft WHERE status = 'PUBLISHED'"),
                (String) first("SELECT model FROM review_analysis ORDER BY analyzed_at DESC LIMIT 1"),
                count("SELECT count(*) FROM reply_draft WHERE status = 'BLOCKED' "
                        + "AND 'AUTOMATION_MODEL_UNAVAILABLE' = ANY(guardrail_flags)"),
                portOne.enabled(),
                instant("SELECT max(paid_at) FROM payment WHERE status = 'PAID'"),
                billingScheduler,
                mail.isConfigured(),
                count("SELECT count(*) FROM reply_draft WHERE status = 'SCHEDULED' "
                        + "AND scheduled_at < now() - interval '30 minutes'"),
                count("SELECT count(*) FROM reply_draft WHERE status = 'SCHEDULED' "
                        + "AND scheduled_at < now() - interval '30 minutes' "
                        + "AND fail_reason = 'DATAAPI_WRITE_DISABLED'"),
                count("SELECT count(*) FROM platform_account WHERE link_status = 'ERROR' AND revoked_at IS NULL"),
                count("SELECT coalesce(sum(renewal_failures), 0) FROM subscription "
                        + "WHERE status IN ('TRIAL','ACTIVE','PAST_DUE')"),
                count("SELECT count(*) FROM payment WHERE status = 'FAILED' "
                        + "AND created_at > now() - interval '24 hours'"),
                // 결제 실패로 제한된 매장만 센다 — SubscriptionRepository.findDunningTargets 와 같은 조건.
                // 해지 요청 뒤 유예가 끝난 구독·카드 없이 체험이 끝난 구독은 결제 실패가 아니다.
                count("SELECT count(*) FROM subscription WHERE cancellation_requested_at IS NULL "
                        + "AND auto_renew = true AND renewal_failures > 0 "
                        + "AND status NOT IN ('CANCELED','SUSPENDED') "
                        + "AND service_until IS NOT NULL AND service_until <= now()"),
                new ReadinessEvaluator.DeployInfo(deploySha, startedAt, deployHead.enabled(),
                        deployHead.headSha(now), deployHead.headCommittedAt(now), firstLine(deployStatusFile)),
                diskUsedRatio(), rotateStatusLine());
        List<ReadinessEvaluator.Item> items = new java.util.ArrayList<>(ReadinessEvaluator.evaluate(in, now));
        items.add(ReadinessEvaluator.backupItem(firstLine(backupStatusFile), now));
        items.add(ReadinessEvaluator.restoreTestItem(firstLine(restoreTestStatusFile), now));
        return new Report(now, ReadinessEvaluator.mode(worker), items);
    }

    /** rotate.status 첫 줄. 경로 미설정·파일 없음은 null(미등록), 읽기 실패·빈 파일은 ""(UNKNOWN). 경로는 밖으로 내지 않는다. */
    public String rotateStatusLine() {
        return firstLine(rotateStatusFile);
    }

    /** 상태 파일 첫 줄 — rotate·deploy·backup·restore-test.status 공용. 규칙은 rotateStatusLine 주석과 같다. */
    private static String firstLine(String path) {
        if (path.isEmpty()) {
            return null;
        }
        try {
            java.nio.file.Path f = java.nio.file.Path.of(path);
            if (!java.nio.file.Files.isRegularFile(f)) {
                return null;
            }
            List<String> lines = java.nio.file.Files.readAllLines(f);
            return lines.isEmpty() ? "" : lines.get(0);
        } catch (java.io.IOException | RuntimeException e) {
            return "";
        }
    }

    private static double diskUsedRatio() {
        java.io.File root = new java.io.File("/");
        long total = root.getTotalSpace();
        return total <= 0 ? Double.NaN : (double) (total - root.getUsableSpace()) / total;
    }

    private Object first(String sql) {
        List<?> r = em.createNativeQuery(sql).getResultList();
        return r.isEmpty() ? null : r.get(0);
    }

    private long count(String sql) {
        Object o = first(sql);
        return o == null ? 0 : ((Number) o).longValue();
    }

    private Instant instant(String sql) {
        Object o = first(sql);
        if (o == null) {
            return null;
        }
        if (o instanceof Instant i) {
            return i;
        }
        if (o instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (o instanceof java.time.OffsetDateTime d) {
            return d.toInstant();
        }
        throw new IllegalArgumentException("지원하지 않는 시각 타입: " + o.getClass().getName());
    }
}
