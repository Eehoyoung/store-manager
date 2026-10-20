package com.storemanager.api.billing;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** 포트원 V2 빌링키 자동결제 DTO(2026-09-29, docs/13 9장). */
final class BillingDtos {

    private BillingDtos() {
    }

    record CustomerInfo(String customerId, String fullName, String email, String phoneNumber) {}

    /** GET /stores/{storeId}/billing 응답의 결제 이력 항목. */
    record PaymentItem(long amountKrw, String status, String createdAt, String paidAt, String failureReason) {}

    /**
     * GET /stores/{storeId}/billing 응답. 결제창에 넘길 값과 현재 결제 상태를 한 번에 준다 —
     * 화면이 채널 키를 따로 들고 있지 않게 서버가 준다.
     *
     * @param heldReplyCount 이용 제한으로 보류된 답글 수(BLOCKED·STORE_INACTIVE 단독). 결제를
     *        재개해도 자동으로 나가지 않는다 — 화면이 이 값으로 재개 배너를 띄운다.
     * @param unitPriceKrw 이번 결제 대상 월의 매장 단가(부가세 별도, V49 가맹 브랜드 구간 단가).
     * @param brandPaidStoreCount 매장이 가맹 브랜드 소속일 때만 값이 있다(소속 아니면 null) — 그
     *        브랜드의 현재 유료 이용 매장 수. 화면이 "N개 매장 기준 단가입니다" 를 보여줄 때 쓴다.
     */
    record BillingView(boolean enabled, String portoneStoreId, String channelKey, CustomerInfo customer,
            String serviceState, boolean trialPending, int trialDays, String trialEndsAt, String nextBillingAt,
            String restrictedFrom, String lastPaidAt, boolean hasCard, boolean autoRenew, int renewalFailures,
            long amountKrw, long chargeNowKrw, String consentVersion, List<PaymentItem> payments,
            int heldReplyCount, int unitPriceKrw, Integer brandPaidStoreCount,
            @JsonInclude(JsonInclude.Include.NON_NULL) TieredPlanView tiered) {

        /** 선택형 요금 필드가 없는 기존 형태(꺼짐). tiered 가 null 이라 JSON 에서 키가 빠진다. */
        BillingView(boolean enabled, String portoneStoreId, String channelKey, CustomerInfo customer,
                String serviceState, boolean trialPending, int trialDays, String trialEndsAt, String nextBillingAt,
                String restrictedFrom, String lastPaidAt, boolean hasCard, boolean autoRenew, int renewalFailures,
                long amountKrw, long chargeNowKrw, String consentVersion, List<PaymentItem> payments,
                int heldReplyCount, int unitPriceKrw, Integer brandPaidStoreCount) {
            this(enabled, portoneStoreId, channelKey, customer, serviceState, trialPending, trialDays, trialEndsAt,
                    nextBillingAt, restrictedFrom, lastPaidAt, hasCard, autoRenew, renewalFailures, amountKrw,
                    chargeNowKrw, consentVersion, payments, heldReplyCount, unitPriceKrw, brandPaidStoreCount, null);
        }
    }

    /** 선택형 요금(D38) 현재/다음 결제 명단과 서버 산정 가격. 켜짐일 때만 응답에 나온다. */
    /** optionTotalsKrw: 배달앱 1·2·3개 상품의 월 요금(VAT 포함) - 결제 전 상품표·동의 금액용. canChange: 지금 변경 예약 가능. */
    record TieredPlanView(PlanPrice current, @JsonInclude(JsonInclude.Include.NON_NULL) PlanPrice next,
            boolean changedThisMonth, List<Integer> optionTotalsKrw, boolean canChange) {}

    record PlanPrice(List<String> deliveryPlatforms, int supplyKrw, int vatKrw, int totalKrw) {}

    /** PUT /stores/{storeId}/billing/plan 요청. 클라이언트가 보낸 금액은 받지 않는다. */
    record PlanChangeRequest(List<String> deliveryPlatforms) {}

    /** deliveryPlatforms: 선택형 요금 켜짐에서만 필수(꺼짐이면 무시). */
    record CheckoutRequest(@NotBlank @Size(max = 200) String billingKey, boolean billingConsentAgreed,
            @NotBlank @Size(max = 20) String billingConsentVersion, List<String> deliveryPlatforms) {

        CheckoutRequest(String billingKey, boolean billingConsentAgreed, String billingConsentVersion) {
            this(billingKey, billingConsentAgreed, billingConsentVersion, null);
        }
    }

    record AutoRenewRequest(boolean on) {}

    /** POST /stores/{storeId}/billing/held-replies/resume 요청. 비우거나 생략하면 보류 답글 전부. */
    record ResumeHeldRepliesRequest(List<UUID> draftIds) {}

    record HeldReplyResumeResponse(int resumed, BillingView view) {}
}
