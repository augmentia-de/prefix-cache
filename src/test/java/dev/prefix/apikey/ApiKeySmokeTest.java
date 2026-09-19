package dev.prefix.apikey;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Smoke-Test: ein kurzer LLM-Call, um die API-Key / Modell-Konfiguration zu pruefen.
 * <p>
 * baseUrl und Modell stehen hier fest im Code. Der API-Key kommt aus der
 * Umgebungsvariable OPENAI_API_KEY (wird von .env via start.sh gesetzt) —
 * KEIN Secret im Source-File. Ausfuehren z. B. mit:
 * <pre>
 *   set -a; source .env; set +a; mvn test -Dtest=ApiKeySmokeTest
 * </pre>
 */
//@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class ApiKeySmokeTest {

    // === in Code gesetzt ===
//    private static final String BASE_URL = "https://openrouter.ai/api/v1";
//    private static final String MODEL = "deepseek/deepseek-v4-flash-0731";
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/";
    private static final String MODEL = "gemini-3.5-flash-lite";

    //@Test
    void shortLlCallWorks() {
        String apiKey = "AIzaSyCpy5Qs6BFaF5WCcSugT4lY6HqRdtf4T7Q";//System.getenv("OPENAI_API_KEY");

        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(BASE_URL)
                .modelName(MODEL)
                .temperature(0.0)
                .build();

        ChatRequest request = ChatRequest.builder()
                .messages(java.util.List.of(UserMessage.from("Sag kurz Hallo in einem Satz.")))
                .build();

        String answer = model.chat(request).aiMessage().text();

        System.out.println("=== ApiKeySmokeTest answer ===");
        System.out.println(answer);
        System.out.println("=== model: " + MODEL + " / baseUrl: " + BASE_URL + " ===");

        assertNotNull(answer);
        assertFalse(answer.isBlank(), "Modellantwort darf nicht leer sein");
    }
}