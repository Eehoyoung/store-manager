import { apiRequest } from "./client";
import type { PersonaRequest, PersonaResponse, PreviewResponse, StyleSampleListResponse, StyleSampleResponse } from "./types";

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
};
