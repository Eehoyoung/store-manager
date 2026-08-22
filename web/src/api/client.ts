import { getAccessToken, setAccessToken, triggerForceLogout } from "../auth/tokenStore";
import type { ApiErrorEnvelope } from "./types";

// docs/13 §1 공통규약: Base URL 환경변수, 기본값은 로컬 Spring.
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1";

/** 서버가 docs/13 §1.1 에러 봉투({code,message,traceId,details})로 응답한 실패. */
export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  readonly traceId?: string;
  readonly details: Record<string, unknown> | null;

  constructor(status: number, body: ApiErrorEnvelope) {
    super(body.message || "요청 처리 중 오류가 발생했습니다.");
    this.code = body.code || "UNKNOWN";
    this.status = status;
    this.traceId = body.traceId;
    this.details = body.details ?? null;
  }
}

/** 서버 응답 자체를 받지 못한 경우(오프라인, 서버 다운 등) — 에러 봉투가 없다. */
export class NetworkError extends Error {}

interface RequestOptions extends Omit<RequestInit, "body"> {
  body?: unknown;
  /** 되돌릴 수 없는 요청(게시·해지 접수 등)에 붙인다(docs/13 §1 멱등성). 승인·거절 API는 풀자동화로 폐기됐다. */
  idempotencyKey?: string;
}

let refreshPromise: Promise<boolean> | null = null;

// 401 을 받았을 때 refresh 를 "한 번만" 시도한다 — 재시도 플래그(_retried) 없이 재귀하면
// refresh 자체가 401 을 반환할 때 무한 루프에 빠진다.
async function refreshOnce(): Promise<boolean> {
  if (!refreshPromise) {
    refreshPromise = doRefresh().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

async function doRefresh(): Promise<boolean> {
  try {
    const res = await fetch(`${BASE_URL}/auth/refresh`, { method: "POST", credentials: "include" });
    if (!res.ok) {
      triggerForceLogout();
      return false;
    }
    const data = (await res.json()) as { accessToken: string };
    setAccessToken(data.accessToken);
    return true;
  } catch {
    triggerForceLogout();
    return false;
  }
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}, _retried = false): Promise<T> {
  const { body, idempotencyKey, headers, ...rest } = options;
  const finalHeaders: Record<string, string> = { ...(headers as Record<string, string> | undefined) };
  const token = getAccessToken();
  if (token) finalHeaders.Authorization = `Bearer ${token}`;
  if (body !== undefined) finalHeaders["Content-Type"] = "application/json";
  if (idempotencyKey) finalHeaders["Idempotency-Key"] = idempotencyKey;

  let res: Response;
  try {
    res = await fetch(`${BASE_URL}${path}`, {
      ...rest,
      headers: finalHeaders,
      credentials: "include", // refresh 쿠키(HttpOnly) 동봉
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new NetworkError("서버에 연결할 수 없습니다. 인터넷 연결을 확인해 주세요.");
  }

  // /auth/login, /auth/refresh 자체의 401 은 재시도 대상이 아니다(무한 루프 방지).
  const isAuthEndpoint = path === "/auth/login" || path === "/auth/refresh";
  if (res.status === 401 && !_retried && !isAuthEndpoint) {
    const ok = await refreshOnce();
    if (ok) return apiRequest<T>(path, options, true);
  }

  if (res.status === 204) return undefined as T;

  const text = await res.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null; // 서버가 JSON 이 아닌 응답(예: 라우트 미존재 시 기본 에러 페이지)을 준 경우
    }
  }

  if (!res.ok) {
    const envelope: ApiErrorEnvelope =
      data && typeof data === "object"
        ? (data as ApiErrorEnvelope)
        : { code: `HTTP_${res.status}`, message: `요청이 실패했습니다. (HTTP ${res.status})` };
    if (res.status === 401) triggerForceLogout();
    throw new ApiError(res.status, envelope);
  }

  return data as T;
}
