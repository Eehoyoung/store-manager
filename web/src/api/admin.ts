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

export const adminApi = {
  me: () => apiRequest<{ admin: boolean }>("/admin/me"),
  requests: () => apiRequest<AffiliationRequest[]>("/admin/franchise-requests"),
  decide: (id: string, decision: "APPROVE" | "REJECT") =>
    apiRequest<void>(`/admin/franchise-requests/${id}`, { method: "PATCH", body: { decision } }),
};
