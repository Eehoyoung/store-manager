import { describe, expect, it } from "vitest";
import validSpec from "../../../packages/selector-spec/fixtures/valid-review-list.json";
import { CACHE_KEY, loadSpec, MAX_MISSES, recordMiss, resetMissState, type CacheDeps } from "./cache";

function makeMemoryStorage(): Pick<CacheDeps, "storageGet" | "storageSet"> {
  const store = new Map<string, unknown>();
  return {
    storageGet: async (key) => store.get(key),
    storageSet: async (key, value) => {
      store.set(key, value);
    },
  };
}

describe("loadSpec", () => {
  it("캐시가 없으면 원격에서 조회해 검증 후 캐시에 기록한다", async () => {
    const storage = makeMemoryStorage();
    const deps: CacheDeps = { ...storage, fetchSpec: async () => validSpec };

    const result = await loadSpec(deps);

    expect(result.status).toBe("ok");
    expect(await storage.storageGet(CACHE_KEY)).toBeDefined();
  });

  it("fetch 실패 시 last-known-good 캐시를 사용한다", async () => {
    const storage = makeMemoryStorage();
    // 1회차: 정상 캐시 적재
    await loadSpec({ ...storage, fetchSpec: async () => validSpec });

    // 2회차: 캐시를 강제로 만료시키고 fetch 는 실패하게 한다
    const cached = (await storage.storageGet(CACHE_KEY)) as { spec: unknown; fetchedAt: number };
    await storage.storageSet(CACHE_KEY, { ...cached, fetchedAt: 0 });

    const result = await loadSpec({
      ...storage,
      fetchSpec: async () => {
        throw new Error("network down");
      },
    });

    expect(result.status).toBe("ok");
    if (result.status === "ok") {
      expect(result.spec.version).toBe(validSpec.version);
    }
  });

  it("캐시도 없고 fetch 도 실패하면 disabled 를 반환한다", async () => {
    const storage = makeMemoryStorage();
    const result = await loadSpec({
      ...storage,
      fetchSpec: async () => {
        throw new Error("network down");
      },
    });
    expect(result).toEqual({ status: "disabled" });
  });

  it("원격 응답이 스펙 검증에 실패하면 disabled 로 폴백한다(캐시 없을 때)", async () => {
    const storage = makeMemoryStorage();
    const result = await loadSpec({ ...storage, fetchSpec: async () => ({ broken: true }) });
    expect(result).toEqual({ status: "disabled" });
  });
});

describe("recordMiss", () => {
  it("미스 3회 누적 시 disabled 상태가 된다", async () => {
    const storage = makeMemoryStorage();
    const deps: CacheDeps = { ...storage, fetchSpec: async () => validSpec };

    await loadSpec(deps); // 정상 스펙 로드
    expect((await recordMiss(deps)) as boolean).toBe(false);
    expect((await recordMiss(deps)) as boolean).toBe(false);
    expect((await recordMiss(deps)) as boolean).toBe(true); // MAX_MISSES(3)번째

    const result = await loadSpec(deps);
    expect(result).toEqual({ status: "disabled" });
  });

  it("resetMissState 이후에는 다시 정상 동작한다", async () => {
    const storage = makeMemoryStorage();
    const deps: CacheDeps = { ...storage, fetchSpec: async () => validSpec };

    for (let i = 0; i < MAX_MISSES; i++) await recordMiss(deps);
    expect((await loadSpec(deps)).status).toBe("disabled");

    await resetMissState(deps);
    expect((await loadSpec(deps)).status).toBe("ok");
  });
});
