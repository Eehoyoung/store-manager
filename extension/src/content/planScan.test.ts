import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { validateSelectorSpec } from "@selector-spec";
import { extractReviews } from "../selector/runtime";
import { planScan } from "./index";

/**
 * ★ 이 파일의 존재 이유 — content script 가 실측 스펙과 어긋나 있었다(2026-09-20).
 *   maskAndReport 가 raw.id / raw.hasReply / raw.createdAt 을 읽었는데 스펙이 내는
 *   필드는 authorRef / dateBlock / replyWriteButton 이다. 그래서 전건이 첫 줄에서
 *   early return 돼 **리뷰가 한 건도 수집되지 않았다.** 오류도 안 나고 큐만 비어 있어
 *   확장을 올려 봐도 "아직 리뷰가 없나 보다" 로 읽힌다.
 *
 *   테스트는 목 데이터가 아니라 **서버가 실제로 배포하는 스펙 + 실측 DOM 픽스처**를
 *   그대로 통과시킨다. 스펙과 코드가 갈라지면 여기서 깨져야 한다.
 */
const SPEC_PATH = resolve(
  __dirname,
  "../../../api-spring/src/main/resources/naver/selector-spec.json",
);
const FIXTURE_PATH = resolve(__dirname, "../selector/fixtures/smartplace-review-item.html");

function extractFromFixture() {
  const checked = validateSelectorSpec(JSON.parse(readFileSync(SPEC_PATH, "utf-8")));
  if (!checked.ok) throw new Error(`배포 스펙이 유효하지 않다: ${checked.errors.join(", ")}`);
  document.body.innerHTML = readFileSync(FIXTURE_PATH, "utf-8");
  return extractReviews(document, checked.spec.pages.reviewList);
}

describe("planScan — 배포 스펙 × 실측 DOM", () => {
  it("★ 2열 <ul> 3건을 모두 잡고, 답글 달린 1건만 제외해 2건을 초안 대상으로 낸다", () => {
    const { items } = extractFromFixture();
    expect(items).toHaveLength(3);

    const plan = planScan(items);
    expect(plan.targets).toHaveLength(2);
    expect(plan.alreadyReplied).toBe(1);
    expect(plan.unidentified).toBe(0);
    expect(plan.colliding).toBe(0);
  });

  it("초안 대상에는 서버로 보낼 재료가 다 있다 — 식별자·작성일·별점", () => {
    const plan = planScan(extractFromFixture().items);
    for (const t of plan.targets) {
      expect(t.identity).toMatch(/^https:\/\/m\.place\.naver\.com\/my\/\w+\/review\|\d{4}-\d{2}-\d{2}\|/);
      expect(t.writtenAt).toMatch(/^\d{4}-\d{2}-\d{2}$/);
      expect(typeof t.raw.rating).toBe("number");
    }
    expect(plan.targets.map((t) => t.raw.rating)).toEqual([5, 2]);
  });

  it("식별할 수 없으면 처리하지 않는다 — 빈 값끼리 묶어 충돌시키지 않는다", () => {
    const plan = planScan([
      { authorRef: null, dateBlock: "작성일2026. 9. 1(월)", replyWriteButton: true },
      { authorRef: "https://m.place.naver.com/my/x/review", dateBlock: "", replyWriteButton: true },
    ]);
    expect(plan.targets).toHaveLength(0);
    expect(plan.unidentified).toBe(2);
  });

  it("같은 식별자가 둘이면 양쪽 다 건너뛴다 — 잘못 붙이느니 안 붙인다", () => {
    const row = {
      authorRef: "https://m.place.naver.com/my/x/review",
      dateBlock: "방문일2026. 9. 1(월)작성일2026. 9. 2(화)",
      replyWriteButton: true,
    };
    const plan = planScan([{ ...row }, { ...row }]);
    expect(plan.targets).toHaveLength(0);
    expect(plan.colliding).toBe(2);
  });

  it("★ 셀렉터가 깨지면 초안 0건으로 멈춘다 — 이미 답글 단 리뷰에 또 달지 않는다", () => {
    // replyWriteButton 이 통째로 안 잡히는 상황(false/undefined)
    const plan = planScan([
      {
        authorRef: "https://m.place.naver.com/my/x/review",
        dateBlock: "작성일2026. 9. 2(화)",
        replyWriteButton: false,
      },
    ]);
    expect(plan.targets).toHaveLength(0);
    expect(plan.alreadyReplied).toBe(1);
  });
});
