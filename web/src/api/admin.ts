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

export interface StoreServiceRow {
  storeId: string;
  storeName: string;
  ownerName: string | null;
  ownerEmail: string | null;
  contractSigned: boolean;
  /** null 이면 구독 행 자체가 없다 = 한 번도 결제되지 않은 매장 */
  subscriptionStatus: string | null;
  currentPeriodEnd: string | null;
  /** 계약과 구독을 모두 통과했는가 — 실제로 비용이 나가는 상태인지 */
  serviceActive: boolean;
}

export const adminApi = {
  me: () => apiRequest<{ admin: boolean }>("/admin/me"),
  requests: () => apiRequest<AffiliationRequest[]>("/admin/franchise-requests"),
  decide: (id: string, decision: "APPROVE" | "REJECT") =>
    apiRequest<void>(`/admin/franchise-requests/${id}`, { method: "PATCH", body: { decision } }),
  stores: () => apiRequest<StoreServiceRow[]>("/admin/stores"),
  activate: (storeId: string, note: string) =>
    apiRequest<void>(`/admin/stores/${storeId}/subscription/activate`, { method: "POST", body: { note } }),
  suspend: (storeId: string, note: string) =>
    apiRequest<void>(`/admin/stores/${storeId}/subscription/suspend`, { method: "POST", body: { note } }),
};
