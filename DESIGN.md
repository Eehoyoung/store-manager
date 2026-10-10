# 소담리뷰 DESIGN.md

공통 기준은 SODAM LABS `BRAND.md`(이름·색·서체·말투)와 `docs/standards/`의 `design-web.md`·`design-app.md`·`ux-writing.md`다.
이 문서는 **소담리뷰가 공통 기준과 다른 점과 그 이유**, 그리고 **현재 코드가 기준을 어긴 곳**만 적는다. 같은 내용은 반복하지 않는다.

- 토큰 정본: `web/src/styles/tokens.css`
- 확장 스타일: `extension/src/sidepanel/panel.css`, `extension/src/content/banner.ts`

## 1. 제품 고유 결정

| 항목 | 공통 기준 | 소담리뷰 | 이유 |
|---|---|---|---|
| 본문 글자 | 16px | **18px** (`--font-size-body`) | 주 사용자가 40~60대 사장님이다. 매장 PC·태블릿에서 멀리서 읽는다 |
| 제목 단계 | 24/20 (16 기준) | h1 30 · h2 23 · h3 19 · small 16 | 18px 본문과 비율을 맞췄다. small도 16 아래로 내리지 않는다 |
| 주 버튼 | 제품 primary | `--sodam-teal-strong` `#0F4C5C` | BRAND §4. 상태색 success(초록)와 구분된다. 흰 글자 대비 9.5:1 |
| 상태색 | 글자용 AA 값 | danger `#B3261E` · warning `#8A5200` · success `#0B6E4F` | 흰 바탕 6.25~6.54:1. 상태를 글자로 직접 쓰는 화면(검수 대기·차단)이 많다 |
| 반경 | 입력·버튼 10, 카드 14~16 | **6 / 4** (`--radius`·`-sm`) | **D17-A 확정(2026-10-11): 표 위주 운영 화면이라 6/4·2단계 유지.** 계기판처럼 촘촘한 운영 화면을 의도했다 |
| 그림자 | 1단계 | 2단계(`--shadow-panel`·`-lift`) | 위와 같음 |
| 색만으로 상태 표시 금지 | 공통 | 배지는 색 + 글자 + 아이콘. 위험 배너는 문구를 함께 낸다 | docs/14 40~60대 원칙 |

크롬 확장(design-app.md §5 기준):
- 사이드패널만 쓴다(팝업 없음).
- 초안은 네이버 답글 입력창에 텍스트로만 넣는다. 출처 표식을 붙이면 그대로 게시되므로 붙이지 않는다.
- 게시는 사장님이 네이버 버튼을 직접 누르고, 확장은 `isTrusted` 이벤트로 감지만 한다(CLAUDE.md NAVER 규칙 6~8, `extension/src/security.test.ts`).

## 2. 토큰 이름 대응 (공용 `--sodam-*` ↔ 제품)

`tokens.css`는 아직 `--sodam-*` 변수를 정의하지 않는다. hex를 쓰고 주석에 대응을 적어 두었다.

| 제품 토큰 | 공용 토큰 | 메모 |
|---|---|---|
| `--color-bg` | `--sodam-ground` | |
| `--color-panel` | `--sodam-surface` (#FFF) | ★ 이름 엇갈림 |
| `--color-surface` | — (#EEF2F6, 한 단계 낮은 면) | ★ 공용 `surface`와 이름은 같은데 값이 다르다 |
| `--color-border` | `--sodam-border` | |
| `--color-border-control` | `--sodam-line-control` | |
| `--color-text` | `--sodam-ink` | |
| `--color-text-muted` | `--sodam-ink-2` | ★ 한 칸 어긋남 |
| `--color-text-subtle` | `--sodam-ink-muted` | ★ 한 칸 어긋남 |
| `--color-primary` | `--sodam-teal-strong` | |

## 3. 기준 위반 목록 (2026-10-10 — 2026-10-11 토큰 수정 PR로 전부 반영)

| # | 위치 | 위반 | 고칠 방향 |
|---|---|---|---|
| 1 | `web/src/styles/tokens.css:14-90` | `--sodam-*`를 정의하지 않고 hex를 직접 쓴다(design-web §1) | 맨 위에 `--sodam-*` 블록을 두고 의미 토큰이 `var(--sodam-*)`를 참조하게 한다. 값은 같아서 화면 변화 없음 |
| 2 | `web/src/pages/intro.css:2-8` | 지역 변수 `--intro-*`가 공용 값을 복제한다(`--intro-ink`=#191f28, `--intro-orange`=#ff7440) | 공용 토큰 참조로 바꾼다. `--intro-teal` #007c6a·`--intro-green` #087a55는 대비를 확인한 뒤 정한다 |
| 3 | `web/src/pages/intro.css:221,226,248,254,255,259,307,308` 등 | **녹회색 테두리**(#cbdad5·#bdd0cb·#b8d1cb·#a8ccc4). BRAND §4에서 폐기한 녹회색 계열이다 | `--color-border`(#E4E9F0)나 `--color-rule`로 바꾼다 |
| 4 | `web/src/pages/intro.css` 그 밖 | hex 직접 사용, 총 45곳(#fff 12 포함) | 토큰으로 바꾼다 |
| 5 | `web/src/pages/pages.css:14,15,18,21` | Tailwind 회색 `#f3f4f6`·`#d1d5db`, 앰버 `#d97706`(동의 박스) | `--color-surface`·`--color-border`·`--color-warning`으로 |
| 6 | `web/src/pages/pages.css:216,1629`, `components.css:116,122,128,134` | 상태 배지 테두리 hex(#c3d8de·#b9dbcb·#e3cba3·#eebfbb) | `--color-*-border` 토큰을 새로 둔다 |
| 7 | `components.css:35,52,69,197`, `shell.css:82,89`, `pages.css:68,229` | `#fff` 직접 사용(주 버튼 글자 등) | `--color-on-primary` 토큰 |
| 8 | `components.css:280` | 스켈레톤 그라디언트 안의 `#F5F7FA` | `var(--color-bg)` |
| 9 | `extension/src/sidepanel/panel.css` | **토큰 0개, hex 32곳**, 시스템 글꼴(`:7`) | 공용 토큰 블록을 복사해 쓴다(확장은 web CSS를 import할 수 없다). 글꼴은 시스템 글꼴을 유지할지 결정 — Pretendard를 넣으면 확장 용량이 는다 |
| 10 | `extension/src/sidepanel/panel.css:35,40,65`(14px), `:111,183`(13px) | 확장 글자 16px 미만(design-app §5) | 16px 이상으로 |
| 11 | `extension/src/content/banner.ts:19-21` | 인라인 hex(#fff3cd·#664d03·#ffe69c), 14px | warning 토큰 값과 16px. shadow DOM이라 변수가 상속되지 않아 값을 복사해야 한다 |
| 12 | `extension/src/sidepanel/panel.ts`(hex 2) | 스크립트 안의 색 | CSS 클래스로 옮긴다 |

문구는 ux-writing.md·BRAND §6을 따른다. 남은 합니다체(확장 `pairError.ts`·`background/index.ts`, web `SettingsPage.tsx`)와 `IntroPage.tsx:16`의 "고객이"는 문구 PR에서 함께 고친다.

## 4. 검수

UI PR마다 design-web.md §9 체크리스트를 따른다.
- 375px·1280px 화면
- 키보드·포커스
- 대비
- 새 hex 없음
- 전후 스크린샷

네이버 경로를 바꿨으면 확장을 크롬에 올려 한 바퀴 돌려 본다.

## 변경 기록

- 2026-10-10 신설(전수조사 결과 기준, master `0b47401`).
- 2026-10-11 D17-A 확정(반경 6/4·그림자 2단계 유지). §3 위반 12건을 토큰 수정 PR로 고쳤다 — web CSS hex는 tokens.css 밖 0곳, 확장은 tokens.css 값을 복사한 토큰 블록을 쓰고 글자는 16px 이상. 소개 페이지의 초록(#007c6a·#087a55)은 primary(teal-strong)·success로 바뀌었다. 확장 글꼴은 시스템 글꼴을 유지한다.
