package dev.prefix.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import dev.prefix.config.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.prefix.tool.ToolExecutor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generischer Agent-Runner — führt den ReAct Loop mit Tool Calling aus.
 * <p>
 * Öffnet OpenAI-spezifische Response-Metadaten für Token-Nutzungssichtbarkeit:
 * inputTokens, outputTokens, cachedTokens, totalTokens pro Request und insgesamt.
 */
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /** Maximale Tool Calls pro Iteration */
    private static final int MAX_TOOL_CALLS = 5;

    private final ChatModel chatModel;
    private final String knowledgePrefix;
    private final String headerKey;

    public AgentRunner(ChatModel chatModel, String knowledgePrefix) {
        this(chatModel, knowledgePrefix, null);
    }

    public AgentRunner(ChatModel chatModel, String knowledgePrefix, String headerKey) {
        this.chatModel = chatModel;
        this.knowledgePrefix = knowledgePrefix;
        this.headerKey = headerKey;
    }

    /**
     * Startet den ReAct Loop für einen Agenten.
     * Protokolliert Token-Nutzung pro Schritt und aggregiert am Ende.
     */
    public RunResult run(
            String agentName,
            String agentPrompt,
            String userInput,
            List<ToolSpecification> toolSpecs,
            ToolExecutor toolExecutor,
            String sessionId) {

        String fullSystemMessage = buildFullSystem(agentName, agentPrompt);

        Map<String, Object> customParameters = null;
        // session_id ist eine OPENROUTER-spezifische Erweiterung (Provider Sticky Routing:
        // pinnt ALLE Requests eines Workflow-Runs auf denselben Upstream-Endpoint, damit der
        // von agent1 geschriebene gemeinsame Prefix-Block von nachfolgenden Agents als
        // Cache-Hit wiederverwendet wird). Andere OpenAI-kompatible Backends (z.B. Google
        // Gemini) lehnen das Feld ab (400 INVALID_ARGUMENT) — daher nur bei stickySessions.
        if (sessionId != null) {
            customParameters = new HashMap<>();
            customParameters.put("session_id", sessionId);
            customParameters.put("prompt_cache_key", sessionId);
            log.info("[{}] Using sticky session routing: {}={}", agentName, headerKey, sessionId);
            RequestContext.currentRequestId.set(sessionId);
        }
        var messages = new ArrayList<ChatMessage>();
        messages.add(new SystemMessage(fullSystemMessage));
        messages.add(UserMessage.from(userInput));

        int toolCallCount = 0;
        String finalAnswer = null;
        List<ToolCall> toolCalls = new ArrayList<>();
        RunResult result = new RunResult(null, "", toolCalls);

        while (toolCallCount <= MAX_TOOL_CALLS) {

            // LangChain4j 1.13: toolSpecifications + OpenRouter session_id laufen ueber
            // die ChatRequest.parameters (nicht beide separat auf dem ChatRequest).
            OpenAiChatRequestParameters.Builder paramsBuilder = OpenAiChatRequestParameters.builder()
                    .toolSpecifications(toolSpecs);
            if (customParameters != null) {
                paramsBuilder.customParameters(customParameters);
            }
            // Die defaultRequestParameters des Models (temperature etc.) werden via
            // defaultRequestParameters().overrideWith(parameters) automatisch gemerged.
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .parameters(paramsBuilder.build())
                    .build();

            ChatResponse response = chatModel.chat(request);
            TokenUsage tokenUsage = extractTokenUsage(response);

            // Token-Nutzung dieses Schrittes protokollieren und aggregieren
            logTokenUsage(agentName, toolCallCount, tokenUsage);
            result = result.add(tokenUsage);

            AiMessage aiMessage = response.aiMessage();

            if (!aiMessage.hasToolExecutionRequests()) {
                finalAnswer = aiMessage.text();
                log.info("[{}] Final answer after {} tool calls", agentName, toolCallCount);
                messages.add(aiMessage);
                break;
            }

            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            log.debug("[{}] {} tool call(s): {}", agentName, requests.size(),
                    requests.stream().map(ToolExecutionRequest::name).toList());

            for (ToolExecutionRequest req : requests) {
                String res;
                boolean ok = true;
                try {
                    res = toolExecutor.execute(req.name(), req.arguments());
                } catch (Exception e) {
                    res = "[ERROR] " + e.getMessage();
                    ok = false;
                    log.warn("[{}] Tool error: {}", agentName, req.name(), e);
                }
                toolCalls.add(new ToolCall(req.name(), req.arguments(), res, toolCallCount, ok));
                messages.add(aiMessage);
                messages.add(ToolExecutionResultMessage.from(req.id(), req.name(), res));
                toolCallCount++;
            }
        }

        if (finalAnswer == null) {
            log.warn("[{}] Hit max tool calls ({}) — fallback", agentName, MAX_TOOL_CALLS);
            return new RunResult(result.tokenUsage(), "", toolCalls);
        }

        log.info("[{}] === COMPLETE === ({} total tokens used, {} tool call(s))",
                agentName, result.totalTokens(), toolCalls.size());
        return new RunResult(result.tokenUsage(), finalAnswer, toolCalls);
    }

    private String buildFullSystem(String agentName, String agentPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("## SHARED KNOWLEDGE BASE ##\n\n");
        if (knowledgePrefix != null && !knowledgePrefix.isBlank()) {
            sb.append(knowledgePrefix);
        }
        sb.append("\n\n--- END OF SHARED KNOWLEDGE ---\n\n").append(agentPrompt);
        return sb.toString();
    }

    /**
     * Extrahiert TokenUsage aus der Response.
     * Bevorzugt OpenAI-spezifische Metadaten, damit cachedTokens sichtbar
     * werden — entscheidend für die Prefix-Cache-Sichtbarkeit.
     */
    private TokenUsage extractTokenUsage(ChatResponse response) {
        var metadata = response.metadata();
        if (metadata == null) return null;
        if (metadata instanceof OpenAiChatResponseMetadata oai) {
            return oai.tokenUsage();
        }
        return metadata.tokenUsage();
    }

    private void logTokenUsage(String agentName, int step, TokenUsage usage) {
        if (usage == null) {
            log.debug("[{}] Step {}: no token usage data", agentName, step);
            return;
        }
        if (usage instanceof OpenAiTokenUsage oai) {
            Integer cached = oai.inputTokensDetails() != null
                    ? oai.inputTokensDetails().cachedTokens() : null;
            Integer reasoning = oai.outputTokensDetails() != null
                    ? oai.outputTokensDetails().reasoningTokens() : null;
            log.info("[{}] Step {}: input={} | cached={} | output={} | reasoning={} | total={}",
                    agentName, step,
                    usage.inputTokenCount(),
                    cached != null ? cached : 0,
                    usage.outputTokenCount(),
                    reasoning != null ? reasoning : 0,
                    usage.totalTokenCount());
        } else {
            log.info("[{}] Step {}: input={} | output={} | total={}",
                    agentName, step, usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
        }
    }

    /** Protokolliert Aufruf einer einzelnen Tool-Funktion im ReAct Loop */
    public record ToolCall(String toolName, String arguments, String result, int step, boolean success) {
        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("tool", toolName);
            m.put("arguments", arguments);
            m.put("result", result);
            m.put("step", step);
            m.put("success", success);
            return m;
        }
    }

    /** Ergebnis einer Runner-Iteration mit Token-Nutzung, Textantwort und Tool-Calls */
    public record RunResult(TokenUsage tokenUsage, String text, List<ToolCall> toolCalls) {
        public int inputTokens() {
            return tokenUsage != null ? tokenUsage.inputTokenCount() : 0;
        }
        public int outputTokens() {
            return tokenUsage != null ? tokenUsage.outputTokenCount() : 0;
        }
        public int cachedTokens() {
            if (tokenUsage instanceof OpenAiTokenUsage oai && oai.inputTokensDetails() != null) {
                Integer cached = oai.inputTokensDetails().cachedTokens();
                return cached != null ? cached : 0;
            }
            return 0;
        }
        public int totalTokens() {
            return tokenUsage != null ? tokenUsage.totalTokenCount() : 0;
        }
        public RunResult add(TokenUsage other) {
            if (other == null) return this;
            if (tokenUsage == null) return new RunResult(other, this.text, this.toolCalls);
            return new RunResult(tokenUsage.add(other), this.text, this.toolCalls);
        }
    }
}
