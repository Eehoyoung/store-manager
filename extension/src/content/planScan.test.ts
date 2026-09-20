import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { validateSelectorSpec } from "@selector-spec";
import { extractReviews } from "../selector/runtime";
import type { PageSpec } from "@selector-spec";
import { planScan, scanSummary } from "./index";

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

function specPage(): PageSpec {
  const checked = validateSelectorSpec(JSON.parse(readFileSync(SPEC_PATH, "utf-8")));
  if (!checked.ok) throw new Error(`배포 스펙이 유효하지 않다: ${checked.errors.join(", ")}`);
  return checked.spec.pages.reviewList;
}

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

describe("scanSummary", () => {
  const empty = { targets: [], unidentified: 0, colliding: 0, alreadyReplied: 0 };

  /**
   * ★ 이 테스트의 이유 — 실기동에서 "리뷰 0건" 만 찍히고 **왜** 0인지가 없어
   *   왕복이 한 번 날아갔다(2026-09-20). 컨테이너를 못 찾은 것과 항목이 아직
   *   안 그려진 것은 대처가 완전히 다르다. 미스 키를 반드시 함께 낸다.
   */
  it("0건일 때 미스 키를 함께 낸다", () => {
    expect(scanSummary(0, empty, ["item"], false)).toContain("미스: item");
  });

  it("같은 미스가 리뷰 수만큼 쌓여도 한 번만 쓴다", () => {
    const line = scanSummary(3, empty, ["fields.authorRef", "fields.authorRef", "fields.body"], true);
    expect(line).toContain("미스: fields.authorRef, fields.body");
  });

  it("정상이면 미스 표기가 없고, 연결 여부를 밝힌다", () => {
    expect(scanSummary(9, empty, [], true)).not.toContain("미스");
    expect(scanSummary(9, empty, [], false)).toContain("미연결");
  });
});

describe("부분 결손은 셀렉터 고장이 아니다", () => {
  /**
   * ★ 실기동에서 이것 때문에 "일시 점검 중" 알림이 초당 여러 번 떴다(2026-09-20).
   *   네이버에는 본문 없는 키워드 리뷰가 있고("이런 점이 좋았어요" 만 고른 리뷰),
   *   스크롤로 방금 붙은 <li> 를 그려지는 중에 잡을 수도 있다. 전부 정상 변동이다.
   *   한 건 비었다고 misses 에 넣으면 킬스위치(MAX_MISSES 3)가 즉시 돈다.
   */
  it("일부만 비면 misses 에 넣지 않는다 — fieldMisses 로만 센다", () => {
    const { items, misses, fieldMisses } = extractFromFixture();
    // ★ 첫 리뷰의 본문 앵커를 전부 지운다 — 한 개만 지우면 "더보기" 앵커가 남아
    //   여전히 잡힌다(픽스처의 첫 li 에는 본문 + 더보기 두 개가 있다).
    const first = document.body.querySelector('li[class*="Review_pui_review"]')!;
    for (const el of first.querySelectorAll('[data-pui-click-code="text"]')) el.remove();
    const again = extractReviews(document, specPage());

    expect(items).toHaveLength(3);
    expect(misses).not.toContain("fields.body");
    expect(fieldMisses.body ?? 0).toBe(0);

    // 3건 중 1건만 본문이 없어졌다 → 미스가 아니라 결손 1건
    expect(again.misses).not.toContain("fields.body");
    expect(again.fieldMisses.body).toBe(1);
  });

  it("전건이 비면 그때는 셀렉터 고장이다", () => {
    document.body.innerHTML = readFileSync(FIXTURE_PATH, "utf-8");
    for (const el of document.body.querySelectorAll('[data-pui-click-code="text"]')) el.remove();
    const { items, misses, fieldMisses } = extractReviews(document, specPage());
    expect(items).toHaveLength(3);
    expect(misses).toContain("fields.body");
    expect(fieldMisses.body).toBe(3);
  });

  it("요약이 부분 결손과 셀렉터 미스를 구분해 보여준다", () => {
    const empty = { targets: [], unidentified: 0, colliding: 0, alreadyReplied: 0 };
    const line = scanSummary(10, empty, ["fields.rating"], true, { body: 2, rating: 10 });
    expect(line).toContain("빈 필드: body 2/10");
    expect(line).toContain("★미스: fields.rating");
    expect(line).not.toContain("빈 필드: rating"); // 전건 결손은 미스 쪽에만
  });
});
