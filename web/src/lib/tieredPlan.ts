import type { DeliveryPlatform } from "../api/types";

export type PlanCount = 1 | 2 | 3;

export const ALL_PLATFORMS: DeliveryPlatform[] = ["BAEMIN", "COUPANGEATS", "YOGIYO"];

export const PLATFORM_LABEL: Record<string, string> = {
  BAEMIN: "배달의민족",
  COUPANGEATS: "쿠팡이츠",
  YOGIYO: "요기요",
};

/** 서버 상품표(tiered.optionTotalsKrw, 1·2·3개 순)에서 n개 상품 요금. 값이 없거나 이상하면 null - 결제를 열지 않는다. */
export function optionPrice(totals: number[] | undefined, n: PlanCount): number | null {
  const v = totals?.[n - 1];
  return typeof v === "number" && Number.isFinite(v) && v > 0 ? v : null;
}

export interface PlanSelection {
  /** 아무것도 미리 고르지 않는다 — null 이 처음 상태. */
  count: PlanCount | null;
  /** count 가 1·2 일 때 고른 배달앱. 3개 상품은 전부라 쓰지 않는다. */
  platforms: DeliveryPlatform[];
}

export const EMPTY_SELECTION: PlanSelection = { count: null, platforms: [] };

export function isSelectionComplete(s: PlanSelection): boolean {
  if (s.count === null) return false;
  return s.count === 3 || s.platforms.length === s.count;
}

/** 서버로 보낼 명단. 선택이 덜 끝났으면 null. */
export function selectedPlatforms(s: PlanSelection): DeliveryPlatform[] | null {
  if (!isSelectionComplete(s)) return null;
  return s.count === 3 ? [...ALL_PLATFORMS] : [...s.platforms];
}

export function planLabel(platforms: string[]): string {
  return `배달앱 ${platforms.length}개 (${platforms.map((p) => PLATFORM_LABEL[p] ?? p).join(", ")})`;
}

export function fmtDate(d: Date | string): string {
  return (typeof d === "string" ? new Date(d) : d).toLocaleDateString("ko-KR");
}

/** 한국 시간 기준 현재 월과 다음 달(1~12). 월 1회 변경 제한이 한국 시간 달력 월이라 브라우저 시간대와 무관하게 맞춘다. */
export function kstMonths(now: Date = new Date()): { month: number; nextMonth: number } {
  const month = Number(new Intl.DateTimeFormat("en-US", { timeZone: "Asia/Seoul", month: "numeric" }).format(now));
  return { month, nextMonth: (month % 12) + 1 };
}

/**
 * 카드 등록 버튼을 열어도 되는지. 선택 완료 + 서버 가격 값이 와 있을 것 + 동의 + 진행 중 아님.
 * quoteReady 는 서버 응답의 가격(tiered.current.totalKrw)이 유효한 숫자인지다.
 * selection 이 null 이면 상품 선택이 필요 없는 경우(카드 교체)다.
 */
export function canCheckout(a: { selection: PlanSelection | null; quoteReady: boolean; agreed: boolean; busy: boolean }): boolean {
  if (a.busy || !a.agreed || !a.quoteReady) return false;
  return a.selection === null || isSelectionComplete(a.selection);
}
