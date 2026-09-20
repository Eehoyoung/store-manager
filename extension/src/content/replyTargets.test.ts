import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { beforeEach, describe, expect, it } from "vitest";
import { validateSelectorSpec, type PageSpec } from "@selector-spec";
import { insertDraft } from "./index";

/**
 * 답글 입력창 셀렉터를 실측 DOM 으로 잠근다.
 *
 * ★ 이전 스펙은 replyInput="textarea" · replyForm="form" 이었고 **둘 다 추측**이었다.
 *   실측(2026-09-20) 결과 답글 영역에 <form> 이 아예 없다. 그 값으로 두면
 *   document.querySelector("form") 이 페이지의 다른 폼(검색·필터)을 잡고, 거기 달린
 *   submit 리스너가 isTrusted=true 로 발화해 **답글을 올리지 않았는데 POSTED 로
 *   기록된다.** 추측을 스펙에 남겨두면 이런 식으로 조용히 틀린다.
 */
const SPEC_PATH = resolve(__dirname, "../../../api-spring/src/main/resources/naver/selector-spec.json");
const OPEN_FIXTURE = resolve(__dirname, "../selector/fixtures/smartplace-reply-open.html");

function page(): PageSpec {
  const checked = validateSelectorSpec(JSON.parse(readFileSync(SPEC_PATH, "utf-8")));
  if (!checked.ok) throw new Error(checked.errors.join(", "));
  return checked.spec.pages.reviewList;
}

describe("답글 입력창 — 실측 스펙 × 열린 입력창 DOM", () => {
  beforeEach(() => {
    // jsdom 에 scrollIntoView 가 없다. 실브라우저에는 항상 있으므로 코드를 약하게
    // 만들지 않고 테스트에서 채운다.
    Element.prototype.scrollIntoView = () => {};
    document.body.innerHTML = readFileSync(OPEN_FIXTURE, "utf-8");
  });

  it("replyInput 이 textarea 를 찾고, 초안이 실제로 들어간다", () => {
    const ok = insertDraft(page(), "소중한 후기 감사합니다. 웨이팅은 개선하겠습니다.");
    expect(ok).toBe(true);
    expect(document.querySelector("textarea")!.value).toContain("웨이팅은 개선하겠습니다");
  });

  it("★ replyForm 을 두지 않는다 — 답글 영역에 <form> 이 없다", () => {
    expect(page().replyForm).toBeUndefined();
    expect(document.querySelector("form")).toBeNull();
  });

  it("replySubmitButton 이 등록 버튼을 정확히 하나 가리킨다 (닫기가 아니다)", () => {
    const sel = page().replySubmitButton!;
    const found = document.querySelectorAll(sel);
    expect(found).toHaveLength(1);
    expect(found[0].textContent).toBe("등록");
  });

  it("입력창이 열린 리뷰에는 답글 쓰기 버튼이 없다 — 그 스캔에서 제외된다", () => {
    expect(document.querySelector(page().replyOpenButton!)).toBeNull();
  });

  it("입력창이 닫혀 있으면 삽입은 조용히 실패한다 — 엉뚱한 곳에 쓰지 않는다", () => {
    document.body.innerHTML = "<div><textarea id='남의것'></textarea></div>";
    expect(insertDraft(page(), "내용")).toBe(false);
    expect((document.getElementById("남의것") as HTMLTextAreaElement).value).toBe("");
  });
});
