package dev.prefix.apikey;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Smoke test: a short LLM call to check the API key / model configuration.
 * <p>
 * API key, baseUrl and model come from the environment variables (via .env). Run:
 * <pre>
 *   set -a; source .env; set +a; mvn -Dtest=ApiKeySmokeTest test
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class ApiKeySmokeTest {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-4o";

    @Test
    void shortLlCallWorks() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        String baseUrl = System.getenv().getOrDefault("OPENAI_BASE_URL", DEFAULT_BASE_URL);
        String modelName = System.getenv().getOrDefault(
                "LANGCHAIN4J_OPEN_AI_CHAT_MODEL_MODEL_NAME", DEFAULT_MODEL);

        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.0)
                .build();

        ChatRequest request = ChatRequest.builder()
                .messages(java.util.List.of(UserMessage.from("Reply with exactly: OK")))
                .build();

        String answer = model.chat(request).aiMessage().text();

        System.out.println("=== model: " + modelName + " / baseUrl: " + baseUrl + " ===");

        assertNotNull(answer);
        assertFalse(answer.isBlank(), "model answer must not be empty");
    }
}