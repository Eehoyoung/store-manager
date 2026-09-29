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
  /** docs/26a — 개별 리뷰 제공 동의 기능 플래그. false 면 동의 카드 자체를 렌더링하지 않는다. */
  reviewSharingEnabled: boolean;
  reviewSharingAgreed: boolean;
}

export const agreementsApi = {
  catalog: () => apiRequest<{ currentVersion: string; items: AgreementItem[] }>("/agreements"),
  document: (slug: string) => apiRequest<{ version: string; content: string }>(`/agreements/documents/${slug}`),
  history: () => apiRequest<AgreementHistoryRow[]>("/agreements/history"),
  hqWithdrawalStatus: () => apiRequest<HqWithdrawalStatus>("/agreements/hq-withdrawal/status"),
  requestHqWithdrawal: () => apiRequest<void>("/agreements/hq-withdrawal", { method: "POST" }),
  /** 플래그가 꺼져 있으면 404. 철회는 agreed:false 로 같은 API 를 다시 호출한다. */
  setHqReviewSharing: (agreed: boolean) =>
    apiRequest<void>("/agreements/hq-review-sharing", { method: "POST", body: { agreed } }),
};
