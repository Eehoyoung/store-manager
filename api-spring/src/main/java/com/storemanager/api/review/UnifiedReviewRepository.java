package com.storemanager.api.review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UnifiedReviewRepository extends JpaRepository<UnifiedReview, Long> {

    Optional<UnifiedReview> findByPlatformAndPlatformReviewId(String platform, String platformReviewId);

    Optional<UnifiedReview> findByPublicId(UUID publicId);

    /**
     * 아직 답글 초안이 하나도 없는 리뷰 (DraftScheduler 입력).
     *
     * ★ 초안이 '하나라도' 있으면 제외한다. BLOCKED 도 초안이다 — 다시 만들면 위험 리뷰를
     *   폴링 주기마다 T3 로 재분석하게 된다.
     * ★ has_owner_reply=true 는 제외한다. 리뷰 1건당 댓글 1개이고 등록은 되돌릴 수 없다.
     * ★ activated_at IS NULL 매장은 제외한다(전자계약 게이트).
     * ★ 페르소나가 없으면 생성할 수 없으므로 조인으로 거른다.
     * ★ 구독이 살아 있는 매장만 대상으로 한다 — 미납·해지 매장에 LLM 비용을 쓰면 못 받을 돈을
     *   우리가 대신 내는 셈이다. 조건을 여기(집합 단위)에 두어 건당 조회를 만들지 않는다.
     *   상태 문자열은 StoreServiceGate.SERVICEABLE_LIST 와 같아야 한다 - 바꿀 때 함께 바꿀 것.
     */
    @Query("""
            SELECT r FROM UnifiedReview r
             WHERE r.hasOwnerReply = false
               AND NOT EXISTS (SELECT 1 FROM ReplyDraft d WHERE d.reviewId = r.id)
               AND EXISTS (SELECT 1 FROM Store s
                            WHERE s.id = r.storeId AND s.deletedAt IS NULL AND s.activatedAt IS NOT NULL)
               AND EXISTS (SELECT 1 FROM StorePersona p WHERE p.storeId = r.storeId)
               AND EXISTS (SELECT 1 FROM Subscription sub
                            WHERE sub.storeId = r.storeId AND sub.status IN ('TRIAL', 'ACTIVE'))
             ORDER BY r.collectedAt ASC
            """)
    List<UnifiedReview> findNeedingDraft(Pageable pageable);
}
