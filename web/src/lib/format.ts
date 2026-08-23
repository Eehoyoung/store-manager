/**
 * 입력 정규화 — 사장님이 아무렇게나 쳐도 서버가 기대하는 모양으로 만든다.
 *
 * ★ 하이픈을 사람이 치게 하지 않는다. 40~60대 대상이라 자리수를 세며 '-' 를 넣는 것 자체가
 *   실수 지점이다(문서 14 §1). 숫자만 받고 하이픈은 우리가 넣는다.
 * ★ 여기 값은 화면 표시용이다. 검증은 서버에서 다시 한다 — 클라이언트 검증은 편의일 뿐
 *   신뢰 경계가 아니다.
 */

const digits = (v: string) => v.replace(/\D/g, "");

/** 휴대폰: 01012345678 → 010-1234-5678. 서울 02(2자리 국번)도 함께 처리한다. */
export function formatPhone(value: string): string {
  const d = digits(value).slice(0, 11);
  if (d.startsWith("02")) {
    // 02-123-4567 / 02-1234-5678
    if (d.length <= 2) return d;
    if (d.length <= 5) return `${d.slice(0, 2)}-${d.slice(2)}`;
    if (d.length <= 9) return `${d.slice(0, 2)}-${d.slice(2, 5)}-${d.slice(5)}`;
    return `${d.slice(0, 2)}-${d.slice(2, 6)}-${d.slice(6, 10)}`;
  }
  if (d.length <= 3) return d;
  if (d.length <= 7) return `${d.slice(0, 3)}-${d.slice(3)}`;
  if (d.length <= 10) return `${d.slice(0, 3)}-${d.slice(3, 6)}-${d.slice(6)}`;
  return `${d.slice(0, 3)}-${d.slice(3, 7)}-${d.slice(7)}`;
}

/**
 * 가맹코드: 서버가 SHA-256(공백·하이픈 제거 + 대문자화) 로 대조하므로 같은 규칙으로 맞춘다.
 * 이게 없으면 소문자로 치거나 하이픈을 넣은 사장님이 "코드가 틀렸다" 만 보게 된다.
 * 코드 알파벳에서 I·O·0·1 을 뺐으므로 그 문자는 애초에 들어올 수 없다.
 */
export function normalizeFranchiseCode(value: string): string {
  return value.replace(/[\s-]/g, "").toUpperCase();
}
