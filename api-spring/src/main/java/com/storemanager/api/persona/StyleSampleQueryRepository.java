package com.storemanager.api.persona;

import com.storemanager.api.review.ReplyStyleSample;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * GET /stores/{storeId}/persona/style-samples 용 페이징 조회 (Sprint 5 P4).
 * 기존 review.ReplyStyleSampleRepository 는 dedupe 체크(existsBy...)만 갖고 있어 손대지 않고 새로 만든다.
 */
public interface StyleSampleQueryRepository extends JpaRepository<ReplyStyleSample, Long> {

    Page<ReplyStyleSample> findByStoreId(Long storeId, Pageable pageable);
}
