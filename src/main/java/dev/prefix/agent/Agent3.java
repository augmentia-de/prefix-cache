package dev.prefix.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import dev.prefix.state.WorkflowSessionState;
import dev.prefix.tool.AnalysisTools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent3 — SHARED KNOWLEDGE PREFIX + optional defineTask tool.
 * <p>
 * Last agent of the sequence: receives the handoffs from Agent1 and Agent2 and
 * hands in its own via {@link SubtaskHandoff#TOOL_NAME}.
 */
@Component
public class Agent3 implements Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent3.class);

    @Override
    public String name() {
        return "agent3";
    }

    @Override
    public String prompt() {
        return """
                You are the final synthesis agent for the Novaris prefix-cache workflow.

                CONTEXT: Everything above this line is the SHARED KNOWLEDGE BASE — synthetic facts
                about the world of Novaris (founding in 847 by Dr. Elena Voss on Lake Kaelen, Mount Thorne
                at 3,241 m, the river Ombra into Cerulean Bay, Xytherium-7, the Lumina festival every
                September under the full moon, Comet Voss on a 52-year cycle, Professor Aris Thorne's
                acoustic levitation research, the Cantor-Nova Conjecture). ALL PRIOR STAGES have already
                processed this user request using this same knowledge base.

                YOUR TASK: Provide the comprehensive final answer to the user's request.

                INSTRUCTIONS:
                1. Ground your answer in the shared knowledge base — cite specific facts
                   (exact names, years, places and numbers).
                2. If the exact task is still ambiguous, you MAY call defineTask(input: <user input>)
                   once to clarify; otherwise proceed directly without analysis tools.
                3. Weave the supporting facts into a flowing, contextual response rather than a bare list.
                4. Cover the user's request fully and reuse the strongest facts from the knowledge base.

                INPUT FROM PRIOR AGENTS: you will see submit_subtask_summary results at the end of
                this conversation, from the agents that ran before you. They are the structured
                record of what was already established. Build your final answer on them and on the
                knowledge base; where a prior finding is wrong, say so and give the correct fact.

                HANDOFF — when your work is done, call the tool submit_subtask_summary:
                  status:            "success" | "partial" | "failed"
                  key_findings:      3-6 concise facts, one per entry, exact names/numbers/years
                  next_action_recommendation: one sentence; you are last, so state what the
                                            workflow should surface to the user
                That call ENDS your turn — the workflow stops there and your handoff becomes
                your result. Call it once.
                """;
    }

    private final AgentRunner runner;
    private final AnalysisTools executor;

    public Agent3(AgentRunner runner, AnalysisTools executor) {
        this.runner = runner;
        this.executor = executor;
    }

    @Override
    public void execute(String userInput, WorkflowSessionState state) {
        log.info("[{}] Starting with shared knowledge + handoffs from prior agents", name());

        AgentRunner.RunResult result = runner.run(
                name(),
                prompt(),
                userInput,
                ToolProvider.getAll(),   // same list (byte-identical!)
                executor,                // defineTask is allowed on opt-in
                state.getSessionId(),
                state.visibleHandoffs()
        );

        SubtaskHandoff handoff = HandoffSupport.extract(name(), result);
        state.recordHandoff(handoff);

        Map<String, Object> runData = new LinkedHashMap<>();
        runData.put("completion", result.text());
        runData.put("prefix_used", true);
        runData.put("handoff_received", state.visibleHandoffs().stream().map(SubtaskHandoff::agent).toList());
        runData.put("handoff", handoff);
        runData.put("tool_calls", result.toolCalls().stream().map(AgentRunner.ToolCall::toMap).toList());
        runData.put("token_usage", TokenStatsMapper.toMap(result.tokenUsage()));
        state.setAgentResult(name(), runData);

        log.info("[{}] Done. handoff_received={} handoff={}",
                name(), runData.get("handoff_received"), handoff);
    }
}
