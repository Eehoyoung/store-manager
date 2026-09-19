import type { QueueEntry } from "../state/queueEntry";

/**
 * 위험 사유 코드 → 사장님이 읽을 문구. 정본은 ai-python 의 {@code RISK_REASON_VALUES} 9종이다.
 *
 * ★ 모르는 코드는 숨기지 않고 코드를 그대로 보여준다. 서버가 사유를 늘렸는데 화면에서
 *   조용히 사라지는 것이 가장 나쁘다 — 사장님은 경고가 없으면 안전한 줄 안다.
 */
export const RISK_REASON_LABEL: Record<string, string> = {
  FOOD_POISONING: "식중독 의심",
  FOREIGN_OBJECT: "이물질",
  HYGIENE: "위생",
  LEGAL: "법적 조치 언급",
  THREAT: "협박·영업방해 예고",
  REVIEW_TRADE: "리뷰 대가 거래",
  ORIGIN_LABEL: "원산지 표시",
  UNDERAGE_ALCOHOL: "청소년 주류",
  PRIVACY_LEAK: "개인정보 노출",
};

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/**
 * 위험 배너 HTML. 위험하지 않으면 빈 문자열이다.
 *
 * ★ 네이버는 어떤 답글도 자동으로 올라가지 않는다 — 확장은 본문을 넣기만 하고 게시는
 *   사장님이 직접 누른다(`!event.isTrusted` 게이트, NAVER ABSOLUTE RULES 6·7·8).
 *   그래서 위험한 리뷰라고 초안을 빼지 않는다. 빼 봐야 아무것도 막지 못하고 사장님만
 *   빈손이 된다. **초안은 주되 무엇이 위험한지 알린다** — 이 배너가 그 유일한 장치다.
 *
 * ★ 승인 버튼을 막지는 않는다. 판단은 사장님 몫이고, 우리는 판단에 필요한 것을 줄 뿐이다.
 */
export function riskBanner(entry: QueueEntry): string {
  const reasons = entry.riskReasons ?? [];
  if (entry.riskLevel < 2 && reasons.length === 0) return "";

  const labels = reasons.map((r) => RISK_REASON_LABEL[r] ?? r);
  const detail = labels.length > 0 ? labels.join(" · ") : "내용 확인 필요";
  const level = entry.riskLevel >= 3 ? "high" : "mid";
  // 색만으로 구분하지 않는다 — 40~60대 사장님이 주 사용자다(docs/14 UI 안전 규약).
  const head = entry.riskLevel >= 3 ? "그대로 올리기 전에 꼭 읽어보세요" : "한 번 더 확인해 주세요";
  return `<div class="risk risk-${level}">
      <strong>${escapeHtml(head)}</strong>
      <span>${escapeHtml(detail)}</span>
    </div>`;
}
