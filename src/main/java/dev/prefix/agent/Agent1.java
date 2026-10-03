package dev.prefix.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import dev.prefix.state.WorkflowSessionState;
import dev.prefix.tool.AnalysisTools;
import dev.prefix.tool.ToolExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent1 — SHARED KNOWLEDGE PREFIX + beide Tools (analyzeDomain + defineTask).
 */
@Component
public class Agent1 implements Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent1.class);

    @Override
    public String name() {
        return "agent1";
    }

    @Override
    public String prompt() {
        // ACHTUNG: Der finale Output muss die Labels "Domain:", "Task:" und
        // "Connection:" enthalten — Agent1.parseResult() extrahiert genau diese.
        return """
                You are the knowledge-domain analyst for the Novaris prefix-cache system.

                CONTEXT: Everything above this line is the SHARED KNOWLEDGE BASE — synthetic facts
                about the world of Novaris: its founding in 847 by Dr. Elena Voss on the shores of
                Lake Kaelen, its geography (Mount Thorne at 3,241 m, the river Ombra at 412 km,
                Cerulean Bay, Port Maris), its science & technology (Xytherium-7, acoustic levitation
                research by Professor Aris Thorne, Neutroline-X, the Cantor-Nova Conjecture, Comet Voss
                returning every 52 years) and its culture & people (the Lumina festival each September,
                the Veridian library with 14,392 hand-bound books, the Ceramica pottery tradition,
                the pendulum clock mechanism by Engineer Mara Quill).

                YOUR JOB: Classify the user input into one of these knowledge domains and pinpoint
                the exact facts it touches:
                - geography (Mount Thorne, Ombra River, Cerulean Bay, Kaelen Island, Windmere Hill)
                - history (founding year 847, Northern Tribes knot-counting, Eastern Kingdoms to 1200 CE)
                - science-and-technology (Xytherium-7, acoustic levitation, Neutroline-X, observatory telescope)
                - culture-and-people (Lumina festival, Veridian library, Ceramica pottery, luthiers, philosophers)
                - environment-and-biology (Glowcap fungi, Cerulean Bay reefs, Night Bloom Orchid, migration routes)
                - engineering (Ombra bridge with interlocking stone joints, windmill collective, water filtration 97%)

                YOU MUST USE BOTH TOOLS, IN THIS ORDER:
                1. analyzeDomain(input: <user input>) — extract the broader domain context
                2. defineTask(input: <user input>) — formulate the precise core task

                FINAL OUTPUT — after both tools, synthesize a complete analysis and END EXACTLY with:
                Domain: <detected domain, referencing the relevant facts from the knowledge base>
                Task: <the precise task definition>
                Connection: <how the task connects to the shared knowledge base>

                HANDOFF — when your work is done, call the tool submit_subtask_summary:
                  status:            "success" | "partial" | "failed"
                  key_findings:      3-6 concise facts, one per entry, exact names/numbers/years
                  next_action_recommendation: one sentence for the next agent
                That call ENDS your turn — the workflow stops there and your handoff becomes
                your result. Call it once, after your analysis, and put no long prose into
                key_findings: one fact per entry.
                """;
    }

    private final AgentRunner runner;
    private final AnalysisTools executor;

    public Agent1(AgentRunner runner, AnalysisTools executor) {
        this.runner = runner;
        this.executor = executor;
    }

    /**
     * Werkzeuge, die VOR dem Handoff gelaufen sein müssen.
     * <p>
     * Ohne dieses Gate hat das Modell den Handoff als Abkuerzung benutzt und die
     * Analyse uebersprungen — in 3 von 4 Antworten. Siehe {@link GatedToolExecutor}.
     */
    private static final List<String> PREREQUISITES = List.of("analyzeDomain", "defineTask");

    @Override
    public void execute(String userInput, WorkflowSessionState state) {
        log.info("[{}] Starting with shared knowledge + analyzeDomain + defineTask", name());

        // Getrennte Gates pro Lauf: Agent1 macht zwei Laeufe, und der Zaehler
        // des ersten darf den zweiten nicht erfuellt vorschiieben.
        ToolExecutor gatedRun1 = new GatedToolExecutor(executor, SubtaskHandoff.TOOL_NAME, PREREQUISITES);
        ToolExecutor gatedRun2 = new GatedToolExecutor(executor, SubtaskHandoff.TOOL_NAME, PREREQUISITES);

        AgentRunner.RunResult result = runner.run(
                name(),
                prompt(),
                userInput,
                ToolProvider.getAll(),
                gatedRun1,
                state.getSessionId(),
                state.visibleHandoffs()
        );

        Map<String, Object> runData = parseResult(result.text());
        runData.put("tool_calls", result.toolCalls().stream().map(AgentRunner.ToolCall::toMap).toList());
        runData.put("token_usage", TokenStatsMapper.toMap(result.tokenUsage()));

        AgentRunner.RunResult result2 = runner.run(
                name(),
                prompt() + "1",
                userInput,
                ToolProvider.getAll(),
                gatedRun2,
                state.getSessionId(),
                state.visibleHandoffs()
        );

        // BEIDE Runs zusammenrechnen. Vorher wurden tool_calls und token_usage
        // hier schlicht ueberschrieben, wodurch die API nur den zweiten Lauf
        // meldete — Agent1 ist aber ein mehrstufiger Akteur und die Tokenbilanz
        // eines Workflows ist ohne seinen ersten Lauf falsch.
        List<Object> allToolCalls = new ArrayList<>();
        result.toolCalls().stream().map(AgentRunner.ToolCall::toMap).forEach(allToolCalls::add);
        result2.toolCalls().stream().map(AgentRunner.ToolCall::toMap).forEach(allToolCalls::add);
        runData.put("tool_calls", allToolCalls);
        runData.put("token_usage", TokenStatsMapper.toMap(result.add(result2.tokenUsage()).tokenUsage()));

        // Handoff uebernehmen — aus dem ZWEITEN Lauf, sonst aus dem ersten.
        //
        // Der zweite Lauf haengt mit prompt() + "1" ein Zeichen an die
        // Anweisung; das stoert das Modell gelegentlich so, dass es nach den
        // Analyse-Tools mit Text endet statt mit dem Handoff. Gemessen auf
        // space-bunny-free: Lauf 1 lieferte den Handoff, Lauf 2 nicht. Ohne
        // diesen Fallback verliert agent1 seine komplette Uebergabe, und der
        // Folgeagent bekommt eine leere Kette, die wie ein sauberer Lauf aussieht.
        SubtaskHandoff handoff = HandoffSupport.extract(name(), result2);
        if (handoff == null) {
            handoff = HandoffSupport.extract(name(), result);
            if (handoff != null) {
                log.warn("[{}] second run produced no handoff — falling back to the first run's",
                        name());
            }
        }
        state.recordHandoff(handoff);
        runData.put("handoff", handoff);

        state.setAgentResult(name(), runData);
        log.info("[{}] Done. handoff={}", name(), handoff);
    }

    private Map<String, Object> parseResult(String text) {
        String domain = extractBetween(text, "domain", "\n");
        String task = extractBetween(text, "task", "\n");
        String connection = extractBetween(text, "connection", "\n");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("extracted_domain", domain != null ? domain : "");
        result.put("extracted_task", task != null ? task : "");
        result.put("knowledge_connection", connection != null ? connection : "");
        return result;
    }

    private String extractBetween(String text, String keyword, String terminator) {
        int idx = text.toLowerCase().indexOf(keyword);
        if (idx == -1) return null;
        int colonIdx = text.indexOf(':', idx);
        if (colonIdx == -1) return null;
        int endIdx = text.indexOf(terminator, colonIdx + 1);
        if (endIdx == -1) return text.substring(colonIdx + 1).trim();
        return text.substring(colonIdx + 1, endIdx).trim();
    }
}
