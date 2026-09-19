/**
 * 브라우저 로컬 1차 PII 마스킹.
 *
 * ★ 순수 함수만 둔다 — DOM·chrome API 를 import 하지 않는다 (테스트 가능해야 한다).
 * ★ 마스킹 전 원문은 이 모듈 밖으로 반환하지 않는다. maskReviewBody 의 반환값에는
 *   전화번호·이메일·긴 숫자열·주소가 남아있지 않아야 한다.
 */

// 순서가 중요하다: 전화번호(하이픈 없는 11자리)가 LONG_DIGIT_RE 보다 먼저 걸려야
// "[전화번호]"로 마스킹되고 "[번호]"로 뭉개지지 않는다.
const PHONE_RE = /01[0-9]-?\d{3,4}-?\d{4}/g;
const EMAIL_RE = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
const ADDRESS_RE = /[가-힣]{2,}(?:시|도)\s?[가-힣]{2,}(?:구|군)/g;
// 주문번호 등 숫자 10자리 이상 연속 (전화번호는 위에서 이미 치환된 뒤라 안전)
const LONG_DIGIT_RE = /\d{10,}/g;

export function maskReviewBody(text: string): string {
  return text
    .replace(PHONE_RE, "[전화번호]")
    .replace(EMAIL_RE, "[이메일]")
    .replace(ADDRESS_RE, "[주소]")
    .replace(LONG_DIGIT_RE, "[번호]");
}

async function sha256Hex(input: string): Promise<string> {
  const data = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/** 작성자 닉네임 → SHA-256 해시 앞 12자. storeSalt 로 매장 간 재식별을 막는다. */
export async function hashAuthor(nickname: string, storeSalt: string): Promise<string> {
  const full = await sha256Hex(`${storeSalt}:${nickname}`);
  return full.slice(0, 12);
}

/** (platform, reviewId) → SHA-256 전체 hex 64자. 서버 review_hash CHAR(64) 와 길이를 맞춘다. */
export async function reviewHash(platformReviewId: string, storeId: string): Promise<string> {
  return sha256Hex(`${storeId}:${platformReviewId}`);
}
