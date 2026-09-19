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

    /**
     * 매장 금칙어 최대 개수.
     *
     * <p>★ 이 값은 UX 취향이 아니라 <b>원가 상한</b>이다. 금칙어는 매 답글 생성 프롬프트에
     * 통째로 실려 나가므로 개수가 곧 입력 토큰이다. 30개 × 50자 = 1,500자로, 프롬프트
     * 본문(약 1,500자)과 같은 수준까지만 허용한다. 늘리기 전에 원가 영향을 계산할 것.
     */
    static final int MAX_BANNED_WORDS = 30;

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
            // ★ 배열 '길이' 제한이 반드시 필요하다. 원소 @Size 는 단어 하나의 길이만 막는다.
            //   금칙어는 프롬프트에 그대로 들어간다(prompts.build_generate_messages
            //   "5. 다음 단어를 쓰지 마라: {banned}"). 개수를 안 막으면 1,000개 등록 시
            //   프롬프트에 5만 자가 붙어 답글 1건 원가가 4.56원 → 55원(12배)이 된다.
            //   악의가 없어도 발생한다 — 엑셀 목록을 복사해 붙여넣는 것만으로 충분하다.
            @Size(max = MAX_BANNED_WORDS, message = "금칙어는 최대 " + MAX_BANNED_WORDS + "개까지 등록할 수 있습니다.")
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

    record StyleSampleResponse(String id, String sampleType, String reviewText, String replyText, Integer rating, String source,
            String createdAt) {
    }

    /** 답글 형식은 유형별 1건씩 — 감사(THANKS)·사과(APOLOGY)·기타(GENERAL) 3슬롯이다. */
    record StyleSampleRequest(
            @NotBlank @Pattern(regexp = "THANKS|APOLOGY|GENERAL",
                    message = "형식 유형은 THANKS·APOLOGY·GENERAL 중 하나여야 합니다.") String sampleType,
            @NotBlank @Size(max = 280) String replyText) {
    }

    /**
     * 매장 사실 — 사장님이 확정 입력한 것만 답글에 인용된다.
     *
     * <p>★ 전부 선택 입력이다. 비어 있으면 지금까지처럼 "확인해 보겠습니다" 로만 답한다.
     * 채워 두면 그 항목의 리뷰에 실제 정보가 나간다 — 손님이 다음에 써먹을 수 있는 것.
     */
    record StoreFactDto(@NotBlank String key, @Size(max = 200) String text) {
    }

    record StoreFactsRequest(@Valid List<StoreFactDto> facts) {
    }

    record StoreFactsResponse(String storeId, List<StoreFactDto> facts, List<String> allowedKeys) {
    }

    record StyleSampleListResponse(List<StyleSampleResponse> items, boolean hasMore, long manualCount) {
    }
}
