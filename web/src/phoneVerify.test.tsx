// 문자 인증(#85) 설정 화면 — 서버 스위치(phoneVerifyEnabled)가 꺼져 있으면 확인 UI 가 전혀 나오지 않는다.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import type { AccountProfile } from "./api/types";

const account = vi.hoisted(() => ({
  get: vi.fn(),
  update: vi.fn(),
  requestPhoneVerification: vi.fn(),
  confirmPhoneVerification: vi.fn(),
  changePassword: vi.fn(),
}));
vi.mock("./api/account", () => ({ accountApi: account }));
vi.mock("./api/agreements", () => ({
  agreementsApi: {
    history: vi.fn().mockResolvedValue([]),
    hqWithdrawalStatus: vi.fn().mockResolvedValue(null),
    requestHqWithdrawal: vi.fn(),
    setHqReviewSharing: vi.fn(),
  },
}));
vi.mock("./api/naverExtension", () => ({ naverExtensionApi: { issuePairingCode: vi.fn(), setPin: vi.fn() } }));
vi.mock("./auth/AuthContext", () => ({ useAuth: () => ({ updateUser: () => {}, logout: () => {} }) }));
vi.mock("./components/Toast", () => ({ useToast: () => ({ show: () => {} }) }));

import { SettingsPage } from "./pages/SettingsPage";

function profile(over: Partial<AccountProfile> = {}): AccountProfile {
  return {
    id: "u1", email: "a@b.c", name: "홍길동", phone: "010-1234-5678",
    phoneVerified: false, phoneVerifyEnabled: true,
    status: "ACTIVE", createdAt: "2026-11-01T00:00:00Z", lastLoginAt: null,
    ...over,
  };
}

async function open(p: AccountProfile) {
  account.get.mockResolvedValue(p);
  render(<MemoryRouter><SettingsPage /></MemoryRouter>);
  await screen.findByRole("heading", { name: "계정 정보" });
}

afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("설정 화면 문자 인증", () => {
  it("스위치가 꺼져 있으면 확인 UI·안내 문구가 없다", async () => {
    await open(profile({ phoneVerifyEnabled: false }));
    expect(screen.queryByRole("button", { name: "인증번호 받기" })).toBeNull();
    expect(screen.queryByText("번호를 바꾸면 다시 확인해야 해요.")).toBeNull();
    expect(screen.queryByText("확인됨")).toBeNull();
  });

  it("번호가 없으면 확인 버튼이 없다", async () => {
    await open(profile({ phone: null }));
    expect(screen.queryByRole("button", { name: "인증번호 받기" })).toBeNull();
  });

  it("확인된 번호는 '확인됨' 만 보인다", async () => {
    await open(profile({ phoneVerified: true }));
    expect(screen.getByText("확인됨")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "인증번호 받기" })).toBeNull();
  });

  it("번호를 고치면 저장 전에는 확인할 수 없다", async () => {
    await open(profile());
    fireEvent.change(screen.getByLabelText(/휴대폰 번호/), { target: { value: "01099998888" } });
    expect(screen.getByText("번호를 저장한 뒤 확인할 수 있어요.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "인증번호 받기" })).toBeNull();
  });

  it("받기 → 6자리 입력 → 확인하면 '확인됨' 으로 바뀐다", async () => {
    account.requestPhoneVerification.mockResolvedValue({ sent: true });
    account.confirmPhoneVerification.mockResolvedValue(profile({ phoneVerified: true }));
    await open(profile());
    fireEvent.click(screen.getByRole("button", { name: "인증번호 받기" }));
    const input = await screen.findByLabelText(/인증번호 6자리/);
    const confirm = screen.getByRole("button", { name: "확인" }) as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    expect((screen.getByRole("button", { name: /다시 받기 \(\d+초\)/ }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(input, { target: { value: "12a3456" } });
    expect((input as HTMLInputElement).value).toBe("123456");
    expect(confirm.disabled).toBe(false);
    fireEvent.click(confirm);
    await screen.findByText("확인됨");
    expect(account.confirmPhoneVerification).toHaveBeenCalledWith("123456");
  });
});
