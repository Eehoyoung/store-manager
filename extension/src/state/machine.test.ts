import { describe, expect, it } from "vitest";
import { bulkApprovable, canTransition, parseQueueState, type QueueItem, type QueueState, selectBulkTargets } from "./machine";

const ALL_STATES: QueueState[] = [
  "DETECTED",
  "DRAFTED",
  "VIEWED",
  "EDITED",
  "APPROVED",
  "INSERTED",
  "POSTED",
  "SKIPPED",
];

describe("canTransition", () => {
  it("★ DRAFTED → APPROVED 직접 전이는 불가능하다", () => {
    expect(canTransition("DRAFTED", "APPROVED")).toBe(false);
  });

  it("허용된 정상 경로는 모두 통과한다", () => {
    expect(canTransition("DETECTED", "DRAFTED")).toBe(true);
    expect(canTransition("DRAFTED", "VIEWED")).toBe(true);
    expect(canTransition("VIEWED", "EDITED")).toBe(true);
    expect(canTransition("VIEWED", "APPROVED")).toBe(true);
    expect(canTransition("EDITED", "APPROVED")).toBe(true);
    expect(canTransition("APPROVED", "INSERTED")).toBe(true);
    expect(canTransition("INSERTED", "POSTED")).toBe(true);
    expect(canTransition("DRAFTED", "SKIPPED")).toBe(true);
    expect(canTransition("VIEWED", "SKIPPED")).toBe(true);
  });

  it("전이표 전수 — 정의되지 않은 조합은 전부 거부한다", () => {
    for (const from of ALL_STATES) {
      for (const to of ALL_STATES) {
        // 자기 자신으로의 전이는 어떤 경우에도 허용하지 않는다(멱등 처리는 호출부 책임)
        if (from === to) expect(canTransition(from, to)).toBe(false);
      }
    }
    // 종결 상태에서는 어디로도 못 간다
    for (const to of ALL_STATES) {
      expect(canTransition("POSTED", to)).toBe(false);
      expect(canTransition("SKIPPED", to)).toBe(false);
    }
  });
});

function item(overrides: Partial<QueueItem> = {}): QueueItem {
  return { state: "VIEWED", rating: 5, blocked: false, riskLevel: 0, ...overrides };
}

describe("bulkApprovable / selectBulkTargets", () => {
  it("VIEWED + 별점 3 이상 + 미차단 + riskLevel<2 만 통과한다", () => {
    expect(bulkApprovable(item())).toBe(true);
    expect(bulkApprovable(item({ state: "DRAFTED" }))).toBe(false);
    expect(bulkApprovable(item({ rating: 2 }))).toBe(false);
    expect(bulkApprovable(item({ rating: 1 }))).toBe(false);
    expect(bulkApprovable(item({ blocked: true }))).toBe(false);
    expect(bulkApprovable(item({ riskLevel: 2 }))).toBe(false);
  });

  it("selectBulkTargets 는 rating 1·2 와 VIEWED 아닌 건을 제외한다", () => {
    const items = [
      item({ rating: 5 }),
      item({ rating: 1 }),
      item({ rating: 2 }),
      item({ state: "DRAFTED", rating: 5 }),
      item({ state: "EDITED", rating: 5 }),
      item({ blocked: true, rating: 5 }),
    ];
    const targets = selectBulkTargets(items);
    expect(targets).toHaveLength(1);
    expect(targets[0]).toBe(items[0]);
  });
});

describe("parseQueueState — 서버 상태 복원", () => {
  /**
   * ★ 확장 로컬 큐가 사라져도(POS 프로필 초기화·크롬 재설치·확장 재설치) 서버에는
   *   진행 상태가 남아 있다. 이전에는 무조건 DRAFTED 로 저장해서, 어제 승인까지
   *   끝낸 리뷰가 다음 날 "미확인" 으로 되살아났다. 돈 문제는 아니지만 사장님이
   *   처리한 일을 다시 처리하게 되고 "미확인 N건" 을 믿을 수 없게 된다.
   */
  it("서버가 준 상태를 그대로 읽는다", () => {
    for (const s of ["DRAFTED", "VIEWED", "EDITED", "APPROVED", "INSERTED", "POSTED", "SKIPPED"] as const) {
      expect(parseQueueState(s)).toBe(s);
    }
  });

  it("모르는 값·빈 값은 DRAFTED 로 떨어진다 — 큐가 깨지느니 한 번 더 확인한다", () => {
    expect(parseQueueState("WAT")).toBe("DRAFTED");
    expect(parseQueueState(null)).toBe("DRAFTED");
    expect(parseQueueState(undefined)).toBe("DRAFTED");
    expect(parseQueueState("")).toBe("DRAFTED");
  });

  /**
   * ★ 이 방향이 중요하다. 모르는 값을 APPROVED·POSTED 로 추측해 올리면 확인하지
   *   않은 답글이 확인된 것처럼 보인다. 건너뛰는 쪽으로 틀리지 않는다.
   */
  it("복원된 DRAFTED 는 일괄 승인 대상이 아니다 — VIEWED 를 거쳐야 한다", () => {
    expect(bulkApprovable({ state: parseQueueState("WAT"), rating: 5, blocked: false, riskLevel: 0 })).toBe(false);
  });

  it("복원된 VIEWED 는 종전대로 일괄 승인 대상이다", () => {
    expect(bulkApprovable({ state: parseQueueState("VIEWED"), rating: 5, blocked: false, riskLevel: 0 })).toBe(true);
  });
});
