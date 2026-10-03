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
 * Subagenten hinter Tools — 1 Orchestrator, 3 Subagenten, 2 davon mit Basiskontext.
 * <p>
 * Ein "Subagent" ist hier kein eigener Prozess, sondern ein <b>verschachtelter
 * {@code AgentRunner.run()}-Aufruf</b>: das Tool fuehrt im ReAct-Loop des
 * Orchestrators einen komplett eigenen ChatRequest aus. Damit ist die Frage
 * "woher bekommt der Subagent seinen Kontext" eine Architekturentscheidung
 * statt eines Zufalls, und genau darum geht es hier.
 *
 * <h2>Die drei Subagenten</h2>
 * <table border="1">
 *   <caption>Kontextbedarf und Ausfuehrungspfad</caption>
 *   <tr><th>Tool</th><th>Knowledge Base</th><th>Runner</th></tr>
 *   <tr><td>{@code lookupEvidence}</td><td>ja</td><td>{@code kbRunner}</td></tr>
 *   <tr><td>{@code crossCheck}</td><td>ja</td><td>{@code kbRunner}</td></tr>
 *   <tr><td>{@code renderSummary}</td><td><b>nein</b></td><td>{@code bareRunner}</td></tr>
 * </table>
 *
 * <h2>Warum das billig ist</h2>
 * Die beiden KB-Subagenten bekommen die Knowledge Base ueber ihren eigenen
 * System-Block. Sie ist byte-identisch mit dem des Orchestrators und mit dem
 * des jeweils anderen KB-Subagenten — also derselbe Cache-Block. Der Orchestrator
 * muss sie <b>nicht</b> in seinen Suffix schreiben, um sie weiterzugeben.
 * <p>
 * {@code renderSummary} bekommt sie absichtlich nicht. Es reichen die Findings der
 * beiden anderen. Gemessen: 728 statt 1732 Input-Tokens bei identischer Tool-Liste.
 * Ein fehlender Cache-Hit ist hier billiger als ein bezahlter.
 *
 * <h2>Die Negativprobe: {@link Mode#VIA_ARGUMENTS}</h2>
 * Hier landet die Knowledge Base in der <b>User-Message</b> des Subagenten,
 * also im dynamischen Suffix. Der System-Block bleibt klein und jeder Aufruf
 * zahlt die Daten neu: 0 Cache-Tokens bei ~1741 Input-Tokens. Gleiche Daten,
 * gleiche Tool-Liste, andere Position — und die Ersparnis verschwindet.
 *
 * <h2>Lebensdauer</h2>
 * Bewusst <b>kein Singleton</b>: pro Workflow-Aufruf wird eine neue Instanz
 * erzeugt. Sonst wuerden die Metriken zweier gleichzeitiger Laeufe
 * durcheinandergeraten (die Tool-Schnittstelle hat keinen Run-Kontext).
 */
public class SubAgentTools implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(SubAgentTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Wie die Knowledge Base an die KB-Subagenten gelangt. */
    public enum Mode {
        /** System-Block — cachebar, das ist der Soll-Fall. */
        VIA_PREFIX,
        /** User-Message — nicht cachebar, die Negativprobe. */
        VIA_ARGUMENTS
    }

    /**
     * Subagenten bekommen dieselbe Tool-Liste wie der Orchestrator (Byte-Identitaet
     * des Tool-Blocks) — aber einen Executor, der jeden Tool-Call ablehnt.
     *
     * So sind (a) die Tool-Spezifikationen identisch und (b) Rekursion strukturell
     * unmoeglich. Die Engine hat zusaetzlich eine Tiefenwache.
     */
    private static final ToolExecutor NO_FURTHER_TOOLS =
            (toolName, args) -> "[UNREACHABLE] subagents must not dispatch tools";

    private final AgentRunner kbRunner;
    private final AgentRunner bareRunner;
    private final String knowledgeBaseText;
    private final Mode mode;

    /** Reihenfolge = Ausfuehrungsreihenfolge = Reihenfolge der Tool-Calls. */
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
     * Fuehrt einen Subagenten aus.
     *
     * @param needsKnowledgeBase ob der Subagent den geteilten Prefix sehen darf
     */
    private String runKnowledgeAgent(String agentName, String prompt, String arguments, boolean needsKnowledgeBase) {
        String userInput = readInput(arguments);
        String findings = readFindings(arguments);

        String effectivePrompt = prompt;
        boolean usesPrefix = false;

        if (!needsKnowledgeBase) {
            // renderSummary: weder Prefix noch Argumente. Der dynamische Tail
            // enthaelt nur, was der Orchestrator an Findings uebergibt.
            effectivePrompt = prompt + (findings.isBlank() ? "" : "\n\nFINDINGS FROM THE OTHER SUBAGENTS:\n" + findings);
        } else if (mode == Mode.VIA_ARGUMENTS) {
            // Negativprobe: die Daten wandern in die User-Message.
            effectivePrompt = prompt
                    + "\n\nKNOWLEDGE BASE (passed as arguments — this part is NOT cacheable):\n"
                    + knowledgeBaseText;
        } else {
            usesPrefix = true;
        }

        AgentRunner runner = needsKnowledgeBase ? kbRunner : bareRunner;

        // Eigener Werkzeug-Satz, innerhalb der Subagent-Demo byte-identisch fuer
        // Orchestrator und alle Subagenten. Bewusst NICHT getAll() — sonst kaemen
        // die Analyse-Tools in die Subagent-Demo und umgekehrt.
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

    /** Metriken aller ausgefuehrten Subagenten, in Ausfuehrungsreihenfolge. */
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
     * Metrik eines Subagenten. {@code hitPercent} ist der Anteil gecachter
     * Input-Tokens — bei {@code renderSummary} bewusst 0, weil der Subagent den
     * Prefix gar nicht erst bezieht und deshalb auch nichts zu cachen hat.
     * {@code requests} sind echte Modell-Aufrufe, nicht Tool-Aufrufe: ein
     * Subagent mit eigenem ReAct-Loop braucht mehr als einen.
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
         * Input-Tokens, die NICHT aus dem Cache kamen und damit vollpreisig
         * abgerechnet werden. Das ist die entscheidende Groesse — nicht
         * {@link #hitPercent()}: die wird vom grossen, immer gecachten
         * Tool-Block verwassert und faellt deshalb viel zu gut aus.
         */
        public int uncachedTokens() {
            return Math.max(0, inputTokens - cachedTokens);
        }
    }

    // --- Subagent-Prompts ---------------------------------------------------------
    // Statische Konstanten: der Subagent-Prompt gehoert in die User-Message
    // (dynamischer Tail), nicht in den System-Block.

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