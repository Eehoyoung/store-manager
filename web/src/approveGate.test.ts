// 위험 리뷰 승인 버튼 판정(canApproveBlockedDraft) — 절대규칙 3 의 화면 쪽 문지기. 최종 판정은 서버(RiskApprovalService).
import { describe, expect, it } from "vitest";
import { APPROVABLE_RISK_FLAG, DRAFT_CONTENT_MAX_LENGTH, canApproveBlockedDraft } from "./api/drafts";

const ok = { guardrailFlags: [APPROVABLE_RISK_FLAG], riskAcknowledged: true, content: "고객님 죄송합니다." };

describe("위험 리뷰 승인 버튼 판정", () => {
  it("위험도 플래그 하나·확인 체크·정상 본문이면 승인할 수 있다", () => {
    expect(canApproveBlockedDraft(ok)).toEqual({ ok: true });
  });

  it("다른 안전규칙 플래그가 섞이거나 위험도 플래그가 아니면 막는다", () => {
    for (const flags of [[APPROVABLE_RISK_FLAG, "G3_COMPENSATION"], ["G3_COMPENSATION"], []]) {
      expect(canApproveBlockedDraft({ ...ok, guardrailFlags: flags }).ok, String(flags)).toBe(false);
    }
  });

  it("플래그를 모르면(null) 화면에서 막지 않고 서버 판정에 맡긴다", () => {
    expect(canApproveBlockedDraft({ ...ok, guardrailFlags: null }).ok).toBe(true);
  });

  it("확인 체크가 없으면 막는다", () => {
    expect(canApproveBlockedDraft({ ...ok, riskAcknowledged: false }).ok).toBe(false);
  });

  it("빈 본문·공백 본문·글자 수 초과는 막는다", () => {
    expect(canApproveBlockedDraft({ ...ok, content: "   " }).ok).toBe(false);
    expect(canApproveBlockedDraft({ ...ok, content: "가".repeat(DRAFT_CONTENT_MAX_LENGTH) }).ok).toBe(true);
    expect(canApproveBlockedDraft({ ...ok, content: "가".repeat(DRAFT_CONTENT_MAX_LENGTH + 1) }).ok).toBe(false);
  });
});
