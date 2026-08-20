import { apiRequest } from "./client";
import type {
  AnalyticsIssuesResponse,
  AnalyticsMenusResponse,
  AnalyticsResponsePerformance,
  AnalyticsSummaryResponse,
  AnalyticsTrendResponse,
} from "./types";

// docs/13 §8, 실제 AnalyticsController(api-spring) 기준. from/to 를 생략하면 서버 기본값(최근 30일).
function qs(from?: string, to?: string) {
  const p = new URLSearchParams();
  if (from) p.set("from", from);
  if (to) p.set("to", to);
  const s = p.toString();
  return s ? `?${s}` : "";
}

export const analyticsApi = {
  summary: (storeId: string, from?: string, to?: string) =>
    apiRequest<AnalyticsSummaryResponse>(`/stores/${storeId}/analytics/summary${qs(from, to)}`),
  trend: (storeId: string, from?: string, to?: string) =>
    apiRequest<AnalyticsTrendResponse>(`/stores/${storeId}/analytics/trend${qs(from, to)}`),
  issues: (storeId: string, from?: string, to?: string) =>
    apiRequest<AnalyticsIssuesResponse>(`/stores/${storeId}/analytics/issues${qs(from, to)}`),
  menus: (storeId: string, from?: string, to?: string) =>
    apiRequest<AnalyticsMenusResponse>(`/stores/${storeId}/analytics/menus${qs(from, to)}`),
  response: (storeId: string, from?: string, to?: string) =>
    apiRequest<AnalyticsResponsePerformance>(`/stores/${storeId}/analytics/response${qs(from, to)}`),
};
