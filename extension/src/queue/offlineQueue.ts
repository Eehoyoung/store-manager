/**
 * 네트워크 실패 시 재시도 큐. chrome.storage.local 에 ★ 마스킹된 payload 만
 * 저장한다 — 호출자가 마스킹을 마친 payload 만 enqueue 해야 한다(이 모듈은
 * 강제하지 않는다, content/index.ts 가 마스킹 이후에만 호출).
 * 24시간 이상 미처리 건이 있으면 알림을 낸다.
 */

export interface QueuedItem {
  id: string;
  endpoint: string;
  payload: Record<string, unknown>;
  queuedAt: number;
}

export interface OfflineQueueDeps {
  storageGet: (key: string) => Promise<QueuedItem[] | undefined>;
  storageSet: (key: string, value: QueuedItem[]) => Promise<void>;
  notify?: (title: string, message: string) => void;
  now?: () => number;
}

export const QUEUE_KEY = "naverOfflineQueue";
const STALE_MS = 24 * 60 * 60 * 1000;

export async function enqueue(
  deps: OfflineQueueDeps,
  item: Omit<QueuedItem, "queuedAt">,
): Promise<void> {
  const current = (await deps.storageGet(QUEUE_KEY)) ?? [];
  const now = (deps.now ?? Date.now)();
  await deps.storageSet(QUEUE_KEY, [...current, { ...item, queuedAt: now }]);
}

export async function peekAll(deps: OfflineQueueDeps): Promise<QueuedItem[]> {
  return (await deps.storageGet(QUEUE_KEY)) ?? [];
}

/**
 * 큐를 비운다. send 가 true 를 반환한 항목만 제거한다.
 * 24시간 넘게 남은 항목이 있으면 notify 를 호출한다.
 */
export async function drain(
  deps: OfflineQueueDeps,
  send: (item: QueuedItem) => Promise<boolean>,
): Promise<void> {
  const current = (await deps.storageGet(QUEUE_KEY)) ?? [];
  const remaining: QueuedItem[] = [];
  for (const item of current) {
    const ok = await send(item).catch(() => false);
    if (!ok) remaining.push(item);
  }
  await deps.storageSet(QUEUE_KEY, remaining);

  const now = (deps.now ?? Date.now)();
  const staleCount = remaining.filter((i) => now - i.queuedAt > STALE_MS).length;
  if (staleCount > 0) {
    deps.notify?.(
      "처리 대기 중인 답글이 있습니다",
      `${staleCount}건이 24시간 넘게 대기 중입니다. 크롬이 네이버 리뷰 페이지에 연결돼 있는지 확인해 주세요.`,
    );
  }
}
