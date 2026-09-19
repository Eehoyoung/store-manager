/**
 * 승인 큐 사이드 패널. React 없이 순수 TS + DOM (번들 예산 500KB, 저사양 POS PC).
 * docs/naver/04-extension-spec.md 의 스케치를 따른다.
 */
import type { QueueEntry } from "../state/queueEntry";
import { bulkApprovable } from "../state/machine";
import { createViewportTracker, type ViewportTracker } from "./viewportTracker";

const app = document.getElementById("app")!;

function sendToBackground<T>(message: Record<string, unknown>): Promise<T> {
  return chrome.runtime.sendMessage(message);
}

function escapeHtml(s: string): string {
  const div = document.createElement("div");
  div.textContent = s;
  return div.innerHTML;
}

let tracker: ViewportTracker = createViewportTracker(handleViewed, { threshold: 0.6, minDurationMs: 1000 });

function handleViewed(el: Element): void {
  const hash = (el as HTMLElement).dataset.reviewHash;
  if (!hash) return;
  void sendToBackground({ type: "VIEW_ITEM", reviewHash: hash });
}

function renderPairing(): void {
  app.innerHTML = `
    <div class="pairing">
      <h1>리뷰파일럿 연결하기</h1>
      <p>웹 대시보드에서 발급받은 8자 코드를 입력하세요.</p>
      <input id="pair-code" maxlength="8" placeholder="ABCD1234" />
      <button class="primary" id="pair-submit">연결</button>
      <p id="pair-error" style="color:#b3261e"></p>
    </div>
  `;
  document.getElementById("pair-submit")!.addEventListener("click", async () => {
    const code = (document.getElementById("pair-code") as HTMLInputElement).value.trim();
    const res = await sendToBackground<{ ok: boolean }>({ type: "PAIR", code });
    if (res.ok) {
      await renderQueue();
    } else {
      document.getElementById("pair-error")!.textContent = "코드가 올바르지 않습니다. 다시 확인해 주세요.";
    }
  });
}

function openPinModal(onSubmit: (pin: string) => void | Promise<void>): void {
  const modal = document.createElement("div");
  modal.className = "pin-modal";
  modal.innerHTML = `
    <div class="box">
      <p>일괄 승인 PIN 을 입력하세요</p>
      <input type="password" inputmode="numeric" id="pin-input" />
      <button class="primary" id="pin-submit">확인</button>
    </div>
  `;
  document.body.appendChild(modal);

  const input = modal.querySelector<HTMLInputElement>("#pin-input")!;
  modal.querySelector("#pin-submit")!.addEventListener("click", async () => {
    const pin = input.value;
    input.value = ""; // ★ 전송 즉시 메모리에서 지운다. chrome.storage 에 절대 쓰지 않는다.
    modal.remove();
    await onSubmit(pin);
  });
}

function renderCard(entry: QueueEntry): HTMLElement {
  const card = document.createElement("div");
  card.className = `card${entry.rating <= 2 ? " low-rating" : ""}`;
  card.dataset.reviewHash = entry.reviewHash;

  // ★ 별점 1~2 는 일괄 승인 체크박스를 렌더하지 않는다(이중 방어) — 배지만 표시.
  const lowRatingBadge = entry.rating <= 2 ? '<span class="badge">개별 확인 필요</span>' : "";

  card.innerHTML = `
    <div class="meta">${"★".repeat(Math.max(0, entry.rating))} ${lowRatingBadge}</div>
    <div class="body">${escapeHtml(entry.body)}</div>
    <textarea class="draft" data-role="draft">${escapeHtml(entry.draftContent)}</textarea>
    <div class="actions">
      <button data-role="approve">승인</button>
      <button data-role="skip">건너뛰기</button>
    </div>
  `;

  card.querySelector<HTMLTextAreaElement>('[data-role="draft"]')!.addEventListener("change", (e) => {
    const content = (e.target as HTMLTextAreaElement).value;
    void sendToBackground({ type: "EDIT_ITEM", reviewHash: entry.reviewHash, content });
  });
  card.querySelector('[data-role="approve"]')!.addEventListener("click", () => {
    void sendToBackground({ type: "APPROVE_ITEM", reviewHash: entry.reviewHash }).then(renderQueue);
  });
  card.querySelector('[data-role="skip"]')!.addEventListener("click", () => {
    void sendToBackground({ type: "SKIP_ITEM", reviewHash: entry.reviewHash }).then(renderQueue);
  });

  if (entry.state === "DRAFTED") tracker.observe(card);
  return card;
}

async function renderQueue(): Promise<void> {
  const { queue } = await sendToBackground<{ queue: Record<string, QueueEntry> }>({ type: "GET_QUEUE" });
  const entries = Object.values(queue).filter((e) => e.state !== "POSTED" && e.state !== "SKIPPED");

  tracker.disconnect();
  tracker = createViewportTracker(handleViewed, { threshold: 0.6, minDurationMs: 1000 });

  // "확인한 N건" — 전체 건수가 아니라 VIEWED 를 거쳐 조건을 만족한 건수다.
  const bulkTargets = entries.filter((e) => e.state === "VIEWED" && bulkApprovable(e));

  app.innerHTML = `
    <header>
      <h1>리뷰파일럿</h1>
      <div class="summary">미확인 ${entries.filter((e) => e.state === "DRAFTED").length}건 · 부정 ${entries.filter((e) => e.rating <= 2).length}건</div>
    </header>
    <div id="cards"></div>
    <div class="bulk-bar">
      <button class="primary" id="bulk-approve" ${bulkTargets.length === 0 ? "disabled" : ""}>
        확인한 ${bulkTargets.length}건 일괄 승인
      </button>
    </div>
  `;

  const cardsEl = document.getElementById("cards")!;
  for (const entry of entries) cardsEl.appendChild(renderCard(entry));

  document.getElementById("bulk-approve")!.addEventListener("click", () => {
    if (bulkTargets.length === 0) return;
    openPinModal(async (pin) => {
      await sendToBackground({
        type: "BULK_APPROVE",
        reviewHashes: bulkTargets.map((e) => e.reviewHash),
        pin,
      });
      await renderQueue();
    });
  });
}

async function main(): Promise<void> {
  const { storeId } = await sendToBackground<{ storeId: string | null }>({ type: "GET_STORE_ID" });
  if (!storeId) {
    renderPairing();
    return;
  }
  await renderQueue();
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && changes.naverDraftQueue) void renderQueue();
  });
}

void main();
