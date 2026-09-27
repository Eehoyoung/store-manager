import { apiRequest } from "./client";

export interface AgreementItem {
  code: string;
  name: string;
  required: boolean;
  summary: string;
  documentUrl: string;
}

export interface AgreementHistoryRow {
  code: string;
  agreed: boolean;
  agreedAt: string;
  docVersion: string;
  documentUrl: string;
}

export interface HqWithdrawalStatus {
  brandName: string | null;
  canRequest: boolean;
  requested: boolean;
}

export const agreementsApi = {
  catalog: () => apiRequest<{ currentVersion: string; items: AgreementItem[] }>("/agreements"),
  document: (slug: string) => apiRequest<{ version: string; content: string }>(`/agreements/documents/${slug}`),
  history: () => apiRequest<AgreementHistoryRow[]>("/agreements/history"),
  hqWithdrawalStatus: () => apiRequest<HqWithdrawalStatus>("/agreements/hq-withdrawal/status"),
  requestHqWithdrawal: () => apiRequest<void>("/agreements/hq-withdrawal", { method: "POST" }),
};
