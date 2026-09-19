/**
 * Review Pilot API 클라이언트.
 *
 * ★ 요청 payload 타입에 리뷰 원문 필드를 두지 않는다 — 보낼 수 있는 것은
 *   마스킹된 body 뿐이다. 서버에는 2차 마스킹(PersonalIdentifierMasker)이
 *   또 있지만, 확장은 애초에 원문을 들고 있지 않은 것을 계약으로 못박는다.
 *
 * ★ 아래 타입들은 api-spring/src/main/java/com/storemanager/api/naver/NaverDtos.java 의
 *   record 를 그대로 옮긴 것이다(IMPLEMENTATION_PLAN_NAVER.md §5). 필드명·nullable 여부를
 *   서버와 다르게 바꾸지 말 것 — 어긋나면 400/역직렬화 실패로 조용히 드러난다.
 */

export const DEFAULT_BASE_URL = "https://review.sodamlabs.kr";

export interface NaverStore {
  storeId: string;
  name: string;
}

/** NaverDtos.DraftRequest */
export interface DraftRequest {
  storeId: string;
  reviewHash: string;
  rating: number | null;
  /** 마스킹된 리뷰 본문. maskReviewBody() 를 거치지 않은 값을 넣지 말 것. */
  body: string;
  createdAt: string | null;
  hasReply: boolean | null;
}

/** NaverDtos.DraftResponse */
export interface DraftResponse {
  reviewHash: string;
  status: string;
  draft: string | null;
  blocked: boolean;
  blockReasons: string[];
  riskLevel: number;
  category: string | null;
  bulkApprovable: boolean;
}

export type QueueEvent = "VIEWED" | "EDITED" | "APPROVED" | "INSERTED" | "POSTED" | "SKIPPED";

/** NaverDtos.EventRequest */
export interface EventRequest {
  storeId: string;
  reviewHash: string;
  event: QueueEvent;
  edited: boolean | null;
  editDistance: number | null;
}

/** NaverDtos.BulkApproveRequest */
export interface BulkApproveRequest {
  storeId: string;
  reviewHashes: string[];
  pin: string;
}

/** NaverDtos.BulkApproveResponse — excluded 값은 제외 사유 코드(NOT_VIEWED 등). */
export interface BulkApproveResponse {
  approved: string[];
  excluded: Record<string, string>;
}

/** NaverDtos.SelectorMissRequest — timestamp 는 서버가 찍으므로 계약에서 뺀다. */
export interface SelectorMissRequest {
  selectorKey: string;
  pagePath: string;
  extensionVersion: string;
}

/** NaverDtos.StatusResponse */
export interface StatusResponse {
  counts: Record<string, number>;
}

export type TokenRevokedHandler = () => void | Promise<void>;

export interface ApiClientDeps {
  getBaseUrl: () => Promise<string>;
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
    const [token, baseUrl] = await Promise.all([this.deps.getToken(), this.deps.getBaseUrl()]);
    const fetchImpl = this.deps.fetchImpl ?? fetch;
    const res = await fetchImpl(`${baseUrl}${path}`, {
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

  pair(code: string): Promise<{ token: string; expiresInSeconds: number; stores: NaverStore[] }> {
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

  postBulkApprove(req: BulkApproveRequest): Promise<BulkApproveResponse> {
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
