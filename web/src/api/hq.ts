import { apiRequest, BASE_URL } from "./client";
import { getAccessToken } from "../auth/tokenStore";
import type {
  HqAnalyticsResponse,
  HqBrand,
  HqBrandPricing,
  HqFeatures,
  HqOverviewResponse,
  HqReportResponse,
  HqReviewDetail,
  HqReviewListResponse,
  HqStore,
  OtpVerifyResponse,
} from "./types";

// docs/26a auth.otp — 본부 담당자는 비밀번호가 아니라 이메일 OTP 로만 로그인한다.
export const hqAuthApi = {
  request: (email: string) => apiRequest<{ sent: boolean }>("/hq-auth/request", { method: "POST", body: { email } }),
  verify: (email: string, code: string) =>
    apiRequest<OtpVerifyResponse>("/hq-auth/verify", { method: "POST", body: { email, code } }),
  logout: () => apiRequest<void>("/hq-auth/logout", { method: "POST" }),
};

const enc = (brand: string) => encodeURIComponent(brand);

export interface HqReviewFilter {
  from?: string;
  to?: string;
  storeId?: string;
  platform?: string;
  rating?: number;
  status?: string;
  riskLevel?: number;
  cursor?: string;
  size?: number;
}

// docs/13 §11.5, docs/26a §8.4, 실제 HqController(api-spring) 기준. 조회 전용 — 쓰기 메서드를 추가하지 않는다.
// ★ brandName 은 한글이다 — 경로에 넣을 때 반드시 encodeURIComponent 한다(안 하면 404).
export const hqApi = {
  features: () => apiRequest<HqFeatures>("/hq/features"),

  brands: () => apiRequest<HqBrand[]>("/hq/brands"),

  overview: (brandName: string) => apiRequest<HqOverviewResponse>(`/hq/brands/${enc(brandName)}/overview`),

  pricing: (brandName: string) => apiRequest<HqBrandPricing>(`/hq/brands/${enc(brandName)}/pricing`),

  stores: (brandName: string) => apiRequest<HqStore[]>(`/hq/brands/${enc(brandName)}/stores`),

  analytics: (brandName: string, from?: string, to?: string) => {
    const qs = new URLSearchParams();
    if (from) qs.set("from", from);
    if (to) qs.set("to", to);
    const s = qs.toString();
    return apiRequest<HqAnalyticsResponse>(`/hq/brands/${enc(brandName)}/analytics${s ? `?${s}` : ""}`);
  },

  // 플래그(reviewAccessEnabled)가 꺼져 있으면 404 — 화면은 /hq/features 로 먼저 확인하고 메뉴를 숨긴다.
  reviews: (brandName: string, filter: HqReviewFilter) => {
    const qs = new URLSearchParams();
    if (filter.from) qs.set("from", filter.from);
    if (filter.to) qs.set("to", filter.to);
    if (filter.storeId) qs.set("storeId", filter.storeId);
    if (filter.platform) qs.set("platform", filter.platform);
    if (filter.rating != null) qs.set("rating", String(filter.rating));
    if (filter.status) qs.set("status", filter.status);
    if (filter.riskLevel != null) qs.set("riskLevel", String(filter.riskLevel));
    if (filter.cursor) qs.set("cursor", filter.cursor);
    qs.set("size", String(filter.size ?? 20));
    return apiRequest<HqReviewListResponse>(`/hq/brands/${enc(brandName)}/reviews?${qs.toString()}`);
  },
  review: (brandName: string, reviewId: string) =>
    apiRequest<HqReviewDetail>(`/hq/brands/${enc(brandName)}/reviews/${reviewId}`),

  report: (brandName: string, from: string, to: string) =>
    apiRequest<HqReportResponse>(`/hq/brands/${enc(brandName)}/report?from=${from}&to=${to}&format=json`),
};

/**
 * CSV 는 JSON 이 아니라 apiRequest 를 쓸 수 없다 — 토큰을 헤더에만 실어 직접 fetch 한다.
 * 쿼리스트링에 토큰을 넣지 않는다(문서 26a web.ownerConsentUi 규약과 같은 원칙).
 */
export async function downloadHqReportCsv(brandName: string, from: string, to: string): Promise<Blob> {
  const token = getAccessToken();
  const res = await fetch(
    `${BASE_URL}/hq/brands/${enc(brandName)}/report?from=${from}&to=${to}&format=csv`,
    {
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
      credentials: "include",
    },
  );
  if (!res.ok) throw new Error("보고서를 내려받지 못했습니다.");
  return res.blob();
}
