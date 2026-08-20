import { apiRequest } from "./client";
import type { BulkApproveResponse, DraftListResponse, DraftResponse } from "./types";

export interface BulkApproveFilter {
  minRating?: number;
  maxRiskLevel?: number;
  category?: string[];
}

// docs/13 §6, 실제 DraftController/DraftDtos(api-spring) 기준. 큐 조회는 문서의 cursor 방식이 아니라
// 실제 구현인 page/size 다 — 코드가 정답이다.
export const draftsApi = {
  queue: (storeId: string, params: { status?: string; page?: number; size?: number }) => {
    const qs = new URLSearchParams();
    if (params.status) qs.set("status", params.status);
    qs.set("page", String(params.page ?? 0));
    qs.set("size", String(params.size ?? 20));
    return apiRequest<DraftListResponse>(`/stores/${storeId}/drafts?${qs.toString()}`);
  },
  patch: (draftId: string, content: string) =>
    apiRequest<DraftResponse>(`/drafts/${draftId}`, { method: "PATCH", body: { content } }),
  approve: (draftId: string, publishMode: "SCHEDULED" | "IMMEDIATE" = "SCHEDULED") =>
    apiRequest<DraftResponse>(`/drafts/${draftId}/approve`, {
      method: "POST",
      body: { publishMode },
      idempotencyKey: crypto.randomUUID(),
    }),
  reject: (draftId: string) => apiRequest<DraftResponse>(`/drafts/${draftId}/reject`, { method: "POST" }),
  bulkApprove: (storeId: string, filter?: BulkApproveFilter) =>
    apiRequest<BulkApproveResponse>("/drafts/bulk-approve", {
      method: "POST",
      body: { storeId, filter },
      idempotencyKey: crypto.randomUUID(),
    }),
};
