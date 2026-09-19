package com.storemanager.api.naver;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * /api/v1/naver/** 요청·응답 DTO (IMPLEMENTATION_PLAN_NAVER.md §5).
 * ★ 외부에는 매장 public_id(UUID)만 노출한다. review_hash 는 확장이 만든 불가역 해시라 그대로 오간다.
 */
final class NaverDtos {

    private NaverDtos() {
    }

    record DraftRequest(@NotBlank String storeId, @NotBlank String reviewHash, Integer rating,
            @Size(max = 10_000) String body, String createdAt, Boolean hasReply) {
    }

    record DraftResponse(String reviewHash, String status, String draft, boolean blocked,
            List<String> blockReasons, int riskLevel, String category, boolean bulkApprovable) {
    }

    record EventRequest(@NotBlank String storeId, @NotBlank String reviewHash, @NotBlank String event,
            Boolean edited, Integer editDistance) {
    }

    record BulkApproveRequest(@NotBlank String storeId, List<String> reviewHashes, @NotBlank String pin) {
    }

    /** 제외된 항목은 이유를 함께 돌려준다 — 화면이 "왜 빠졌는지" 보여줘야 한다(docs/naver/03 §Tier 1). */
    record BulkApproveResponse(List<String> approved, Map<String, String> excluded) {
    }

    record PairingCodeResponse(String code, long expiresInSeconds) {
    }

    record PairRequest(@NotBlank String code) {
    }

    /** ★ storeId 는 store.public_id(UUID) 문자열이다. 내부 BIGSERIAL 은 절대 노출하지 않는다. */
    record StoreRef(String storeId, String name) {
    }

    record PairResponse(String token, long expiresInSeconds, List<StoreRef> stores) {
    }

    record SetPinRequest(@NotBlank String pin) {
    }

    record SelectorMissRequest(String selectorKey, String pagePath, String extensionVersion) {
    }

    /** 매장별 상태별 건수. 화면의 "미답변 N" 배지가 이 값을 그대로 쓴다. */
    record StatusResponse(Map<String, Long> counts) {
    }
}
