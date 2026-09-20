import { describe, expect, it } from "vitest";
import type { PageSpec } from "@selector-spec";
import { extractReviews } from "./runtime";

const PAGE: PageSpec = {
  match: "/bizes/place/*/reviews",
  container: "[data-testid='review-list']",
  item: "[data-testid='review-item']",
  fields: {
    id: { selector: "[data-review-id]", attr: "data-review-id" },
    rating: { selector: ".rating", parse: "int" },
    body: { selector: ".review-body", parse: "text" },
    authorName: { selector: ".author", parse: "text" },
    createdAt: { selector: "time", attr: "datetime" },
    hasReply: { selector: ".reply-badge", parse: "exists" },
  },
  replyInput: "textarea[name='reply']",
  replyForm: "form[data-testid='reply-form']",
};

function buildDom(html: string): Document {
  return new DOMParser().parseFromString(`<div data-testid="review-list">${html}</div>`, "text/html");
}

describe("extractReviews", () => {
  it("리뷰 3건을 추출하고 parse 타입별로 변환한다", () => {
    const html = [1, 2, 3]
      .map(
        (n) => `
      <div data-testid="review-item" data-review-id="r${n}">
        <span class="rating">${n}</span>
        <p class="review-body">맛있어요 ${n}</p>
        <span class="author">사장님${n}</span>
        <time datetime="2026-09-1${n}"></time>
        ${n === 1 ? '<span class="reply-badge"></span>' : ""}
      </div>`,
      )
      .join("");
    const doc = buildDom(html);

    const { items, misses } = extractReviews(doc, PAGE);

    expect(items).toHaveLength(3);
    expect(misses).toHaveLength(0);
    expect(items[0]).toEqual({
      id: "r1",
      rating: 1,
      body: "맛있어요 1",
      authorName: "사장님1",
      createdAt: "2026-09-11",
      hasReply: true,
    });
    expect(items[1].hasReply).toBe(false);
  });

  it("필드 셀렉터가 틀리면 misses 에 기록되고 예외를 던지지 않는다", () => {
    const html = `
      <div data-testid="review-item" data-review-id="r1">
        <span class="rating">5</span>
        <!-- .review-body 없음 -->
        <span class="author">사장님</span>
      </div>`;
    const doc = buildDom(html);

    const { items, misses } = extractReviews(doc, PAGE);

    expect(items).toHaveLength(1);
    expect(misses).toContain("fields.body");
    expect(misses).toContain("fields.createdAt");
    expect(items[0].body).toBeNull();
  });

  it("container 자체를 못 찾으면 misses 에 기록하고 빈 배열을 반환한다", () => {
    const doc = new DOMParser().parseFromString("<div>다른 페이지</div>", "text/html");
    const { items, misses } = extractReviews(doc, PAGE);
    expect(items).toEqual([]);
    expect(misses).toEqual(["container"]);
  });

  it("item 자체를 못 찾으면 misses 에 기록하고 빈 배열을 반환한다", () => {
    const doc = buildDom("<p>리뷰 없음</p>");
    const { items, misses } = extractReviews(doc, PAGE);
    expect(items).toEqual([]);
    expect(misses).toEqual(["item"]);
  });
});

// ★ 2026-09-20 실측 회귀 — 스마트플레이스는 2열 레이아웃이라 같은 클래스의 <ul> 이
//   두 개이고 리뷰가 그 둘에 나뉜다(실측 6건 + 5건). 첫 컨테이너만 보면 절반이
//   조용히 사라진다. 오류가 안 나고 건수만 줄어 알아채기 가장 어려운 누락이다.
describe("컨테이너가 여러 개인 2열 레이아웃", () => {
  it("모든 컨테이너의 항목을 합쳐서 추출한다", () => {
    document.body.innerHTML = `
      <ul class="Review_columns_list__QiSQh">
        <li class="Review_pui_review__6lInP"><a data-pui-click-code="text">첫째 열 1</a></li>
        <li class="Review_pui_review__6lInP"><a data-pui-click-code="text">첫째 열 2</a></li>
      </ul>
      <ul class="Review_columns_list__QiSQh">
        <li class="Review_pui_review__6lInP"><a data-pui-click-code="text">둘째 열 1</a></li>
      </ul>`;
    const page: PageSpec = {
      match: "/bizes/place/*/reviews",
      container: 'ul[class*="Review_columns_list"]',
      item: 'li[class*="Review_pui_review"]',
      fields: { body: { selector: '[data-pui-click-code="text"]', parse: "text" } },
    };
    const { items, misses } = extractReviews(document, page);
    expect(misses).toEqual([]);
    expect(items).toHaveLength(3);
    expect(items.map((i) => i.body)).toEqual(["첫째 열 1", "첫째 열 2", "둘째 열 1"]);
  });
});
