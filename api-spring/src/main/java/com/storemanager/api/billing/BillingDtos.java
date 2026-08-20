package com.storemanager.api.billing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/** 레거시 자체 결제 조회·운영자 확인 DTO. Groble 신규 결제에는 사용하지 않는다. */
final class BillingDtos {

    private BillingDtos() {
    }

    record BillingMethod(String type) {
    }

    /** GET /stores/{storeId}/subscription 레거시 응답. */
    record SubscriptionResponse(String status, String planCode, long priceKrw, long vatKrw, long totalKrw,
            String currentPeriodStart, String currentPeriodEnd, BillingMethod billingMethod) {
    }

    /** PENDING 청구에만 동봉하는 입금 안내. 계좌 정보는 app.billing.* (환경변수)에서 온다. */
    record TransferInfo(String bankName, String accountNo, String accountHolder, String depositCode, String guide) {
    }

    /** GET /stores/{storeId}/payments 항목. transferInfo 는 PENDING 건에만 채운다. */
    record PaymentItem(String id, String status, long amountKrw, long vatKrw, long totalKrw, String dueAt,
            String paidAt, TransferInfo transferInfo) {
    }

    record PaymentListResponse(List<PaymentItem> items, boolean hasMore) {
    }

    /**
     * POST /internal/payments/{paymentId}/confirm 요청(docs/13 §9.1, Worker → 사람이 아니라 운영자 도구가 호출).
     * confirmedByUserId 는 선택 — 공유 시크릿 인증이라 JWT 주체가 없어 운영자를 특정할 수 없을 때는 비워도 된다.
     */
    record ConfirmPaymentRequest(@NotBlank String depositorName, @NotNull Instant paidAt, @NotNull Long amountKrw,
            Long confirmedByUserId) {
    }

    record ConfirmPaymentResponse(String id, String status, String paidAt) {
    }
}
