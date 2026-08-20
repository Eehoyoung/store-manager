import { apiRequest } from "./client";
import type { StoreResponse } from "./types";

export interface CreateStorePayload {
  name: string;
  brandName?: string;
  category?: string;
  address?: string;
}

// docs/13 §3, 실제 StoreController(api-spring). GET /{id}/summary 는 미구현이라 여기 두지 않는다.
export const storesApi = {
  list: () => apiRequest<StoreResponse[]>("/stores"),
  create: (payload: CreateStorePayload) => apiRequest<StoreResponse>("/stores", { method: "POST", body: payload }),
  get: (storeId: string) => apiRequest<StoreResponse>(`/stores/${storeId}`),
};
