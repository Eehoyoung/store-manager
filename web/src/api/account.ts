import { apiRequest } from "./client";
import type { AccountProfile } from "./types";

export const accountApi = {
  get: () => apiRequest<AccountProfile>("/me"),
  update: (payload: { name: string; phone?: string }) =>
    apiRequest<AccountProfile>("/me", { method: "PATCH", body: payload }),
  changePassword: (payload: { currentPassword: string; newPassword: string }) =>
    apiRequest<void>("/me/password", { method: "PATCH", body: payload }),
};
