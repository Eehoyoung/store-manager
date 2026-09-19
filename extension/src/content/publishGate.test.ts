import { describe, expect, it, vi } from "vitest";
import { handlePublishEvent } from "./publishGate";

describe("handlePublishEvent — 게시 감지 게이트 (법적 방어선)", () => {
  it("isTrusted=false 인 합성 이벤트로는 onPosted 가 호출되지 않는다", () => {
    const onPosted = vi.fn();
    // jsdom 에서 new Event(...) 로 만든 이벤트는 항상 isTrusted === false 다.
    const syntheticEvent = new Event("click");
    expect(syntheticEvent.isTrusted).toBe(false);

    handlePublishEvent(syntheticEvent, onPosted);

    expect(onPosted).not.toHaveBeenCalled();
  });

  it("isTrusted=true 인 이벤트만 onPosted 를 호출한다 (실제 브라우저에서만 자연 발생)", () => {
    const onPosted = vi.fn();
    // isTrusted 는 읽기 전용이라 테스트 목적으로만 흉내낸다.
    const fakeTrustedEvent = { isTrusted: true } as Event;

    handlePublishEvent(fakeTrustedEvent, onPosted);

    expect(onPosted).toHaveBeenCalledTimes(1);
  });
});
