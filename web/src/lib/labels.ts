// 서버가 코드값으로 내려주는 위험 사유·가드레일 플래그를 40~60대 사장님이 읽을 수 있는 한국어로 바꾼다.
// 출처: docs/12_프롬프트_및_평가명세.md §4 가드레일 명세(G1~G9), risk_reasons 목록.
// 목록에 없는 새 코드가 오면(=업체·정책 변경) 원본 코드를 그대로 보여준다 — "알 수 없는 오류"로 뭉개지 않는다.

const RISK_REASON_LABELS: Record<string, string> = {
  FOOD_POISONING: "식중독 의심",
  FOREIGN_OBJECT: "이물질 발견",
  HYGIENE: "위생 문제 제기",
  LEGAL: "법적 분쟁 소지",
  MEDIA: "언론·SNS 확산 우려",
};

const GUARDRAIL_FLAG_LABELS: Record<string, string> = {
  G1_LENGTH: "답글 길이 기준 위반",
  G2_BANNED_WORD: "금칙어 포함",
  G3_COMPENSATION: "금전적 보상(환불 등) 표현 포함",
  G4_PII: "개인정보(연락처 등) 포함",
  G5_COMPETITOR: "경쟁 배달앱 언급",
  G6_QUOTE: "리뷰 원문을 그대로 인용",
  G7_DUPLICATE: "기존 답글과 지나치게 유사",
  // G8 은 위생·이물질·법적분쟁 등 고위험 리뷰 차단(절대규칙 3)이라 실제로 가장 자주 노출된다.
  G8_RISK: "고위험 리뷰 — 사람 검수 필요",
  G9_INJECTION: "리뷰 내 지시문이 답글에 반영된 것으로 의심",
};

export function describeRiskReason(code: string): string {
  return RISK_REASON_LABELS[code] ?? code;
}

export function describeGuardrailFlag(code: string): string {
  return GUARDRAIL_FLAG_LABELS[code] ?? code;
}

// 출처: docs/12_프롬프트_및_평가명세.md §2 분류 프롬프트 category enum.
const CATEGORY_LABELS: Record<string, string> = {
  PRAISE: "칭찬",
  POSITIVE: "긍정",
  IMPROVEMENT: "개선 요청",
  COMPLAINT: "불만",
  ABUSIVE: "악성",
  NOISE: "무의미",
};

export function describeCategory(code: string): string {
  return CATEGORY_LABELS[code] ?? code;
}

// ★ 실기동 확인 결과(2026-08-20) DB 의 platform 컬럼 값은 대문자(BAEMIN)로 저장돼 있다 —
// CLAUDE.md 의 엔드포인트 경로 표기(소문자)와 다르다. 대소문자 모두 대응한다.
const PLATFORM_LABELS: Record<string, string> = {
  BAEMIN: "배달의민족",
  YOGIYO: "요기요",
  COUPANGEATS: "쿠팡이츠",
};

export function describePlatform(code: string): string {
  return PLATFORM_LABELS[code.toUpperCase()] ?? code;
}
