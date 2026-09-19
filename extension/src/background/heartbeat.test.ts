import { describe, expect, it } from "vitest";
import { HEARTBEAT_TIMEOUT_MS, isHeartbeatStale } from "./heartbeat";

describe("isHeartbeatStale — 탭 부재(heartbeat 끊김) 감지", () => {
  it("신호를 한 번도 못 받았으면 stale 로 보지 않는다(설치 직후 오탐 방지)", () => {
    expect(isHeartbeatStale(undefined, Date.now())).toBe(false);
  });

  it("타임아웃 이내면 stale 이 아니다", () => {
    const now = 1_000_000;
    expect(isHeartbeatStale(now - HEARTBEAT_TIMEOUT_MS + 1, now)).toBe(false);
  });

  it("타임아웃을 넘기면 stale 이다", () => {
    const now = 1_000_000;
    expect(isHeartbeatStale(now - HEARTBEAT_TIMEOUT_MS - 1, now)).toBe(true);
  });
});
