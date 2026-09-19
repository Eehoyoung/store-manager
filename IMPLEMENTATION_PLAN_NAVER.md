# IMPLEMENTATION_PLAN_NAVER.md

네이버 스마트플레이스 리뷰 답글 지원을 **기존 Review Pilot 위에 어댑터로 얹는** 계획서.

- 작업 프롬프트: `docs/naver/Claude Code — Review Pilot 네이버 기능 신규 구축 프롬프트.md`
- 설계 기준: `docs/naver/01~06`, `docs/naver/review-pilot-integrated-architecture.md`

---

## 0. STEP 2 — 사전 질문에 대한 답 (코드 작성 전 확정)

프롬프트 §53 STEP 2 가 요구한 5개 질문에 저장소 실물을 읽고 답한다.

### Q1. 네이버 리뷰를 기존 `AnalyzeAndDraftRequest` 에 넣을 수 있는가?

**넣을 수 있다. 수정 없이.** `ai-python/main.py::ReviewIn.platform` 은 `str` 이고 enum 이 아니다.

```python
class ReviewIn(BaseModel):
    rating: int = Field(ge=0, le=5)
    body: str = Field(default="", max_length=10_000)
    menus: list[str] = Field(default_factory=list)
    platform: str          # ← 자유 문자열
```

### Q2. platform enum 만 추가하면 되는가?

**ai-python 에는 platform enum 이 아예 없다.** `grep -n "platform" prompts.py router.py llm.py`
결과가 0건이다 — 분류·라우팅·프롬프트·가드레일 어디도 platform 을 읽지 않는다.

Spring 쪽 유일한 화이트리스트는 `platform/PlatformAccountService.java:151` 이며 이것은
**DataAPI 자격증명 등록용**이다. 네이버는 자격증명을 저장하지 않으므로 이 목록에 넣으면 안 된다.

> **결론: `ai-python` 변경 0줄. `PlatformAccountService` 변경 0줄.**

### Q3. 어떤 기존 API 를 그대로 쓸 수 있는가?

`POST /internal/ai/analyze-and-draft` 를 **계약 변경 없이** 그대로 쓴다.
Spring 쪽 호출부는 `ai/AiClient.java` + `ai/AiClientDtos.java` 를 재사용한다.

### Q4. 기존 draft entity 를 재사용할 수 있는가?

**재사용하지 않는다.** `draft/DraftService.java` 는 `UnifiedReview` 를 DB에서 읽는 것으로 시작하고
(`unifiedReviewRepository.findByPublicId`), `ReplyDraft.reviewId` 는 `unified_review.id` 를 가리키는
BIGINT 다. 네이버 리뷰를 여기 태우려면 **리뷰 원문을 `unified_review.body` 에 저장해야 하고**
이는 프롬프트 §14 위반이다.

또한 `ReplyDraft` 에 행이 생기는 순간 `PublishScheduler` → `worker/publish.py` 게시 큐에
진입한다(프롬프트 §6·§41 위반). 즉 재사용이 아니라 **격리가 요구사항이다.**

### Q5. 네이버 metadata 는 어디에 저장하는 것이 가장 안전한가?

신규 테이블 **`naver_review_event`** 1개. 본문 컬럼을 아예 만들지 않는다.
컬럼이 없으면 실수로도 저장되지 않는다 — 코드 규칙보다 스키마가 강하다.

---

## 1. 재사용하는 기존 구성요소

| 구성요소 | 위치 | 재사용 방식 |
|---|---|---|
| AI 파이프라인 전체 | `ai-python/*` | **무변경.** 분류·risk 룰·T0~T3 라우팅·RAG·프롬프트·G1~G9 가드레일 그대로 |
| AI 호출 클라이언트 | `api-spring/.../ai/AiClient` | 그대로 호출 |
| AI 요청 DTO | `ai/AiClientDtos` | 그대로 사용 (`platform="NAVER"`) |
| 서버측 PII Sanitizer | `draft/PersonalIdentifierMasker` | AI 전송 직전 2차 방어로 그대로 적용 |
| Persona | `store/StorePersona`, `StorePersonaRepository` | 매장 페르소나 그대로. 네이버 전용 Persona 없음 |
| 금칙어 | `ai/BannedWordQueryRepository` | 전역 금칙어 그대로 주입 |
| RAG 예시 | `ai-python/rag.py` + `reply_style_sample` | `store_id` 기준이라 플랫폼 무관하게 동작 |
| 최근 게시 답글(G7) | `ReplyDraftRepository.findRecentPublishedContents` | 같은 매장 중복 답글 방지에 그대로 사용 |
| LLM 사용량 | `ai/LlmUsageLog` | 네이버 생성 원가도 같은 테이블에 적재 |
| 구독 게이트 | `store/StoreServiceGate` | AI 호출 전 차단 |
| 인증 | `security/JwtTokenProvider`, `JwtAuthFilter`, `CurrentUser` | 확장 토큰이 같은 principal(UUID)로 수렴 |
| Redis | `StringRedisTemplate` (AuthService 패턴) | 페어링 코드·확장 토큰 저장 |
| 감사로그 | `audit/AuditLog` | 승인·게시 이벤트 기록 |

---

## 2. 신규 파일

### 2.1 `packages/selector-spec/` — 런타임 의존성 0

```
packages/selector-spec/
├─ schema/selector-spec.schema.json    JSON Schema (계약 문서, CI 대조용)
├─ types/index.ts                      TypeScript 타입
├─ validator/index.ts                  손수 짠 런타임 검증기 (의존성 없음)
├─ fixtures/valid-*.json, invalid-*.json
└─ index.ts
```

> ajv 를 넣지 않는다. 확장 번들 예산이 500KB 인데 검증기 하나에 120KB 를 쓸 이유가 없다.
> schema.json 은 계약 문서로 두고, `required` 키 목록이 검증기와 어긋나지 않는지
> 테스트 한 개로 대조한다.

### 2.2 `extension/` — 신규 MV3 확장

```
extension/
├─ manifest.json                 MV3, host_permissions 2개
├─ package.json / tsconfig.json / vite.config.ts   (Vite + CRXJS 2.7)
└─ src/
   ├─ masking/mask.ts            브라우저 로컬 1차 PII 마스킹 + authorHash
   ├─ selector/runtime.ts        spec 기반 DOM 추출
   ├─ selector/cache.ts          fetch → validate → chrome.storage.local, 폴백
   ├─ state/machine.ts           DETECTED~POSTED 상태머신 (순수 함수)
   ├─ api/client.ts              Review Pilot API 클라이언트
   ├─ telemetry/selectorMiss.ts
   ├─ content/index.ts           추출·삽입·isTrusted 게시 감지
   ├─ background/index.ts        service worker (spec·토큰·탭감지·큐·알람)
   └─ sidepanel/                 승인 큐 UI (IntersectionObserver → VIEWED)
```

### 2.3 `api-spring/.../naver/` — 신규 패키지, 기존 패키지 무침범

| 파일 | 역할 |
|---|---|
| `NaverReviewEvent.java` | 메타데이터 엔티티. **본문 컬럼 없음** |
| `NaverReviewEventRepository.java` | |
| `NaverDraftService.java` | 마스킹 2차 → AiClient → 메타 저장 |
| `NaverEventService.java` | 상태머신 · 일괄승인 규칙 |
| `NaverController.java` | `/api/v1/naver/**` |
| `NaverDtos.java` | 요청·응답 DTO |
| `ExtensionAuthService.java` | 페어링 코드 · 불투명 토큰(Redis) |
| `ExtensionTokenFilter.java` | `X-Extension-Token` → 기존 principal |
| `resources/naver/selector-spec.json` | 서버가 배포하는 셀렉터 스펙(데이터) |
| `resources/db/migration/V38__naver_review_event.sql` | 신규 테이블 + `app_user.naver_bulk_pin_hash` |

---

## 3. 수정하는 기존 파일 (최소)

| 파일 | 변경 | 영향 |
|---|---|---|
| `security/SecurityConfig.java` | `ExtensionTokenFilter` 등록, `/api/v1/naver/extension/pair` permitAll, CORS 허용 헤더에 `X-Extension-Token` 추가 | JWT 경로 동작 불변. 필터는 헤더가 있고 Redis 조회가 성공할 때만 principal 을 세팅 |
| `CLAUDE.md` | `## NAVER ABSOLUTE RULES` 절 추가 (기존 내용 무삭제) | 문서 |
| `.github/workflows/ci.yml` | `extension` 잡 추가 | 기존 잡 불변 |

> 위 3개 외에 기존 파일을 건드리지 않는다. `worker/`, `ai-python/`, `web/`, `draft/`,
> `review/`, `platform/` 은 **한 줄도 바꾸지 않는다.**

---

## 4. DB 변경

`V38__naver_review_event.sql`

```sql
CREATE TABLE naver_review_event (
  id              BIGSERIAL PRIMARY KEY,
  public_id       UUID        NOT NULL DEFAULT gen_random_uuid(),
  store_id        BIGINT      NOT NULL REFERENCES store(id),
  review_hash     CHAR(64)    NOT NULL,   -- 확장이 만든 SHA-256 hex. 원문·닉네임 불가역
  rating          SMALLINT,
  category        VARCHAR(20),
  risk_level      SMALLINT    NOT NULL DEFAULT 0,
  status          VARCHAR(20) NOT NULL DEFAULT 'DRAFTED',
  draft_content   TEXT,                   -- 자사 저작물(생성 답글). 리뷰 원문 아님
  guardrail_flags TEXT[]      NOT NULL DEFAULT '{}',
  blocked         BOOLEAN     NOT NULL DEFAULT FALSE,
  edited          BOOLEAN     NOT NULL DEFAULT FALSE,
  edit_distance   INTEGER,
  drafted_at TIMESTAMPTZ, viewed_at TIMESTAMPTZ, approved_at TIMESTAMPTZ,
  inserted_at TIMESTAMPTZ, posted_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (store_id, review_hash)
);
ALTER TABLE app_user ADD COLUMN naver_bulk_pin_hash VARCHAR(72);
```

> **`body` 컬럼을 추가하지 말 것.** 없는 컬럼에는 실수로도 저장되지 않는다.
> 기존 테이블(`unified_review`, `reply_draft`, `platform_account`, `subscription`,
> `payment`)의 의미는 바뀌지 않는다.

---

## 5. API 변경 — 전부 신규 경로, 기존 계약 무변경

| 메서드 | 경로 | 인증 | 용도 |
|---|---|---|---|
| POST | `/api/v1/naver/extension/pairing-code` | JWT(웹) | 1회용 페어링 코드 발급 (TTL 5분) |
| POST | `/api/v1/naver/extension/pair` | 없음 | 코드 → 확장 토큰 교환(GETDEL) |
| POST | `/api/v1/naver/extension/pin` | JWT(웹) | 일괄승인 PIN 설정(BCrypt) |
| GET | `/api/v1/naver/selector-spec` | 확장 토큰 | 셀렉터 스펙(JSON 데이터) |
| POST | `/api/v1/naver/drafts` | 확장 토큰 | 마스킹된 리뷰 → 초안 |
| POST | `/api/v1/naver/events` | 확장 토큰 | 상태 전이 기록 |
| POST | `/api/v1/naver/events/bulk-approve` | 확장 토큰 + PIN | 일괄 승인 |
| POST | `/api/v1/naver/telemetry/selector-miss` | 확장 토큰 | 셀렉터 미스 |
| GET | `/api/v1/naver/status` | 확장 토큰/JWT | 매장별 처리 현황 |

---

## 6. Extension 아키텍처 / 보안 경계

```
네이버 스마트플레이스 (사용자 브라우저)
   │ DOM 읽기 (spec 기반)
   ▼
content script ── 1차 마스킹(mask.ts) ─┐
   ▲                                   │ masked body + reviewHash
   │ textarea 삽입                      ▼
   │                             Spring /api/v1/naver/drafts
   │                                   │ 2차 PersonalIdentifierMasker
   │                                   ▼
   │                             ai-python (무변경 파이프라인)
   │                                   │
   └───────── side panel 승인 ◄────────┘ draft
   │
   ▼
사장님이 네이버 "등록" 클릭 → isTrusted 이벤트만 POSTED 로 인정
```

**경계 강제 수단**

1. 서버에 네이버 도메인을 호출하는 코드가 존재하지 않는다(워커·스케줄러 신규 0개).
2. 확장 소스 전체를 스캔해 `.click()`, `.submit()`, `dispatchEvent(new MouseEvent`,
   `dispatchEvent(new KeyboardEvent` 가 있으면 **테스트가 실패한다**.
3. 게시 감지 핸들러 첫 줄이 `if (!event.isTrusted) return;`.
4. `naver_review_event` 에 본문 컬럼이 없다.
5. `manifest.json` 에 `cookies`/`webRequest`/`tabs`/`<all_urls>` 없음.

---

## 7. 테스트 계획

| 영역 | 항목 |
|---|---|
| selector-spec | 유효/무효 픽스처 검증, schema↔validator required 키 대조 |
| extension/masking | 전화·이메일·주문번호·주소 치환, 닉네임 SHA-256 앞 12자, 원문 미전송 |
| extension/selector | spec 기반 추출(jsdom), 미스매치 카운트, 폴백 |
| extension/state | 전이표, `DRAFTED→APPROVED` 직접 전이 거부, 1~2점 bulk 제외 |
| extension/publish | isTrusted false 이벤트는 POSTED 아님, 금지 API 소스 스캔 |
| extension/ux | 세션 만료 감지, 탭 부재 감지, 네트워크 실패 시 마스킹된 payload 만 큐 |
| spring/naver | 상태머신 서버 강제, VIEWED 미경유 승인 거부, 1~2점 bulk 제외, 타 매장 404, PIN 없으면 bulk 거부 |
| spring/naver | 요청 본문의 PII 가 AI 요청에서 마스킹됐는지(목) |
| 회귀 | `./gradlew test`, `pytest`(ai-python·worker), `npm run build`(web) |

---

## 8. 하지 않는 것 (이번 범위 밖)

Google Business Profile · 네이버 순위 분석 · 서버 Health Canary(헤드리스 금지, 개발자
브라우저 방식으로 Phase 2) · 모바일/Firefox 확장 · `docs/naver` → `docs/naver-extension`
디렉터리 이동(현행 convention 우선, 프롬프트 §7 단서).
