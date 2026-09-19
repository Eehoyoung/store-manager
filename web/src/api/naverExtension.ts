import { apiRequest } from "./client";

export interface NaverPairingCode {
  code: string;
  expiresInSeconds: number;
}

// 값이 0건이면 키 자체가 없을 수 있다 — 항상 옵셔널로 읽는다.
export interface NaverStatusResponse {
  counts: Partial<Record<"DRAFTED" | "VIEWED" | "EDITED" | "APPROVED" | "INSERTED" | "POSTED" | "SKIPPED", number>>;
}

export const naverExtensionApi = {
  issuePairingCode: () => apiRequest<NaverPairingCode>("/naver/extension/pairing-code", { method: "POST" }),
  setPin: (pin: string) => apiRequest<void>("/naver/extension/pin", { method: "POST", body: { pin } }),
  getStatus: (storeId: string) => apiRequest<NaverStatusResponse>(`/naver/status?storeId=${storeId}`),
};
