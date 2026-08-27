import { apiRequest } from "./client";

export interface AffiliationRequest {
  id: string;
  brandName: string;
  requesterName: string;
  requesterEmail: string;
  storeName: string;
  storeAddress: string | null;
  requestedAt: string;
}
export interface HqWithdrawalRequest { id: number; requesterName: string; requesterEmail: string; requestedAt: string; }

export interface StoreServiceRow {
  storeId: string;
  storeName: string;
  ownerName: string | null;
  ownerEmail: string | null;
  credentialConsentCompleted: boolean;
  /** null 이면 구독 행 자체가 없다 = 한 번도 결제되지 않은 매장 */
  subscriptionStatus: string | null;
  currentPeriodEnd: string | null;
  /** 자격증명 위탁 동의와 구독을 모두 통과했는가 — 실제로 비용이 나가는 상태인지 */
  serviceActive: boolean;
}

export const adminApi = {
  me: () => apiRequest<{ admin: boolean }>("/admin/me"),
  requests: () => apiRequest<AffiliationRequest[]>("/admin/franchise-requests"),
  hqWithdrawalRequests: () => apiRequest<HqWithdrawalRequest[]>("/admin/hq-withdrawal-requests"),
  decide: (id: string, decision: "APPROVE" | "REJECT") =>
    apiRequest<void>(`/admin/franchise-requests/${id}`, { method: "PATCH", body: { decision } }),
  stores: () => apiRequest<StoreServiceRow[]>("/admin/stores"),
  activate: (storeId: string, note: string) =>
    apiRequest<void>(`/admin/stores/${storeId}/subscription/activate`, { method: "POST", body: { note } }),
  suspend: (storeId: string, note: string) =>
    apiRequest<void>(`/admin/stores/${storeId}/subscription/suspend`, { method: "POST", body: { note } }),

  /** 재시도를 소진하고 실패한 건. 조회 전용 — 재시도 API 는 두지 않는다. */
  failures: (limit = 100) => apiRequest<FailureReport>(`/admin/failures?limit=${limit}`),
};
export type PublishFailureRow = {
  storeName: string;
  reviewId: string | null;
  platform: string;
  platformReviewId: string | null;
  rating: number | null;
  reviewExcerpt: string | null;
  draftId: string | null;
  failCode: string | null;
  failReason: string | null;
  retryCount: number;
  failedAt: string | null;
};

export type CollectFailureRow = {
  storeName: string | null;
  platform: string;
  loginIdMasked: string | null;
  jobType: string | null;
  startDate: string | null;
  endDate: string | null;
  ecode: string | null;
  failedAt: string | null;
};

export type AlimtalkFailureRow = {
  storeName: string | null;
  template: string;
  status: string;
  errorCode: string | null;
  attemptCount: number;
  sentAt: string | null;
  refType: string | null;
  refId: number | null;
  providerMessageIdPresent: boolean;
};

export type FailureReport = {
  publishFailures: PublishFailureRow[];
  collectFailures: CollectFailureRow[];
  alimtalkFailures: AlimtalkFailureRow[];
};
