/**
 * 항목이 뷰포트에 threshold 이상 비율로 minDurationMs 이상 머물 때만 onViewed 를 호출한다.
 * IntersectionObserver 생성자를 주입받을 수 있어 테스트에서 실제 브라우저 없이 검증한다.
 */
export interface ViewportTrackerOptions {
  threshold?: number;
  minDurationMs?: number;
  IntersectionObserverCtor?: typeof IntersectionObserver;
  now?: () => number;
}

export interface ViewportTracker {
  observe: (el: Element) => void;
  disconnect: () => void;
}

export function createViewportTracker(
  onViewed: (el: Element) => void,
  options: ViewportTrackerOptions = {},
): ViewportTracker {
  const threshold = options.threshold ?? 0.6;
  const minDurationMs = options.minDurationMs ?? 1000;
  const Ctor = options.IntersectionObserverCtor ?? IntersectionObserver;

  const timers = new WeakMap<Element, ReturnType<typeof setTimeout>>();
  const firedFor = new WeakSet<Element>();

  const observer = new Ctor(
    (entries) => {
      for (const entry of entries) {
        const el = entry.target;
        if (firedFor.has(el)) continue;

        const isVisibleEnough = entry.isIntersecting && entry.intersectionRatio >= threshold;
        if (isVisibleEnough) {
          if (!timers.has(el)) {
            const timer = setTimeout(() => {
              firedFor.add(el);
              timers.delete(el);
              onViewed(el);
              observer.unobserve(el);
            }, minDurationMs);
            timers.set(el, timer);
          }
        } else {
          const timer = timers.get(el);
          if (timer !== undefined) {
            clearTimeout(timer);
            timers.delete(el);
          }
        }
      }
    },
    { threshold },
  );

  return {
    observe: (el: Element) => observer.observe(el),
    disconnect: () => observer.disconnect(),
  };
}
