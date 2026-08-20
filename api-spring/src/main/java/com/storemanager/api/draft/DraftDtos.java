package com.storemanager.api.draft;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * /reviews/{id}/drafts, /drafts/** 요청·응답 DTO (docs/13 §6).
 * ★ reviewId/draftId 는 doc13 의 공개 규약상 public_id(UUID) 여야 하지만 unified_review/reply_draft
 * 테이블에는 public_id 컬럼이 없다(docs/11 §2.4~2.5에 없음). 새 마이그레이션을 만들지 말라는 지침에 따라
 * 이 두 리소스는 BIGSERIAL id 를 문자열로 그대로 노출한다 — storeId 만 기존처럼 UUID(public_id) 를 쓴다.
 */
final class DraftDtos {

    private DraftDtos() {
    }

    record GenerateDraftsRequest(@Min(1) @Max(1) Integer variants, @Size(max = 200) String instruction) {
    }

    record DraftResponse(String id, String reviewId, String content, String status, String tier,
            List<String> guardrailFlags, String scheduledAt, String publishedAt, String generatedBy, String model,
            String promptVersion, Float similarityMax, String createdAt) {

        static DraftResponse from(ReplyDraft d) {
            return new DraftResponse(
                    String.valueOf(d.getId()),
                    String.valueOf(d.getReviewId()),
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
