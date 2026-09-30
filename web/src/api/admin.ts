import { apiRequest } from "./client";
import type {
  AdminAffiliationRow,
  AdminAuditLogRow,
  AdminFranchiseDetail,
  AdminFranchiseListItem,
  AdminFranchisePricing,
  AffiliationStatus,
  FranchiseStatus,
  HqMemberStatus,
  OtpVerifyResponse,
} from "./types";

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

// docs/26a auth.otp — 시스템 관리자는 app_user 없이 이메일 화이트리스트 + OTP 로만 로그인한다.
export const adminAuthApi = {
  request: (email: string) => apiRequest<{ sent: boolean }>("/admin-auth/request", { method: "POST", body: { email } }),
  verify: (email: string, code: string) =>
    apiRequest<OtpVerifyResponse>("/admin-auth/verify", { method: "POST", body: { email, code } }),
  logout: () => apiRequest<void>("/admin-auth/logout", { method: "POST" }),
};

// enc = brandName 은 한글이라 경로에 넣을 때 반드시 encodeURIComponent 한다(안 하면 404).
const enc = (brand: string) => encodeURIComponent(brand);

export const adminApi = {
  me: () => apiRequest<{ admin: boolean; email?: string }>("/admin/me"),

  // ── 가맹점 소속 승인·해제 ─────────────────────────────────────────────
  affiliations: (status: AffiliationStatus) =>
    apiRequest<AdminAffiliationRow[]>(`/admin/franchise-affiliations?status=${status}`),
  decide: (id: string, decision: "APPROVE" | "REJECT", reason: string) =>
    apiRequest<void>(`/admin/franchise-requests/${id}`, { method: "PATCH", body: { decision, reason } }),
  release: (id: string, reason: string) =>
    apiRequest<void>(`/admin/franchise-affiliations/${id}/release`, { method: "POST", body: { reason } }),
  hqWithdrawalRequests: () => apiRequest<HqWithdrawalRequest[]>("/admin/hq-withdrawal-requests"),

  // ── 가맹본부 관리(시스템 콘솔) ────────────────────────────────────────
  franchises: (q?: string) =>
    apiRequest<AdminFranchiseListItem[]>(`/admin/franchises${q ? `?q=${encodeURIComponent(q)}` : ""}`),
  createFranchise: (body: {
    brandName: string;
    memberName: string;
    memberEmail: string;
    memberTitle?: string;
    issueJoinCode: boolean;
    reason: string;
  }) => apiRequest<{ brandName: string; memberId: string; joinCode: string | null }>("/admin/franchises", {
    method: "POST",
    body,
  }),
  franchiseDetail: (brand: string) => apiRequest<AdminFranchiseDetail>(`/admin/franchises/${enc(brand)}`),
  setFranchiseStatus: (brand: string, status: FranchiseStatus, reason: string) =>
    apiRequest<void>(`/admin/franchises/${enc(brand)}/status`, { method: "PATCH", body: { status, reason } }),
  addMember: (brand: string, body: { name: string; email: string; title?: string; reason: string }) =>
    apiRequest<{ memberId: string }>(`/admin/franchises/${enc(brand)}/members`, { method: "POST", body }),
  updateMember: (
    brand: string,
    memberId: string,
    body: { name?: string; title?: string; status?: HqMemberStatus; reason: string },
  ) => apiRequest<void>(`/admin/franchises/${enc(brand)}/members/${memberId}`, { method: "PATCH", body }),
  removeMember: (brand: string, memberId: string, reason: string) =>
    apiRequest<void>(`/admin/franchises/${enc(brand)}/members/${memberId}`, { method: "DELETE", body: { reason } }),
  revokeMemberSessions: (brand: string, memberId: string, reason: string) =>
    apiRequest<void>(`/admin/franchises/${enc(brand)}/members/${memberId}/sessions/revoke`, {
      method: "POST",
      body: { reason },
    }),
  rotateJoinCode: (brand: string, reason: string) =>
    apiRequest<{ joinCode: string }>(`/admin/franchises/${enc(brand)}/join-code/rotate`, {
      method: "POST",
      body: { reason },
    }),
  setJoinCodeStatus: (brand: string, active: boolean, reason: string) =>
    apiRequest<void>(`/admin/franchises/${enc(brand)}/join-code/status`, { method: "PATCH", body: { active, reason } }),
  auditLogs: (brand: string, limit = 100) =>
    apiRequest<AdminAuditLogRow[]>(`/admin/franchises/${enc(brand)}/audit-logs?limit=${limit}`),

  // ── 가맹 브랜드 구간 단가(차등 가격제) ───────────────────────────────
  pricing: () => apiRequest<AdminFranchisePricing[]>("/admin/franchises/pricing"),
  setCommittedStoreCount: (brand: string, committedStoreCount: number | null, reason?: string) =>
    apiRequest<AdminFranchisePricing>(`/admin/franchises/${enc(brand)}/committed-store-count`, {
      method: "PATCH",
      body: { committedStoreCount, reason },
    }),

  // ── 매장 서비스 상태 ────────────────────────────────────────────────
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
