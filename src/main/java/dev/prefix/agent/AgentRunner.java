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
 * Generic agent runner — executes the ReAct loop with tool calling.
 * <p>
 * Opens up OpenAI-specific response metadata for token usage visibility:
 * inputTokens, outputTokens, cachedTokens, totalTokens per request and overall.
 */
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /** Maximum number of tool calls per iteration */
    private static final int MAX_TOOL_CALLS = 5;

    /**
     * Maximum nesting depth of nested run() calls.
     * The orchestrator runs at depth 1, its subagents at depth 2 — depth 3
     * (subagent calls subagent) is rejected. The ReAct loop itself is
     * iterative and does NOT increase the depth, only real subagent calls do.
     */
    private static final int MAX_NESTING_DEPTH = 2;

    private static final ThreadLocal<Integer> NESTING_DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * System block for subagents WITHOUT base context.
     * <p>
     * Deliberately its own byte-identical constant instead of an empty
     * knowledge block: the bare subagent must not get the shared block,
     * otherwise it pays ~1.1k tokens for data it does not use. Measured
     * against the KB variant: 728 instead of 1732 input tokens with an identical
     * tool list. At the same time a separate, short cache block arises — a
     * missing hit is cheaper here than a paid one.
     */
    static final String NO_KNOWLEDGE_SYSTEM =
            "## SHARED KNOWLEDGE BASE ##\n\n(no knowledge base available for this subagent)\n\n--- END OF SHARED KNOWLEDGE ---";

    private final ChatModel chatModel;
    private final String knowledgePrefix;
    private final String headerKey;

    public AgentRunner(ChatModel chatModel, String knowledgePrefix) {
        this(chatModel, knowledgePrefix, null);
    }

    /**
     * @param knowledgePrefix the shared knowledge prefix, or {@code null} /
     *                        empty for subagents that must not get a base
     *                        context (see {@link #NO_KNOWLEDGE_SYSTEM})
     * @param headerKey       sticky-routing header of the provider, or
     *                        {@code null} if the provider supports none
     */
    public AgentRunner(ChatModel chatModel, String knowledgePrefix, String headerKey) {
        this.chatModel = chatModel;
        this.knowledgePrefix = knowledgePrefix;
        this.headerKey = headerKey;
    }

    /**
     * Starts the ReAct loop for an agent.
     * Logs token usage per step and aggregates it at the end.
     */
    public RunResult run(
            String agentName,
            String agentPrompt,
            String userInput,
            List<ToolSpecification> toolSpecs,
            ToolExecutor toolExecutor,
            String sessionId) {
        return run(agentName, agentPrompt, userInput, toolSpecs, toolExecutor, sessionId, List.of());
    }

/**
 * Runner call with previous agents' handoffs.
 * <p>
 * {@code priorHandoffs} land at the <b>end</b> of the message list, after the
 * agent's own user prompt. This position is the actual cache protection: because
 * causal attention only looks back at tokens, any content at
 * this point changes nothing about the prefix in front of it — the cached region even
 * grows with it. Had the handoffs been rendered into the system block, the entire
 * prefix behind them would be worthless.
 * <p>
 * The JSON schema of the handoffs provides structure and length limiting, but
 * <b>no</b> cache protection. Confusing the two is the most common mistake
 * in this area.
 * <p>
 * <b>Format — and why not {@code role: "tool"}:</b> The obvious choice,
 * sending the handoff as a {@code ToolResultMessage}, creates an
 * <b>orphaned</b> tool message: {@code role: "tool"} without a preceding
 * {@code assistant} message with a matching {@code tool_call_id}. The schema of the
 * chat completion API does not provide for this. Providers disagree:
 * <ul>
 *   <li>OpenRouter/DeepSeek: tolerates it (verified, 200)</li>
 *   <li>openCode Zen / {@code space-bunny-free}: <b>400 invalid_request</b>
 *       (verified) — the same message, the same payload</li>
 * </ul>
 * A feature that works on one provider and fails on the other with
 * {@code invalid_request} is not a feature. Therefore the handoff is
 * transported as a <b>UserMessage</b> — portable, and semantically correct:
 * after all it is not the result of a tool call <i>in this</i> conversation,
 * but a handed-over message.
 *
 * @see SubtaskHandoff
 */
    public RunResult run(
            String agentName,
            String agentPrompt,
            String userInput,
            List<ToolSpecification> toolSpecs,
            ToolExecutor toolExecutor,
            String sessionId,
            List<SubtaskHandoff> priorHandoffs) {

        int depth = NESTING_DEPTH.get();
        if (depth >= MAX_NESTING_DEPTH) {
            throw new IllegalStateException("Subagent nesting beyond " + (MAX_NESTING_DEPTH - 1)
                    + " level(s) refused for '" + agentName
                    + "' — a subagent must not dispatch further subagents");
        }
        NESTING_DEPTH.set(depth + 1);
        try {
            return runLoop(agentName, agentPrompt, userInput, toolSpecs, toolExecutor,
                    sessionId, priorHandoffs);
        } finally {
            // finally is mandatory: the thread goes back into the servlet pool,
            // a leftover depth value would block the next request.
            NESTING_DEPTH.set(depth);
        }
    }

    private RunResult runLoop(
            String agentName,
            String agentPrompt,
            String userInput,
            List<ToolSpecification> toolSpecs,
            ToolExecutor toolExecutor,
            String sessionId,
            List<SubtaskHandoff> priorHandoffs) {

        // System message DELIBERATELY purely static (SHARED KNOWLEDGE BASE, byte-identical
        // across all agents and turns): Google needs an immutable systemInstruction
        // for prefix caching; a dynamic agent tail in the system block prevents the
        // block from being cached (OpenRouter/Google best practice: dynamics at the end => user message).
        String sharedSystem = buildSharedSystem();

        Map<String, Object> customParameters = null;
        // session_id and prompt_cache_key are OpenRouter-specific extensions
        // (provider sticky routing: pins ALL requests of a workflow run to the same
        // upstream endpoint, so that the shared prefix block written by
        // agent1 is reused as a cache hit by subsequent agents).
        //
        // Coupled to headerKey: if the provider is not known to be sticky-capable,
        // the fields are not sent at all. An unknown gateway otherwise rejects them with
        // 400 INVALID_ARGUMENT (Gemini) — and a 400 would abort the run, not just
        // silently disable routing.
        if (sessionId != null) {
            // The ThreadLocal applies regardless of the provider: nested subagents
            // need the run ID regardless of whether sticky routing is possible.
            RequestContext.currentRequestId.set(sessionId);

            if (headerKey != null) {
                customParameters = new HashMap<>();
                customParameters.put("session_id", sessionId);
                customParameters.put("prompt_cache_key", sessionId);
                log.info("[{}] Using sticky session routing: {}={}", agentName, headerKey, sessionId);
            } else {
                log.debug("[{}] Sticky routing off (provider has no known header) — custom parameters withheld",
                        agentName);
            }
        }
        var messages = new ArrayList<ChatMessage>();
        messages.add(new SystemMessage(sharedSystem));
        messages.add(UserMessage.from("Execute the following agent prompt."));
        messages.add(UserMessage.from(buildUserPrompt(agentPrompt, userInput)));

        // Handoffs of PREVIOUS agents — to the end, never into the system block.
        // The order is the execution order of the agents and is therefore
        // stable. As a UserMessage, not a ToolResultMessage: an orphaned
        // tool message is rejected by strictly checking providers with
        // 400 invalid_request (see the run(...) javadoc).
        if (priorHandoffs != null && !priorHandoffs.isEmpty()) {
            for (SubtaskHandoff handoff : priorHandoffs) {
                messages.add(UserMessage.from(handoff.renderAsMessage()));
            }
            log.info("[{}] received {} structured handoff(s) from prior agents: {}",
                    agentName, priorHandoffs.size(),
                    priorHandoffs.stream().map(SubtaskHandoff::agent).toList());
        }

        int toolCallCount = 0;
        int requestCount = 0;
        boolean terminatedByHandoff = false;
        String finalAnswer = null;
        List<ToolCall> toolCalls = new ArrayList<>();
        RunResult result = new RunResult(null, "", toolCalls, 0);

        while (toolCallCount <= MAX_TOOL_CALLS) {

            // LangChain4j 1.13: toolSpecifications + OpenRouter session_id go via
            // the ChatRequest.parameters (not both separately on the ChatRequest).
            OpenAiChatRequestParameters.Builder paramsBuilder = OpenAiChatRequestParameters.builder()
                    .toolSpecifications(toolSpecs);
            if (customParameters != null) {
                paramsBuilder.customParameters(customParameters);
            }
            // The model's defaultRequestParameters (temperature etc.) are merged automatically via
            // defaultRequestParameters().overrideWith(parameters).
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .parameters(paramsBuilder.build())
                    .build();

            ChatResponse response = chatModel.chat(request);
            TokenUsage tokenUsage = extractTokenUsage(response);
            requestCount++;

            // Log the token usage of this step and aggregate it
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

            messages.add(aiMessage);
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
                messages.add(ToolExecutionResultMessage.from(req.id(), req.name(), res));
                toolCallCount++;

                // TERMINAL tool, but only on SUCCESS. submit_subtask_summary
                // terminates the agent — the handoff IS its result. A call
                // rejected by the GatedToolExecutor does not count: otherwise a
                // premature handoff would end the agent even though it has not
                // accomplished anything at all yet.
                //
                // Without this break condition the model called the tool 5x
                // in a row, because a tool result with a JSON echo is not a
                // stop signal. The prompt could not enforce this.
                if (ok && SubtaskHandoff.TOOL_NAME.equals(req.name())) {
                    terminatedByHandoff = true;
                }
            }

            if (terminatedByHandoff) {
                log.info("[{}] Terminated by {} after {} tool call(s) — the handoff is the result",
                        agentName, SubtaskHandoff.TOOL_NAME, toolCallCount);
                break;
            }
        }

        if (finalAnswer == null && !terminatedByHandoff) {
            log.warn("[{}] Hit max tool calls ({}) — fallback", agentName, MAX_TOOL_CALLS);
            return new RunResult(result.tokenUsage(), "", toolCalls, requestCount);
        }

        log.info("[{}] === COMPLETE === ({} total tokens used, {} tool call(s), {} request(s))",
                agentName, result.totalTokens(), toolCalls.size(), requestCount);
        return new RunResult(result.tokenUsage(), finalAnswer == null ? "" : finalAnswer,
                toolCalls, requestCount);
    }

    /**
     * Derives a runner <b>without</b> a knowledge base.
     * <p>
     * Deliberately a method and not a second Spring bean: two beans
     * of the same type make constructor injection ambiguous, and an
     * {@code @Primary} would hide exactly the confusion that is
     * expensive here — an agent that gets the wrong variant silently pays
     * ~1k tokens for a prefix it does not read.
     * <p>
     * The derived runner shares ChatModel and headerKey, but differs
     * in the system block and thus in its own cache block.
     */
    public AgentRunner withoutKnowledgeBase() {
        return new AgentRunner(chatModel, null, headerKey);
    }

    /**
     * Whether this runner puts the knowledge base into the system block.
     */
    public boolean hasKnowledgeBase() {
        return knowledgePrefix != null && !knowledgePrefix.isBlank();
    }

    /**
     * Builds the byte-identical system block.
     * <p>
     * With a knowledge prefix: the shared block, cacheable across all agents and turns.
     * Without a prefix (subagent that needs no base context): a fixed constant.
     * Both branches are deterministic — the byte-identity within one
     * branch is the actual cache prerequisite.
     */
    private String buildSharedSystem() {
        if (knowledgePrefix == null || knowledgePrefix.isBlank()) {
            return NO_KNOWLEDGE_SYSTEM;
        }
        return "## SHARED KNOWLEDGE BASE ##\n\n" + knowledgePrefix + "\n\n--- END OF SHARED KNOWLEDGE ---";
    }

    private String buildUserPrompt(String agentPrompt, String userInput) {
        return agentPrompt + "\n\nUSER INPUT:\n" + userInput;
    }

    /**
     * Extracts the TokenUsage from the response.
     * Prefers OpenAI-specific metadata so that cachedTokens become visible
     * — decisive for prefix-cache visibility.
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

    /** Logs the invocation of a single tool function in the ReAct loop */
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

    /**
     * Result of one runner pass.
     *
     * @param requests the actual number of {@code chatModel.chat(...)} calls.
     *                Deliberately counted instead of estimated: the cache effect
     *                scales with K-1, and an estimated K would distort exactly the
     *                size it is all about.
     */
    public record RunResult(TokenUsage tokenUsage, String text, List<ToolCall> toolCalls, int requests) {
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
            if (tokenUsage == null) return new RunResult(other, this.text, this.toolCalls, this.requests);
            return new RunResult(tokenUsage.add(other), this.text, this.toolCalls, this.requests);
        }
    }
}
