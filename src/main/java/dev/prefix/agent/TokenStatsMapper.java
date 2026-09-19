package dev.prefix.agent;

import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Baut aus einem TokenUsage eine flache Map für Agent-Ergebnisse/API-Antworten.
 * Erfasst bei OpenAI zusätzlich cachedTokens und reasoningTokens.
 */
final class TokenStatsMapper {

    private TokenStatsMapper() {}

    static Map<String, Number> toMap(TokenUsage usage) {
        Map<String, Number> map = new LinkedHashMap<>();
        if (usage == null) {
            map.put("input_tokens", 0);
            map.put("output_tokens", 0);
            map.put("cached_tokens", 0);
            map.put("total_tokens", 0);
            return map;
        }

        int cached = 0;
        int reasoning = 0;
        if (usage instanceof OpenAiTokenUsage oai) {
            if (oai.inputTokensDetails() != null && oai.inputTokensDetails().cachedTokens() != null) {
                cached = oai.inputTokensDetails().cachedTokens();
            }
            if (oai.outputTokensDetails() != null && oai.outputTokensDetails().reasoningTokens() != null) {
                reasoning = oai.outputTokensDetails().reasoningTokens();
            }
        }

        map.put("input_tokens", usage.inputTokenCount());
        map.put("output_tokens", usage.outputTokenCount());
        map.put("cached_tokens", cached);
        map.put("reasoning_tokens", reasoning);
        map.put("total_tokens", usage.totalTokenCount());
        return map;
    }
}