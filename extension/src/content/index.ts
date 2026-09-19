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

async function maskAndReport(raw: RawReview, storeId: string): Promise<void> {
  const platformReviewId = String(raw.id ?? "");
  if (!platformReviewId) return;

  const [hash, authorHash] = await Promise.all([
    reviewHash(platformReviewId, storeId),
    hashAuthor(String(raw.authorName ?? ""), storeId),
  ]);

  await sendToBackground({
    type: "REVIEW_DETECTED",
    storeId,
    reviewHash: hash,
    authorHash,
    rating: typeof raw.rating === "number" ? raw.rating : 0,
    body: maskReviewBody(String(raw.body ?? "")),
    hasReply: Boolean(raw.hasReply),
    createdAt: String(raw.createdAt ?? new Date().toISOString()),
  });
}

async function scan(page: PageSpec, storeId: string): Promise<void> {
  const { items, misses } = extractReviews(document, page);
  for (const selectorKey of misses) {
    void sendToBackground({ type: "SELECTOR_MISS", selectorKey, pagePath: window.location.pathname });
  }
  for (const item of items) {
    await maskAndReport(item, storeId);
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
  if (!currentSpec || !currentStoreId) return;
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
