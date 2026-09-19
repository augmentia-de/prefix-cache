package dev.prefix.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.prefix.agent.AgentRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Konfiguriert den OpenAiChatModel mit Token-Nutzungssichtbarkeit.
 * <p>
 * Verwendet OpenAI-spezifische Klassen (statt generischer LangChain4j-Schnittstellen),
 * damit Token-Verbrauch pro Request sichtbar wird: inputTokens, outputTokens,
 * cachedTokens, totalTokens.
 */
@Configuration
public class LangChain4jConfig {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jConfig.class);

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.model-name:gpt-4o}")
    private String modelName;

    @Value("${langchain4j.open-ai.chat-model.temperature:0.7}")
    private Double temperature;

    /**
     * Erzeugt den OpenAiChatModel mit Prompt-Caching aktiviert.
     * Das Caching wird über OpenAiChatRequestParameters gesetzt.
     */
    @Bean
    public OpenAiChatModel openAiChatModel(KnowledgePrefixLoader knowledgeLoader, ChatExchangeFileLogger exchangeLogger) {
        // session_id (Provider Sticky Routing) ist eine OpenRouter-Extension.
        // Andere OpenAI-kompatible Backends (z.B. Google Gemini) lehnen das Feld ab.
        String headerKey = null;
        switch (baseUrl) {
            case "https://openrouter.ai/api/v1" -> headerKey = "x-session-id";
            case "https://ai-gateway.vercel.sh/v1" -> headerKey = "x-session-affinity";
        }
        final String hk = headerKey;

        log.info("[LangChain4jConfig] session routing (sticky sessions): {}", headerKey!=null
                ? headerKey : "off");

        OpenAiChatModel model;
        if (hk != null) {
            model = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .temperature(temperature)
                    .listeners(List.of(exchangeLogger))
                    .customHeaders(() -> Map.of(hk, RequestContext.currentRequestId.get() != null ? RequestContext.currentRequestId.get() : "default"))
                    .build();
        }   else {
            model = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .temperature(temperature)
                    .listeners(List.of(exchangeLogger))
                    .build();
        }
        log.info("[LangChain4jConfig] OpenAiChatModel configured: baseUrl={}, model={}, temperature={}",
                baseUrl, modelName, temperature);
        log.info("[LangChain4jConfig] Knowledge prefix loaded: {} bytes", knowledgeLoader.getPrefix().length());

        return model;
    }

    @Bean
    public AgentRunner agentRunner(ChatModel chatModel, KnowledgePrefixLoader knowledgeLoader) {
        return new AgentRunner(chatModel, knowledgeLoader.getPrefix());
    }
}
