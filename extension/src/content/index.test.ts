import { afterEach, describe, expect, it } from "vitest";
import type { SelectorSpec } from "@selector-spec";
import validSpec from "../../../packages/selector-spec/fixtures/valid-review-list.json";
import { findPageSpec, isSessionExpired } from "./index";

const SPEC = validSpec as SelectorSpec;

afterEach(() => {
  document.body.innerHTML = "";
  history.pushState({}, "", "/");
});

describe("findPageSpec", () => {
  it("URL 이 match 패턴과 일치하면 해당 PageSpec 을 반환한다", () => {
    history.pushState({}, "", "/bizes/place/123/reviews");
    const page = findPageSpec(SPEC);
    expect(page).not.toBeNull();
    expect(page?.container).toBe(SPEC.pages.reviewList.container);
  });

  it("일치하는 페이지가 없으면 null 을 반환한다", () => {
    history.pushState({}, "", "/bizes/place/123/settings");
    expect(findPageSpec(SPEC)).toBeNull();
  });
});

describe("isSessionExpired", () => {
  it("리뷰 컨테이너가 DOM 에 있으면 세션 만료가 아니다", () => {
    document.body.innerHTML = `<div data-testid="review-list"></div>`;
    expect(isSessionExpired(SPEC.pages.reviewList)).toBe(false);
  });

  it("리뷰 컨테이너가 사라졌으면(로그인 페이지 리다이렉트 등) 세션 만료로 본다", () => {
    document.body.innerHTML = `<div>로그인 페이지</div>`;
    expect(isSessionExpired(SPEC.pages.reviewList)).toBe(true);
  });
});
