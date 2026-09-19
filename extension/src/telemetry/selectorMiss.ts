/**
 * 셀렉터 미스 텔레메트리. selectorKey / pagePath / extensionVersion 만 전송한다
 * — DOM·본문·닉네임·쿠키를 담지 않는다. timestamp 는 서버가 찍으므로 보내지 않는다.
 * 같은 키는 1시간에 1회만 전송한다.
 */
import type { ApiClient } from "../api/client";

const DEDUPE_WINDOW_MS = 60 * 60 * 1000;

export interface SelectorMissDeps {
  client: Pick<ApiClient, "postSelectorMiss">;
  extensionVersion: string;
  now?: () => number;
}

const lastSentAt = new Map<string, number>();

export async function reportSelectorMiss(
  deps: SelectorMissDeps,
  selectorKey: string,
  pagePath: string,
): Promise<void> {
  const now = (deps.now ?? Date.now)();
  const last = lastSentAt.get(selectorKey);
  if (last !== undefined && now - last < DEDUPE_WINDOW_MS) return;

  lastSentAt.set(selectorKey, now);
  await deps.client.postSelectorMiss({ selectorKey, pagePath, extensionVersion: deps.extensionVersion });
}

/** 테스트 전용: 모듈 전역 dedupe 상태 초기화. */
export function __resetSelectorMissDedupeForTest(): void {
  lastSentAt.clear();
}
