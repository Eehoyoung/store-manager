// 서버가 코드값으로 내려주는 위험 사유·가드레일 플래그를 40~60대 사장님이 읽을 수 있는 한국어로 바꾼다.
// 출처: docs/12_프롬프트_및_평가명세.md §4 가드레일 명세(G1~G9), risk_reasons 목록.
// 목록에 없는 새 코드가 오면(=업체·정책 변경) 원본 코드를 그대로 보여준다 — "알 수 없는 오류"로 뭉개지 않는다.

import type { MonthPrice } from "../api/types";

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

// 인공지능기본법 제31조 제2항 — 답글 초안이 생성형 AI 로 만들어졌음을 화면에 표시한다.
// ReplyDraft.generatedBy: AI|HUMAN|AI_EDITED|TEMPLATE. 사람이 처음부터 쓴 값(HUMAN)이나
// 알려지지 않은 값은 표시하지 않는다 — 화면 문구는 확실한 것만 말한다.
const GENERATED_BY_LABELS: Record<string, string> = {
  AI: "AI 생성 초안",
  AI_EDITED: "AI 초안 · 사람이 수정함",
};

export function describeGeneratedBy(code: string | null | undefined): string | null {
  if (!code) {
    return null;
  }
  return GENERATED_BY_LABELS[code] ?? null;
}

// 동의 내역 화면용. 서버 AgreementService 의 코드와 같아야 한다 — 모르는 코드는 그대로 보여준다.
const AGREEMENT_LABELS: Record<string, string> = {
  TERMS_OF_SERVICE: "이용약관",
  PRIVACY_POLICY: "개인정보 수집·이용",
  HQ_DATA_SHARING: "가맹본부 정보 제공",
  HQ_REVIEW_SHARING: "가맹본부 리뷰 열람",
  HQ_AFFILIATION_WITHDRAWAL_REQUESTED: "가맹본부 소속 해제 요청",
  PLATFORM_CREDENTIAL: "배달앱 로그인 정보 처리 위탁",
  BILLING_AUTO_PAYMENT: "카드 자동결제",
};

export function describeAgreement(code: string): string {
  return AGREEMENT_LABELS[code] ?? code;
}

// 가맹 브랜드 구간 단가 — MonthPrice.basis 를 사장님·본부 화면에 보여줄 부가 설명으로.
// SNAPSHOT(매월 25일 확정) 외 값은 아직 확정 전이라는 뜻을 함께 담는다.
export function describePriceBasis(p: MonthPrice): string {
  switch (p.basis) {
    case "SNAPSHOT":
      return `${p.basisStoreCount ?? 0}곳 기준`;
    case "COMMITTED":
      return `약정 ${p.basisStoreCount ?? 0}곳 기준`;
    case "LIVE_ESTIMATE":
      return `현재 ${p.basisStoreCount ?? 0}곳 기준 예상`;
    case "DEFAULT":
    default:
      return "기본 단가";
  }
}
