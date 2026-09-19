import { describe, expect, it } from "vitest";

import { riskBanner } from "./riskBanner";
import type { QueueEntry } from "../state/queueEntry";

/**
 * ★ 네이버는 어떤 답글도 자동으로 올라가지 않는다(NAVER ABSOLUTE RULES 6·7·8).
 *   그래서 위험한 리뷰라고 초안을 빼지 않고 **초안은 주되 무엇이 위험한지 알린다.**
 *   이 배너가 위험을 알리는 유일한 화면 장치다 — 지우면 사장님은 아무 경고 없이
 *   고위험 초안을 그대로 올리게 된다.
 */
function entry(over: Partial<QueueEntry> = {}): QueueEntry {
  return {
    reviewHash: "h", storeId: "1", rating: 1, body: "본문", draftContent: "초안",
    blocked: false, riskLevel: 0, riskReasons: [], state: "DRAFTED", edited: false,
    detectedAt: 0, ...over,
  } as QueueEntry;
}

describe("위험 배너", () => {
  it("안전한 건에는 아무것도 붙지 않는다", () => {
    expect(riskBanner(entry({ riskLevel: 0 }))).toBe("");
    expect(riskBanner(entry({ riskLevel: 1 }))).toBe("");
  });

  it("risk 2 는 확인 문구를, risk 3 은 경고 문구를 낸다", () => {
    expect(riskBanner(entry({ riskLevel: 2 }))).toContain("한 번 더 확인");
    expect(riskBanner(entry({ riskLevel: 3 }))).toContain("꼭 읽어보세요");
  });

  it("사유 코드를 사장님이 읽을 문구로 바꾼다", () => {
    const html = riskBanner(entry({ riskLevel: 3, riskReasons: ["HYGIENE", "THREAT"] }));
    expect(html).toContain("위생");
    expect(html).toContain("협박");
    expect(html).not.toContain("HYGIENE");
  });

  it("모르는 사유 코드는 숨기지 않고 그대로 보여준다", () => {
    // 서버가 사유를 늘렸는데 화면에서 조용히 사라지는 것이 가장 나쁘다.
    expect(riskBanner(entry({ riskLevel: 3, riskReasons: ["NEW_REASON"] }))).toContain("NEW_REASON");
  });

  it("사유가 비어도 위험도가 높으면 알린다", () => {
    expect(riskBanner(entry({ riskLevel: 3, riskReasons: [] }))).toContain("내용 확인 필요");
  });

  it("사유만 있고 위험도가 낮아도 알린다", () => {
    // risk 는 룰이 내리지만 사유는 모델이 붙일 수 있다. 둘 중 하나만 있어도 표시한다.
    expect(riskBanner(entry({ riskLevel: 1, riskReasons: ["FOREIGN_OBJECT"] }))).toContain("이물질");
  });

  it("사유 문구를 HTML 로 해석하지 않는다", () => {
    const html = riskBanner(entry({ riskLevel: 3, riskReasons: ["<img src=x onerror=alert(1)>"] }));
    expect(html).not.toContain("<img");
    expect(html).toContain("&lt;img");
  });
});
