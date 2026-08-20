// accessToken 은 XSS 시 탈취 범위를 줄이기 위해 localStorage/sessionStorage 가 아니라
// 이 모듈의 메모리 변수에만 둔다(절대규칙 5 는 아니지만 F4 지시사항). 새로고침하면 사라지므로
// AuthContext 가 마운트 시 /auth/refresh 쿠키로 복구한다.
let accessToken: string | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

// 401 → refresh 시도까지 실패했을 때만 발화한다(api/client.ts). AuthContext 가 구독해 user 상태를 비운다.
const forceLogoutListeners = new Set<() => void>();

export function onForceLogout(listener: () => void): () => void {
  forceLogoutListeners.add(listener);
  return () => forceLogoutListeners.delete(listener);
}

export function triggerForceLogout(): void {
  accessToken = null;
  forceLogoutListeners.forEach((listener) => listener());
}
