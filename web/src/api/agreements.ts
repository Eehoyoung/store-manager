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

export const agreementsApi = {
  catalog: () => apiRequest<{ currentVersion: string; items: AgreementItem[] }>("/agreements"),
  document: (slug: string) => apiRequest<{ version: string; content: string }>(`/agreements/documents/${slug}`),
  history: () => apiRequest<AgreementHistoryRow[]>("/agreements/history"),
  requestHqWithdrawal: () => apiRequest<void>("/agreements/hq-withdrawal", { method: "POST" }),
};
