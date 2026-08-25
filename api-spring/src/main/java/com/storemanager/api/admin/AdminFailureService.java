package com.storemanager.api.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재시도를 소진하고 실패한 건을 운영자에게 보여 준다.
 *
 * <p>★ 왜 필요한가 — DataAPI 재시도는 2회까지다(2026-08-25 결정). 호출당 과금이라
 * 무한 재시도는 실패 1건에 호출료를 계속 태우는 셈이다. 대신 <b>2회로 안 되면 사람이
 * 본다.</b> 이 화면이 없으면 실패한 답글은 아무도 모르게 사라진다 — 사장님은 답글이
 * 달린 줄 알고, 우리는 실패한 줄 모른다.
 *
 * <p>★ 새 테이블을 만들지 않았다. {@code reply_draft} 에 이미 fail_code·fail_reason·
 * retry_count 가, {@code collection_job} 에 ecode 가 있다. 실패는 이미 기록되고 있었고
 * <b>읽는 경로만 없었다.</b>
 *
 * <p>★ 조회 전용이다. 여기에 재시도 버튼을 붙이지 말 것 — 되돌릴 수 없는 댓글 등록을
 * 화면에서 한 번 더 쏘는 길이 된다. 원인을 고친 뒤 정상 경로로 다시 태운다.
 */
@Service
public class AdminFailureService {

    /** 화면 한 번에 너무 많이 끌어오지 않는다. 오래된 실패는 이미 대응 시점을 놓친 것이다. */
    private static final int DEFAULT_LIMIT = 200;

    private final EntityManager em;

    public AdminFailureService(EntityManager em) {
        this.em = em;
    }

    @Transactional(readOnly = true)
    public List<PublishFailureRow> publishFailures(int limit) {
        // 네이티브로 쓰는 이유: 매장명·리뷰 식별자·플랫폼이 세 테이블에 흩어져 있고,
        // 이 화면은 운영자용 읽기 전용이라 엔티티 그래프를 만들 값어치가 없다.
        Query q = em.createNativeQuery("""
                SELECT s.name, r.public_id, r.platform, r.review_id, r.rating,
                       left(coalesce(r.body, ''), 120),
                       d.public_id, d.fail_code, d.fail_reason, d.retry_count, d.updated_at
                  FROM reply_draft d
                  JOIN unified_review r ON r.id = d.review_id
                  JOIN store s ON s.id = d.store_id
                 WHERE d.status = 'FAILED'
                 ORDER BY d.updated_at DESC
                 LIMIT :lim
                """);
        q.setParameter("lim", limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, DEFAULT_LIMIT));

        List<PublishFailureRow> out = new ArrayList<>();
        for (Object[] c : (List<Object[]>) q.getResultList()) {
            out.add(new PublishFailureRow(
                    (String) c[0],
                    c[1] == null ? null : c[1].toString(),
                    (String) c[2],
                    (String) c[3],
                    c[4] == null ? null : ((Number) c[4]).intValue(),
                    (String) c[5],
                    c[6] == null ? null : c[6].toString(),
                    (String) c[7],
                    (String) c[8],
                    c[9] == null ? 0 : ((Number) c[9]).intValue(),
                    c[10] == null ? null : ((java.sql.Timestamp) c[10]).toInstant()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<CollectFailureRow> collectFailures(int limit) {
        Query q = em.createNativeQuery("""
                SELECT s.name, pa.platform, pa.login_id, j.job_type,
                       j.start_date, j.end_date, j.ecode, j.started_at
                  FROM collection_job j
                  JOIN platform_account pa ON pa.id = j.account_id
                  LEFT JOIN store s ON s.owner_id = pa.owner_id AND s.deleted_at IS NULL
                 WHERE j.status = 'FAILED'
                 ORDER BY j.started_at DESC
                 LIMIT :lim
                """);
        q.setParameter("lim", limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, DEFAULT_LIMIT));

        List<CollectFailureRow> out = new ArrayList<>();
        for (Object[] c : (List<Object[]>) q.getResultList()) {
            out.add(new CollectFailureRow(
                    (String) c[0],
                    (String) c[1],
                    // ★ login_id 는 자격증명이다. 어느 계정인지 구분할 만큼만 남기고 가린다.
                    maskLoginId((String) c[2]),
                    (String) c[3],
                    c[4] == null ? null : c[4].toString(),
                    c[5] == null ? null : c[5].toString(),
                    (String) c[6],
                    c[7] == null ? null : ((java.sql.Timestamp) c[7]).toInstant()));
        }
        return out;
    }

    /** ★ 운영자 화면에도 로그인 아이디를 그대로 뿌리지 않는다(절대규칙 5 의 취지). */
    static String maskLoginId(String loginId) {
        if (loginId == null || loginId.isBlank()) {
            return null;
        }
        if (loginId.length() <= 3) {
            return loginId.charAt(0) + "**";
        }
        return loginId.substring(0, 3) + "*".repeat(Math.min(loginId.length() - 3, 6));
    }

    /** 게시 실패 — 답글이 매장에 달리지 않았다. 사장님이 기다리는 건이다. */
    public record PublishFailureRow(String storeName, String reviewId, String platform,
            String platformReviewId, Integer rating, String reviewExcerpt,
            String draftId, String failCode, String failReason, int retryCount, Instant failedAt) {
    }

    /** 수집 실패 — 리뷰가 들어오지 않았다. 조용히 비어 보이는 게 가장 위험하다. */
    public record CollectFailureRow(String storeName, String platform, String loginIdMasked,
            String jobType, String startDate, String endDate, String ecode, Instant failedAt) {
    }
}
