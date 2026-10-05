package dev.prefix.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.prefix.agent.AgentRunner;
import dev.prefix.agent.ToolProvider;
import dev.prefix.config.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Subagents behind tools — 1 orchestrator, 3 subagents, 2 of them with a base context.
 * <p>
 * A "subagent" is not a process of its own here, but a <b>nested
 * {@code AgentRunner.run()} call</b>: the tool executes a completely separate ChatRequest
 * inside the orchestrator's ReAct loop. That makes the question
 * "where does the subagent get its context" an architectural decision
 * instead of a matter of chance, and that is exactly what this is about.
 *
 * <h2>The three subagents</h2>
 * <table border="1">
 *   <caption>Context need and execution path</caption>
 *   <tr><th>Tool</th><th>Knowledge Base</th><th>Runner</th></tr>
 *   <tr><td>{@code lookupEvidence}</td><td>yes</td><td>{@code kbRunner}</td></tr>
 *   <tr><td>{@code crossCheck}</td><td>yes</td><td>{@code kbRunner}</td></tr>
 *   <tr><td>{@code renderSummary}</td><td><b>no</b></td><td>{@code bareRunner}</td></tr>
 * </table>
 *
 * <h2>Why this is cheap</h2>
 * Both KB subagents receive the knowledge base via their own
 * system block. It is byte-identical with the orchestrator's and with
 * the other KB subagent's — so the same cache block. The orchestrator
 * does <b>not</b> have to write it into its suffix in order to pass it on.
 * <p>
 * {@code renderSummary} deliberately does not get it. The findings of
 * the other two suffice. Measured: 728 instead of 1732 input tokens with an identical tool list.
 * A missing cache hit is cheaper here than a paid one.
 *
 * <h2>The negative control: {@link Mode#VIA_ARGUMENTS}</h2>
 * Here the knowledge base ends up in the subagent's <b>user message</b>,
 * that is in the dynamic suffix. The system block stays small and every call
 * pays for the data again: 0 cache tokens at ~1741 input tokens. Same data,
 * same tool list, different position — and the saving disappears.
 *
 * <h2>Lifetime</h2>
 * Deliberately <b>not a singleton</b>: a new instance is
 * created per workflow call. Otherwise the metrics of two concurrent runs
 * would get mixed up (the tool interface has no run context).
 */
public class SubAgentTools implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(SubAgentTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How the knowledge base reaches the KB subagents. */
    public enum Mode {
        /** System block — cacheable, this is the intended case. */
        VIA_PREFIX,
        /** User message — not cacheable, the negative control. */
        VIA_ARGUMENTS
    }

    /**
     * Subagents get the same tool list as the orchestrator (byte identity
     * of the tool block) — but an executor that rejects every tool call.
     *
     * That way (a) the tool specifications are identical and (b) recursion is structurally
     * impossible. The engine additionally has a depth guard.
     */
    private static final ToolExecutor NO_FURTHER_TOOLS =
            (toolName, args) -> "[UNREACHABLE] subagents must not dispatch tools";

    private final AgentRunner kbRunner;
    private final AgentRunner bareRunner;
    private final String knowledgeBaseText;
    private final Mode mode;

    /** Order = execution order = order of the tool calls. */
    private final Map<String, SubAgentMetrics> metrics = new LinkedHashMap<>();

    public SubAgentTools(AgentRunner kbRunner,
                         AgentRunner bareRunner,
                         String knowledgeBaseText,
                         Mode mode) {
        this.kbRunner = kbRunner;
        this.bareRunner = bareRunner;
        this.knowledgeBaseText = knowledgeBaseText;
        this.mode = mode;
    }

    @Override
    public String execute(String toolName, String arguments) {
        return switch (toolName) {
            case "lookupEvidence" -> runKnowledgeAgent("subagent:lookupEvidence", LOOKUP_PROMPT, arguments, true);
            case "crossCheck" -> runKnowledgeAgent("subagent:crossCheck", CROSSCHECK_PROMPT, arguments, true);
            case "renderSummary" -> runKnowledgeAgent("subagent:renderSummary", RENDER_PROMPT, arguments, false);
            default -> "[ERROR] unknown subagent: " + toolName;
        };
    }

    /**
     * Runs a subagent.
     *
     * @param needsKnowledgeBase whether the subagent may see the shared prefix
     */
    private String runKnowledgeAgent(String agentName, String prompt, String arguments, boolean needsKnowledgeBase) {
        String userInput = readInput(arguments);
        String findings = readFindings(arguments);

        String effectivePrompt = prompt;
        boolean usesPrefix = false;

        if (!needsKnowledgeBase) {
            // renderSummary: neither prefix nor arguments. The dynamic tail
            // contains only what the orchestrator hands over as findings.
            effectivePrompt = prompt + (findings.isBlank() ? "" : "\n\nFINDINGS FROM THE OTHER SUBAGENTS:\n" + findings);
        } else if (mode == Mode.VIA_ARGUMENTS) {
            // Negative control: the data moves into the user message.
            effectivePrompt = prompt
                    + "\n\nKNOWLEDGE BASE (passed as arguments — this part is NOT cacheable):\n"
                    + knowledgeBaseText;
        } else {
            usesPrefix = true;
        }

        AgentRunner runner = needsKnowledgeBase ? kbRunner : bareRunner;

        // Own tool set, byte-identical for orchestrator and all subagents
        // within the subagent demo. Deliberately NOT getAll() — otherwise the
        // analysis tools would leak into the subagent demo and vice versa.
        List<ToolSpecification> toolSpecs = ToolProvider.getSubAgentTools();

        log.info("[{}] knowledgeBase={} path={} mode={}",
                agentName, needsKnowledgeBase,
                usesPrefix ? "system-prefix" : (needsKnowledgeBase ? "user-message" : "no-knowledge"),
                mode);

        AgentRunner.RunResult result;
        try {
            result = runner.run(
                    agentName,
                    effectivePrompt,
                    userInput,
                    toolSpecs,
                    NO_FURTHER_TOOLS,
                    RequestContext.currentRequestId.get()
            );
        } catch (Exception e) {
            log.warn("[{}] subagent failed: {}", agentName, e.toString());
            record(agentName, needsKnowledgeBase, usesPrefix, 0, 0, 0, 0, "[ERROR] " + e.getMessage());
            return "[ERROR] subagent " + agentName + " failed: " + e.getMessage();
        }

        record(agentName, needsKnowledgeBase, usesPrefix,
                result.inputTokens(), result.cachedTokens(), result.outputTokens(), result.requests(), null);

        return result.text();
    }

    private void record(String agentName, boolean needsKnowledgeBase, boolean usesPrefix,
                        int input, int cached, int output, int requests, String error) {
        SubAgentMetrics m = new SubAgentMetrics(agentName, needsKnowledgeBase, usesPrefix,
                input, cached, output, requests, error);
        metrics.put(agentName, m);
        log.info("[{}] input={} cached={} output={} requests={} hit={}",
                agentName, input, cached, output, requests,
                input > 0 ? String.format("%.1f%%", 100.0 * cached / input) : "n/a");
    }

    /** Metrics of all executed subagents, in execution order. */
    public List<SubAgentMetrics> metrics() {
        return new ArrayList<>(metrics.values());
    }

    private String readInput(String arguments) {
        try {
            JsonNode root = MAPPER.readTree(arguments);
            return root.path("input").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String readFindings(String arguments) {
        try {
            JsonNode root = MAPPER.readTree(arguments);
            return root.path("findings").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Metric of a subagent. {@code hitPercent} is the share of cached
     * input tokens — deliberately 0 for {@code renderSummary}, because the subagent
     * does not even obtain the prefix and therefore has nothing to cache.
     * {@code requests} are real model calls, not tool calls: a
     * subagent with its own ReAct loop needs more than one.
     */
    public record SubAgentMetrics(String agent,
                                  boolean knowledgeBaseRequired,
                                  boolean knowledgeBaseViaPrefix,
                                  int inputTokens,
                                  int cachedTokens,
                                  int outputTokens,
                                  int requests,
                                  String error) {
        public int totalTokens() {
            return inputTokens + outputTokens;
        }

        public double hitPercent() {
            return inputTokens > 0 ? Math.round(1000.0 * cachedTokens / inputTokens) / 10.0 : 0.0;
        }

        /**
         * Input tokens that did NOT come from the cache and are therefore billed
         * at full price. This is the decisive size — not
         * {@link #hitPercent()}: that one is diluted by the large, always cached
         * tool block and therefore comes out far too good.
         */
        public int uncachedTokens() {
            return Math.max(0, inputTokens - cachedTokens);
        }
    }

    // --- Subagent prompts ---------------------------------------------------------
    // Static constants: the subagent prompt belongs in the user message
    // (dynamic tail), not in the system block.

    private static final String LOOKUP_PROMPT = """
            You are a retrieval subagent. You have full access to the SHARED KNOWLEDGE BASE
            provided in the system block above it.

            Collect the concrete facts from that knowledge base which bear on the user's request.
            Report only what the knowledge base actually contains — never invent a fact, and never
            fall back on outside knowledge.

            Return a compact bullet list, one fact per line, each with its exact name, number or
            year. No preamble, no summary paragraph.
            """;

    private static final String CROSSCHECK_PROMPT = """
            You are a verification subagent. You have full access to the SHARED KNOWLEDGE BASE
            provided in the system block above it.

            Check the user's request against that knowledge base. State plainly which parts are
            supported by the knowledge base, which parts it contradicts, and which parts it simply
            does not cover. Never invent a fact to fill a gap — an uncovered point is a finding.

            Return exactly three sections: SUPPORTED, CONTRADICTED, NOT COVERED.
            """;

    private static final String RENDER_PROMPT = """
            You are a writing subagent. You have NO access to the shared knowledge base —
            everything you are allowed to state has been handed to you in the FINDINGS section.

            Write ONE short, fluent paragraph (three to five sentences) that answers the user's
            request using those findings. Weave them into prose; do not produce a list.

            Rules: state nothing that is not in the FINDINGS section. Do not ask for more
            context. Do not mention that you lack a knowledge base.
            """;
}