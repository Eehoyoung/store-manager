/**
 * spec 기반 DOM 추출. innerHTML 은 읽지 않는다 — textContent 만 사용한다.
 */
import type { FieldSpec, PageSpec } from "@selector-spec";

export type RawReview = Record<string, string | number | boolean | null>;

export interface ExtractResult {
  items: RawReview[];
  misses: string[];
}

/** 리뷰 식별자처럼 항목 루트 엘리먼트 자신에 속성이 붙는 경우도 있어 자신도 포함해 찾는다. */
function queryWithinItem(el: Element, selector: string): Element | null {
  if (el.matches(selector)) return el;
  return el.querySelector(selector);
}

/** 문자열에서 첫 번째 숫자를 뽑는다. "별점5점" → 5, "4.5점" → 4.5, 없으면 null. */
function firstNumber(text: string): number | null {
  const m = /-?\d+(?:\.\d+)?/.exec(text);
  if (!m) return null;
  const n = Number(m[0]);
  return Number.isFinite(n) ? n : null;
}

function parseField(el: Element, spec: FieldSpec): string | number | boolean | null {
  const target = queryWithinItem(el, spec.selector);
  if (!target) return spec.parse === "exists" ? false : null;

  if (spec.parse === "exists") return true;

  const raw = spec.attr ? target.getAttribute(spec.attr) : target.textContent;
  const text = (raw ?? "").trim();

  switch (spec.parse) {
    // ★ 숫자가 한국어에 둘러싸여 온다. 스마트플레이스 별점은 "별점5점" 이라
    //   parseInt 가 곧장 NaN 을 낸다(실측 2026-09-20). 앞뒤 글자를 떼고 **첫 숫자**를 쓴다.
    //   ★ 첫 숫자라는 점이 중요하다 — 셀렉터를 느슨하게 잡아 "리뷰 12 사진 34" 같은
    //     덩어리를 가리키면 엉뚱한 값이 조용히 들어온다. 셀렉터를 좁게 유지할 것.
    case "int": {
      const n = firstNumber(text);
      return n === null ? null : Math.trunc(n);
    }
    case "float":
      return firstNumber(text);
    case "text":
    default:
      return text;
  }
}

export function extractReviews(root: Document | Element, page: PageSpec): ExtractResult {
  const misses: string[] = [];
  // ★ 컨테이너가 여러 개일 수 있다. 스마트플레이스 리뷰 목록은 2열 레이아웃이라
  //   같은 클래스의 <ul> 이 두 개이고, 리뷰가 그 둘에 나뉘어 들어간다(실측 2026-09-20:
  //   6건 + 5건). querySelector 로 첫 번째만 보면 **절반이 조용히 사라진다** —
  //   오류도 안 나고 건수만 줄어서 알아채기 가장 어려운 종류의 누락이다.
  const containers = root.querySelectorAll(page.container);
  if (containers.length === 0) {
    misses.push("container");
    return { items: [], misses };
  }

  const itemEls: Element[] = [];
  containers.forEach((c) => {
    c.querySelectorAll(page.item).forEach((el) => itemEls.push(el));
  });
  if (itemEls.length === 0) {
    misses.push("item");
    return { items: [], misses };
  }

  const items: RawReview[] = [];
  itemEls.forEach((el) => {
    const record: RawReview = {};
    for (const [key, fieldSpec] of Object.entries(page.fields)) {
      const value = parseField(el, fieldSpec);
      // exists 필드는 부재 자체가 유효한 값(false)이므로 미스로 세지 않는다.
      if (value === null && fieldSpec.parse !== "exists") {
        misses.push(`fields.${key}`);
      }
      record[key] = value;
    }
    items.push(record);
  });

  return { items, misses };
}
