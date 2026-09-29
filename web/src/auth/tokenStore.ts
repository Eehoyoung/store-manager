import type { SessionKind } from "../api/types";

// accessToken 은 XSS 시 탈취 범위를 줄이기 위해 localStorage/sessionStorage 가 아니라
// 이 모듈의 메모리 변수에만 둔다(절대규칙 5 는 아니지만 F4 지시사항). 새로고침하면 사라지므로
// 각 세션(사장님/관리자/본부)의 진입 화면이 마운트 시 각자의 refresh 쿠키로 복구한다.
let accessToken: string | null = null;
// 세 세션은 한 브라우저에서 배타적으로 동작한다(사장님 화면 vs 시스템 콘솔 vs 가맹본부).
// api/client.ts 가 401 발생 시 이 값으로 어느 refresh 엔드포인트를 부를지 고른다.
let sessionKind: SessionKind = "USER";

export function getAccessToken(): string | null {
  return accessToken;
}

export function getSessionKind(): SessionKind {
  return sessionKind;
}

export function setAccessToken(token: string | null, kind: SessionKind = "USER"): void {
  accessToken = token;
  if (token) sessionKind = kind;
}

// 401 → refresh 시도까지 실패했을 때만 발화한다(api/client.ts). 각 세션의 진입점이 구독해
// 로그인 화면으로 돌려보낸다.
const forceLogoutListeners = new Set<() => void>();

export function onForceLogout(listener: () => void): () => void {
  forceLogoutListeners.add(listener);
  return () => forceLogoutListeners.delete(listener);
}

export function triggerForceLogout(): void {
  accessToken = null;
  forceLogoutListeners.forEach((listener) => listener());
}
