import { describe, expect, it } from "vitest";
import { bulkApprovable, canTransition, type QueueItem, type QueueState, selectBulkTargets } from "./machine";

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
