/**
 * Review Pilot API 클라이언트.
 *
 * ★ 요청 payload 타입에 리뷰 원문 필드를 두지 않는다 — 보낼 수 있는 것은
 *   마스킹된 body 뿐이다. 서버에는 2차 마스킹(PersonalIdentifierMasker)이
 *   또 있지만, 확장은 애초에 원문을 들고 있지 않은 것을 계약으로 못박는다.
 */

export const DEFAULT_BASE_URL = "https://review.sodamlabs.kr";

export interface DraftRequest {
  storeId: string;
  reviewHash: string;
  rating: number;
  /** 마스킹된 리뷰 본문. maskReviewBody() 를 거치지 않은 값을 넣지 말 것. */
  body: string;
  createdAt: string;
  hasReply: boolean;
}

export interface DraftResponse {
  draftContent: string;
  blocked: boolean;
  riskLevel: number;
}

export interface EventRequest {
  reviewHash: string;
  platform: "naver";
  rating: number;
  edited: boolean;
  editDistance?: number;
  draftedAt?: string;
  postedAt?: string;
}

export interface BulkApproveRequest {
  reviewHashes: string[];
  pin: string;
}

export interface SelectorMissRequest {
  selectorKey: string;
  pagePath: string;
  extensionVersion: string;
  timestamp: string;
}

export interface StatusResponse {
  storeId: string;
  pendingCount: number;
  blockedCount: number;
}

export type TokenRevokedHandler = () => void | Promise<void>;

export interface ApiClientDeps {
  baseUrl: string;
  getToken: () => Promise<string | null>;
  onTokenRevoked: TokenRevokedHandler;
  fetchImpl?: typeof fetch;
}

export class TokenRevokedError extends Error {
  constructor() {
    super("EXTENSION_TOKEN_REVOKED");
  }
}

export class ApiError extends Error {
  constructor(public status: number) {
    super(`API_ERROR_${status}`);
  }
}

export class ApiClient {
  constructor(private readonly deps: ApiClientDeps) {}

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const token = await this.deps.getToken();
    const fetchImpl = this.deps.fetchImpl ?? fetch;
    const res = await fetchImpl(`${this.deps.baseUrl}${path}`, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { "X-Extension-Token": token } : {}),
        ...(init.headers ?? {}),
      },
    });

    if (res.status === 401) {
      await this.deps.onTokenRevoked();
      throw new TokenRevokedError();
    }
    if (!res.ok) {
      throw new ApiError(res.status);
    }
    if (res.status === 204) return undefined as T;
    return (await res.json()) as T;
  }

  pair(code: string): Promise<{ token: string; storeId: string }> {
    return this.request("/api/v1/naver/extension/pair", {
      method: "POST",
      body: JSON.stringify({ code }),
    });
  }

  getSelectorSpec(): Promise<unknown> {
    return this.request("/api/v1/naver/selector-spec");
  }

  postDraft(req: DraftRequest): Promise<DraftResponse> {
    return this.request("/api/v1/naver/drafts", { method: "POST", body: JSON.stringify(req) });
  }

  postEvent(req: EventRequest): Promise<void> {
    return this.request("/api/v1/naver/events", { method: "POST", body: JSON.stringify(req) });
  }

  postBulkApprove(req: BulkApproveRequest): Promise<void> {
    return this.request("/api/v1/naver/events/bulk-approve", {
      method: "POST",
      body: JSON.stringify(req),
    });
  }

  postSelectorMiss(req: SelectorMissRequest): Promise<void> {
    return this.request("/api/v1/naver/telemetry/selector-miss", {
      method: "POST",
      body: JSON.stringify(req),
    });
  }

  getStatus(storeId: string): Promise<StatusResponse> {
    return this.request(`/api/v1/naver/status?storeId=${encodeURIComponent(storeId)}`);
  }
}
