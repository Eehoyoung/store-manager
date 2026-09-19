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

function parseField(el: Element, spec: FieldSpec): string | number | boolean | null {
  const target = queryWithinItem(el, spec.selector);
  if (!target) return spec.parse === "exists" ? false : null;

  if (spec.parse === "exists") return true;

  const raw = spec.attr ? target.getAttribute(spec.attr) : target.textContent;
  const text = (raw ?? "").trim();

  switch (spec.parse) {
    case "int": {
      const n = Number.parseInt(text, 10);
      return Number.isNaN(n) ? null : n;
    }
    case "float": {
      const n = Number.parseFloat(text);
      return Number.isNaN(n) ? null : n;
    }
    case "text":
    default:
      return text;
  }
}

export function extractReviews(root: Document | Element, page: PageSpec): ExtractResult {
  const misses: string[] = [];
  const container = root.querySelector(page.container);
  if (!container) {
    misses.push("container");
    return { items: [], misses };
  }

  const itemEls = container.querySelectorAll(page.item);
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
