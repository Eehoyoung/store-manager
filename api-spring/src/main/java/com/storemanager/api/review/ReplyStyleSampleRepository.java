package com.storemanager.api.review;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReplyStyleSampleRepository extends JpaRepository<ReplyStyleSample, Long> {

    boolean existsByStoreIdAndReplyText(Long storeId, String replyText);

    /**
     * 보유기간 파기(DataRetentionScheduler) — review_id 가 없어(V6) 리뷰 단위 삭제 요청과
     * 연결할 수 없다. created_at 기준 3년 경과로만 판단한다. anonymizeExpired 와 같은
     * "IN (SELECT ... LIMIT)" 패턴으로 한 번에 batchSize 건만 지운다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            DELETE FROM reply_style_sample
             WHERE id IN (
                 SELECT id FROM reply_style_sample
                  WHERE created_at <= :cutoff
                  ORDER BY created_at
                  LIMIT :batchSize
             )
            """, nativeQuery = true)
    int deleteExpired(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
