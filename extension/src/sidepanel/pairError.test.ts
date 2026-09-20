import { describe, expect, it } from "vitest";
import { pairErrorMessage } from "./pairError";

/**
 * ★ 실기동에서 이것 때문에 한참 헤맸다(2026-09-20). API 주소가 운영 서버로
 *   남아 있어 요청이 로컬에 닿지도 않았는데, 화면은 "코드가 올바르지 않습니다" 만
 *   띄웠다. 서버 Redis 에는 코드가 멀쩡히 살아 있었다.
 *   원인마다 사장님이 할 일이 다르다 — 문구를 뭉치지 말 것.
 */
describe("pairErrorMessage", () => {
  it("코드 문제면 재발급을 안내한다", () => {
    expect(pairErrorMessage("BAD_CODE")).toContain("새로 발급");
  });

  it("못 닿았으면 주소를 보여주고 설정으로 보낸다 — 재발급을 시키지 않는다", () => {
    const msg = pairErrorMessage("UNREACHABLE", "https://review.sodamlabs.kr");
    expect(msg).toContain("https://review.sodamlabs.kr");
    expect(msg).toContain("API 주소 설정");
    expect(msg).not.toContain("발급");
  });

  it("모르는 이유도 숨기지 않고 그대로 보여준다", () => {
    expect(pairErrorMessage("HTTP_503")).toContain("HTTP_503");
  });
});
