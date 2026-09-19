/**
 * 승인 큐 상태머신. 순수 함수만 — DOM·chrome API 의존 없음.
 * 상태 다이어그램은 docs/naver/04-extension-spec.md 참고.
 */

export type QueueState =
  | "DETECTED"
  | "DRAFTED"
  | "VIEWED"
  | "EDITED"
  | "APPROVED"
  | "INSERTED"
  | "POSTED"
  | "SKIPPED";

// ★ DRAFTED → APPROVED 직접 전이는 없다. 반드시 VIEWED 를 거친다.
const TRANSITIONS: Record<QueueState, QueueState[]> = {
  DETECTED: ["DRAFTED"],
  DRAFTED: ["VIEWED", "SKIPPED"],
  VIEWED: ["EDITED", "APPROVED", "SKIPPED"],
  EDITED: ["APPROVED"],
  APPROVED: ["INSERTED"],
  INSERTED: ["POSTED"],
  POSTED: [],
  SKIPPED: [],
};

export function canTransition(from: QueueState, to: QueueState): boolean {
  return TRANSITIONS[from].includes(to);
}

export interface QueueItem {
  state: QueueState;
  rating: number;
  blocked: boolean;
  riskLevel: number;
}

/** 일괄 승인 대상 조건. 별점 1~2 는 개별 확인을 강제한다(절대 규칙). */
export function bulkApprovable(item: QueueItem): boolean {
  return item.state === "VIEWED" && item.rating >= 3 && !item.blocked && item.riskLevel < 2;
}

export function selectBulkTargets<T extends QueueItem>(items: T[]): T[] {
  return items.filter(bulkApprovable);
}
