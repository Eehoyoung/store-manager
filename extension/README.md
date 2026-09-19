# 리뷰파일럿 — 네이버 답글 도우미 (Chrome 확장)

네이버 스마트플레이스 리뷰에 답글 초안을 만들어 입력창까지 채워주는 크롬 확장입니다.
**게시(등록) 버튼은 항상 사장님이 직접 클릭합니다** — 확장이 대신 누르는 코드 경로는
설계상 존재하지 않습니다. 근거와 법적 논리는 `docs/naver/03-compliance.md`,
클릭 동선 최적화는 `docs/naver/04-extension-spec.md` 참고.

## ⚠ 현재 상태

**셀렉터 스펙은 실측 미검증 초안입니다.** `resources/naver/selector-spec.json` 및
`packages/selector-spec/fixtures/valid-review-list.json` 의 `[data-testid=...]` 류
셀렉터는 실제 네이버 스마트플레이스 DOM 을 보고 만든 것이 아니라 스펙 문서의 예시를
그대로 옮긴 것입니다. **실제 네이버 페이지에서는 셀렉터 미스매치로 곧바로
"일시 점검 중" 으로 비활성화될 가능성이 높습니다.** 실 DOM 확인 후
`selector-spec.json` 을 갱신해야 동작합니다.

## 설치 (개발 모드)

```bash
cd extension
npm install
npm run dev      # HMR 개발 서버 — chrome://extensions 에 dist 폴더를 "압축해제된 확장" 으로 로드
npm run build    # 배포용 빌드 (dist/)
```

1. `npm run build` 후 크롬 주소창에 `chrome://extensions` 입력
2. "개발자 모드" 켜기
3. "압축해제된 확장 프로그램을 로드합니다" → `extension/dist` 폴더 선택
4. 네이버 스마트플레이스 리뷰 페이지(`https://new.smartplace.naver.com/...`)를 열어두면
   content script 가 동작을 시작합니다(탭이 백그라운드여도 무관).

## 웹 대시보드에서 페어링하기

1. 웹 대시보드(`web/`)에 로그인 후 매장 설정에서 "확장 프로그램 연결" → 8자 페어링 코드 발급 (TTL 5분)
2. 확장 아이콘을 눌러 사이드 패널을 열면 페어링 화면이 뜹니다
3. 발급받은 코드를 입력하고 "연결" — 이후 확장은 해당 매장 전용 토큰으로 동작합니다
4. 매장 설정에서 "일괄 승인 PIN" 을 등록해야 사이드 패널의 일괄 승인 기능을 쓸 수 있습니다
   (공용 포스 PC 에서 직원이 대신 승인하는 것을 막기 위한 장치입니다)

## 개발 명령어

```bash
npm run dev         # Vite + CRXJS 개발 서버 (HMR)
npm run typecheck   # tsc --noEmit
npm run test        # vitest run (extension/src/**/*.test.ts + packages/selector-spec/**/*.test.ts)
npm run build       # tsc --noEmit && vite build
```

## 아키텍처 요약

```
content script  — 리뷰 목록 스캔(5분 주기) → 마스킹 → background 로 전달
                   → 승인된 초안을 입력창에 삽입(UI 조작)
                   → 게시(등록) 클릭/제출은 감지만 한다 (isTrusted 검사)
background      — 스펙 캐시, 토큰 보관, 승인 큐 상태머신, 오프라인 재시도 큐,
                   heartbeat 타임아웃 알림. 우리 서버 외에는 아무 것도 호출하지 않는다.
side panel      — 승인 큐 UI. 뷰포트에 실제로 들어온 항목만 "확인함" 으로 센다.
```

## 절대 금지 — 이 확장에서 아래 API 를 쓰면 안 됩니다

| 금지 API | 이유 |
|---|---|
| `button.click()` / `form.submit()` | 확장이 게시를 대신하면 "도구"가 아니라 "매크로"가 됩니다 |
| `dispatchEvent(new MouseEvent(...))` / `dispatchEvent(new KeyboardEvent(...))` | 합성 이벤트로 게시를 흉내낼 수 있어 위와 동일하게 금지 |
| `eval(...)` / `new Function(...)` | 원격 코드 실행. 셀렉터 스펙은 반드시 JSON 데이터로만 받는다 |
| `document.cookie` 접근, 쿠키/세션/ID·PW 저장 | 네이버 자격증명은 어디에도 남기지 않는다 |

`src/security.test.ts` 가 소스 전체와 `manifest.json` 을 스캔해 이 규칙을 강제합니다.
게시 감지 핸들러(`src/content/publishGate.ts`)의 첫 줄은 항상
`if (!event.isTrusted) return;` 입니다 — 사람이 실제로 누른 이벤트만 게시로 인정합니다.

## 알려진 제약 / TODO

- **아이콘 자산 미준비.** `chrome.notifications.create` 가 참조하는 `icon128.png` 를
  배포 전에 추가해야 알림이 정상 표시됩니다.
- 승인된 초안은 `chrome.tabs.sendMessage` 로 "최근 heartbeat 를 보낸 탭" 에만 삽입됩니다.
  네이버 리뷰 탭이 여러 개 열려 있으면 마지막으로 heartbeat 를 보낸 탭 하나만 대상이 됩니다.
- 크롬 웹스토어/Edge 애드온 심사 제출, 실제 네이버 DOM 셀렉터 확정은 이번 범위 밖입니다.
