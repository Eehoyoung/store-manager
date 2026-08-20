package com.storemanager.api.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * POST /internal/collect-result 요청 계약 (docs/13 §11.2 + 코디네이터 확정 조정사항, Worker → Spring).
 * ★ 이 요청 본문은 authorRaw(가명처리 전 원본 닉네임)를 포함하므로 어디에도 로깅하지 않는다.
 * 알 수 없는 필드는 거부하지 않고 무시한다(@JsonIgnoreProperties) — 워커가 필드를 추가해도 400 이 나지 않아야 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record CollectResultRequest(
        @NotBlank String jobId, // ★ 정수가 아니라 문자열. 워커가 job_id 없으면 uuid4().hex 를 자체 생성해 보낸다
        String accountId, // 계약대로 문자열. 수치로 파싱되는 경우에만 platform_account 조회에 사용한다
        @NotBlank String platform,
        @NotBlank String status,
        String ecode, // status=FAILED 일 때만 옴 (DataAPI 원본 ECODE)
        String action, // status=FAILED 일 때만 옴: LINK_ERROR | ALREADY_REPLIED | FAIL
        List<StoreBlock> stores, // LINK_ERROR 등 실패 시 비어 있거나 없을 수 있다
        Stats stats) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoreBlock(
            @NotBlank String platformStoreId,
            String storeName,
            BigDecimal avgRating,
            List<ReviewBlock> reviews) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReviewBlock(
            @NotBlank String platformReviewId,
            Integer rating,
            String body,
            String authorRaw, // [PII] 원본 닉네임 — Pseudonymizer 를 거치기 전에는 어디에도 저장·로깅 금지
            List<String> orderedMenus,
            List<String> imageUrls,
            Map<String, Object> platformExtra, // 값이 없는 키는 null 일 수 있다
            String reviewStatus,
            String writtenDate, // yyyy-MM-dd
            ExistingReply existingReply) { // RC_LIST 가 없으면 null
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExistingReply(String id, String contents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Stats(Integer found, @JsonProperty("new") Integer newCount, Integer latencyMs) {
        // newCount(stats.new) 는 워커가 DB dedupe 결과를 모른 채 보내는 값이라 신뢰하지 않는다.
        // reviews_new 는 CollectResultService 가 UPSERT 결과로 직접 센다.
    }
}
