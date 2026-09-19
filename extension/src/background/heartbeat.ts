/**
 * 탭 부재(heartbeat 끊김) 판정. 순수 함수로 분리해 chrome.alarms 없이 테스트한다.
 */
export const HEARTBEAT_TIMEOUT_MS = 2 * 60 * 1000;

export function isHeartbeatStale(
  lastHeartbeatAt: number | undefined,
  now: number,
  timeoutMs: number = HEARTBEAT_TIMEOUT_MS,
): boolean {
  if (lastHeartbeatAt === undefined) return false; // 설치 직후 등 아직 신호가 없으면 알리지 않는다
  return now - lastHeartbeatAt > timeoutMs;
}
