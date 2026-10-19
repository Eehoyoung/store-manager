import type { DeliveryPlatform } from "../api/types";

export const ALL_PLATFORMS: DeliveryPlatform[] = ["BAEMIN", "COUPANGEATS", "YOGIYO"];

export const PLATFORM_LABEL: Record<string, string> = {
  BAEMIN: "배달의민족",
  COUPANGEATS: "쿠팡이츠",
  YOGIYO: "요기요",
};

/**
 * 상품별 월 요금(VAT 포함) — 결제 전 '안내용' 표시값이다. 실제 청구액은 서버가 계산한다.
 * 서버 PricingTier 와 같이 바꿀 것. 결제 후·변경 후에는 서버 응답 값(tiered.*.totalKrw)만 보여준다.
 */
export const PLAN_PRICE_KRW: Record<1 | 2 | 3, number> = { 1: 25000, 2: 29000, 3: 33000 };

export type PlanCount = 1 | 2 | 3;

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
  return platforms.length >= 3
    ? "배달앱 3개 (전체)"
    : `배달앱 ${platforms.length}개 (${platforms.map((p) => PLATFORM_LABEL[p] ?? p).join(", ")})`;
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
