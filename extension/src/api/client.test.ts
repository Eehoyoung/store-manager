/**
 * 서버 계약 회귀 테스트 — extension 이 보내는 JSON body 의 키가 서버 record 와
 * 어긋나면 400/역직렬화 실패가 난다(2026-09 실기동 준비 중 실제로 발생한 버그).
 * api-spring/src/main/java/com/storemanager/api/naver/NaverDtos.java 의 필드명을
 * 그대로 단언한다 — 필드명을 하나 지우거나 이름을 바꾸면 이 테스트가 실패해야 한다.
 */
import { describe, expect, it, vi } from "vitest";
import { ApiClient, type NaverStore } from "./client";

function fakeResponse(status: number, body: unknown): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    json: async () => body,
  } as Response;
}

function makeClient(fetchImpl: typeof fetch) {
  return new ApiClient({
    getBaseUrl: async () => "http://localhost:18080",
    getToken: async () => "tok",
    onTokenRevoked: async () => {},
    fetchImpl,
  });
}

function lastCallBody(fetchImpl: ReturnType<typeof vi.fn>): Record<string, unknown> {
  const [, init] = fetchImpl.mock.calls[fetchImpl.mock.calls.length - 1] as [string, RequestInit];
  return JSON.parse(String(init.body));
}

describe("ApiClient — 서버 DTO 필드명 계약", () => {
  it("postDraft: DraftRequest(storeId, reviewHash, rating, body, createdAt, hasReply)", async () => {
    const fetchImpl = vi.fn(async (..._args: Parameters<typeof fetch>) =>
      fakeResponse(200, {
        reviewHash: "h1",
        status: "DRAFTED",
        draft: "초안",
        blocked: false,
        blockReasons: [],
        riskLevel: 0,
        category: null,
        bulkApprovable: true,
      }),
    );
    const client = makeClient(fetchImpl);
    await client.postDraft({
      storeId: "store-1",
      reviewHash: "h1",
      rating: 5,
      body: "맛있어요",
      createdAt: "2026-09-19",
      hasReply: false,
    });

    const body = lastCallBody(fetchImpl);
    expect(Object.keys(body).sort()).toEqual(
      ["storeId", "reviewHash", "rating", "body", "createdAt", "hasReply"].sort(),
    );
  });

  it("postDraft 응답: draftContent 가 아니라 draft 필드를 그대로 읽는다", async () => {
    const fetchImpl = vi.fn(async (..._args: Parameters<typeof fetch>) =>
      fakeResponse(200, {
        reviewHash: "h1",
        status: "DRAFTED",
        draft: "이 초안입니다",
        blocked: false,
        blockReasons: [],
        riskLevel: 0,
        category: "PRAISE",
        bulkApprovable: true,
      }),
    );
    const client = makeClient(fetchImpl);
    const res = await client.postDraft({
      storeId: "s",
      reviewHash: "h",
      rating: 5,
      body: "b",
      createdAt: null,
      hasReply: null,
    });
    expect(res.draft).toBe("이 초안입니다");
    expect((res as unknown as { draftContent?: string }).draftContent).toBeUndefined();
  });

  it("postEvent: EventRequest(storeId, reviewHash, event, edited, editDistance) — platform/rating/draftedAt/postedAt 없음", async () => {
    const fetchImpl = vi.fn(async (..._args: Parameters<typeof fetch>) => fakeResponse(204, undefined));
    const client = makeClient(fetchImpl);
    await client.postEvent({
      storeId: "store-1",
      reviewHash: "h1",
      event: "POSTED",
      edited: false,
      editDistance: null,
    });

    const body = lastCallBody(fetchImpl);
    expect(Object.keys(body).sort()).toEqual(["storeId", "reviewHash", "event", "edited", "editDistance"].sort());
    expect(body).not.toHaveProperty("platform");
    expect(body).not.toHaveProperty("rating");
    expect(body).not.toHaveProperty("draftedAt");
    expect(body).not.toHaveProperty("postedAt");
  });

  it("postBulkApprove: BulkApproveRequest(storeId, reviewHashes, pin)", async () => {
    const fetchImpl = vi.fn(async (..._args: Parameters<typeof fetch>) => fakeResponse(200, { approved: ["h1"], excluded: {} }));
    const client = makeClient(fetchImpl);
    await client.postBulkApprove({ storeId: "store-1", reviewHashes: ["h1", "h2"], pin: "1234" });

    const body = lastCallBody(fetchImpl);
    expect(Object.keys(body).sort()).toEqual(["storeId", "reviewHashes", "pin"].sort());
  });

  it("postBulkApprove 응답: approved/excluded 를 그대로 반환한다", async () => {
    const fetchImpl = vi.fn(async (..._args: Parameters<typeof fetch>) =>
      fakeResponse(200, { approved: ["h1"], excluded: { h2: "NOT_VIEWED" } }),
    );
    const client = makeClient(fetchImpl);
    const res = await client.postBulkApprove({ storeId: "s", reviewHashes: ["h1", "h2"], pin: "1234" });
    expect(res.approved).toEqual(["h1"]);
    expect(res.excluded).toEqual({ h2: "NOT_VIEWED" });
  });

  it("postSelectorMiss: timestamp 를 보내지 않는다 (서버가 찍는다)", async () => {
    const fetchImpl = vi.fn(async (..._args: Parameters<typeof fetch>) => fakeResponse(204, undefined));
    const client = makeClient(fetchImpl);
    await client.postSelectorMiss({ selectorKey: "k", pagePath: "/p", extensionVersion: "0.1.0" });

    const body = lastCallBody(fetchImpl);
    expect(Object.keys(body).sort()).toEqual(["selectorKey", "pagePath", "extensionVersion"].sort());
    expect(body).not.toHaveProperty("timestamp");
  });

  it("pair: 응답 stores 배열을 그대로 반환한다(빈 배열도 방어적으로 처리 가능해야 함)", async () => {
    const stores: NaverStore[] = [{ storeId: "s1", name: "1호점" }];
    const fetchImpl = vi.fn(async (..._args: Parameters<typeof fetch>) => fakeResponse(200, { token: "tok", expiresInSeconds: 300, stores }));
    const client = makeClient(fetchImpl);
    const res = await client.pair("ABCD1234");
    expect(res.stores).toEqual(stores);

    const [url] = fetchImpl.mock.calls[0] as [string];
    expect(url).toBe("http://localhost:18080/api/v1/naver/extension/pair");
  });

  it("getStatus: StatusResponse 는 counts 맵이다", async () => {
    const fetchImpl = vi.fn(async (..._args: Parameters<typeof fetch>) => fakeResponse(200, { counts: { DRAFTED: 3, POSTED: 1 } }));
    const client = makeClient(fetchImpl);
    const res = await client.getStatus("store-1");
    expect(res.counts).toEqual({ DRAFTED: 3, POSTED: 1 });
  });

  it("모든 요청에 X-Extension-Token 헤더를 싣는다", async () => {
    const fetchImpl = vi.fn(async (..._args: Parameters<typeof fetch>) => fakeResponse(204, undefined));
    const client = makeClient(fetchImpl);
    await client.postEvent({ storeId: "s", reviewHash: "h", event: "VIEWED", edited: null, editDistance: null });

    const [, init] = fetchImpl.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>)["X-Extension-Token"]).toBe("tok");
  });
});
