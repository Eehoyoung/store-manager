import { apiRequest } from "./client";
import type { HqAnalyticsResponse, HqBrand, HqReviewListResponse, HqStore } from "./types";

export interface HqReviewFilter {
  storeId?: string;
  minRating?: number;
  maxRating?: number;
  category?: string;
  riskLevel?: number;
  status?: string;
  issueTag?: string;
  from?: string; // yyyy-MM-dd
  to?: string; // yyyy-MM-dd
  page?: number;
  size?: number;
}

// docs/13 §11.5, 실제 HqController(api-spring) 기준. 조회 전용 — 쓰기 메서드를 추가하지 않는다.
// ★ brandName 은 한글이다 — 경로에 넣을 때 반드시 encodeURIComponent 한다(안 하면 404).
export const hqApi = {
  brands: () => apiRequest<HqBrand[]>("/hq/brands"),

  stores: (brandName: string) => apiRequest<HqStore[]>(`/hq/brands/${encodeURIComponent(brandName)}/stores`),

  reviews: (brandName: string, filter: HqReviewFilter) => {
    const qs = new URLSearchParams();
    if (filter.storeId) qs.set("storeId", filter.storeId);
    if (filter.minRating != null) qs.set("minRating", String(filter.minRating));
    if (filter.maxRating != null) qs.set("maxRating", String(filter.maxRating));
    if (filter.category) qs.set("category", filter.category);
    if (filter.riskLevel != null) qs.set("riskLevel", String(filter.riskLevel));
    if (filter.status) qs.set("status", filter.status);
    if (filter.issueTag) qs.set("issueTag", filter.issueTag);
    if (filter.from) qs.set("from", filter.from);
    if (filter.to) qs.set("to", filter.to);
    qs.set("page", String(filter.page ?? 0));
    qs.set("size", String(filter.size ?? 20));
    return apiRequest<HqReviewListResponse>(`/hq/brands/${encodeURIComponent(brandName)}/reviews?${qs.toString()}`);
  },

  analytics: (brandName: string, from?: string, to?: string) => {
    const qs = new URLSearchParams();
    if (from) qs.set("from", from);
    if (to) qs.set("to", to);
    const s = qs.toString();
    return apiRequest<HqAnalyticsResponse>(
      `/hq/brands/${encodeURIComponent(brandName)}/analytics${s ? `?${s}` : ""}`,
    );
  },
};
