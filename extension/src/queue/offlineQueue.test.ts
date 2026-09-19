import { describe, expect, it, vi } from "vitest";
import { drain, enqueue, peekAll, QUEUE_KEY, type OfflineQueueDeps } from "./offlineQueue";

function makeDeps(overrides: Partial<OfflineQueueDeps> = {}): OfflineQueueDeps {
  const store = new Map<string, unknown>();
  return {
    storageGet: async (key) => store.get(key) as never,
    storageSet: async (key, value) => {
      store.set(key, value);
    },
    ...overrides,
  };
}

describe("offlineQueue", () => {
  it("enqueue 한 payload 가 원문 변형 없이 그대로 저장된다 (마스킹 전 원문 없음)", async () => {
    const deps = makeDeps();
    const maskedPayload = { body: "[전화번호] 로 연락주세요", reviewHash: "abc123" };

    await enqueue(deps, { id: "1", endpoint: "postDraft", payload: maskedPayload });

    const all = await peekAll(deps);
    expect(all).toHaveLength(1);
    expect(all[0].payload).toEqual(maskedPayload);
    expect(JSON.stringify(all[0])).not.toContain("010-");
  });

  it("drain 은 send 가 true 를 반환한 항목만 제거한다", async () => {
    const deps = makeDeps();
    await enqueue(deps, { id: "1", endpoint: "postDraft", payload: { a: 1 } });
    await enqueue(deps, { id: "2", endpoint: "postDraft", payload: { a: 2 } });

    await drain(deps, async (item) => item.id === "1");

    const remaining = await peekAll(deps);
    expect(remaining).toHaveLength(1);
    expect(remaining[0].id).toBe("2");
  });

  it("24시간 넘게 남은 항목이 있으면 notify 를 호출한다", async () => {
    const notify = vi.fn();
    const deps = makeDeps({ notify, now: () => 1_000_000 });
    await enqueue(deps, { id: "1", endpoint: "postDraft", payload: {} });

    // 24시간 + 1ms 이후 시점에서 drain
    const laterDeps = { ...deps, now: () => 1_000_000 + 24 * 60 * 60 * 1000 + 1 };
    await drain(laterDeps, async () => false);

    expect(notify).toHaveBeenCalledTimes(1);
  });

  it("24시간 이내면 notify 를 호출하지 않는다", async () => {
    const notify = vi.fn();
    const deps = makeDeps({ notify, now: () => 1_000_000 });
    await enqueue(deps, { id: "1", endpoint: "postDraft", payload: {} });

    await drain(deps, async () => false);

    expect(notify).not.toHaveBeenCalled();
  });

  it("QUEUE_KEY 는 고정된 storage 키다", () => {
    expect(QUEUE_KEY).toBe("naverOfflineQueue");
  });
});
