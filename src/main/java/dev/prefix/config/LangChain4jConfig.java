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
 * <p>
 * Stellt zwei {@link AgentRunner}-Varianten bereit: eine MIT Knowledge-Prefix
 * (byte-identischer, cachebarer Block) und eine OHNE (Subagenten, die den
 * Basiskontext nicht brauchen und ihn deshalb nicht bezahlen sollen).
 */
@Configuration
public class LangChain4jConfig {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jConfig.class);

    private static final String OPENROUTER_URL = "https://openrouter.ai/api/v1";
    private static final String VERCEL_GATEWAY_URL = "https://ai-gateway.vercel.sh/v1";

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.model-name:gpt-4o}")
    private String modelName;

    @Value("${langchain4j.open-ai.chat-model.temperature:0.7}")
    private Double temperature;

    /**
     * Loest den Sticky-Routing-Header fuer eine baseUrl auf.
     * <p>
     * Bewusst als statische, seiteneffektfreie Methode: das ist eine
     * Provider-Kapazitaetsaussage und gehoert getestet, nicht in eine
     * Bean-Methode mit {@code @Value}-Feldern versteckt. {@code null}
     * heisst "dieser Provider unterstuetzt kein Sticky Routing" — dann
     * werden {@code session_id} und {@code prompt_cache_key} gar nicht erst
     * gesendet (siehe {@link AgentRunner}).
     */
    static String stickyHeaderFor(String baseUrl) {
        if (baseUrl == null) return null;
        return switch (baseUrl) {
            case OPENROUTER_URL -> "x-session-id";
            case VERCEL_GATEWAY_URL -> "x-session-affinity";
            default -> null;
        };
    }

    /**
     * Erzeugt den OpenAiChatModel mit Prompt-Caching aktiviert.
     * Das Caching wird über OpenAiChatRequestParameters gesetzt.
     */
    @Bean
    public OpenAiChatModel openAiChatModel(KnowledgePrefixLoader knowledgeLoader, ChatExchangeFileLogger exchangeLogger) {
        String headerKey = stickyHeaderFor(baseUrl);
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

    /**
     * Der Standard-Runner: mit Knowledge-Prefix und mit Sticky-Routing-Header.
     * <p>
     * Der Header wird hier bewusst DURCHGERECHT. Vorher wurde der 2-arg-Konstruktor
     * benutzt, wodurch {@code headerKey} null blieb — das {@code x-session-id}-Header
     * wurde dadurch nie gesetzt und die in der Praxis teuerste Cache-Bedingung
     * (Routing-Stabilitaet, prefix-caching.md 5.2) war stillschweigend abgeschaltet,
     * waehrend das Log-Statement weiter Sticky Routing behauptete.
     * <p>
     * Es gibt bewusst nur EINEN Runner-Bean. Der prefixlose Runner wird per
     * {@link AgentRunner#withoutKnowledgeBase()} abgeleitet: ein zweiter Bean
     * gleichen Typs wuerde die Constructor-Injektion mehrdeutig machen.
     */
    @Bean
    public AgentRunner agentRunner(ChatModel chatModel, KnowledgePrefixLoader knowledgeLoader) {
        AgentRunner runner = new AgentRunner(chatModel, knowledgeLoader.getPrefix(), stickyHeaderFor(baseUrl));
        log.info("[LangChain4jConfig] runner: knowledgeBase={} stickyHeader={}",
                runner.hasKnowledgeBase(), stickyHeaderFor(baseUrl));
        return runner;
    }
}
