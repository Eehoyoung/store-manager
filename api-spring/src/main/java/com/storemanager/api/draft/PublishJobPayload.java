package com.storemanager.api.draft;

/**
 * Redis 'q:publish' LIST 로 보내는 게시잡 payload (오케스트레이터 고정계약, 변경 금지).
 * ★ 자격증명(LOGINPWD)을 담지 않는다 — 워커가 accountId 로 자기 경로로 복호화한다(절대규칙 5).
 */
/**
 * 게시 잡 페이로드.
 *
 * <p>★ {@code humanApproved} 는 "사람이 차단 사유를 읽고 승인했다" 는 신호다. 워커는
 * {@code riskLevel >= 3} 을 기본 차단하는데, 이 값이 true 일 때만 통과시킨다.
 * <b>필드가 없으면 워커는 false 로 본다</b> — 구버전·위조 payload 가 위험 게시를 열어서는 안 된다.
 */
record PublishJobPayload(Long draftId, Long accountId, String platform, String platformStoreId,
        String platformReviewId, String content, int riskLevel, boolean storeActive, boolean humanApproved,
        String dispatchToken) {
}
