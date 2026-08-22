import { apiRequest } from "./client";
import type { ReviewDetail, ReviewListResponse, ReviewResponse } from "./types";

export interface ReviewListFilter {
  category?: string;
  minRating?: number;
  maxRating?: number;
  riskLevel?: number;
  hasReply?: boolean;
  from?: string; // yyyy-MM-dd
  to?: string; // yyyy-MM-dd
  cursor?: string;
  size?: number;
}

// docs/13 §5, 실제 ReviewController(api-spring) 기준.
export const reviewsApi = {
  list: (storeId: string, filter: ReviewListFilter) => {
    const qs = new URLSearchParams();
    if (filter.category) qs.set("category", filter.category);
    if (filter.minRating != null) qs.set("minRating", String(filter.minRating));
    if (filter.maxRating != null) qs.set("maxRating", String(filter.maxRating));
    if (filter.riskLevel != null) qs.set("riskLevel", String(filter.riskLevel));
    if (filter.hasReply != null) qs.set("hasReply", String(filter.hasReply));
    if (filter.from) qs.set("from", filter.from);
    if (filter.to) qs.set("to", filter.to);
    if (filter.cursor) qs.set("cursor", filter.cursor);
    qs.set("size", String(filter.size ?? 20));
    return apiRequest<ReviewListResponse>(`/stores/${storeId}/reviews?${qs.toString()}`);
  },
  get: (reviewId: string) => apiRequest<ReviewDetail>(`/reviews/${reviewId}`),
};

/**
 * docs/13 §5 GET /reviews/{reviewId}. 실기동 확인 결과(2026-08-20) 백엔드에 컨트롤러가 존재한다.
 * 그래도 best-effort 로 흡수한다 — 리뷰가 삭제됐거나 storeId 불일치 등으로 개별 실패할 수 있고,
 * 리뷰 화면 전체를 막을 이유는 없다. 실패하면 "리뷰 정보를 불러올 수 없음" 자리표시자로 대체한다.
 */
export async function fetchReviewBestEffort(reviewId: string): Promise<ReviewResponse | null> {
  try {
    return await apiRequest<ReviewResponse>(`/reviews/${reviewId}`);
  } catch {
    return null;
  }
}
