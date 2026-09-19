import { describe, expect, it } from "vitest";
import { hashAuthor, maskReviewBody, reviewHash } from "./mask";

describe("maskReviewBody", () => {
  it("전화번호를 마스킹한다 (하이픈 유무 모두)", () => {
    expect(maskReviewBody("연락처 010-1234-5678 입니다")).toBe("연락처 [전화번호] 입니다");
    expect(maskReviewBody("01012345678 로 연락주세요")).toBe("[전화번호] 로 연락주세요");
  });

  it("이메일을 마스킹한다", () => {
    expect(maskReviewBody("메일은 owner@example.co.kr 입니다")).toBe("메일은 [이메일] 입니다");
  });

  it("숫자 10자리 이상 연속을 마스킹한다 (주문번호 등)", () => {
    expect(maskReviewBody("주문번호 1234567890123 확인해주세요")).toBe("주문번호 [번호] 확인해주세요");
  });

  it("한국 주소 패턴(시/도 + 구/군)을 마스킹한다", () => {
    expect(maskReviewBody("서울시 강남구에서 배달왔어요")).toBe("[주소]에서 배달왔어요");
  });

  it("여러 개가 동시에 등장해도 모두 치환한다", () => {
    const input = "010-1234-5678 로 연락주시거나 owner@example.com 로 메일 주세요. 서울시 강남구 삽니다.";
    const result = maskReviewBody(input);
    expect(result).not.toMatch(/010-1234-5678/);
    expect(result).not.toMatch(/owner@example\.com/);
    expect(result).not.toMatch(/서울시 강남구/);
    expect(result).toContain("[전화번호]");
    expect(result).toContain("[이메일]");
    expect(result).toContain("[주소]");
  });

  it("한국어 일반 문장은 오염되지 않는다", () => {
    expect(maskReviewBody("30분 늦었어요")).toBe("30분 늦었어요");
    expect(maskReviewBody("2인분 시켰는데 양이 적어요")).toBe("2인분 시켰는데 양이 적어요");
    expect(maskReviewBody("별점 5점 드립니다")).toBe("별점 5점 드립니다");
  });
});

describe("hashAuthor", () => {
  it("12자 hex 를 반환하고 같은 입력에 같은 출력을 낸다", async () => {
    const h1 = await hashAuthor("히리릴", "store-salt-1");
    const h2 = await hashAuthor("히리릴", "store-salt-1");
    expect(h1).toMatch(/^[0-9a-f]{12}$/);
    expect(h1).toBe(h2);
  });

  it("원문 닉네임이 반환값에 포함되지 않는다", async () => {
    const h = await hashAuthor("히리릴", "store-salt-1");
    expect(h).not.toContain("히리릴");
  });

  it("매장(salt)이 다르면 같은 닉네임도 다른 해시가 나온다", async () => {
    const h1 = await hashAuthor("김철수", "store-a");
    const h2 = await hashAuthor("김철수", "store-b");
    expect(h1).not.toBe(h2);
  });
});

describe("reviewHash", () => {
  it("64자 hex 를 반환한다 (DB review_hash CHAR(64) 와 길이 일치)", async () => {
    const h = await reviewHash("review-123", "store-1");
    expect(h).toMatch(/^[0-9a-f]{64}$/);
  });

  it("같은 입력에 같은 출력을 낸다", async () => {
    const h1 = await reviewHash("review-123", "store-1");
    const h2 = await reviewHash("review-123", "store-1");
    expect(h1).toBe(h2);
  });
});
