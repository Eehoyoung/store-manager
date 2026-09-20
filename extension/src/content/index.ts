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
const HEARTBEAT_INTERVAL_MS = 30 * 1000;

let currentSpec: SelectorSpec | null = null;
let currentStoreId: string | null = null;
// background 가 INSERT_DRAFT 로 밀어준 초안이 어느 리뷰의 것인지 추적한다.
// 게시 감지 시점에는 DOM 에 리뷰 식별자가 없으므로 이 값으로만 매칭한다.
let activeReviewHash: string | null = null;
const wiredElements = new WeakSet<Element>();

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

async function scan(page: PageSpec, storeId: string | null): Promise<void> {
  const { items, misses } = extractReviews(document, page);
  const plan = planScan(items);

  // ★ 항상 콘솔에 남긴다. 확장이 도는지·몇 건을 보는지 확인할 유일한 창구다.
  //   숫자만 남기고 본문·닉네임은 찍지 않는다(원문을 로그에 흘리지 않는다).
  console.log(
    `[리뷰파일럿] 리뷰 ${items.length}건 · 초안대상 ${plan.targets.length} · `
      + `이미답글 ${plan.alreadyReplied} · 식별불가 ${plan.unidentified} · 충돌 ${plan.colliding}`
      + (storeId ? "" : "  (미연결 — 서버로 보내지 않는다)"),
  );

  for (const selectorKey of misses) {
    void sendToBackground({ type: "SELECTOR_MISS", selectorKey, pagePath: window.location.pathname });
  }
  // 연결 전에는 여기서 멈춘다. 추출은 확인할 수 있고 전송은 하지 않는다.
  if (!storeId) return;

  for (const t of plan.targets) {
    await maskAndReport(t.raw, storeId, t.identity, t.writtenAt);
  }
}

/** 초안을 입력창에 삽입한다. 폼 열기·스크롤·포커스·값 설정은 UI 조작이지 콘텐츠 발행이 아니다. */
export function insertDraft(page: PageSpec, content: string): boolean {
  if (!page.replyInput) return false;
  const textarea = document.querySelector<HTMLTextAreaElement>(page.replyInput);
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

/** 게시 감지 배선. onPosted 는 handlePublishEvent 를 통해서만 호출된다(isTrusted 게이트). */
function wirePublishDetection(page: PageSpec): void {
  const onPosted = () => {
    void sendToBackground({
      type: "REVIEW_POSTED",
      pagePath: window.location.pathname,
      reviewHash: activeReviewHash,
    });
    activeReviewHash = null;
  };

  const form = page.replyForm ? document.querySelector(page.replyForm) : null;
  if (form && !wiredElements.has(form)) {
    form.addEventListener("submit", (e) => handlePublishEvent(e, onPosted));
    wiredElements.add(form);
  }

  const button = page.replySubmitButton ? document.querySelector(page.replySubmitButton) : null;
  if (button && !wiredElements.has(button)) {
    button.addEventListener("click", (e) => handlePublishEvent(e, onPosted));
    wiredElements.add(button);
  }
}

function tick(): void {
  // ★ currentStoreId 가 없어도(페어링 전) 추출까지는 돌린다 — 콘솔로 수집 상태를
  //   확인할 수 있어야 한다. 전송은 scan() 안에서 막힌다.
  if (!currentSpec) return;
  const page = findPageSpec(currentSpec);
  if (!page) return;

  if (isSessionExpired(page)) {
    showBanner("네이버 로그인이 만료된 것 같습니다. 다시 로그인해 주세요.");
    void sendToBackground({ type: "SESSION_EXPIRED" });
    return;
  }

  hideBanner();
  wirePublishDetection(page);
  void scan(page, currentStoreId);
}

function startHeartbeat(): void {
  setInterval(() => void sendToBackground({ type: "HEARTBEAT" }), HEARTBEAT_INTERVAL_MS);
}

function wireDraftInsertion(): void {
  // side panel 승인 후 background 가 밀어주는 초안을 입력창에 반영한다.
  chrome.runtime.onMessage.addListener(
    (message: { type: string; reviewHash?: string; content?: string }) => {
      if (message.type !== "INSERT_DRAFT" || !currentSpec || !message.reviewHash || !message.content) return;
      const page = findPageSpec(currentSpec);
      if (page && insertDraft(page, message.content)) {
        activeReviewHash = message.reviewHash;
      }
    },
  );
}

async function main(): Promise<void> {
  wireDraftInsertion();
  const { storeId } = await sendToBackground<{ storeId: string | null }>({ type: "GET_STORE_ID" });
  currentStoreId = storeId;
  await loadSpecFromBackground();

  startHeartbeat();
  setInterval(tick, SCAN_INTERVAL_MS);
  tick();
}

// 테스트에서는 이 모듈을 순수 헬퍼(findPageSpec/isSessionExpired/insertDraft) 조회 목적으로만
// import 한다 — chrome API 가 없는 환경에서 자동 실행되지 않도록 가드한다.
if (typeof chrome !== "undefined" && chrome.runtime?.id) {
  void main();
}
