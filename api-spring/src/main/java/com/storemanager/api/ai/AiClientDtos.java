package com.storemanager.api.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * POST /internal/ai/analyze-and-draft 계약 (docs/13 §11.1, Spring → Python).
 * 필드명이 그대로 JSON 키(camelCase)가 되므로 Python 쪽 alias 와 정확히 일치시킨다.
 * ★ review.body 는 있는 그대로 담아 전달만 한다 — 여기서 가공·생성하지 않는다(절대규칙 1).
 */
public final class AiClientDtos {

    private AiClientDtos() {
    }

    public record ReviewIn(int rating, String body, List<String> menus, String platform) {
    }

    public record BannedWordIn(String word, String category, String matchType) {
    }

    public record PersonaIn(String tone, boolean useEmoji, int emojiLevel, String customerTitle, String signature,
            String openingStyle, List<String> bannedWords, List<BannedWordIn> globalBannedWords,
            int lengthMin, int lengthMax, Integer personaSeed) {
    }

    public record OptionsIn(int variants, String instruction, String forceTier) {
    }

    /**
     * @param storeFacts 사장님이 확정 입력한 매장 사실(주차·대기시간·좌석…). 비어 있는 것이 기본이다.
     *                   ★ 답글이 사실을 인용할 수 있는 <b>유일한 출처</b>다 — [절대 규칙] 8번은
     *                   확정하지 않은 것을 약속하지 말라는 규칙이지, 사장님이 확정한 것까지
     *                   막는 규칙이 아니다. AI 가 이 값을 만들어 내게 하지 말 것.
     */
    public record AnalyzeAndDraftRequest(String reviewId, String storeId, ReviewIn review, PersonaIn persona,
            OptionsIn options, List<String> recentReplies, java.util.Map<String, String> storeFacts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    // ★ tone·praisedTags 는 프롬프트 v2.0 에서 추가됐다. 구버전 AI 응답에는 없으므로
    //   역직렬화 시 null 이 올 수 있다 — 소비하는 쪽에서 기본값을 채운다.
    public record AnalysisOut(String category, String tone, float sentiment, List<String> issueTags,
            List<String> praisedTags, int riskLevel,
            List<String> riskReasons, String model, String promptVersion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DraftOut(String content, String tier, String model, String promptVersion,
            List<String> guardrailFlags, Float similarityMax, int tokenIn, int tokenOut, double costKrw) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnalyzeAndDraftResponse(AnalysisOut analysis, List<DraftOut> drafts, boolean blocked,
            List<String> blockReasons) {
    }
}
