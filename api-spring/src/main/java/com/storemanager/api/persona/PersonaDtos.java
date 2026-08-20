package com.storemanager.api.persona;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * /stores/{storeId}/persona, /persona/preview, /persona/style-samples DTO (docs/13 §7, Sprint 5 P1~P4).
 * ★ lengthMax 는 280 초과 불가(CLAUDE.md 데이터처리 8번 — 플랫폼 300자, 이모지 여유).
 */
final class PersonaDtos {

    private PersonaDtos() {
    }

    /** HH:mm 24시간제. PublishScheduleCalculator.Window 파싱 규약과 맞춘다(draft 패키지, 읽기전용 참고). */
    record WindowDto(
            @NotBlank @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "HH:mm 24시간 형식이어야 합니다.") String start,
            @NotBlank @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "HH:mm 24시간 형식이어야 합니다.") String end) {
    }

    record PersonaRequest(
            @NotBlank @Pattern(regexp = "POLITE|FRIENDLY|CHEERFUL|CONCISE",
                    message = "POLITE|FRIENDLY|CHEERFUL|CONCISE 중 하나여야 합니다.") String tone,
            boolean useEmoji,
            @NotNull @Min(0) @Max(3) Short emojiLevel,
            @Size(max = 20) String customerTitle,
            @Size(max = 100) String signature,
            @Size(max = 100) String openingStyle,
            List<@Size(max = 50) String> bannedWords,
            @NotNull @Min(1) Short lengthMin,
            @NotNull @Min(1) @Max(280) Short lengthMax,
            @NotNull @Min(0) Short delayHours,
            @Valid List<WindowDto> publishWindows) {
    }

    record PersonaResponse(String storeId, String tone, boolean useEmoji, short emojiLevel, String customerTitle,
            String signature, String openingStyle, List<String> bannedWords, short lengthMin, short lengthMax,
            short delayHours,
            List<WindowDto> publishWindows, int personaSeed, String updatedAt) {
    }

    /** POST /persona/preview 요청. persona 가 null 이면 저장된 페르소나를 그대로 쓴다. */
    record PreviewRequest(@NotBlank String reviewId, @Valid PersonaRequest persona) {
    }

    record PreviewResponse(String content, String tier, String model, String promptVersion,
            List<String> guardrailFlags) {
    }

    record StyleSampleResponse(String id, String reviewText, String replyText, Integer rating, String source,
            String createdAt) {
    }

    record StyleSampleRequest(@NotBlank @Size(max = 280) String replyText) {
    }

    record StyleSampleListResponse(List<StyleSampleResponse> items, boolean hasMore) {
    }
}
