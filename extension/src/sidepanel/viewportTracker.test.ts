import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createViewportTracker } from "./viewportTracker";

type Callback = (entries: Partial<IntersectionObserverEntry>[]) => void;

/** 실제 IntersectionObserver 를 흉내내는 테스트 더블. 콜백을 밖에서 수동으로 트리거한다. */
function makeFakeIntersectionObserverCtor() {
  let lastCallback: Callback | null = null;
  const observed = new Set<Element>();

  class FakeIntersectionObserver {
    constructor(callback: Callback) {
      lastCallback = callback;
    }
    observe(el: Element) {
      observed.add(el);
    }
    unobserve(el: Element) {
      observed.delete(el);
    }
    disconnect() {
      observed.clear();
    }
  }

  return {
    Ctor: FakeIntersectionObserver as unknown as typeof IntersectionObserver,
    trigger: (entries: Partial<IntersectionObserverEntry>[]) => lastCallback?.(entries),
  };
}

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

describe("createViewportTracker", () => {
  it("threshold 이상으로 minDurationMs 이상 머물면 onViewed 를 호출한다", () => {
    const { Ctor, trigger } = makeFakeIntersectionObserverCtor();
    const onViewed = vi.fn();
    const tracker = createViewportTracker(onViewed, {
      threshold: 0.6,
      minDurationMs: 1000,
      IntersectionObserverCtor: Ctor,
    });
    const el = document.createElement("div");
    tracker.observe(el);

    trigger([{ target: el, isIntersecting: true, intersectionRatio: 0.8 }]);
    expect(onViewed).not.toHaveBeenCalled(); // 아직 1초 안 지남

    vi.advanceTimersByTime(999);
    expect(onViewed).not.toHaveBeenCalled();

    vi.advanceTimersByTime(2);
    expect(onViewed).toHaveBeenCalledTimes(1);
    expect(onViewed).toHaveBeenCalledWith(el);
  });

  it("threshold 미만이면 onViewed 를 호출하지 않는다", () => {
    const { Ctor, trigger } = makeFakeIntersectionObserverCtor();
    const onViewed = vi.fn();
    const tracker = createViewportTracker(onViewed, {
      threshold: 0.6,
      minDurationMs: 1000,
      IntersectionObserverCtor: Ctor,
    });
    const el = document.createElement("div");
    tracker.observe(el);

    trigger([{ target: el, isIntersecting: true, intersectionRatio: 0.3 }]);
    vi.advanceTimersByTime(2000);

    expect(onViewed).not.toHaveBeenCalled();
  });

  it("1초 채우기 전에 뷰포트를 벗어나면 타이머가 취소된다", () => {
    const { Ctor, trigger } = makeFakeIntersectionObserverCtor();
    const onViewed = vi.fn();
    const tracker = createViewportTracker(onViewed, {
      threshold: 0.6,
      minDurationMs: 1000,
      IntersectionObserverCtor: Ctor,
    });
    const el = document.createElement("div");
    tracker.observe(el);

    trigger([{ target: el, isIntersecting: true, intersectionRatio: 0.8 }]);
    vi.advanceTimersByTime(500);
    trigger([{ target: el, isIntersecting: false, intersectionRatio: 0 }]);
    vi.advanceTimersByTime(1000);

    expect(onViewed).not.toHaveBeenCalled();
  });
});
