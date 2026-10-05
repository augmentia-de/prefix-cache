package dev.prefix.agent;

import dev.prefix.tool.AnalysisTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import dev.prefix.state.WorkflowSessionState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent2 — SHARED KNOWLEDGE PREFIX + no analysis tools (prompt filtering).
 * <p>
 * May call {@link SubtaskHandoff#TOOL_NAME} as the only tool, in order to hand
 * the result to Agent3 in a structured way.
 */
@Component
public class Agent2 implements Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent2.class);

    @Override
    public String name() {
        return "agent2";
    }

    @Override
    public String prompt() {
        return """
                You are a direct-response agent for the Novaris prefix-cache workflow.

                YOU ARE WORKING FROM A SHARED KNOWLEDGE BASE PROVIDED IN THE SYSTEM BLOCK.

                STRICT RULES:
                1. You must NOT use the tools analyzeDomain or defineTask — answer directly from the
                   shared knowledge above. The ONLY tool you may call is submit_subtask_summary.
                2. Ground every claim in at least one concrete fact from the knowledge base
                   (exact names, numbers and years, e.g. "3,241 meters", "year 847", "every 52 years").
                3. Prefer quoting exact figures from the knowledge base over approximations.

                INPUT FROM PRIOR AGENTS: if you see a submit_subtask_summary result at the end of
                this conversation, it comes from the previous agent. Treat it as context to build
                on — check whether it points you at the right part of the knowledge base. Do not
                contradict it without a fact from the knowledge base.

                HANDOFF — when your work is done, call the tool submit_subtask_summary:
                  status:            "success" | "partial" | "failed"
                  key_findings:      3-6 concise facts, one per entry, exact names/numbers/years
                  next_action_recommendation: one sentence for the next agent
                That call ENDS your turn — the workflow stops there and your handoff becomes
                your result. Call it once.
                """;
    }

    private final AgentRunner runner;
    private final AnalysisTools executor;

    public Agent2(AgentRunner runner, AnalysisTools executor) {
        this.runner = runner;
        this.executor = executor;
    }

    @Override
    public void execute(String userInput, WorkflowSessionState state) {
        log.info("[{}] Starting with shared knowledge, no analysis tools", name());

        // Handoffs of PREVIOUS agents arrive at the end of the prompt. Previously
        // there was a commented-out block here with an executor that rejected every
        // tool call — that would be wrong now: agent2 MUST be allowed to call
        // submit_subtask_summary, otherwise there would be no handoff to agent3.
        AgentRunner.RunResult result = runner.run(
                name(),
                prompt(),
                userInput,
                ToolProvider.getAll(),   // same list as Agent1 (byte-identical!)
                executor,
                state.getSessionId(),
                state.visibleHandoffs()
        );

        SubtaskHandoff handoff = HandoffSupport.extract(name(), result);
        state.recordHandoff(handoff);

        Map<String, Object> runData = new LinkedHashMap<>();
        runData.put("direct_answer", result.text());
        runData.put("used_prefix", true);
        runData.put("analysis_tools_available", false);
        runData.put("handoff_received", state.visibleHandoffs().stream().map(SubtaskHandoff::agent).toList());
        runData.put("handoff", handoff);
        runData.put("tool_calls", result.toolCalls().stream().map(AgentRunner.ToolCall::toMap).toList());
        runData.put("token_usage", TokenStatsMapper.toMap(result.tokenUsage()));
        state.setAgentResult(name(), runData);

        log.info("[{}] Done. handoff_received={} handoff={}",
                name(), runData.get("handoff_received"), handoff);
    }
}
