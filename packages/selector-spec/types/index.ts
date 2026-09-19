/**
 * 네이버 DOM 셀렉터 스펙 타입.
 * 이 타입이 정의하는 것은 "코드"가 아니라 "데이터 형태"다 — 스펙 파일은
 * 확장이 부팅 시 원격에서 받아오는 JSON 이며, 이 타입은 그 JSON 의 계약이다.
 */

export type FieldParse = "text" | "int" | "float" | "exists";

export interface FieldSpec {
  /** DOM 셀렉터 문자열 (querySelector 인자) */
  selector: string;
  /** 지정 시 textContent 대신 이 속성값을 읽는다 */
  attr?: string;
  /** 값 파싱 방식. 생략 시 런타임 기본값(text)을 따른다 */
  parse?: FieldParse;
}

export interface PageSpec {
  /** 이 페이지 스펙이 적용되는 URL 경로 패턴 */
  match: string;
  /** 리뷰 목록 컨테이너 셀렉터 */
  container: string;
  /** 리뷰 항목(반복 요소) 셀렉터 */
  item: string;
  /** 항목 하나에서 추출할 필드들 */
  fields: Record<string, FieldSpec>;
  replyInput?: string;
  replyForm?: string;
  replyOpenButton?: string;
  replySubmitButton?: string;
  /** 언더스코어로 시작하는 키는 주석용으로 허용한다 (예: _note) */
  [key: `_${string}`]: unknown;
}

export interface SelectorSpec {
  /** ISO 8601 날짜/시각. 스펙 버전 식별자 */
  version: string;
  /** 이 스펙이 요구하는 최소 확장 버전 (semver, x.y.z) */
  minExtensionVersion: string;
  pages: Record<string, PageSpec>;
  [key: `_${string}`]: unknown;
}
