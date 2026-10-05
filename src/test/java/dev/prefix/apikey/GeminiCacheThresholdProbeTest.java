package dev.prefix.apikey;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Measures the MINIMUM shared prefix size from which the configured backend
 * provides prefix caching (threshold). For Gemini that default minimum is 4096 tokens
 * (Flash 2.5: 1024) — our shared prefix (~1536 tokens) lies below it, which prevents cache writes.
 * For each size S two requests are sent (System(S) + differing
 * user tails); the cached_tokens of the follow-up request decide whether the prefix block was cached.
 * <p>
 * Messages are built exactly as in the workflow (static system message, no session_id,
 * no tools). The Java API does not expose cache_write_tokens — the write observation
 * happens indirectly via the cached_tokens of the follow-up request.
 * <p>
 * Run (model + baseUrl from .env, e.g. Gemini): {@code
 * set -a; source .env; set +a; mvn test -Dgroups=measure -Dtest=GeminiCacheThresholdProbeTest}
 */
@Tag("measure")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class GeminiCacheThresholdProbeTest {

    private static final int[] SIZES = {1500, 2200, 2600, 4000, 6000, 9000};
    private static final String Q1 = "First probe question: summarise the facts above.";
    private static final String Q2 = "Second probe question: what changed between the facts above?";

    @Test
    void reportMinimumCacheableSharedPrefix() {
        String baseUrl = System.getenv().getOrDefault("OPENAI_BASE_URL",
                "https://generativelanguage.googleapis.com/v1beta/openai/");
        String model = System.getenv().getOrDefault("LANGCHAIN4J_OPEN_AI_CHAT_MODEL_MODEL_NAME",
                "gemini-3.5-flash-lite");
        String apiKey = System.getenv("OPENAI_API_KEY");

        OpenAiChatModel llm = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(0.0)
                .build();

        List<String> rows = new ArrayList<>();
        Integer threshold = null;

        for (int target : SIZES) {
            String sharedSystem = buildFiller(target);
            ResponsePair p1 = call(llm, baseUrl, sharedSystem, Q1);
            ResponsePair p2 = call(llm, baseUrl, sharedSystem, Q2);

            boolean hit = p2.cachedTokens > 0;           // second request reads the prefix
            if (hit && threshold == null) {
                threshold = target;
            }
            rows.add(String.format(
                    "S≈%5d | promptA=%5d cachedA=%5d | promptB=%5d cachedB=%5d | hit=%s",
                    target, p1.promptTokens, p1.cachedTokens, p2.promptTokens, p2.cachedTokens, hit));
        }

        System.out.println("=== Prefix cache threshold measurement ===");
        System.out.println("backend: " + baseUrl + " | model: " + model);
        rows.forEach(System.out::println);
        System.out.println("=== RESULT ===");
        System.out.println(threshold != null
                ? "Minimum cacheable shared prefix: ~" + threshold + " tokens"
                : "No cache hit in this sweep (no prefix caching or minimum > 9000)");
    }

    private record ResponsePair(int promptTokens, int cachedTokens) {
    }

    private ResponsePair call(OpenAiChatModel model, String baseUrl, String system, String question) {
        var builder = ChatRequest.builder()
                .messages(List.of(
                        new SystemMessage(system),
                        UserMessage.from(question)));
        // Same routing premise as in the workflow: session_id ONLY for OpenRouter
        // (Gemini/Google rejects the field as a 400). Constant measurement session => one sticky chain.
        if (baseUrl.contains("openrouter.ai")) {
            builder.parameters(OpenAiChatRequestParameters.builder()
                    .customParameters(Map.of("session_id", "measure-probe"))
                    .build());
        }
        var metadata = model.chat(builder.build()).metadata();
        OpenAiTokenUsage usage = (OpenAiTokenUsage) ((OpenAiChatResponseMetadata) metadata).tokenUsage();
        int cached = usage.inputTokensDetails() != null && usage.inputTokensDetails().cachedTokens() != null
                ? usage.inputTokensDetails().cachedTokens() : 0;
        return new ResponsePair(usage.inputTokenCount(), cached);
    }

    /** Deterministic filler (~4 chars/token, all static text). */
    private static String buildFiller(int approxTokens) {
        int targetChars = approxTokens * 4;
        StringBuilder sb = new StringBuilder(targetChars + 64);
        int i = 0;
        while (sb.length() < targetChars) {
            sb.append("KANON-").append(i).append(": The synthetic world of Novaris holds fact ")
                    .append(i).append(" about its geography, history and culture, recorded by Dr Elena Voss. ");
            i++;
        }
        return sb.toString();
    }
}