package com.storemanager.api.review;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplyStyleSampleRepository extends JpaRepository<ReplyStyleSample, Long> {

    boolean existsByStoreIdAndReplyText(Long storeId, String replyText);
}
