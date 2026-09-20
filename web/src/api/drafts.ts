// 위험 리뷰 사람 승인 API (CLAUDE.md '위험 리뷰 사람 승인' 절, 2026-08-27 신설).
// 서버 계약: RiskApprovalController/RiskApprovalService(api-spring). types.ts 는 다른 에이전트가
// 작업 중이라 건드리지 않고, 이 기능 전용 타입·호출 함수를 여기 둔다.
import { apiRequest } from "./client";

/** api-spring DraftDtos.DraftResponse 그대로. */
export interface DraftResponse {
  id: string;
  reviewId: string | null;
  content: string;
  status: string;
  tier: string | null;
  guardrailFlags: string[];
  scheduledAt: string | null;
  publishedAt: string | null;
  generatedBy: string | null;
  model: string | null;
  promptVersion: string | null;
  similarityMax: number | null;
  createdAt: string;
}

export interface ApproveDraftRequest {
  /** 차단 사유를 확인했다는 표시. false 로 보내면 서버가 400 을 던진다(RiskApprovalService). */
  riskAcknowledged: boolean;
  /** 사람이 고친 본문(<=280자). 비우면 AI 초안을 그대로 게시한다. */
  content?: string;
}

export const draftsApi = {
  approve: (draftId: string, req: ApproveDraftRequest) =>
    apiRequest<DraftResponse>(`/drafts/${draftId}/approve`, { method: "POST", body: req }),
  reject: (draftId: string) => apiRequest<DraftResponse>(`/drafts/${draftId}/reject`, { method: "POST" }),
  /** 예약된 답글을 게시 전에 취소한다(약관 제6조 제4항). 이미 게시 처리가 시작됐으면 서버가 거절한다. */
  cancel: (draftId: string) => apiRequest<DraftResponse>(`/drafts/${draftId}/cancel`, { method: "POST" }),
};

/**
 * 승인 가능한 유일한 차단 사유(RiskApprovalService.APPROVABLE_FLAG 와 동일 문자열).
 * ★ 이 값을 늘리지 말 것 — 서버 규칙과 반드시 같아야 한다.
 */
export const APPROVABLE_RISK_FLAG = "RISK_LEVEL_TOO_HIGH";

export const DRAFT_CONTENT_MAX_LENGTH = 280;

/**
 * 승인 버튼을 켤지 판정하는 순수 함수 — 화면 로직을 테스트 가능하게 분리해 둔다
 * (저장소에 web 테스트 하네스가 없어 유닛테스트 파일 대신 여기 순수 함수로만 남긴다).
 *
 * ★ guardrailFlags 는 GET /reviews/{id} 응답이 내려준다. 여기서 "정확히 RISK_LEVEL_TOO_HIGH
 * 하나인가" 를 미리 판정해 승인 버튼을 막고 사유를 보여준다 — 누르고 나서 422 를 받는 것보다 낫다.
 * null 을 넘기면 모른다는 뜻이라 막지 않고 서버 판정에 맡긴다.
 *
 * ★ 그래도 최종 판정은 서버가 한다(RiskApprovalService.APPROVABLE_FLAG). 이 함수를 근거로
 * 서버 검사를 줄이지 말 것 — 화면 검사는 API 를 직접 호출하면 그대로 우회된다.
 */
export function canApproveBlockedDraft(params: {
  guardrailFlags: string[] | null;
  riskAcknowledged: boolean;
  content: string;
}): { ok: boolean; reason?: string } {
  const { guardrailFlags, riskAcknowledged, content } = params;

  if (guardrailFlags != null) {
    const onlyRisk = guardrailFlags.length === 1 && guardrailFlags[0] === APPROVABLE_RISK_FLAG;
    if (!onlyRisk) {
      return { ok: false, reason: "이 답글은 다른 안전규칙도 위반해 승인할 수 없습니다." };
    }
  }
  if (content.trim().length === 0) {
    return { ok: false, reason: "답글 내용이 비어 있습니다." };
  }
  if (content.length > DRAFT_CONTENT_MAX_LENGTH) {
    return { ok: false, reason: `답글은 ${DRAFT_CONTENT_MAX_LENGTH}자를 넘을 수 없습니다.` };
  }
  if (!riskAcknowledged) {
    return { ok: false, reason: "차단 사유를 확인했다는 체크가 필요합니다." };
  }
  return { ok: true };
}
