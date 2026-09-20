/**
 * service worker 용 셀렉터 스펙 캐시.
 *
 * loadSpec(): (1) chrome.storage.local 캐시 확인 → (2) 6시간 지났거나 없으면
 * 원격 조회 → (3) validateSelectorSpec 통과분만 캐시 기록 → (4) 실패 시
 * last-known-good 사용 → (5) 캐시도 없거나 깨졌으면 disabled.
 *
 * recordMiss(): 미스 카운터 증가, 3회 누적 시 기능 비활성.
 *
 * chrome API 를 직접 import 하지 않고 의존성 주입으로 받는다 — 테스트에서
 * chrome.storage 전체를 목킹할 필요가 없다. background/index.ts 가 실제
 * chrome.storage.local 바인딩을 주입한다.
 */
import { validateSelectorSpec, type SelectorSpec } from "@selector-spec";
// ★ 서버가 배포하는 것과 **같은 파일**을 번들에 넣는다. 사본을 따로 두지 않는다 —
//   두 벌이 되면 한쪽만 고쳐지는 날이 온다(정본은 api-spring 쪽 리소스 하나다).
import bundledSpec from "../../../api-spring/src/main/resources/naver/selector-spec.json";

export const CACHE_KEY = "selectorSpecCache";
const MISS_KEY = "selectorSpecMissCount";
const DISABLED_KEY = "selectorSpecDisabled";
export const CACHE_TTL_MS = 6 * 60 * 60 * 1000; // 6시간
export const MAX_MISSES = 3;

export type SpecState = { status: "ok"; spec: SelectorSpec } | { status: "disabled" };

interface CacheEntry {
  spec: SelectorSpec;
  fetchedAt: number;
}

export interface CacheDeps {
  fetchSpec: () => Promise<unknown>;
  storageGet: (key: string) => Promise<unknown>;
  storageSet: (key: string, value: unknown) => Promise<void>;
  now?: () => number;
}

export async function loadSpec(deps: CacheDeps): Promise<SpecState> {
  const disabled = await deps.storageGet(DISABLED_KEY);
  if (disabled) return { status: "disabled" };

  const now = (deps.now ?? Date.now)();
  const cached = (await deps.storageGet(CACHE_KEY)) as CacheEntry | undefined;
  const isStale = !cached || now - cached.fetchedAt > CACHE_TTL_MS;

  if (isStale) {
    try {
      const raw = await deps.fetchSpec();
      const result = validateSelectorSpec(raw);
      if (result.ok) {
        const entry: CacheEntry = { spec: raw as SelectorSpec, fetchedAt: now };
        await deps.storageSet(CACHE_KEY, entry);
        return { status: "ok", spec: entry.spec };
      }
      // 검증 실패 — 원격이 깨진 스펙을 보냈다. 아래에서 last-known-good 폴백.
    } catch {
      // 네트워크 실패 — 아래에서 last-known-good 폴백.
    }
  }

  if (cached) {
    return { status: "ok", spec: cached.spec };
  }

  // ★ 캐시도 없고 원격도 못 받았다 — 빌드에 동봉한 스펙으로 떨어진다.
  //   이게 없으면 서버가 안 뜬 상태(개발·첫 설치·점검 중)에서 확장이 통째로 죽는다.
  //   원격 데이터 설계는 그대로다: 서버가 응답하면 위에서 항상 덮어쓰고,
  //   3회 미스 킬스위치(DISABLED_KEY)도 이 폴백보다 먼저 걸린다.
  const fallback = validateSelectorSpec(bundledSpec);
  if (fallback.ok) return { status: "ok", spec: fallback.spec };
  return { status: "disabled" };
}

/** 미스 카운터를 증가시키고, 임계치 도달 시 true(비활성화됨)를 반환한다. */
export async function recordMiss(deps: CacheDeps): Promise<boolean> {
  const count = ((await deps.storageGet(MISS_KEY)) as number | undefined) ?? 0;
  const next = count + 1;
  await deps.storageSet(MISS_KEY, next);
  if (next >= MAX_MISSES) {
    await deps.storageSet(DISABLED_KEY, true);
    return true;
  }
  return false;
}

/** 새 스펙이 정상 수신되면 미스 카운터와 비활성 플래그를 초기화한다. */
export async function resetMissState(deps: CacheDeps): Promise<void> {
  await deps.storageSet(MISS_KEY, 0);
  await deps.storageSet(DISABLED_KEY, false);
}
