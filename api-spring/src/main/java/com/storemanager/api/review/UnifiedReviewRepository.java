package com.storemanager.api.review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UnifiedReviewRepository extends JpaRepository<UnifiedReview, Long> {

    Optional<UnifiedReview> findByPlatformAndPlatformReviewId(String platform, String platformReviewId);

    Optional<UnifiedReview> findByPublicId(UUID publicId);

    /**
     * 아직 답글 초안이 하나도 없는 리뷰 (DraftScheduler 입력).
     *
     * ★ FAILED 를 제외한 초안이 하나라도 있으면 대상이 아니다. BLOCKED 도 초안이다 —
     *   다시 만들면 위험 리뷰를 폴링 주기마다 T3 로 재분석하게 된다.
     * ★ FAILED 만 있는 리뷰는 다시 만든다. FAILED 는 재시도를 소진한 '게시 실패' 인데,
     *   게시 스케줄러는 SCHEDULED 만 집으므로 그대로 두면 재게시도 재생성도 안 되는
     *   막다른 길이 된다(실기동에서 확인). 답글 내용이 아니라 전달이 실패한 것이므로
     *   다시 시도할 값어치가 있다.
     * ★ 다만 총 3회로 막는다. 계속 실패하는 리뷰를 무한히 재생성하면 LLM 비용과 DataAPI
     *   호출이 함께 샌다. 3회를 넘으면 사람이 봐야 하는 상태다.
     * ★ has_owner_reply=true 는 제외한다. 리뷰 1건당 댓글 1개이고 등록은 되돌릴 수 없다.
     * ★ activated_at IS NULL 매장은 제외한다(전자계약 게이트).
     * ★ 페르소나가 없으면 생성할 수 없으므로 조인으로 거른다.
     * ★ 구독이 살아 있는 매장만 대상으로 한다 — 미납·해지 매장에 LLM 비용을 쓰면 못 받을 돈을
     *   우리가 대신 내는 셈이다. 조건을 여기(집합 단위)에 두어 건당 조회를 만들지 않는다.
     *   상태 문자열은 StoreServiceGate.SERVICEABLE_SUBSCRIPTION_STATUSES 와 같아야 한다 - 함께 바꿀 것.
     */
    @Query("""
            SELECT r FROM UnifiedReview r
             WHERE r.hasOwnerReply = false
               AND NOT EXISTS (SELECT 1 FROM ReplyDraft d
                                WHERE d.reviewId = r.id AND d.status <> 'FAILED')
               AND (SELECT COUNT(f) FROM ReplyDraft f WHERE f.reviewId = r.id) < 3
               AND EXISTS (SELECT 1 FROM Store s
                            WHERE s.id = r.storeId AND s.deletedAt IS NULL AND s.activatedAt IS NOT NULL)
               AND EXISTS (SELECT 1 FROM StorePersona p WHERE p.storeId = r.storeId)
               AND EXISTS (SELECT 1 FROM Subscription sub
                            WHERE sub.storeId = r.storeId AND sub.status = 'ACTIVE')
             ORDER BY r.collectedAt ASC
            """)
    List<UnifiedReview> findNeedingDraft(Pageable pageable);

    /** 파기 예정일이 없는 행에 collected_at + 보유기간을 채운다 (DataRetentionScheduler). */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE unified_review
               SET purge_after = collected_at + make_interval(days => :retentionDays)
             WHERE purge_after IS NULL
            """, nativeQuery = true)
    int stampMissingPurgeAfter(@Param("retentionDays") int retentionDays);

    /**
     * 보유기간이 지난 행의 개인정보 항목만 비운다 (개인정보보호법 제21조).
     *
     * ★ 행을 지우지 않는다. reply_draft·review_analysis 가 FK 로 참조하므로 삭제하면
     *   게시 이력과 감사 근거가 함께 사라진다. 법이 요구하는 것은 '식별 불가' 이지 행 제거가 아니다.
     * ★ 별점·작성일·이슈태그는 남긴다. 통계가 소급해서 바뀌면 사장님이 보던 숫자가 달라진다.
     * ★ purge_after 를 NULL 로 되돌려 같은 행을 다시 집지 않게 한다 — 안 그러면 매일 같은
     *   행을 갱신하며 배치가 헛돈다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE unified_review
               SET body = NULL,
                   author_masked = NULL,
                   author_hash = NULL,
                   existing_reply = NULL,
                   image_urls = '[]'::jsonb,
                   purge_after = NULL,
                   updated_at = now()
             WHERE id IN (
                 SELECT id FROM unified_review
                  WHERE purge_after IS NOT NULL AND purge_after <= :now
                  ORDER BY purge_after
                  LIMIT :batchSize
             )
            """, nativeQuery = true)
    int anonymizeExpired(@Param("now") java.time.Instant now, @Param("batchSize") int batchSize);
}
