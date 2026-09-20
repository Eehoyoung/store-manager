import { describe, expect, it } from "vitest";
import { parseReviewDates, reviewIdentity } from "./identity";

// 실측 텍스트 (2026-09-20). 라벨과 날짜가 공백 없이 이어져 온다.
const REAL = "방문일2026. 9. 10(목)1번째작성일2026. 9. 16(수)영수증 인증";

describe("parseReviewDates", () => {
  it("실측 날짜 블록에서 방문일과 작성일을 라벨로 구분해 뽑는다", () => {
    expect(parseReviewDates(REAL)).toEqual({ visitedAt: "2026-09-10", writtenAt: "2026-09-16" });
  });

  it("★ 순서가 아니라 라벨로 찾는다 — 두 행이 뒤바뀌어도 올바르게 읽는다", () => {
    const swapped = "작성일2026. 9. 16(수)방문일2026. 9. 10(목)";
    expect(parseReviewDates(swapped)).toEqual({ visitedAt: "2026-09-10", writtenAt: "2026-09-16" });
  });

  it("방문일이 없는 리뷰(예약 없이 작성)도 작성일은 읽는다", () => {
    expect(parseReviewDates("작성일2026. 1. 3(금)")).toEqual({
      visitedAt: null,
      writtenAt: "2026-01-03",
    });
  });

  it("한 자리 월·일을 두 자리로 채운다", () => {
    expect(parseReviewDates("작성일2026. 1. 3(금)").writtenAt).toBe("2026-01-03");
  });

  it("빈 값·null 에 예외를 던지지 않는다", () => {
    expect(parseReviewDates(null)).toEqual({ visitedAt: null, writtenAt: null });
    expect(parseReviewDates("")).toEqual({ visitedAt: null, writtenAt: null });
    expect(parseReviewDates("날짜 없음")).toEqual({ visitedAt: null, writtenAt: null });
  });
});

describe("reviewIdentity", () => {
  const A = "https://m.place.naver.com/my/aaaaaaaaaaaaaaaaaaaaaaaa/review";
  const B = "https://m.place.naver.com/my/bbbbbbbbbbbbbbbbbbbbbbbb/review";

  it("작성자와 작성일이 다르면 서로 다른 식별자가 나온다", () => {
    expect(reviewIdentity(A, "2026-09-16")).not.toBe(reviewIdentity(B, "2026-09-16"));
    expect(reviewIdentity(A, "2026-09-16")).not.toBe(reviewIdentity(A, "2026-09-17"));
  });

  it("같은 리뷰는 항상 같은 식별자다 — 본문 펼침과 무관하다", () => {
    expect(reviewIdentity(A, "2026-09-16")).toBe(reviewIdentity(A, "2026-09-16"));
  });

  /**
   * ★ 이 테스트가 이 파일의 존재 이유다. 실측 하네스에서 두 리뷰가 같은 reviewHash 를
   *   받았다 — 재료가 둘 다 비어 "undefined" 를 해싱했기 때문이다. 그러면 두 번째
   *   리뷰는 중복으로 취급돼 영영 답글을 받지 못한다. 식별할 수 없으면 건너뛴다.
   */
  it("재료가 하나라도 비면 null 이다 — 빈 값끼리 이어 붙여 충돌시키지 않는다", () => {
    expect(reviewIdentity(null, "2026-09-16")).toBeNull();
    expect(reviewIdentity(A, null)).toBeNull();
    expect(reviewIdentity("", "")).toBeNull();
    expect(reviewIdentity("  ", "2026-09-16")).toBeNull();
  });
});
