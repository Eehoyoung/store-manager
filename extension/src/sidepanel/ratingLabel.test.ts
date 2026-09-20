import { describe, expect, it } from "vitest";
import { countNegative, ratingDisplay } from "./ratingLabel";
import { bulkApprovable } from "../state/machine";

/**
 * ★ 실기동에서 나온 버그다(2026-09-20). 네이버에는 별점이 안 붙는 리뷰가 있는데
 *   확장이 null 을 0 으로 접었다. 그러자
 *     - 화면: 칭찬 리뷰에 "개별 확인 필요" 배지 (왜인지 알 수 없다)
 *     - 서버: rating 0 → COMPLAINT, risk 1 → T2(sonnet) 로 라우팅
 *   0 은 "최저 평점" 이지 "모름" 이 아니다.
 */
describe("ratingDisplay", () => {
  it("별점 미상은 '별점 없음' 으로 밝힌다 — 낮은 별점인 척하지 않는다", () => {
    const d = ratingDisplay(null);
    expect(d.stars).toBe("별점 없음");
    expect(d.badge).toContain("알 수 없어");
    expect(d.badge).not.toBe("개별 확인 필요");
  });

  it("별점 1~2 는 종전대로 개별 확인 배지다", () => {
    expect(ratingDisplay(2)).toMatchObject({ stars: "★★", badge: "개별 확인 필요" });
  });

  it("별점 3 이상은 배지가 없다", () => {
    expect(ratingDisplay(5)).toMatchObject({ stars: "★★★★★", badge: null });
  });

  it("★ 별점 0 은 여전히 최저 평점이다 — 미상과 같이 취급하지 않는다", () => {
    expect(ratingDisplay(0).badge).toBe("개별 확인 필요");
  });
});

describe("countNegative", () => {
  it("별점 미상은 부정으로 세지 않는다 — 모르는 것을 나쁘다고 세지 않는다", () => {
    expect(countNegative([null, null, 5, 1, 2, 3])).toBe(2);
  });
});

describe("bulkApprovable — 문구는 갈라도 안전 동작은 같다", () => {
  const base = { state: "VIEWED" as const, blocked: false, riskLevel: 0 };

  it("★ 별점 미상은 일괄 승인에서 빠진다", () => {
    expect(bulkApprovable({ ...base, rating: null })).toBe(false);
  });

  it("별점 1~2 도 빠진다(절대 규칙)", () => {
    expect(bulkApprovable({ ...base, rating: 2 })).toBe(false);
  });

  it("별점 3 이상만 들어간다", () => {
    expect(bulkApprovable({ ...base, rating: 3 })).toBe(true);
  });
});
