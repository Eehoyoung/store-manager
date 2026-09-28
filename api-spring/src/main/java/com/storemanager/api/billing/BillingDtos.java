package com.storemanager.api.billing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 포트원 V2 빌링키 자동결제 DTO(2026-09-29, docs/13 §9). */
final class BillingDtos {

    private BillingDtos() {
    }

    record CustomerInfo(String customerId, String fullName, String email, String phoneNumber) {}

    /** GET /stores/{storeId}/billing 응답의 결제 이력 항목. */
    record PaymentItem(long amountKrw, String status, String createdAt, String paidAt, String failureReason) {}

    /**
     * GET /stores/{storeId}/billing 응답. 결제창에 넘길 값과 현재 결제 상태를 한 번에 준다 —
     * 화면이 채널 키를 따로 들고 있지 않게 서버가 준다.
     */
    record BillingView(boolean enabled, String portoneStoreId, String channelKey, CustomerInfo customer,
            String serviceState, boolean trialPending, int trialDays, String trialEndsAt, String nextBillingAt,
            String restrictedFrom, String lastPaidAt, boolean hasCard, boolean autoRenew, int renewalFailures,
            long amountKrw, long chargeNowKrw, String consentVersion, List<PaymentItem> payments) {}

    record CheckoutRequest(@NotBlank @Size(max = 200) String billingKey, boolean billingConsentAgreed,
            @NotBlank @Size(max = 20) String billingConsentVersion) {}

    record AutoRenewRequest(boolean on) {}
}
