import { apiRequest } from "./client";
import type { DeliveryPlatform, PlatformAccountResponse } from "./types";

export interface RegisterPlatformAccountPayload {
  platform: DeliveryPlatform;
  loginId: string;
  password: string;
  storeId: string;
}

export const platformAccountsApi = {
  list: () => apiRequest<PlatformAccountResponse[]>("/platform-accounts"),
  register: (payload: RegisterPlatformAccountPayload) =>
    apiRequest<PlatformAccountResponse>("/platform-accounts", { method: "POST", body: payload }),
  revoke: (id: string) => apiRequest<void>(`/platform-accounts/${id}`, { method: "DELETE" }),
};
