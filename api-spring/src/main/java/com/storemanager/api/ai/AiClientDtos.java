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

    public record AnalyzeAndDraftRequest(String reviewId, String storeId, ReviewIn review, PersonaIn persona,
            OptionsIn options) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnalysisOut(String category, float sentiment, List<String> issueTags, int riskLevel,
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
