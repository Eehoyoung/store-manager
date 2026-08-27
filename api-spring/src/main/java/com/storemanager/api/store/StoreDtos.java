package com.storemanager.api.store;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record CreateStoreRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 50) String category,
        @Size(max = 300) String address) {
}

record UpdateStoreRequest(
        @Size(max = 100) String name,
        @Size(max = 50) String category,
        @Size(max = 300) String address) {
}

record StoreResponse(
        String id,
        String name,
        String brandName,
        String category,
        String address,
        String status,
        // 배달앱 로그인 정보 처리 위탁 동의 시각. null 이면 수집·게시가 전량 스킵된다 —
        // 프론트가 이 상태를 사장님에게 보여줘야 하므로 반드시 내려준다.
        java.time.Instant activatedAt) {
}
