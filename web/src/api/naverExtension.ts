import { apiRequest } from "./client";

export interface NaverPairingCode {
  code: string;
  expiresInSeconds: number;
}

export const naverExtensionApi = {
  issuePairingCode: () => apiRequest<NaverPairingCode>("/naver/extension/pairing-code", { method: "POST" }),
  setPin: (pin: string) => apiRequest<void>("/naver/extension/pin", { method: "POST", body: { pin } }),
};
