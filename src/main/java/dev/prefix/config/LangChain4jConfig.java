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
 * Configures the OpenAiChatModel with token usage visibility.
 * <p>
 * Uses OpenAI-specific classes (instead of the generic LangChain4j interfaces),
 * so that token consumption per request becomes visible: inputTokens, outputTokens,
 * cachedTokens, totalTokens.
 * <p>
 * Provides two {@link AgentRunner} variants: one WITH knowledge prefix
 * (byte-identical, cacheable block) and one WITHOUT (subagents that do not
 * need the base context and therefore should not pay for it).
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
     * Resolves the sticky routing header for a baseUrl.
     * <p>
     * Deliberately a static, side-effect-free method: this is a
     * provider capacity statement and belongs in tests, not hidden in a
     * bean method with {@code @Value} fields. {@code null}
     * means "this provider does not support sticky routing" — then
     * {@code session_id} and {@code prompt_cache_key} are not sent
     * at all (see {@link AgentRunner}).
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
     * Creates the OpenAiChatModel with prompt caching enabled.
     * The caching is set via OpenAiChatRequestParameters.
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
     * The standard runner: with knowledge prefix and with sticky routing header.
     * <p>
     * The header is deliberately PASSED THROUGH here. Previously the 2-arg constructor
     * was used, which left {@code headerKey} null — the {@code x-session-id} header
     * was therefore never set and the most expensive cache condition in practice
     * (routing stability, prefix-caching.md 5.2) was silently switched off,
     * while the log statement kept claiming sticky routing.
     * <p>
     * There is deliberately only ONE runner bean. The prefix-less runner is derived via
     * {@link AgentRunner#withoutKnowledgeBase()}: a second bean of the same
     * type would make constructor injection ambiguous.
     */
    @Bean
    public AgentRunner agentRunner(ChatModel chatModel, KnowledgePrefixLoader knowledgeLoader) {
        AgentRunner runner = new AgentRunner(chatModel, knowledgeLoader.getPrefix(), stickyHeaderFor(baseUrl));
        log.info("[LangChain4jConfig] runner: knowledgeBase={} stickyHeader={}",
                runner.hasKnowledgeBase(), stickyHeaderFor(baseUrl));
        return runner;
    }
}
