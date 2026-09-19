# @review-pilot/selector-spec

네이버 DOM 셀렉터를 **코드가 아니라 데이터**로 다루기 위한 타입·JSON Schema·런타임 검증기·픽스처.

## 왜 존재하는가

크롬 확장은 네이버 스마트플레이스 리뷰 페이지의 DOM 구조에 의존한다. 네이버가 클래스명이나
마크업을 바꾸면 확장이 깨진다. 이 구조 정보를 확장 코드에 하드코딩하면, 대응할 때마다
새 버전을 빌드해 크롬 웹스토어 심사를 다시 받아야 한다.

그래서 셀렉터는 **원격에서 받아오는 JSON 스펙**으로 뺐다(`docs/naver/04-extension-spec.md`).
확장은 부팅 시 서버에서 최신 스펙을 받고, 실패하면 마지막 정상 캐시로 폴백한다.
이 패키지는 그 스펙의 **형태를 정의하고 검증**하는 부분만 담당한다 — DOM 을 실제로
읽는 코드(`extension/src/selector/runtime.ts`)는 여기 없다.

## 이 패키지가 하는 일

- `types/index.ts` — `SelectorSpec` / `PageSpec` / `FieldSpec` TypeScript 타입
- `schema/selector-spec.schema.json` — 위 타입과 1:1인 JSON Schema (계약 문서, CI 대조용)
- `validator/index.ts` — 손수 짠 런타임 검증기 `validateSelectorSpec()` 와 버전 호환성 확인
  `isCompatible()`
- `fixtures/` — 유효/무효 스펙 예시

## 스펙을 배포하기 전에

새 셀렉터 스펙을 서버(`api-spring/.../resources/naver/selector-spec.json`)에 올리기 전에
반드시 `validateSelectorSpec()` 을 통과시킨다. 통과하지 못한 스펙을 배포하면 확장 전체가
"일시 점검 중" 배너로 멈춘다(스펙 로딩 규칙 4번, `docs/naver/04-extension-spec.md`).

이 검증기는 **셀렉터 문자열이 실제로 뭔가를 찾아내는지는 확인하지 않는다.** 그건 Node
환경(테스트·CI)에 DOM 이 없어서 못 하는 일이고, 카나리 매장에서 실제로 확인해야 한다.
여기서 잡는 것은 형태다 — 필수 필드 누락, 빈 문자열 셀렉터, 허용되지 않는 `parse` 값 같은
것들이 배포되는 순간 확장 전체가 멈추는 사고를 미리 막는다.

## ajv 를 쓰지 않은 이유

확장 번들 예산이 500KB 다. 스펙 하나를 검증하는 데 ajv(런타임 + 컴파일된 스키마)가 붙는
100KB+ 를 쓸 이유가 없다. 검사 항목이 "필수 키 있는지, 문자열이 비었는지, enum 값이
맞는지" 수준이라 손으로 짠 함수 하나로 충분하다. JSON Schema 파일은 지우지 않는다 —
사람이 읽는 계약 문서 역할과, "스키마와 검증기가 조용히 갈라지는 것"을 막는 대조 테스트의
기준으로 쓴다.

## 사용 예

```ts
import { validateSelectorSpec, isCompatible } from "@review-pilot/selector-spec";

const result = validateSelectorSpec(await fetch(specUrl).then((r) => r.json()));
if (!result.ok) {
  console.error(result.errors);
} else if (!isCompatible(result.spec, chrome.runtime.getManifest().version)) {
  // 스펙이 이 확장 버전보다 최신 기능을 요구함 — 업데이트 유도
} else {
  // result.spec 사용
}
```
