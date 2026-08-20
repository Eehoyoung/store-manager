import type { DraftStatus } from "../api/types";
import type { BadgeTone } from "./Badge";

// docs/13 §13 OpenAPI 스켈레톤 Draft.status enum + CLAUDE.md 상태 머신 절.
export const DRAFT_STATUS_META: Record<DraftStatus, { label: string; tone: BadgeTone; icon: string }> = {
  DRAFT: { label: "검토 대기", tone: "neutral", icon: "●" },
  SCHEDULED: { label: "게시 예정", tone: "info", icon: "▷" },
  PUBLISHED: { label: "게시 완료", tone: "success", icon: "✓" },
  FAILED: { label: "실패", tone: "danger", icon: "✕" },
  BLOCKED: { label: "사람 확인 필요", tone: "warning", icon: "⚠" },
  ALREADY_REPLIED: { label: "이미 답글 있음", tone: "success", icon: "✓" },
};
