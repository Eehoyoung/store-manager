import { apiRequest } from "./client";
import type { HqAnalyticsResponse, HqBrand, HqStore } from "./types";

// docs/13 §11.5, 실제 HqController(api-spring) 기준. 조회 전용 — 쓰기 메서드를 추가하지 않는다.
// ★ brandName 은 한글이다 — 경로에 넣을 때 반드시 encodeURIComponent 한다(안 하면 404).
// ★ WP-01(2026-08-28) — 개별 리뷰 통합 조회(reviews)를 제거했다. hq-data-sharing.md 가
// 본부는 집계만 볼 수 있고 개별 리뷰는 볼 수 없다고 명시해 서버 엔드포인트를 없앴다.
export const hqApi = {
  brands: () => apiRequest<HqBrand[]>("/hq/brands"),

  stores: (brandName: string) => apiRequest<HqStore[]>(`/hq/brands/${encodeURIComponent(brandName)}/stores`),

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
