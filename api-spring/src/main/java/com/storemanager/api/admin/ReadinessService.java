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

    public ReadinessService(EntityManager em, StringRedisTemplate redis, PortOneClient portOne, MailService mail,
            @Value("${app.scheduler.billing.enabled:false}") boolean billingScheduler) {
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
                        + "WHERE status IN ('TRIAL','ACTIVE','PAST_DUE')"));
        return new Report(now, ReadinessEvaluator.mode(worker), ReadinessEvaluator.evaluate(in, now));
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
