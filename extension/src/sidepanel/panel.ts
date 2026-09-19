/**
 * 승인 큐 사이드 패널. React 없이 순수 TS + DOM (번들 예산 500KB, 저사양 POS PC).
 * docs/naver/04-extension-spec.md 의 스케치를 따른다.
 */
import type { QueueEntry } from "../state/queueEntry";
import type { NaverStore } from "../api/client";
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

/** 개발용 API 주소 설정 화면. 저장 후 onDone 이 가리키는 이전 화면으로 돌아간다. */
async function renderSettings(onDone: () => void | Promise<void>): Promise<void> {
  const { apiBaseUrl } = await sendToBackground<{ apiBaseUrl: string }>({ type: "GET_SETTINGS" });
  app.innerHTML = `
    <div class="pairing">
      <h1>설정</h1>
      <p>로컬 개발 서버를 쓰려면 API 주소를 바꾸세요(예: http://localhost:18080).</p>
      <input id="api-base-url" />
      <button class="primary" id="settings-save">저장</button>
      <p><a href="#" id="settings-back">뒤로</a></p>
    </div>
  `;
  (document.getElementById("api-base-url") as HTMLInputElement).value = apiBaseUrl;

  document.getElementById("settings-save")!.addEventListener("click", async () => {
    const value = (document.getElementById("api-base-url") as HTMLInputElement).value.trim();
    await sendToBackground({ type: "SET_API_BASE_URL", apiBaseUrl: value });
    await onDone();
  });
  document.getElementById("settings-back")!.addEventListener("click", (e) => {
    e.preventDefault();
    void onDone();
  });
}

function renderPairing(): void {
  app.innerHTML = `
    <div class="pairing">
      <h1>리뷰파일럿 연결하기</h1>
      <p>웹 대시보드에서 발급받은 8자 코드를 입력하세요.</p>
      <input id="pair-code" maxlength="8" placeholder="ABCD1234" />
      <button class="primary" id="pair-submit">연결</button>
      <p id="pair-error" style="color:#b3261e"></p>
      <p><a href="#" id="open-settings">API 주소 설정</a></p>
    </div>
  `;
  document.getElementById("pair-submit")!.addEventListener("click", async () => {
    const code = (document.getElementById("pair-code") as HTMLInputElement).value.trim();
    const res = await sendToBackground<{ ok: boolean; storeId?: string | null; stores?: NaverStore[] }>({
      type: "PAIR",
      code,
    });
    if (!res.ok) {
      document.getElementById("pair-error")!.textContent = "코드가 올바르지 않습니다. 다시 확인해 주세요.";
      return;
    }
    const stores = res.stores ?? [];
    if (res.storeId) {
      await renderQueue();
    } else if (stores.length > 1) {
      renderStoreSelect(stores);
    } else {
      document.getElementById("pair-error")!.textContent = "연결된 매장을 찾을 수 없습니다. 관리자에게 문의해 주세요.";
    }
  });
  document.getElementById("open-settings")!.addEventListener("click", (e) => {
    e.preventDefault();
    void renderSettings(renderPairing);
  });
}

/** 페어링 응답에 매장이 2개 이상일 때만 보인다. 매장 1개면 background 가 자동 선택한다. */
function renderStoreSelect(stores: NaverStore[]): void {
  app.innerHTML = `
    <div class="pairing">
      <h1>매장을 선택해 주세요</h1>
      <p>이 확장이 연결할 매장을 선택하세요.</p>
      <select id="store-select"></select>
      <button class="primary" id="store-select-submit">선택</button>
    </div>
  `;
  const select = document.getElementById("store-select") as HTMLSelectElement;
  for (const store of stores) {
    const option = document.createElement("option");
    option.value = store.storeId;
    option.textContent = store.name;
    select.appendChild(option);
  }
  document.getElementById("store-select-submit")!.addEventListener("click", async () => {
    await sendToBackground({ type: "SELECT_STORE", storeId: select.value });
    await renderQueue();
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
      <div class="header-row">
        <h1>리뷰파일럿</h1>
        <a href="#" id="open-settings" class="settings-link">⚙ 설정</a>
      </div>
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

  document.getElementById("open-settings")!.addEventListener("click", (e) => {
    e.preventDefault();
    void renderSettings(renderQueue);
  });

  document.getElementById("bulk-approve")!.addEventListener("click", () => {
    if (bulkTargets.length === 0) return;
    openPinModal(async (pin) => {
      const res = await sendToBackground<{ ok: boolean; approved: number; excluded: Record<string, string> }>({
        type: "BULK_APPROVE",
        reviewHashes: bulkTargets.map((e) => e.reviewHash),
        pin,
      });
      await renderQueue();
      const excludedReasons = [...new Set(Object.values(res.excluded ?? {}))];
      if (excludedReasons.length > 0) {
        alert(`일부 리뷰는 일괄 승인에서 제외됐습니다.\n${excludedReasons.join("\n")}`);
      }
    });
  });
}

async function main(): Promise<void> {
  const { storeId, stores } = await sendToBackground<{ storeId: string | null; stores: NaverStore[] }>({
    type: "GET_STORES",
  });
  if (!storeId) {
    if (stores.length > 1) {
      renderStoreSelect(stores);
    } else {
      renderPairing();
    }
    return;
  }
  await renderQueue();
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && changes.naverDraftQueue) void renderQueue();
  });
}

void main();
