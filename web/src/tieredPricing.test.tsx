// 선택형 요금(D38) 프론트 — 서버가 tiered 를 내려줄 때만 새 화면이 나오고, 없으면 기존 화면 그대로다.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ApiError } from "./api/client";
import type { BillingResponse } from "./api/billing";
import { canCheckout, EMPTY_SELECTION } from "./lib/tieredPlan";

const api = vi.hoisted(() => ({
  get: vi.fn(),
  checkout: vi.fn(),
  changePlan: vi.fn(),
  setAutoRenew: vi.fn(),
  resumeHeldReplies: vi.fn(),
}));
vi.mock("./api/billing", async (orig) => ({ ...(await orig<typeof import("./api/billing")>()), billingApi: api }));
vi.mock("./layout/AppShell", () => ({ useShellStore: () => ({ setStoreId: () => {} }) }));
vi.mock("@portone/browser-sdk/v2", () => ({ requestIssueBillingKey: vi.fn().mockResolvedValue({ billingKey: "bk-test" }) }));
const register = vi.hoisted(() => vi.fn());
vi.mock("./api/platformAccounts", () => ({
  platformAccountsApi: { list: vi.fn().mockResolvedValue([]), register },
}));
vi.mock("./api/stores", () => ({ storesApi: { list: vi.fn().mockResolvedValue([{ id: "s1", name: "테스트 매장" }]) } }));
vi.mock("./api/agreements", () => ({ agreementsApi: { catalog: vi.fn().mockResolvedValue({ currentVersion: "v1" }) } }));

import { BillingPage } from "./pages/BillingPage";
import { BillingCheckout } from "./components/BillingCheckout";
import { PlatformAccountsPage } from "./pages/PlatformAccountsPage";

const price = (platforms: string[], total: number) => ({ deliveryPlatforms: platforms, supplyKrw: 0, vatKrw: 0, totalKrw: total });

function view(over: Partial<BillingResponse> = {}): BillingResponse {
  return {
    enabled: true,
    portoneStoreId: "ps",
    channelKey: "ck",
    customer: { customerId: "c", fullName: "홍길동", email: "a@b.c", phoneNumber: null },
    serviceState: "ACTIVE",
    trialPending: false,
    trialDays: 14,
    trialEndsAt: null,
    nextBillingAt: "2026-12-01T00:00:00Z",
    restrictedFrom: null,
    lastPaidAt: null,
    hasCard: true,
    autoRenew: true,
    renewalFailures: 0,
    amountKrw: 33000,
    chargeNowKrw: 33000,
    unitPriceKrw: 33000,
    brandPaidStoreCount: null,
    consentVersion: "v2",
    payments: [],
    heldReplyCount: 0,
    ...over,
  };
}

const ALL3 = ["BAEMIN", "COUPANGEATS", "YOGIYO"];
const OPT = [25000, 29000, 33000];

function renderPage(b: BillingResponse) {
  api.get.mockResolvedValue(b);
  return render(
    <MemoryRouter initialEntries={["/stores/s1/billing"]}>
      <Routes>
        <Route path="/stores/:storeId/billing" element={<BillingPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  // jsdom 에는 <dialog> 의 showModal/close 가 없다.
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute("open");
  };
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("선택형 요금 화면", () => {
  it("billingPageUnchangedWhenFieldsAbsent", async () => {
    renderPage(view());
    await screen.findByText("구독 결제");
    expect(screen.queryByText("이용 상품")).toBeNull();
    expect(screen.queryByText("3사 모두 이용")).toBeNull();
    expect(screen.getByText(/월 33,000원/)).toBeTruthy();
  });

  it("selectionRequiredNoPreselect", () => {
    const b = view({ hasCard: false, serviceState: "UNPAID", tiered: { current: price(ALL3, 33000), changedThisMonth: false, optionTotalsKrw: OPT, canChange: true } });
    render(<BillingCheckout storeId="s1" billing={b} onSuccess={() => {}} />);
    for (const r of screen.getAllByRole("radio")) expect((r as HTMLInputElement).checked).toBe(false);
    fireEvent.click(screen.getByRole("checkbox", { name: /자동결제되는 데 동의/ }));
    expect((screen.getByRole("button", { name: /카드 등록/ }) as HTMLButtonElement).disabled).toBe(true);
    // 1개 상품은 배달앱 1곳을 골라야 열린다.
    fireEvent.click(screen.getByRole("radio", { name: /배달앱 1개/ }));
    expect((screen.getByRole("button", { name: /카드 등록/ }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("checkbox", { name: "요기요" }));
    expect((screen.getByRole("button", { name: /카드 등록/ }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("recommendBadgeOnThreePlatforms", () => {
    const b = view({ hasCard: false, serviceState: "UNPAID", tiered: { current: price(ALL3, 33000), changedThisMonth: false, optionTotalsKrw: OPT, canChange: true } });
    render(<BillingCheckout storeId="s1" billing={b} onSuccess={() => {}} />);
    const badges = screen.getAllByText("3사 모두 이용");
    expect(badges).toHaveLength(1);
    expect(badges[0].closest("label")?.textContent).toContain("배달앱 3개");
  });

  it("selectionShowsServerPrice", async () => {
    // 표준 가격이 아닌 값(28,900)으로 화면이 서버 값을 그대로 쓰는지 확인한다.
    const b = view({ tiered: { current: price(["BAEMIN"], 24500), next: price(["BAEMIN", "YOGIYO"], 28900), changedThisMonth: false, optionTotalsKrw: OPT, canChange: true } });
    renderPage(b);
    await screen.findByText("이용 상품");
    expect(screen.getAllByText(/월 28,900원/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/월 24,500원/).length).toBeGreaterThan(0);
    // 변경 직후 응답의 서버 값도 그대로 보인다.
    api.changePlan.mockResolvedValue(view({ tiered: { current: price(["BAEMIN"], 24500), next: price(ALL3, 31700), changedThisMonth: true, optionTotalsKrw: OPT, canChange: true } }));
    fireEvent.click(screen.getByRole("button", { name: "상품 변경" }));
    fireEvent.click(screen.getByRole("radio", { name: /배달앱 3개/ }));
    fireEvent.click(screen.getByRole("button", { name: /다음 결제부터 이 상품으로 바꾸기/ }));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "바꾸기" }));
    await screen.findByText(/월 31,700원/);
    expect(api.changePlan).toHaveBeenCalledWith("s1", ALL3);
  });

  it("blocksCheckoutWhileQuoteLoading", () => {
    expect(canCheckout({ selection: EMPTY_SELECTION, quoteReady: true, agreed: true, busy: false })).toBe(false);
    expect(canCheckout({ selection: { count: 3, platforms: [] }, quoteReady: false, agreed: true, busy: false })).toBe(false);
    expect(canCheckout({ selection: { count: 3, platforms: [] }, quoteReady: true, agreed: true, busy: false })).toBe(true);
    // 서버 상품표가 아직 없으면 상품을 다 골라도 결제 버튼이 닫혀 있다.
    const b = view({ hasCard: false, serviceState: "UNPAID", tiered: { current: price(ALL3, 33000), changedThisMonth: false, optionTotalsKrw: [], canChange: true } });
    render(<BillingCheckout storeId="s1" billing={b} onSuccess={() => {}} />);
    fireEvent.click(screen.getByRole("radio", { name: /배달앱 3개/ }));
    fireEvent.click(screen.getByRole("checkbox", { name: /자동결제되는 데 동의/ }));
    expect((screen.getByRole("button", { name: /카드 등록/ }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("checkoutSendsSelectedPlatforms", async () => {
    const b = view({ hasCard: false, serviceState: "UNPAID", tiered: { current: price(ALL3, 33000), changedThisMonth: false, optionTotalsKrw: OPT, canChange: true } });
    api.checkout.mockResolvedValue(b);
    render(<BillingCheckout storeId="s1" billing={b} onSuccess={() => {}} />);
    fireEvent.click(screen.getByRole("radio", { name: /배달앱 2개/ }));
    fireEvent.click(screen.getByRole("checkbox", { name: "배달의민족" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "쿠팡이츠" }));
    // 2개를 채우면 나머지는 잠긴다.
    expect((screen.getByRole("checkbox", { name: "요기요" }) as HTMLInputElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("checkbox", { name: /자동결제되는 데 동의/ }));
    fireEvent.click(screen.getByRole("button", { name: /카드 등록/ }));
    await waitFor(() => expect(api.checkout).toHaveBeenCalled());
    expect(api.checkout.mock.calls[0][1]).toMatchObject({ deliveryPlatforms: ["BAEMIN", "COUPANGEATS"], billingKey: "bk-test" });
  });

  it("cardReplacementSendsCurrentList", async () => {
    const b = view({ tiered: { current: price(["YOGIYO"], 25000), changedThisMonth: false, optionTotalsKrw: OPT, canChange: true } });
    api.checkout.mockResolvedValue(b);
    render(<BillingCheckout storeId="s1" billing={b} onSuccess={() => {}} />);
    expect(screen.queryAllByRole("radio")).toHaveLength(0);
    fireEvent.click(screen.getByRole("checkbox", { name: /자동결제되는 데 동의/ }));
    fireEvent.click(screen.getByRole("button", { name: /카드/ }));
    await waitFor(() => expect(api.checkout).toHaveBeenCalled());
    expect(api.checkout.mock.calls[0][1].deliveryPlatforms).toEqual(["YOGIYO"]);
  });

  it("unselectedPlatformShowsChangePlanNotCard", async () => {
    register.mockRejectedValue(new ApiError(403, { code: "PLATFORM_NOT_SELECTED", message: "x" }));
    render(
      <MemoryRouter>
        <PlatformAccountsPage />
      </MemoryRouter>,
    );
    await screen.findByText("계정 등록");
    await waitFor(() => expect((screen.getByRole("button", { name: /암호화 저장/ }) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.change(screen.getByLabelText(/배달앱 아이디/), { target: { value: "owner" } });
    fireEvent.change(screen.getByLabelText(/배달앱 비밀번호/), { target: { value: "pw" } });
    fireEvent.click(screen.getByRole("checkbox", { name: /처리 위탁에 동의/ }));
    fireEvent.click(screen.getByRole("button", { name: /암호화 저장/ }));
    const link = await screen.findByRole("link", { name: /상품 변경하러 가기/ });
    expect(link.getAttribute("href")).toBe("/stores/s1/billing");
    // 카드 등록 안내(결제 팝업)는 열리지 않는다.
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(api.get).not.toHaveBeenCalled();
  });

  it("nextBillingLabelShownWhenPending", async () => {
    renderPage(view({ tiered: { current: price(ALL3, 33000), next: price(["BAEMIN"], 25000), changedThisMonth: true, optionTotalsKrw: OPT, canChange: true } }));
    await screen.findByText("이용 상품");
    expect(screen.getByText("현재 상품")).toBeTruthy();
    expect(screen.getByText("다음 결제부터")).toBeTruthy();
    expect(screen.getByRole("button", { name: "변경 예약 취소" })).toBeTruthy();
    // 취소는 현재 명단을 다시 보낸다.
    api.changePlan.mockResolvedValue(view({ tiered: { current: price(ALL3, 33000), changedThisMonth: true, optionTotalsKrw: OPT, canChange: true } }));
    fireEvent.click(screen.getByRole("button", { name: "변경 예약 취소" }));
    await waitFor(() => expect(api.changePlan).toHaveBeenCalledWith("s1", ALL3));
    await waitFor(() => expect(screen.queryByText("다음 결제부터")).toBeNull());
  });

  it("changeDisabledWhenChangedThisMonth", async () => {
    renderPage(view({ tiered: { current: price(ALL3, 33000), changedThisMonth: true, optionTotalsKrw: OPT, canChange: true } }));
    await screen.findByText("이용 상품");
    expect((screen.getByRole("button", { name: "상품 변경" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/이번 달\(\d+월\)에는 이미 상품을 바꿨어요.*\d+월 1일부터 다시 바꿀 수 있어요/)).toBeTruthy();
  });
  it("selectionUsesServerOptionTable", () => {
    // 가맹 매장: 3개 상품이 구간 단가(31,000원). 상품표·동의 금액이 화면 상수가 아니라 서버 값이어야 한다.
    const b = view({ hasCard: false, serviceState: "UNPAID",
      tiered: { current: price(ALL3, 31000), changedThisMonth: false, optionTotalsKrw: [25000, 29000, 31000], canChange: true } });
    render(<BillingCheckout storeId="s1" billing={b} onSuccess={() => {}} />);
    expect(screen.getByRole("radio", { name: /배달앱 3개/ }).closest("label")?.textContent).toContain("31,000원");
    expect(screen.queryByText(/33,000원/)).toBeNull();
  });

  it("changeDisabledWhenServerSaysNotAllowed", async () => {
    renderPage(view({ tiered: { current: price(ALL3, 33000), next: price(["BAEMIN"], 25000), changedThisMonth: false,
      optionTotalsKrw: OPT, canChange: false } }));
    await screen.findByText("이용 상품");
    expect((screen.getByRole("button", { name: "상품 변경" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "변경 예약 취소" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/자동결제와 결제 상태를 먼저 확인해 주세요/)).toBeTruthy();
  });

  async function openConfirm(b: BillingResponse, pick: RegExp) {
    renderPage(b);
    await screen.findByText("이용 상품");
    fireEvent.click(screen.getByRole("button", { name: "상품 변경" }));
    fireEvent.click(screen.getByRole("radio", { name: pick }));
    return screen;
  }

  it("paidUpgradeConfirmShowsBillingDateAndNoRefund", async () => {
    await openConfirm(view({ tiered: { current: price(["BAEMIN"], 25000), changedThisMonth: false, optionTotalsKrw: OPT, canChange: true } }), /배달앱 3개/);
    fireEvent.click(screen.getByRole("button", { name: /다음 결제부터 이 상품으로 바꾸기/ }));
    const d = within(screen.getByRole("dialog")).getByText(/결제일부터 적용돼요/);
    expect(d.textContent).toContain("그날까지는 지금 상품(배달앱 1개 (배달의민족))이 그대로 유지돼요");
    expect(d.textContent).toContain("월 33,000원(VAT 포함)");
    expect(d.textContent).toContain("일할로 돌려드리거나 더 받지는 않아요");
    expect(screen.queryByText(/연동 정보는/)).toBeNull();
  });

  it("paidDowngradeConfirmListsRemovedApps", async () => {
    await openConfirm(view({ tiered: { current: price(ALL3, 33000), changedThisMonth: false, optionTotalsKrw: OPT, canChange: true } }), /배달앱 1개/);
    fireEvent.click(screen.getByRole("checkbox", { name: "배달의민족" }));
    fireEvent.click(screen.getByRole("button", { name: /다음 결제부터 이 상품으로 바꾸기/ }));
    const d = within(screen.getByRole("dialog")).getByText(/연동 정보는/);
    expect(d.textContent).toContain("빠지는 배달앱(쿠팡이츠, 요기요)");
    expect(d.textContent).toContain("변경이 적용될 때(");  // 결제가 실패하면 승격·삭제가 없으므로 날짜로 단정하지 않는다
    expect(d.textContent).toContain("새 리뷰는 더 이상 처리하지 않아요");
  });

  it("trialChangeIsImmediateAndUnlimited", async () => {
    const b = view({ serviceState: "TRIAL", trialEndsAt: "2026-11-05T00:00:00Z",
      tiered: { current: price(ALL3, 33000), changedThisMonth: true, optionTotalsKrw: OPT, canChange: true } });
    await openConfirm(b, /배달앱 2개/);
    // 이번 달 변경 이력이 있어도 체험 중에는 막지 않는다.
    expect(screen.queryByText(/이미 상품을 바꿨어요/)).toBeNull();
    expect(screen.getByText("체험 중에는 상품을 바꾸면 바로 적용되고 횟수 제한도 없어요.")).toBeTruthy();
    fireEvent.click(screen.getByRole("checkbox", { name: "배달의민족" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "쿠팡이츠" }));
    fireEvent.click(screen.getByRole("button", { name: "이 상품으로 바꾸기" }));
    const d = within(screen.getByRole("dialog")).getByText(/바로 적용되고/);
    expect(d.textContent).toContain("체험이 끝나는 2026. 11. 5.에 바꾼 상품 기준으로 월 29,000원(VAT 포함)이 결제돼요");
    expect(d.textContent).toContain("빠지는 배달앱(요기요)의 연동 정보는 지금 바로 삭제돼요");
  });

  it("validationFailedShowsServerReason", async () => {
    const b = view({ tiered: { current: price(["BAEMIN"], 25000), changedThisMonth: false, optionTotalsKrw: OPT, canChange: true } });
    await openConfirm(b, /배달앱 1개/);
    fireEvent.click(screen.getByRole("checkbox", { name: "배달의민족" }));
    api.changePlan.mockRejectedValueOnce(new ApiError(400, { code: "VALIDATION_FAILED", message: "x", details: { reason: "바뀐 내용이 없어요." } }));
    fireEvent.click(screen.getByRole("button", { name: /다음 결제부터 이 상품으로 바꾸기/ }));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "바꾸기" }));
    await screen.findByText("바뀐 내용이 없어요.");
  });

  it("validationFailedFallbackAndPlanErrorCopies", async () => {
    const { billingErrorMessage } = await import("./api/billing");
    expect(billingErrorMessage(new ApiError(409, { code: "PLAN_CHANGE_LIMIT", message: "x" }))).toBe("상품 변경은 한 달에 한 번만 할 수 있어요. 잘못 신청됐거나 연동에 문제가 있다면 고객센터로 알려 주세요.");
    expect(billingErrorMessage(new ApiError(409, { code: "PLAN_CHANGE_NOT_ALLOWED", message: "x" }))).toBe("지금은 상품을 바꿀 수 없어요. 결제 상태를 먼저 확인해 주세요.");
  });

  it("trialStartNoticeAndConsentProductRow", () => {
    const b = view({ hasCard: false, serviceState: "UNPAID", trialPending: true, nextBillingAt: null,
      tiered: { current: price(ALL3, 33000), changedThisMonth: false, optionTotalsKrw: OPT, canChange: true } });
    render(<BillingCheckout storeId="s1" billing={b} onSuccess={() => {}} />);
    // 상품 미선택: 상품·금액 부분 생략, 동의 표 상품 행은 안내.
    expect(screen.getByText(/체험이 끝나는 .*에 첫 결제되고, 이후 매월 같은 날 자동으로 결제돼요/)).toBeTruthy();
    expect(screen.getAllByText("상품을 고른 뒤 확인할 수 있어요")).toHaveLength(2);
    fireEvent.click(screen.getByRole("radio", { name: /배달앱 3개/ }));
    const n = screen.getByText(/무료체험이 시작돼요/).textContent!;
    expect(n).toContain("배달앱 3개 (배달의민족, 쿠팡이츠, 요기요) 월 33,000원(VAT 포함)이 첫 결제되고");
    expect(n).toContain("체험이 끝나기 전에 해지하면 결제되지 않아요");
    expect(screen.getByRole("radio", { name: /배달의민족·쿠팡이츠·요기요/ })).toBeTruthy();
    expect(screen.getByText("상품").closest("tr")?.textContent).toContain("배달앱 3개 (배달의민족, 쿠팡이츠, 요기요)");
  });

  it("firstChargeNoticeAndOffByDefault", () => {
    const b = view({ hasCard: false, serviceState: "UNPAID",
      tiered: { current: price(ALL3, 33000), changedThisMonth: false, optionTotalsKrw: OPT, canChange: true } });
    render(<BillingCheckout storeId="s1" billing={b} onSuccess={() => {}} />);
    fireEvent.click(screen.getByRole("radio", { name: /배달앱 3개/ }));
    expect(screen.getByText(/지금 33,000원\(VAT 포함\)이 결제되고 바로 시작돼요. 이후 매월 결제예정일에 자동결제되며, 결제예정일 확인과 해지는 결제관리에서 언제든 할 수 있어요/)).toBeTruthy();
    cleanup();
    // 꺼짐(tiered 없음): 새 문구와 상품 행이 없다.
    render(<BillingCheckout storeId="s1" billing={view({ hasCard: false, serviceState: "UNPAID" })} onSuccess={() => {}} />);
    expect(screen.queryByText("상품")).toBeNull();
    expect(screen.queryByText(/결제관리에서 언제든 해지/)).toBeNull();
  });
});
