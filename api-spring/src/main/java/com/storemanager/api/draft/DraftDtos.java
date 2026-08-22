package com.storemanager.api.draft;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * /reviews/{id}/drafts, /drafts/** 요청·응답 DTO (docs/13 §6).
 * ★ 외부에는 리뷰·초안의 public_id(UUID)만 노출한다. AI·워커 내부 계약의 BIGINT는 변경하지 않는다.
 */
final class DraftDtos {

    private DraftDtos() {
    }

    record GenerateDraftsRequest(@Min(1) @Max(1) Integer variants, @Size(max = 200) String instruction) {
    }

    record DraftResponse(String id, String reviewId, String content, String status, String tier,
            List<String> guardrailFlags, String scheduledAt, String publishedAt, String generatedBy, String model,
            String promptVersion, Float similarityMax, String createdAt) {

        static DraftResponse from(ReplyDraft d, java.util.UUID reviewPublicId) {
            return new DraftResponse(
                    d.getPublicId().toString(),
                    reviewPublicId.toString(),
                    d.getContent(),
                    d.getStatus(),
                    d.getTier(),
                    d.getGuardrailFlags() == null ? List.of() : List.of(d.getGuardrailFlags()),
                    toIso(d.getScheduledAt()),
                    toIso(d.getPublishedAt()),
                    d.getGeneratedBy(),
                    d.getModel(),
                    d.getPromptVersion(),
                    d.getSimilarityMax(),
                    toIso(d.getCreatedAt()));
        }

        private static String toIso(Instant instant) {
            return instant == null ? null : instant.toString();
        }
    }

    record GenerateDraftsResponse(List<DraftResponse> drafts) {
    }

}
