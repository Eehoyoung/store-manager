import { apiRequest } from "./client";
import type { PersonaRequest, PersonaResponse, PreviewResponse, StoreFactsResponse, StyleSampleListResponse, StyleSampleResponse } from "./types";

// docs/13 §7, 실제 PersonaController(api-spring) 기준.
export const personaApi = {
  get: (storeId: string) => apiRequest<PersonaResponse>(`/stores/${storeId}/persona`),
  update: (storeId: string, req: PersonaRequest) =>
    apiRequest<PersonaResponse>(`/stores/${storeId}/persona`, { method: "PUT", body: req }),
  preview: (storeId: string, reviewId: string, persona: PersonaRequest | null) =>
    apiRequest<PreviewResponse>(`/stores/${storeId}/persona/preview`, {
      method: "POST",
      body: { reviewId, persona },
    }),
  styleSamples: (storeId: string, page: number, size = 20) =>
    apiRequest<StyleSampleListResponse>(`/stores/${storeId}/persona/style-samples?page=${page}&size=${size}`),
  deleteStyleSample: (storeId: string, sampleId: string) =>
    apiRequest<void>(`/stores/${storeId}/persona/style-samples/${sampleId}`, { method: "DELETE" }),
  addStyleSample: (storeId: string, replyText: string) =>
    apiRequest<StyleSampleResponse>(`/stores/${storeId}/persona/style-samples`, { method: "POST", body: { replyText } }),
  // ★ 매장 사실 — 답글이 "확인해 보겠습니다" 로만 끝나지 않게 하는 유일한 출처다.
  //   사장님이 확정한 것만 들어가므로 답글에 인용해도 [절대 규칙] 8번을 어기지 않는다.
  facts: (storeId: string) => apiRequest<StoreFactsResponse>(`/stores/${storeId}/facts`),
  replaceFacts: (storeId: string, facts: { key: string; text: string }[]) =>
    apiRequest<StoreFactsResponse>(`/stores/${storeId}/facts`, { method: "PUT", body: { facts } }),
};
