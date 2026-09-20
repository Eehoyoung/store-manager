/**
 * service worker — spec 캐시 갱신, 확장 토큰 보관/폐기, content script 메시지
 * 라우팅, 승인 큐 상태머신 강제, 초안 삽입 중계, 오프라인 큐 플러시,
 * heartbeat 타임아웃 알림, 24시간 적체 알림.
 *
 * ★ 네이버 도메인을 직접 호출하지 않는다. 이 파일이 호출하는 것은 우리 서버뿐이다.
 * ★ 탭 메시지는 chrome.tabs.sendMessage(tabId, ...) 로만 보낸다 — 이 호출 자체는
 *   "tabs" 권한이 필요 없다(sender.tab.id 로 이미 확보한 tabId 에만 보낸다).
 *   chrome.tabs.query 등 권한이 필요한 API 는 쓰지 않는다.
 */
import { ApiClient, DEFAULT_BASE_URL, type NaverStore, type QueueEvent } from "../api/client";
import { loadSpec, recordMiss, resetMissState, type CacheDeps } from "../selector/cache";
import { drain, enqueue, type OfflineQueueDeps, type QueuedItem } from "../queue/offlineQueue";
import { reportSelectorMiss } from "../telemetry/selectorMiss";
import { canTransition, selectBulkTargets } from "../state/machine";
import type { QueueEntry } from "../state/queueEntry";
import { isHeartbeatStale } from "./heartbeat";

const TOKEN_KEY = "extensionToken";
const STORE_ID_KEY = "storeId";
const STORES_KEY = "naverStores";
const API_BASE_URL_KEY = "apiBaseUrl";
const LAST_HEARTBEAT_KEY = "lastHeartbeatAt";
const QUEUE_KEY = "naverDraftQueue";

const SPEC_ALARM = "refreshSelectorSpec";
const HEARTBEAT_CHECK_ALARM = "checkHeartbeat";
const QUEUE_DRAIN_ALARM = "drainOfflineQueue";

// 제외 사유 코드 → 사람이 읽을 문구. 모르는 코드는 원본 코드를 그대로 보여준다
// (서버가 코드를 늘려도 화면이 깨지지 않게).
const EXCLUDE_REASON_KO: Record<string, string> = {
  NOT_VIEWED: "확인하지 않은 리뷰",
  LOW_RATING: "별점 1~2점은 개별 확인이 필요합니다",
  RISK_BLOCKED: "위험 검수 대상이라 자동 승인할 수 없습니다",
};

// heartbeat 를 보낸 탭에만 초안을 밀어준다. "tabs" 권한 없이도 이미 받은 sender.tab.id 로는
// chrome.tabs.sendMessage 를 쓸 수 있다. service worker 재시작 시 null 로 리셋되지만
// 다음 30초 heartbeat 로 곧 복구된다(허용 가능한 지연).
let activeNaverTabId: number | null = null;

async function storageGet(key: string): Promise<unknown> {
  const result = await chrome.storage.local.get(key);
  return result[key];
}
async function storageSet(key: string, value: unknown): Promise<void> {
  await chrome.storage.local.set({ [key]: value });
}

async function getQueue(): Promise<Record<string, QueueEntry>> {
  return ((await storageGet(QUEUE_KEY)) as Record<string, QueueEntry> | undefined) ?? {};
}
async function setQueueEntry(entry: QueueEntry): Promise<void> {
  const queue = await getQueue();
  queue[entry.reviewHash] = entry;
  await storageSet(QUEUE_KEY, queue);
}

function showNotification(title: string, message: string): void {
  // ponytail: 아이콘 자산 미준비 — icon128.png 는 배포 전 추가해야 실제로 표시된다.
  chrome.notifications.create({ type: "basic", iconUrl: "icons/icon128.png", title, message });
}

async function getToken(): Promise<string | null> {
  return ((await storageGet(TOKEN_KEY)) as string | undefined) ?? null;
}
async function revokeToken(): Promise<void> {
  await storageSet(TOKEN_KEY, null);
  showNotification("재연결이 필요합니다", "확장 연결이 끊어졌습니다. 웹 대시보드에서 다시 페어링해 주세요.");
}
async function getBaseUrl(): Promise<string> {
  return ((await storageGet(API_BASE_URL_KEY)) as string | undefined) || DEFAULT_BASE_URL;
}
async function getStoreId(): Promise<string | null> {
  return ((await storageGet(STORE_ID_KEY)) as string | undefined) ?? null;
}

const client = new ApiClient({ getBaseUrl, getToken, onTokenRevoked: revokeToken });

const specCacheDeps: CacheDeps = { fetchSpec: () => client.getSelectorSpec(), storageGet, storageSet };

const queueDeps: OfflineQueueDeps = {
  storageGet: (key) => storageGet(key) as Promise<QueuedItem[] | undefined>,
  storageSet: (key, value) => storageSet(key, value),
  notify: showNotification,
};

async function sendQueuedItem(item: QueuedItem): Promise<boolean> {
  try {
    if (item.endpoint === "postDraft") await client.postDraft(item.payload as never);
    else if (item.endpoint === "postEvent") await client.postEvent(item.payload as never);
    else return true; // 알 수 없는 endpoint 는 버린다 — 무한 재시도를 막는다
    return true;
  } catch {
    return false;
  }
}

/** 상태 전이를 서버에 기록한다. 실패하면 오프라인 큐에 쌓아 재시도한다. */
async function reportEvent(storeId: string, reviewHash: string, event: QueueEvent, edited = false): Promise<void> {
  const req = { storeId, reviewHash, event, edited, editDistance: null };
  try {
    await client.postEvent(req);
  } catch {
    await enqueue(queueDeps, { id: crypto.randomUUID(), endpoint: "postEvent", payload: req });
  }
}

async function handleReviewDetected(message: Record<string, unknown>): Promise<void> {
  const reviewHash = String(message.reviewHash ?? "");
  const storeId = String(message.storeId ?? "");
  const req = {
    storeId,
    reviewHash,
    rating: typeof message.rating === "number" ? message.rating : Number(message.rating ?? 0),
    body: String(message.body ?? ""),
    createdAt: String(message.createdAt ?? new Date().toISOString()),
    hasReply: Boolean(message.hasReply),
  };

  const existing = (await getQueue())[reviewHash];
  if (existing) return; // 같은 매장 5분 스캔 주기가 재감지해도 중복 초안을 만들지 않는다

  try {
    const draft = await client.postDraft(req);
    await setQueueEntry({
      reviewHash,
      storeId,
      rating: req.rating,
      body: req.body,
      draftContent: draft.draft ?? "",
      blocked: draft.blocked,
      riskLevel: draft.riskLevel,
      riskReasons: draft.riskReasons ?? [],
      state: "DRAFTED",
      edited: false,
      detectedAt: Date.now(),
    });
  } catch {
    await enqueue(queueDeps, { id: crypto.randomUUID(), endpoint: "postDraft", payload: req });
  }
}

async function handleReviewPosted(message: Record<string, unknown>): Promise<void> {
  const reviewHash = message.reviewHash ? String(message.reviewHash) : null;
  if (!reviewHash) return; // 활성 리뷰를 특정할 수 없으면 보고하지 않는다(오귀속 방지)

  const entry = (await getQueue())[reviewHash];
  if (!entry) return;
  if (canTransition(entry.state, "POSTED")) {
    entry.state = "POSTED";
    await setQueueEntry(entry);
  }
  await reportEvent(entry.storeId, reviewHash, "POSTED", entry.edited);
}

/** 승인된 초안을 활성 탭에 삽입 요청한다. 게시는 여전히 사람이 네이버 버튼을 눌러야 한다. */
function pushInsertDraft(entry: QueueEntry): void {
  if (activeNaverTabId === null) return; // 탭 미확인 — 다음 heartbeat 이후 재시도 대상(패널에서 재승인)
  chrome.tabs.sendMessage(activeNaverTabId, {
    type: "INSERT_DRAFT",
    reviewHash: entry.reviewHash,
    content: entry.draftContent,
  });
}

async function checkHeartbeat(): Promise<void> {
  const last = (await storageGet(LAST_HEARTBEAT_KEY)) as number | undefined;
  if (isHeartbeatStale(last, Date.now())) {
    showNotification(
      "네이버 리뷰 페이지가 열려 있지 않습니다",
      "리뷰 자동 수집이 멈췄습니다. 크롬에서 리뷰 페이지 탭을 열어 주세요.",
    );
  }
}

type Message = { type: string; [key: string]: unknown };

async function handleMessage(message: Message, senderTabId: number | undefined): Promise<unknown> {
  if (senderTabId !== undefined) activeNaverTabId = senderTabId;

  switch (message.type) {
    case "GET_STORE_ID":
      return { storeId: await getStoreId() };

    case "GET_STORES":
      return {
        storeId: await getStoreId(),
        stores: ((await storageGet(STORES_KEY)) as NaverStore[] | undefined) ?? [],
      };

    case "SELECT_STORE":
      await storageSet(STORE_ID_KEY, String(message.storeId ?? "") || null);
      return { ok: true };

    case "GET_SETTINGS":
      return { apiBaseUrl: await getBaseUrl() };

    case "SET_API_BASE_URL":
      await storageSet(API_BASE_URL_KEY, String(message.apiBaseUrl ?? "").trim() || DEFAULT_BASE_URL);
      return { ok: true };

    case "GET_SELECTOR_SPEC":
      return loadSpec(specCacheDeps);

    case "GET_QUEUE":
      return { queue: await getQueue() };

    case "HEARTBEAT":
      await storageSet(LAST_HEARTBEAT_KEY, Date.now());
      return { ok: true };

    case "SESSION_EXPIRED":
      showNotification("재로그인이 필요합니다", "네이버 로그인이 만료된 것 같습니다.");
      return { ok: true };

    case "SELECTOR_MISS": {
      const disabled = await recordMiss(specCacheDeps);
      await reportSelectorMiss(
        { client, extensionVersion: chrome.runtime.getManifest().version },
        String(message.selectorKey ?? ""),
        String(message.pagePath ?? ""),
      );
      if (disabled) showNotification("일시 점검 중", "리뷰 페이지 구조 변경이 감지됐습니다. 곧 업데이트하겠습니다.");
      return { ok: true, disabled };
    }

    case "REVIEW_DETECTED":
      await handleReviewDetected(message);
      return { ok: true };

    case "REVIEW_POSTED":
      await handleReviewPosted(message);
      return { ok: true };

    case "VIEW_ITEM": {
      const entry = (await getQueue())[String(message.reviewHash)];
      if (!entry) return { ok: false };
      if (entry.state !== "VIEWED" && canTransition(entry.state, "VIEWED")) {
        entry.state = "VIEWED";
        await setQueueEntry(entry);
        await reportEvent(entry.storeId, entry.reviewHash, "VIEWED");
      }
      return { ok: true };
    }

    case "EDIT_ITEM": {
      const entry = (await getQueue())[String(message.reviewHash)];
      if (!entry || !canTransition(entry.state, "EDITED")) return { ok: false };
      entry.state = "EDITED";
      entry.edited = true;
      entry.draftContent = String(message.content ?? entry.draftContent);
      await setQueueEntry(entry);
      await reportEvent(entry.storeId, entry.reviewHash, "EDITED", true);
      return { ok: true };
    }

    case "APPROVE_ITEM": {
      const entry = (await getQueue())[String(message.reviewHash)];
      if (!entry || !canTransition(entry.state, "APPROVED")) return { ok: false };
      entry.state = "APPROVED";
      await reportEvent(entry.storeId, entry.reviewHash, "APPROVED", entry.edited);
      pushInsertDraft(entry);
      entry.state = "INSERTED"; // APPROVED → INSERTED 는 항상 허용되는 전이다
      await setQueueEntry(entry);
      await reportEvent(entry.storeId, entry.reviewHash, "INSERTED", entry.edited);
      return { ok: true };
    }

    case "SKIP_ITEM": {
      const entry = (await getQueue())[String(message.reviewHash)];
      if (!entry || !canTransition(entry.state, "SKIPPED")) return { ok: false };
      entry.state = "SKIPPED";
      await setQueueEntry(entry);
      await reportEvent(entry.storeId, entry.reviewHash, "SKIPPED");
      return { ok: true };
    }

    case "BULK_APPROVE": {
      const storeId = await getStoreId();
      const hashes = Array.isArray(message.reviewHashes) ? message.reviewHashes.map(String) : [];
      const pin = String(message.pin ?? "");
      const queue = await getQueue();
      const candidates = hashes.map((h) => queue[h]).filter((e): e is QueueEntry => Boolean(e));
      // ★ 서버가 최종 관문이 아니다 — 여기서도 별점 1~2·VIEWED 미경유 항목을 다시 거른다.
      const targets = selectBulkTargets(candidates);
      if (!storeId || targets.length === 0) return { ok: false, approved: 0, excluded: {} };

      let result: { approved: string[]; excluded: Record<string, string> };
      try {
        result = await client.postBulkApprove({ storeId, reviewHashes: targets.map((t) => t.reviewHash), pin });
      } catch {
        return { ok: false, approved: 0, excluded: {} };
      }

      const approvedSet = new Set(result.approved);
      for (const entry of targets) {
        if (!approvedSet.has(entry.reviewHash)) continue;
        entry.state = "APPROVED";
        await setQueueEntry(entry);
        await reportEvent(entry.storeId, entry.reviewHash, "APPROVED", entry.edited);
        pushInsertDraft(entry);
        entry.state = "INSERTED";
        await setQueueEntry(entry);
        await reportEvent(entry.storeId, entry.reviewHash, "INSERTED", entry.edited);
      }

      const excludedKo: Record<string, string> = {};
      for (const [hash, code] of Object.entries(result.excluded ?? {})) {
        excludedKo[hash] = EXCLUDE_REASON_KO[code] ?? code;
      }
      return { ok: true, approved: result.approved.length, excluded: excludedKo };
    }

    case "PAIR": {
      const { token, stores } = await client.pair(String(message.code ?? ""));
      await storageSet(TOKEN_KEY, token);
      const list: NaverStore[] = Array.isArray(stores) ? stores : [];
      await storageSet(STORES_KEY, list);
      const selected = list.length === 1 ? list[0].storeId : null;
      await storageSet(STORE_ID_KEY, selected);
      await resetMissState(specCacheDeps);
      return { ok: true, stores: list, storeId: selected };
    }

    default:
      return { ok: false };
  }
}

chrome.runtime.onMessage.addListener((message: Message, sender, sendResponse) => {
  // ★ 반드시 응답한다. 여기서 예외가 새면 두 가지가 한꺼번에 일어난다 —
  //   unhandled rejection 이 콘솔을 채우고(서버가 없으면 fetch 가 매번 던진다),
  //   sendResponse 가 영영 불리지 않아 **사이드패널이 멈춘 채로 남는다**
  //   (PAIR 이 대표적이다: 코드를 틀리거나 서버가 없으면 버튼이 먹통이 된다).
  handleMessage(message, sender.tab?.id)
    .then(sendResponse)
    .catch((e: unknown) => sendResponse({ ok: false, error: String(e) }));
  return true; // 비동기 응답
});

// 새 버전은 대개 셀렉터를 고친 버전이다. 설치·업데이트 때 킬스위치를 푼다.
// (개발 중 "확장 새로고침" 으로 막힌 상태를 빠져나오는 길이기도 하다.)
chrome.runtime.onInstalled.addListener(() => void resetMissState(specCacheDeps));

chrome.alarms.create(SPEC_ALARM, { periodInMinutes: 360 }); // 6시간
chrome.alarms.create(HEARTBEAT_CHECK_ALARM, { periodInMinutes: 2 });
chrome.alarms.create(QUEUE_DRAIN_ALARM, { periodInMinutes: 15 });

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === SPEC_ALARM) void loadSpec(specCacheDeps);
  if (alarm.name === HEARTBEAT_CHECK_ALARM) void checkHeartbeat();
  if (alarm.name === QUEUE_DRAIN_ALARM) void drain(queueDeps, sendQueuedItem);
});
