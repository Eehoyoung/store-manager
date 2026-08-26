package com.storemanager.api.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매일 정오 브리핑 (2026-08-27 신설).
 *
 * <p>수집은 오전 10시에 끝난다. 정오면 그날 몫이 다 정리돼 있고, 사장님은 점심 장사에
 * 들어가기 전에 확인할 수 있다.
 *
 * <p><b>★ 확인할 것이 없는 날에도 보낸다.</b> "오늘은 조용했습니다" 도 정보다.
 * 아무 소식이 없으면 사장님은 서비스가 도는 중인지 멈춘 것인지 알 수 없고,
 * 그 상태가 며칠 이어지면 서비스를 신뢰하지 않게 된다.
 *
 * <p><b>★ '확인 필요' 에는 기간 필터를 걸지 않는다.</b> 40일 전 리뷰가 아직 검수 대기라면
 * 그건 <i>지금</i> 해야 할 일이다(CLAUDE.md 대시보드 집계 기준과 같은 원칙).
 * 오늘 것만 세면 밀린 일이 브리핑에서 사라지고, 밀릴수록 숨는 구조가 된다.
 */
@Service
public class DailyBriefingService {

    private static final Logger log = LoggerFactory.getLogger(DailyBriefingService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final String TEMPLATE = "DAILY_BRIEFING";
    static final String CHANNEL = "ALIMTALK";

    private final JdbcTemplate jdbcTemplate;
    private final Notifier notifier;
    private final ObjectMapper objectMapper;

    public DailyBriefingService(JdbcTemplate jdbcTemplate, Notifier notifier, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.notifier = notifier;
        this.objectMapper = objectMapper;
    }

    /** 한 매장의 하루치 요약. */
    record Briefing(Long storeId, Long ownerId, String storeName, long collected, long replied, long needsReview) {

        Map<String, String> vars() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("storeName", storeName);
            m.put("collected", String.valueOf(collected));
            m.put("replied", String.valueOf(replied));
            m.put("needsReview", String.valueOf(needsReview));
            return m;
        }
    }

    /**
     * 서비스 대상 매장 전체에 브리핑을 보낸다.
     *
     * <p>★ 한 매장에서 실패해도 나머지는 계속 보낸다. 알림 하나 때문에 전체 배치가
     * 멈추면 그날 아무도 브리핑을 못 받는다.
     */
    @Transactional
    public int sendDailyBriefings() {
        LocalDate today = LocalDate.now(KST);
        List<Briefing> rows = collect(today);
        int sent = 0;
        for (Briefing b : rows) {
            try {
                notifier.send(b.ownerId(), b.storeId(), CHANNEL, TEMPLATE, "STORE", b.storeId(), b.vars());
                sent++;
            } catch (RuntimeException e) {
                // 중복 인덱스(uq_daily_briefing_once) 위반도 여기로 온다 — 이미 보낸 것이므로 정상이다.
                log.warn("브리핑 발송 실패 storeId={} : {}", b.storeId(), e.toString());
            }
        }
        log.info("일일 브리핑 {}건 발송 (대상 {}개 매장, 기준일 {})", sent, rows.size(), today);
        return sent;
    }

    /**
     * 매장별 집계.
     *
     * <p>★ 네이티브 쿼리인 이유 — 매장 수만큼 조회를 반복하면(N+1) 100매장에 300번을 던진다.
     * 배치 한 번에 쿼리 한 번이면 충분하다.
     *
     * <p>★ 서비스가 정지된 매장에는 보내지 않는다. 해지한 매장에 알림이 계속 가면
     * 그건 광고다.
     */
    List<Briefing> collect(LocalDate today) {
        String sql = """
                SELECT s.id, s.owner_id, s.name,
                       COALESCE(c.cnt, 0) AS collected,
                       COALESCE(p.cnt, 0) AS replied,
                       COALESCE(b.cnt, 0) AS needs_review
                  FROM store s
                  LEFT JOIN (
                        SELECT store_id, COUNT(*) cnt FROM unified_review
                         WHERE collected_at >= ?::timestamptz GROUP BY store_id
                  ) c ON c.store_id = s.id
                  LEFT JOIN (
                        SELECT store_id, COUNT(*) cnt FROM reply_draft
                         WHERE status IN ('PUBLISHED','SCHEDULED') AND updated_at >= ?::timestamptz
                         GROUP BY store_id
                  ) p ON p.store_id = s.id
                  LEFT JOIN (
                        -- ★ 기간 필터 없음. 밀린 검수는 오늘의 일이다.
                        SELECT store_id, COUNT(*) cnt FROM reply_draft
                         WHERE status = 'BLOCKED' AND NOT ('HUMAN_REJECTED' = ANY(guardrail_flags))
                         GROUP BY store_id
                  ) b ON b.store_id = s.id
                 WHERE s.deleted_at IS NULL
                   AND s.activated_at IS NOT NULL
                   AND EXISTS (SELECT 1 FROM subscription sub
                                WHERE sub.store_id = s.id AND sub.status = 'ACTIVE')
                   -- ★ 오늘 이미 보낸 매장은 제외한다. 유니크 인덱스에 맡기고 예외로 걸러내면
                   --   제약 위반이 트랜잭션을 rollback-only 로 만들어, 뒤이은 매장까지 통째로
                   --   날아간다(실측). 인덱스는 최후의 방어선으로 남기고 정상 경로는 여기서 막는다.
                   AND NOT EXISTS (
                        SELECT 1 FROM notification_log n
                         WHERE n.store_id = s.id AND n.template = ?
                           AND timezone('Asia/Seoul', n.sent_at)::date = ?::date)
                 ORDER BY s.id
                """;
        String dayStart = today.atStartOfDay(KST).toOffsetDateTime().toString();
        return jdbcTemplate.query(sql, (rs, i) -> new Briefing(
                rs.getLong("id"), rs.getLong("owner_id"), rs.getString("name"),
                rs.getLong("collected"), rs.getLong("replied"), rs.getLong("needs_review")),
                dayStart, dayStart, TEMPLATE, today.toString());
    }
}
