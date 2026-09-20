import { describe, expect, it } from "vitest";
import { deriveHasReply } from "./replyState";

describe("deriveHasReply", () => {
  it("답글 쓰기 버튼이 있으면 아직 답글이 없다", () => {
    expect(deriveHasReply(true)).toBe(false);
  });

  it("버튼이 사라졌으면 이미 답글이 달린 것이다 (실측 2026-09-20)", () => {
    expect(deriveHasReply(false)).toBe(true);
  });

  /**
   * ★ 이 테스트가 방향을 잠근다. 셀렉터가 깨져 값이 안 오면 "답글 있음" 으로 붙어
   *   초안이 0건이 된다 — 쓸모는 없지만 사고는 없고 화면에 바로 드러난다.
   *   반대로 붙이면 이미 답글 단 리뷰에 또 초안을 만든다. 뒤집지 말 것.
   */
  it("값을 못 읽으면 안전한 쪽(답글 있음)으로 붙는다", () => {
    expect(deriveHasReply(undefined)).toBe(true);
    expect(deriveHasReply(null)).toBe(true);
    expect(deriveHasReply("")).toBe(true);
  });
});
