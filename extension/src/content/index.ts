/**
 * content script — 네이버 스마트플레이스 리뷰 페이지에서만 동작한다.
 *
 * 책임: (1) 스펙 로드는 background 에 위임 (2) 5분 주기로 목록 스캔·마스킹 후
 * background 로 전달 (3) 초안을 입력창에 삽입(UI 조작 — 허용) (4) 게시 감지는
 * publishGate.handlePublishEvent 로만 수행 (5) 세션 만료/탭 상태를 배너로 표시
 * (6) 30초마다 heartbeat.
 *
 * ★ 게시 버튼에는 절대 손대지 않는다. 버튼을 대신 누르거나 폼을 대신 제출하는 API,
 *   합성 Mouse·Keyboard 이벤트를 이 파일을 포함해 확장 어디에도 쓰지 않는다.
 */
import type { PageSpec, SelectorSpec } from "@selector-spec";
import { hashAuthor, maskReviewBody, reviewHash } from "../masking/mask";
import type { RawReview } from "../selector/runtime";
import { extractReviews } from "../selector/runtime";
import { collidingIdentities, parseReviewDates, reviewIdentity } from "../selector/identity";
import { deriveHasReply } from "../selector/replyState";
import { handlePublishEvent } from "./publishGate";
import { hideBanner, showBanner } from "./banner";

const SCAN_INTERVAL_MS = 5 * 60 * 1000; // 성능 예산: 리뷰 목록 스캔 주기 5분
// DOM 변화가 멎은 뒤 이만큼 기다렸다가 다시 스캔한다. 리액트가 목록을 채우는 동안
// 수십 번 발화하므로 묶어서 한 번만 돌린다.
const RESCAN_DEBOUNCE_MS = 1000;
// 페이지를 연 직후 이 시간 안에는 컨테이너가 없어도 "로그인 만료" 로 보지 않는다.
// SPA 라 목록이 늦게 그려진다 — 렌더 중인 것과 세션이 끊긴 것은 구분할 수 없다.
const SESSION_GRACE_MS = 15 * 1000;
const HEARTBEAT_INTERVAL_MS = 30 * 1000;

let currentSpec: SelectorSpec | null = null;
let currentStoreId: string | null = null;
// ★ activeReviewHash 전역을 없앴다. "마지막으로 삽입한 리뷰" 하나만 들고 있으면
//   그 사이 다른 리뷰가 게시될 때 오귀속된다. 이제 게시 리스너가 자기 리뷰의 해시를
//   클로저로 들고 있어 전역 상태가 필요 없다.
const wiredElements = new WeakSet<Element>();
// 같은 결과를 반복해서 찍지 않는다 — 옵저버가 자주 깨우므로 콘솔이 금세 묻힌다.
let lastScanLine = "";
// scan() 은 await 를 탄다. 겹쳐 돌면 같은 리뷰를 두 번 보낸다.
let scanning = false;
const loadedAt = Date.now();
// 세션 만료는 한 번만 알린다. 옵저버가 DOM 변화마다 깨우므로 안 그러면 알림이 쏟아진다.
let sessionExpiryReported = false;

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** "/bizes/place/{id}/reviews" 형태의 단순 glob(*) 패턴 매칭. */
export function findPageSpec(spec: SelectorSpec): PageSpec | null {
  const path = window.location.pathname;
  for (const page of Object.values(spec.pages)) {
    const pattern = page.match.split("*").map(escapeRegExp).join(".*");
    if (new RegExp(`^${pattern}$`).test(path)) return page;
  }
  return null;
}

function sendToBackground<T>(message: Record<string, unknown>): Promise<T> {
  return chrome.runtime.sendMessage(message);
}

async function loadSpecFromBackground(): Promise<void> {
  const res = await sendToBackground<{ status: "ok"; spec: SelectorSpec } | { status: "disabled" }>({
    type: "GET_SELECTOR_SPEC",
  });
  if (res.status === "ok") {
    currentSpec = res.spec;
    hideBanner();
  } else {
    currentSpec = null;
    showBanner("일시 점검 중입니다. 잠시 후 다시 시도해 주세요.");
  }
}

async function maskAndReport(
  raw: RawReview,
  storeId: string,
  identity: string,
  writtenAt: string,
): Promise<void> {
  const [hash, authorHash] = await Promise.all([
    reviewHash(identity, storeId),
    hashAuthor(String(raw.authorName ?? ""), storeId),
  ]);

  await sendToBackground({
    type: "REVIEW_DETECTED",
    storeId,
    reviewHash: hash,
    authorHash,
    rating: typeof raw.rating === "number" ? raw.rating : 0,
    body: maskReviewBody(String(raw.body ?? "")),
    hasReply: false, // 여기 도달한 건 "답글 쓰기" 버튼이 남아 있는 리뷰뿐이다
    createdAt: writtenAt,
  });
}

export interface ScanPlan {
  /** 초안을 요청할 리뷰 — 식별 가능하고, 중복이 아니고, 아직 답글이 없다 */
  targets: { raw: RawReview; identity: string; writtenAt: string }[];
  /** authorRef 나 작성일을 못 읽어 식별 불가 */
  unidentified: number;
  /** 서로 다른 리뷰가 같은 식별자 — 잘못 붙이느니 건너뛴다 */
  colliding: number;
  /** 이미 답글이 달려 있어 초안이 필요 없다 */
  alreadyReplied: number;
}

/**
 * 추출 결과를 "무엇을 처리하고 무엇을 왜 건너뛰는가" 로 가른다.
 *
 * ★ 순수 함수다 — chrome API·네트워크를 타지 않는다. 이 판정이 곧 수집 품질이라
 *   테스트가 직접 잡을 수 있어야 한다.
 * ★ 식별자 계산을 전건 먼저 돌린 뒤 충돌을 본다. 한 건씩 처리하면 충돌을 알 수 없다.
 */
export function planScan(items: RawReview[]): ScanPlan {
  const rows = items.map((raw) => {
    const { writtenAt, visitedAt } = parseReviewDates(String(raw.dateBlock ?? ""));
    return { raw, writtenAt, identity: reviewIdentity(String(raw.authorRef ?? ""), writtenAt, visitedAt) };
  });
  const collisions = collidingIdentities(rows.map((r) => r.identity));

  const plan: ScanPlan = { targets: [], unidentified: 0, colliding: 0, alreadyReplied: 0 };
  for (const row of rows) {
    if (!row.identity || !row.writtenAt) {
      plan.unidentified += 1;
      continue;
    }
    if (collisions.has(row.identity)) {
      plan.colliding += 1;
      continue;
    }
    // ★ 이미 답글이 달린 리뷰는 초안을 만들지 않는다. 서버는 hasReply 를 보지 않으므로
    //   여기서 거르지 않으면 답글이 달린 리뷰마다 유료 LLM 호출이 나간다.
    if (deriveHasReply(row.raw.replyWriteButton)) {
      plan.alreadyReplied += 1;
      continue;
    }
    plan.targets.push({ raw: row.raw, identity: row.identity, writtenAt: row.writtenAt });
  }
  return plan;
}

/**
 * 추출 결과 한 줄 요약. ★ 숫자와 셀렉터 키만 남긴다 — 본문·닉네임은 찍지 않는다.
 *
 * ★ misses 를 반드시 함께 낸다. 건수만 찍으면 0건일 때 **왜** 0인지 알 수 없다
 *   (컨테이너를 못 찾은 것과 항목이 아직 안 그려진 것은 대처가 완전히 다르다).
 */
export function scanSummary(
  itemCount: number,
  plan: ScanPlan,
  misses: string[],
  connected: boolean,
  fieldMisses: Record<string, number> = {},
): string {
  const missKeys = [...new Set(misses)];
  // ★ 부분 결손은 미스와 분리해서 보여준다. 둘을 한 덩어리로 찍으면 "본문 없는
  //   리뷰가 2건" 과 "본문 셀렉터가 깨졌다" 가 화면에서 똑같이 보인다.
  const partial = Object.entries(fieldMisses)
    .filter(([, n]) => n < itemCount)
    .map(([k, n]) => `${k} ${n}/${itemCount}`);
  return (
    `리뷰 ${itemCount}건 · 초안대상 ${plan.targets.length} · 이미답글 ${plan.alreadyReplied}`
    + ` · 식별불가 ${plan.unidentified} · 충돌 ${plan.colliding}`
    + (partial.length ? ` · 빈 필드: ${partial.join(", ")}` : "")
    + (missKeys.length ? ` · ★미스: ${missKeys.join(", ")}` : "")
    + (connected ? "" : "  (미연결 — 서버로 보내지 않는다)")
  );
}

async function scan(page: PageSpec, storeId: string | null): Promise<void> {
  if (scanning) return;
  scanning = true;
  try {
    const { items, misses, fieldMisses } = extractReviews(document, page);
    const plan = planScan(items);

    const line = scanSummary(items.length, plan, misses, Boolean(storeId), fieldMisses);
    if (line !== lastScanLine) {
      lastScanLine = line;
      console.log(`[리뷰파일럿] ${line}`);
    }

    // ★ "item" 미스는 셀렉터 고장이 아니라 아직 안 그려진 것일 때가 대부분이다.
    //   옵저버가 곧 다시 부르므로 킬스위치 카운터를 올리지 않는다 — 올리면 페이지를
    //   세 번 여는 것만으로 기능이 꺼진다.
    for (const selectorKey of misses) {
      if (selectorKey === "item") continue;
      void sendToBackground({ type: "SELECTOR_MISS", selectorKey, pagePath: window.location.pathname });
    }
    if (!storeId) return; // 연결 전 — 추출은 확인할 수 있고 전송은 하지 않는다

    for (const t of plan.targets) {
      await maskAndReport(t.raw, storeId, t.identity, t.writtenAt);
    }
  } finally {
    scanning = false;
  }
}

/**
 * DOM 이 바뀌면 다시 스캔한다.
 *
 * ★ 왜 필요한가 (실측 2026-09-20) — 스마트플레이스는 SPA 다. document_idle 시점에는
 *   리뷰 목록 <ul> 만 있고 <li> 는 비어 있다. 한 번 훑고 5분을 기다리면 그 사이를
 *   통째로 놓쳐 **리뷰 0건**으로 보인다. 실제로 그렇게 나왔다.
 *
 * ★ 이 한 가지가 세 가지를 함께 해결한다 — 최초 렌더 지연 · SPA 내부 이동
 *   (content script 가 다시 주입되지 않는다) · 무한 스크롤 추가 로드.
 *
 * ★ 재스캔은 안전하다. background 가 reviewHash 로 큐를 조회해 이미 있으면
 *   초안을 다시 만들지 않는다.
 */
function watchForChanges(): void {
  let timer: ReturnType<typeof setTimeout> | undefined;
  new MutationObserver(() => {
    clearTimeout(timer);
    timer = setTimeout(tick, RESCAN_DEBOUNCE_MS);
  }).observe(document.body, { childList: true, subtree: true });
}

/** INSERT_DRAFT 결과. 실패 이유를 반드시 구분한다 — 사장님이 할 일이 다르다. */
export type InsertResult =
  | { ok: true }
  | { ok: false; reason: "REVIEW_NOT_FOUND" | "BOX_NOT_OPEN" | "NO_SPEC" };

/**
 * reviewHash 가 가리키는 리뷰 항목을 찾는다.
 *
 * ★ 이게 없으면 답글이 엉뚱한 손님에게 간다. 이전 구현은 전역
 *   document.querySelector 로 "열려 있는 아무 입력창" 을 잡았다. 사장님이 A 리뷰의
 *   입력창을 열어 둔 채 패널에서 B 카드를 승인하면 B의 답글이 A 입력창에 들어가고,
 *   그대로 등록하면 **다른 손님 리뷰에 남의 답글이 게시된다.** 게다가 우리 기록은
 *   "B 게시됨" 이라 사후에 알아낼 방법도 없다.
 *
 * ★ 셀렉터 지식을 여기 다시 쓰지 않는다 — extractReviews 가 이미 파싱한 값을 쓴다.
 */
async function findReviewItem(
  page: PageSpec,
  storeId: string,
  targetHash: string,
): Promise<Element | null> {
  const { items, elements } = extractReviews(document, page);
  for (let i = 0; i < items.length; i += 1) {
    const { writtenAt, visitedAt } = parseReviewDates(String(items[i].dateBlock ?? ""));
    const identity = reviewIdentity(String(items[i].authorRef ?? ""), writtenAt, visitedAt);
    if (!identity) continue;
    if ((await reviewHash(identity, storeId)) === targetHash) return elements[i];
  }
  return null;
}

/**
 * 초안을 **그 리뷰의** 입력창에 삽입한다. 폼 열기·스크롤·포커스·값 설정은 UI 조작이지
 * 콘텐츠 발행이 아니다. 게시 버튼은 건드리지 않는다(절대규칙 6·7·8).
 *
 * ★ item 범위 밖은 쳐다보지 않는다. 못 찾으면 조용히 다른 곳에 쓰지 말고 실패한다.
 */
export function insertDraft(item: Element, page: PageSpec, content: string): boolean {
  if (!page.replyInput) return false;
  const textarea = item.querySelector<HTMLTextAreaElement>(page.replyInput);
  if (!textarea) return false;

  textarea.scrollIntoView({ block: "center" });
  textarea.focus();
  textarea.value = content;
  // 리액트 등 프레임워크에 값 변경을 알리는 표준 DOM 이벤트. 게시 이벤트가 아니다.
  textarea.dispatchEvent(new Event("input", { bubbles: true }));
  textarea.dispatchEvent(new Event("change", { bubbles: true }));
  return true;
}

export function isSessionExpired(page: PageSpec): boolean {
  return document.querySelector(page.container) === null;
}

/**
 * 게시 감지 배선 — **초안을 넣은 그 리뷰의 등록 버튼에만** 건다.
 *
 * ★ onPosted 는 handlePublishEvent 를 통해서만 불린다(isTrusted 게이트).
 *   합성 이벤트로는 절대 발화하지 않는다. 이것이 "사장님이 직접 눌렀다" 의 증거다.
 *
 * ★ replyForm 배선은 없앴다. 답글 영역에 <form> 이 존재하지 않는데(실측 2026-09-20)
 *   전역 querySelector("form") 은 페이지의 다른 폼(검색·필터)을 잡는다. 사장님이
 *   검색만 해도 isTrusted=true 인 submit 이 와서 게시하지 않은 답글이 POSTED 로
 *   기록됐다. 되살리지 말 것.
 */
function wirePublishDetection(item: Element, page: PageSpec, hash: string): void {
  if (!page.replySubmitButton) return;
  const button = item.querySelector(page.replySubmitButton);
  if (!button || wiredElements.has(button)) return;
  wiredElements.add(button);
  button.addEventListener("click", (e) =>
    handlePublishEvent(e, () => {
      void sendToBackground({
        type: "REVIEW_POSTED",
        pagePath: window.location.pathname,
        reviewHash: hash,
      });
    }),
  );
}

function tick(): void {
  // ★ currentStoreId 가 없어도(페어링 전) 추출까지는 돌린다 — 콘솔로 수집 상태를
  //   확인할 수 있어야 한다. 전송은 scan() 안에서 막힌다.
  if (!currentSpec) return;
  const page = findPageSpec(currentSpec);
  if (!page) return;

  if (isSessionExpired(page)) {
    // ★ 갓 열린 페이지는 아직 그리는 중일 수 있다. 여기서 단정하면 정상 로딩마다
    //   "다시 로그인하세요" 가 뜬다 — 진짜 만료됐을 때 사장님이 안 믿게 된다.
    if (Date.now() - loadedAt < SESSION_GRACE_MS) return;
    showBanner("네이버 로그인이 만료된 것 같습니다. 다시 로그인해 주세요.");
    if (!sessionExpiryReported) {
      sessionExpiryReported = true;
      void sendToBackground({ type: "SESSION_EXPIRED" });
    }
    return;
  }

  sessionExpiryReported = false;
  hideBanner();
  void scan(page, currentStoreId);
}

function startHeartbeat(): void {
  setInterval(() => void sendToBackground({ type: "HEARTBEAT" }), HEARTBEAT_INTERVAL_MS);
}

async function handleInsertDraft(hash: string, content: string): Promise<InsertResult> {
  if (!currentSpec || !currentStoreId) return { ok: false, reason: "NO_SPEC" };
  const page = findPageSpec(currentSpec);
  if (!page) return { ok: false, reason: "NO_SPEC" };

  const item = await findReviewItem(page, currentStoreId, hash);
  if (!item) return { ok: false, reason: "REVIEW_NOT_FOUND" };
  if (!insertDraft(item, page, content)) return { ok: false, reason: "BOX_NOT_OPEN" };

  wirePublishDetection(item, page, hash);
  return { ok: true };
}

function wireDraftInsertion(): void {
  // side panel 승인 후 background 가 밀어주는 초안을 입력창에 반영한다.
  // ★ 반드시 결과를 돌려준다. 예전에는 fire-and-forget 이라 삽입이 실패해도
  //   background 가 INSERTED 로 마킹했다 — 아무 데도 안 들어갔는데 기록만 남았다.
  chrome.runtime.onMessage.addListener(
    (message: { type: string; reviewHash?: string; content?: string }, _sender, sendResponse) => {
      if (message.type !== "INSERT_DRAFT") return undefined;
      if (!message.reviewHash || !message.content) {
        sendResponse({ ok: false, reason: "NO_SPEC" } satisfies InsertResult);
        return undefined;
      }
      void handleInsertDraft(message.reviewHash, message.content).then(sendResponse);
      return true; // 비동기 응답
    },
  );
}

/**
 * 페어링·매장선택을 즉시 반영한다.
 *
 * ★ 없으면 연결해도 아무 일이 일어나지 않는다(실측 2026-09-20). content script 는
 *   페이지 로드 때 storeId 를 한 번 읽는데, 페어링은 그 뒤에 **사이드패널에서** 일어난다.
 *   이미 떠 있는 content script 는 그 사실을 영영 모르고 scan() 이 전송 직전에 멈춘다.
 *   화면에는 "미확인 0건" 으로만 보여서 어디가 막혔는지 알 수 없다.
 *
 * ★ 메시지 배선 대신 chrome.storage.onChanged 를 쓴다 — 누가 어느 컨텍스트에서
 *   바꾸든 똑같이 잡힌다(사이드패널이 큐를 갱신하는 방식과 같다).
 */
function watchStoreId(): void {
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== "local" || !changes.storeId) return;
    currentStoreId = (changes.storeId.newValue as string | null) ?? null;
    if (!currentStoreId) return;
    // 페어링 전에는 셀렉터 스펙을 401 로 못 받아 번들 폴백을 쓰고 있었다. 이제 서버 것을 받는다.
    void loadSpecFromBackground().then(tick);
  });
}

async function main(): Promise<void> {
  wireDraftInsertion();
  watchStoreId();
  const { storeId } = await sendToBackground<{ storeId: string | null }>({ type: "GET_STORE_ID" });
  currentStoreId = storeId;
  await loadSpecFromBackground();

  startHeartbeat();
  watchForChanges();
  setInterval(tick, SCAN_INTERVAL_MS); // 옵저버가 놓쳐도 결국 돌게 하는 백스톱
  tick();
}

// 테스트에서는 이 모듈을 순수 헬퍼(findPageSpec/isSessionExpired/insertDraft) 조회 목적으로만
// import 한다 — chrome API 가 없는 환경에서 자동 실행되지 않도록 가드한다.
if (typeof chrome !== "undefined" && chrome.runtime?.id) {
  void main();
}
