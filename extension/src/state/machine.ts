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

/**
 * 서버가 준 status 문자열을 큐 상태로 읽는다.
 *
 * ★ 왜 필요한가 — 확장 로컬 큐가 사라져도(POS 프로필 초기화·크롬 재설치·확장 재설치)
 *   서버에는 진행 상태가 그대로 남아 있다. 그걸 안 읽고 무조건 DRAFTED 로 저장하면
 *   **어제 승인까지 끝낸 리뷰가 "미확인" 으로 되살아난다.** 사장님은 처리한 일을 다시
 *   처리하게 되고, 화면의 "미확인 N건" 을 믿을 수 없게 된다.
 *
 * ★ 모르는 값은 DRAFTED 로 떨어뜨린다. 서버가 상태를 늘렸을 때 큐가 깨지는 것보다
 *   "한 번 더 확인해 주세요" 가 안전하다 — 건너뛰는 쪽으로 틀리지 않는다.
 *   ★ 특히 APPROVED·POSTED 로 **추측해서** 올리지 말 것. 확인하지 않은 답글이
 *     확인된 것처럼 보이면 그게 가장 나쁘다.
 */
export function parseQueueState(status: string | null | undefined): QueueState {
  return status != null && status in TRANSITIONS ? (status as QueueState) : "DRAFTED";
}

export interface QueueItem {
  state: QueueState;
  rating: number | null;
  blocked: boolean;
  riskLevel: number;
}

/**
 * 일괄 승인 대상 조건. 별점 1~2 는 개별 확인을 강제한다(절대 규칙).
 *
 * ★ 별점 미상(null)도 제외한다. 낮은 별점인지 아닌지를 모르는 채로 한꺼번에
 *   올리면 절대 규칙을 지켰다고 말할 수 없다. 모르면 개별 확인이다.
 */
export function bulkApprovable(item: QueueItem): boolean {
  return item.state === "VIEWED" && item.rating !== null && item.rating >= 3
    && !item.blocked && item.riskLevel < 2;
}

export function selectBulkTargets<T extends QueueItem>(items: T[]): T[] {
  return items.filter(bulkApprovable);
}
