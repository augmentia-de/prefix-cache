package dev.prefix.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import dev.prefix.state.WorkflowSessionState;
import dev.prefix.tool.AnalysisTools;

import java.util.LinkedHashMap;
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
                """;
    }

    private final AgentRunner runner;
    private final AnalysisTools executor;

    public Agent1(AgentRunner runner, AnalysisTools executor) {
        this.runner = runner;
        this.executor = executor;
    }

    @Override
    public void execute(String userInput, WorkflowSessionState state) {
        log.info("[{}] Starting with shared knowledge + analyzeDomain + defineTask", name());

        AgentRunner.RunResult result = runner.run(
                name(),
                prompt(),
                userInput,
                ToolProvider.getAll(),
                executor,
                state.getSessionId()
        );

        Map<String, Object> runData = parseResult(result.text());
        runData.put("tool_calls", result.toolCalls().stream().map(AgentRunner.ToolCall::toMap).toList());
        runData.put("token_usage", TokenStatsMapper.toMap(result.tokenUsage()));

//        AgentRunner.RunResult result2 = runner.run(
//                name(),
//                prompt(),// + "1",
//                userInput,
//                ToolProvider.getAll(),
//                executor
//        );
//
//        Map<String, Object> runData2 = parseResult(result2.text());
//        runData.put("tool_calls", result2.toolCalls().stream().map(AgentRunner.ToolCall::toMap).toList());
//        runData.put("token_usage", TokenStatsMapper.toMap(result2.tokenUsage()));

        state.setAgentResult(name(), runData);
        log.info("[{}] Done.", name());
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
