import { apiRequest, ApiError } from "./client";

export type BillingServiceState = "UNPAID" | "TRIAL" | "ACTIVE" | "GRACE" | "RESTRICTED" | "SUSPENDED" | "CANCELED";

export interface BillingCustomer {
  customerId: string;
  fullName: string;
  email: string;
  phoneNumber: string | null;
}

export interface BillingPayment {
  amountKrw: number;
  status: string;
  createdAt: string;
  paidAt: string | null;
  failureReason: string | null;
}

export interface BillingResponse {
  enabled: boolean;
  portoneStoreId: string;
  channelKey: string;
  customer: BillingCustomer;
  serviceState: BillingServiceState;
  trialPending: boolean;
  trialDays: number;
  trialEndsAt: string | null;
  nextBillingAt: string | null;
  restrictedFrom: string | null;
  lastPaidAt: string | null;
  hasCard: boolean;
  autoRenew: boolean;
  renewalFailures: number;
  amountKrw: number;
  chargeNowKrw: number;
  /** 가맹 브랜드 구간 단가 적용 시 매장 1곳의 월 단가. 비가맹은 amountKrw 와 같다. */
  unitPriceKrw: number;
  /** 가맹 브랜드 유료 이용 매장 수 — 이 단가의 근거. 비가맹 매장은 null. */
  brandPaidStoreCount: number | null;
  consentVersion: string;
  payments: BillingPayment[];
  /** 이용 중지로 게시되지 않은 채 BLOCKED(STORE_INACTIVE) 로 남은 예약 답글 수. */
  heldReplyCount: number;
}

export interface ResumeHeldRepliesResponse {
  resumed: number;
  view: BillingResponse;
}

export const billingApi = {
  get: (storeId: string) => apiRequest<BillingResponse>(`/stores/${storeId}/billing`),
  checkout: (storeId: string, payload: { billingKey: string; billingConsentAgreed: true; billingConsentVersion: string }) =>
    apiRequest<BillingResponse>(`/stores/${storeId}/billing/checkout`, { method: "POST", body: payload }),
  setAutoRenew: (storeId: string, on: boolean) =>
    apiRequest<BillingResponse>(`/stores/${storeId}/billing/auto-renew`, { method: "PUT", body: { on } }),
  /** draftIds 를 생략하면 이용 중지로 보류된 답글 전부를 게시 예약한다. */
  resumeHeldReplies: (storeId: string, draftIds?: string[]) =>
    apiRequest<ResumeHeldRepliesResponse>(`/stores/${storeId}/billing/held-replies/resume`, {
      method: "POST",
      body: draftIds ? { draftIds } : {},
    }),
};

/** 매장 API가 결제 미비로 막혔을 때(402) 공통 판별 — 빈 화면 대신 결제 안내로 분기한다. */
export function isPaymentRequiredError(e: unknown): boolean {
  return e instanceof ApiError && e.code === "SUBSCRIPTION_PAYMENT_REQUIRED";
}

/** 결제 관련 ApiError 코드를 사장님이 이해할 수 있는 한국어 문구로 옮긴다. */
export function billingErrorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    switch (e.code) {
      case "SUBSCRIPTION_PAYMENT_REQUIRED":
        return "결제가 필요합니다. 결제수단을 등록해 주세요.";
      case "PAYMENT_DECLINED": {
        const reason = e.details?.reason;
        return `카드 결제가 거절됐습니다.${typeof reason === "string" ? ` (${reason})` : ""} 다른 카드로 다시 시도해 주세요.`;
      }
      case "PAYMENT_PENDING":
        return "결제 확인이 진행 중입니다. 잠시 후 다시 확인해 주세요.";
      case "BILLING_BUSY":
        return "처리 중인 결제가 있습니다. 잠시 후 다시 시도해 주세요.";
      case "BILLING_KEY_INVALID":
        return "카드 등록에 실패했습니다. 처음부터 다시 시도해 주세요.";
      case "BILLING_CONSENT_REQUIRED":
        return "자동결제 동의가 필요합니다.";
      case "CONSENT_VERSION_MISMATCH":
        return "약관이 갱신되었습니다. 새로고침한 뒤 다시 시도해 주세요.";
      case "SERVICE_UNAVAILABLE":
        return "지금은 결제를 받을 수 없습니다. 잠시 후 다시 시도해 주세요.";
      default:
        return e.message;
    }
  }
  return e instanceof Error ? e.message : "결제 처리 중 오류가 발생했습니다.";
}
