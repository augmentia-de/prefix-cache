package dev.prefix.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.List;
import java.util.Map;

/**
 * Central tool registry.
 *
 * <h2>Two tool sets, not one</h2>
 * {@link #getAll()} and {@link #getSubAgentTools()} are separate lists, each
 * byte-identical on its own. That is <b>not</b> a violation of the
 * cache invariant: it must hold <i>within one workflow</i>, not
 * across all experiments.
 * <p>
 * The reason for the separation is access control. It happens here
 * <b>exclusively through the prompt</b> — every tool list an agent
 * sees it can also call. If a tool is in the list, the
 * model can use it too, no matter what the prompt forbids. Observed in the
 * exchange logs from 2026-10-02: after adding the subagent tools, agent1
 * called <code>lookupEvidence</code> and
 * <code>crossCheck</code> in the old 3-agent workflow and got
 * {@code [ERROR] Unknown tool: lookupEvidence} back — two of five
 * permitted tool iterations burned on one error.
 *
 * <h2>The invariant</h2>
 * Within one workflow <i>all</i> actors see the same list:
 * orchestrator and its subagents thereby share the same tool block.
 * That is also the reason why the base context is not controlled via the tool list
 * but via the executing {@link AgentRunner}.
 *
 * <h2>CAREFUL when extending</h2>
 * Any change to a list changes the serialized
 * {@code toolSpecifications} block and thus the cacheable prefix. That is not a
 * bug, but a one-time cold start: the first request afterwards
 * necessarily reports {@code cached_tokens: 0} and rewrites the block. Anyone measuring a
 * cache hit rate must mark the run after a tool extension as a
 * cold start, otherwise it counts as a miss.
 */
public class ToolProvider {

    /**
     * ALWAYS THE SAME list — byte-identity across all requests and agents.
     */
    private static final List<ToolSpecification> ALL_TOOLS = buildAllTools();

    /**
     * Subagent tools. A second, likewise byte-identical set for the
     * subagent demo (see {@code SubAgentWorkflowService}).
     */
    private static final List<ToolSpecification> SUB_AGENT_TOOLS = buildSubAgentTools();

    // --- Public API ---

    /**
     * Returns the complete list of all analysis tools.
     * This list is unmodifiable and always identical.
     */
    public static List<ToolSpecification> getAll() {
        return ALL_TOOLS;
    }

    /**
     * Returns the subagent tools — shared by all actors of the
     * subagent demo (orchestrator and its three subagents).
     */
    public static List<ToolSpecification> getSubAgentTools() {
        return SUB_AGENT_TOOLS;
    }

    /**
     * Checks whether a tool with the given name exists.
     */
    public static boolean hasTool(String toolName) {
        return hasToolIn(ALL_TOOLS, toolName) || hasToolIn(SUB_AGENT_TOOLS, toolName);
    }

    private static boolean hasToolIn(List<ToolSpecification> tools, String name) {
        return tools.stream().anyMatch(t -> t.name().equals(name));
    }

    /**
     * Constructs the static tool list at class load time.
     * All tools defined here are contained in every ChatRequest.
     */
    private static List<ToolSpecification> buildAllTools() {
        return List.of(
                // Tool 1: analyzeDomain — usable by Agent1
                ToolSpecification.builder()
                        .name("analyzeDomain")
                        .description("Extract the broader domain context from user input. "
                                + "Returns a concise domain description suitable for reuse across similar requests.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("input", "The full user input text to analyze")
                                .required(List.of("input"))
                                .build())
                        .build(),

// Tool 2: defineTask — usable by Agent1 AND Agent3
                ToolSpecification.builder()
                        .name("defineTask")
                        .description("Formulate the core task definition from user input. "
                                + "Returns a concise 1-2 sentence task definition.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("input", "The full user input text to analyze")
                                .required(List.of("input"))
                                .build())
                        .build(),

                // Tool 3: submit_subtask_summary — structured handoff to the follow-up agent
                // Not an analysis tool, but a protocol tool: every agent
                // calls it EXACTLY ONCE at the end of its work. The engine appends
                // the arguments to the next agent as a ToolResultMessage.
                ToolSpecification.builder()
                        .name(SubtaskHandoff.TOOL_NAME)
                        .description("Submit your structured result for the next agent. Call it once, when your work is "
                                + "done, after any analysis tools and after your analysis is complete. "
                                + "The call ENDS your turn: the workflow stops there and this handoff becomes "
                                + "your result. It carries a fixed schema, so the next agent receives fields "
                                + "instead of prose. Do not put long explanations into key_findings; "
                                + "one concise fact each.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("status",
                                        "'success', 'partial' or 'failed' for this subagent")
                                .addProperty("key_findings",
                                        // LangChain4j 1.13 has no addStringArrayProperty,
                                        // hence explicit: JsonArraySchema with string items.
                                        JsonArraySchema.builder()
                                                .description("The concrete results, one concise entry each "
                                                        + "(exact names, numbers, years)")
                                                .items(JsonStringSchema.builder().build())
                                                .build())
                                .addStringProperty("next_action_recommendation",
                                        "What the next agent should focus on, in one sentence")
                                .required(List.of("status", "key_findings", "next_action_recommendation"))
                                .build())
                        .build()
        );
    }

    /**
     * The three subagent tools. Each one mirrors a subagent that places its
     * OWN ChatRequest to the model — the context need differs
     * and is NOT controlled via this list, but via
     * which {@link AgentRunner} executes the subagent:
     * <pre>
     *   lookupEvidence / crossCheck -&gt; runner WITH knowledge prefix
     *   renderSummary               -&gt; runner WITHOUT (saves ~1.1k tokens)
     * </pre>
     */
    private static List<ToolSpecification> buildSubAgentTools() {
        return List.of(
                // Subagent 1 — REQUIRES the knowledge base
                ToolSpecification.builder()
                        .name("lookupEvidence")
                        .description("Subagent with access to the shared knowledge base. "
                                + "Retrieve the concrete facts from the knowledge base that bear on the input. "
                                + "Returns a compact list of supporting facts with exact names, numbers and years.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("input", "The full user input text to investigate")
                                .required(List.of("input"))
                                .build())
                        .build(),

                // Subagent 2 — REQUIRES the knowledge base
                ToolSpecification.builder()
                        .name("crossCheck")
                        .description("Subagent with access to the shared knowledge base. "
                                + "Verify a draft statement against the knowledge base and report which parts are "
                                + "supported, unsupported or contradicted. Returns a verdict plus the deciding facts.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("input", "The full user input text to verify")
                                .required(List.of("input"))
                                .build())
                        .build(),

                // Subagent 3 — REQUIRES NO knowledge base
                ToolSpecification.builder()
                        .name("renderSummary")
                        .description("Subagent WITHOUT access to the knowledge base. "
                                + "Turn already-collected findings into one short, fluent paragraph. "
                                + "Needs only the findings handed to it — it must not ask for more context.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("input", "The user's original request")
                                .addStringProperty("findings",
                                        "The findings collected by the other subagents, verbatim")
                                .required(List.of("input", "findings"))
                                .build())
                        .build()
        );
    }
}
