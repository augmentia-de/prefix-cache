package dev.prefix.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.List;
import java.util.Map;

/**
 * Zentrale Tool-Registry.
 *
 * <h2>Zwei Werkzeug-Sätze, nicht einer</h2>
 * {@link #getAll()} und {@link #getSubAgentTools()} sind getrennte, jeweils
 * fuer sich byte-identische Listen. Das ist <b>keine</b> Verletzung der
 * Cache-Invariante: die muss <i>innerhalb eines Workflows</i> gelten, nicht
 * ueber alle Experimente hinweg.
 * <p>
 * Der Grund fuer die Trennung ist die Zugangskontrolle. Sie erfolgt hier
 * <b>ausschliesslich ueber den Prompt</b> — jede Tool-Liste, die ein Agent
 * sieht, kann er auch aufrufen. Liegt ein Werkzeug in der Liste, kann das
 * Modell es auch benutzen, egal was der Prompt verbietet. Beobachtet in den
 * Exchange-Logs vom 2026-10-02: nach dem Ergaenzen der Subagent-Tools rief
 * agent1 im alten 3-Agenten-Workflow <code>lookupEvidence</code> und
 * <code>crossCheck</code> auf und bekam
 * {@code [ERROR] Unknown tool: lookupEvidence} zurueck — zwei von fuenf
 * erlaubten Tool-Iterationen fuer einen Fehler verbraucht.
 *
 * <h2>Die Invariante</h2>
 * Innerhalb eines Workflows sehen <i>alle</i> Akteure dieselbe Liste:
 * Orchestrator und seine Subagenten teilen sich dadurch denselben Tool-Block.
 * Das ist auch der Grund, warum der Basiskontext nicht ueber die Tool-Liste
 * gesteuert wird, sondern ueber den ausfuehrenden {@link AgentRunner}.
 *
 * <h2>ACHTUNG beim Erweitern</h2>
 * Jede Aenderung an einer Liste aendert den serialisierten
 * {@code toolSpecifications}-Block und damit den cachebaren Prefix. Das ist kein
 * Fehler, aber ein einmaliger Kaltstart: Der erste Request danach meldet
 * zwingend {@code cached_tokens: 0} und schreibt den Block neu. Wer eine
 * Cache-Hit-Rate misst, muss den Lauf nach einer Tool-Erweiterung als
 * Kaltstart kennzeichnen, sonst zaehlt er ihn als Miss.
 */
public class ToolProvider {

    /**
     * IMMER GLEICHE Liste — Byte-Identitaet ueber alle Requests und Agents hinweg.
     */
    private static final List<ToolSpecification> ALL_TOOLS = buildAllTools();

    /**
     * Subagent-Werkzeuge. Zweiter, ebenfalls byte-identischer Satz fuer die
     * Subagent-Demo (siehe {@code SubAgentWorkflowService}).
     */
    private static final List<ToolSpecification> SUB_AGENT_TOOLS = buildSubAgentTools();

    // --- Public API ---

    /**
     * Gibt die vollstaendige Liste aller Analyse-Tools zurueck.
     * Diese Liste ist unveraenderlich und immer identisch.
     */
    public static List<ToolSpecification> getAll() {
        return ALL_TOOLS;
    }

    /**
     * Gibt die Subagent-Werkzeuge zurueck — von allen Akteuren der
     * Subagent-Demo geteilt (Orchestrator und seine drei Subagenten).
     */
    public static List<ToolSpecification> getSubAgentTools() {
        return SUB_AGENT_TOOLS;
    }

    /**
     * Prueft ob ein Tool mit dem gegebenen Namen existiert.
     */
    public static boolean hasTool(String toolName) {
        return hasToolIn(ALL_TOOLS, toolName) || hasToolIn(SUB_AGENT_TOOLS, toolName);
    }

    private static boolean hasToolIn(List<ToolSpecification> tools, String name) {
        return tools.stream().anyMatch(t -> t.name().equals(name));
    }

    /**
     * Konstruiert die statische Tool-Liste beim Klassenladen.
     * Alle hier definierten Tools sind in jedem ChatRequest enthalten.
     */
    private static List<ToolSpecification> buildAllTools() {
        return List.of(
                // Tool 1: analyzeDomain — von Agent1 verwendbar
                ToolSpecification.builder()
                        .name("analyzeDomain")
                        .description("Extract the broader domain context from user input. "
                                + "Returns a concise domain description suitable for reuse across similar requests.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("input", "The full user input text to analyze")
                                .required(List.of("input"))
                                .build())
                        .build(),

// Tool 2: defineTask — von Agent1 UND Agent3 verwendbar
                ToolSpecification.builder()
                        .name("defineTask")
                        .description("Formulate the core task definition from user input. "
                                + "Returns a concise 1-2 sentence task definition.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("input", "The full user input text to analyze")
                                .required(List.of("input"))
                                .build())
                        .build(),

                // Tool 3: submit_subtask_summary — strukturierte Übergabe an den Folgeagenten
                // Kein Analyse-Werkzeug, sondern ein Protokoll-Werkzeug: jeder Agent
                // ruft es GENAU EINMAL am Ende seiner Arbeit auf. Die Engine hängt
                // die Argumente dem nächsten Agenten als ToolResultMessage an.
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
                                        // LangChain4j 1.13 hat kein addStringArrayProperty,
                                        // deshalb explizit: JsonArraySchema mit String-Items.
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
     * Die drei Subagent-Werkzeuge. Jedes bildet einen Subagenten ab, der seinen
     * EIGENEN ChatRequest an das Modell stellt — der Kontextbedarf ist
     * unterschiedlich und wird NICHT ueber diese Liste gesteuert, sondern
     * darueber, welcher {@link AgentRunner} den Subagenten ausfuehrt:
     * <pre>
     *   lookupEvidence / crossCheck -&gt; Runner MIT Knowledge-Prefix
     *   renderSummary               -&gt; Runner OHNE (spart ~1,1k Tokens)
     * </pre>
     */
    private static List<ToolSpecification> buildSubAgentTools() {
        return List.of(
                // Subagent 1 — BENOETIGT die Knowledge Base
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

                // Subagent 2 — BENOETIGT die Knowledge Base
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

                // Subagent 3 — BENOETIGT KEINE Knowledge Base
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
