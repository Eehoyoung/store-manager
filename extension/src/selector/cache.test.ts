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

  /**
   * ★ 이전에는 여기서 disabled 였다. 서버가 안 뜬 상태(개발·첫 설치·점검)에서
   *   확장이 통째로 죽어 아무것도 확인할 수 없었다. 빌드에 동봉한 스펙으로 떨어진다.
   *   원격이 응답하면 위에서 항상 덮어쓰므로 "원격 데이터" 설계는 그대로다.
   */
  it("캐시도 없고 fetch 도 실패하면 번들 스펙으로 떨어진다", async () => {
    const storage = makeMemoryStorage();
    const result = await loadSpec({
      ...storage,
      fetchSpec: async () => {
        throw new Error("network down");
      },
    });
    expect(result.status).toBe("ok");
    if (result.status === "ok") expect(result.spec.pages.reviewList).toBeDefined();
  });

  it("원격 응답이 깨졌어도 번들 스펙으로 떨어진다(캐시 없을 때)", async () => {
    const storage = makeMemoryStorage();
    const result = await loadSpec({ ...storage, fetchSpec: async () => ({ broken: true }) });
    expect(result.status).toBe("ok");
  });

  /**
   * ★ 킬스위치는 번들 폴백보다 먼저 걸린다. 셀렉터가 3회 빗나가 기능을 끈 상태에서
   *   동봉 스펙으로 되살아나면 끈 의미가 없다 — 그 스펙이 바로 빗나간 그 스펙이다.
   */
  it("3회 미스로 비활성화되면 번들 스펙도 쓰지 않는다", async () => {
    const storage = makeMemoryStorage();
    const deps = { ...storage, fetchSpec: async () => validSpec };
    for (let i = 0; i < MAX_MISSES; i += 1) await recordMiss(deps);
    expect(await loadSpec(deps)).toEqual({ status: "disabled" });
  });
});
