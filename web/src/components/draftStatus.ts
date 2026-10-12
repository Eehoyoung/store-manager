import type { DraftStatus, DraftSummary } from "../api/types";
import type { BadgeTone } from "./Badge";

// docs/13 13장 OpenAPI 스켈레톤 Draft.status enum + CLAUDE.md 상태 머신 절.
export const DRAFT_STATUS_META: Record<DraftStatus, { label: string; tone: BadgeTone; icon: string }> = {
  DRAFT: { label: "검토 대기", tone: "neutral", icon: "●" },
  SCHEDULED: { label: "게시 예정", tone: "info", icon: "▷" },
  PUBLISHED: { label: "게시 완료", tone: "success", icon: "✓" },
  FAILED: { label: "실패", tone: "danger", icon: "✕" },
  BLOCKED: { label: "사람 확인 필요", tone: "warning", icon: "⚠" },
  ALREADY_REPLIED: { label: "이미 답글 있음", tone: "success", icon: "✓" },
};

const PAUSED_META = { label: "게시 대기", tone: "neutral" as BadgeTone, icon: "Ⅱ" };

/** 등록 게이트가 닫혀 미뤄지는 예약 답글은 '게시 예정' 대신 '게시 대기'로 보인다(대표 결정 D32). */
export function draftStatusMeta(d: Pick<DraftSummary, "status" | "publishPaused">) {
  return d.status === "SCHEDULED" && d.publishPaused ? PAUSED_META : DRAFT_STATUS_META[d.status];
}

export const AI_UNAVAILABLE_FLAG = "AUTOMATION_MODEL_UNAVAILABLE";
