package com.storemanager.api.draft;

/**
 * Redis 'q:publish' LIST 로 보내는 게시잡 payload (오케스트레이터 고정계약, 변경 금지).
 * ★ 자격증명(LOGINPWD)을 담지 않는다 — 워커가 accountId 로 자기 경로로 복호화한다(절대규칙 5).
 */
record PublishJobPayload(Long draftId, Long accountId, String platform, String platformStoreId,
        String platformReviewId, String content, int riskLevel, boolean storeActive, String dispatchToken) {
}
