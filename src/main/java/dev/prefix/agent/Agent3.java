package dev.prefix.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import dev.prefix.state.WorkflowSessionState;
import dev.prefix.tool.AnalysisTools;
import dev.prefix.tool.ToolExecutor;

import java.util.Map;

/**
 * Agent3 — SHARED KNOWLEDGE PREFIX + optionales defineTask Tool.
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
                   once to clarify; otherwise proceed directly without tools.
                3. Weave the supporting facts into a flowing, contextual response rather than a bare list.
                4. Cover the user's request fully and reuse the strongest facts from the knowledge base.
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
        log.info("[{}] Starting with shared knowledge + optional defineTask", name());

        AgentRunner.RunResult result = runner.run(
                name(),
                prompt(),
                userInput,
                ToolProvider.getAll(),   // gleiche Liste (byte-identisch!)
                executor,                // defineTask ist opt-in erlaubt
                state.getSessionId()
        );

        Map<String, Object> runData = Map.of(
                "completion", result.text(),
                "prefix_used", true,
                "tools_available_but_optional", "defineTask",
                "tool_calls", result.toolCalls().stream().map(AgentRunner.ToolCall::toMap).toList(),
                "token_usage", TokenStatsMapper.toMap(result.tokenUsage())
        );
        state.setAgentResult(name(), runData);

        log.info("[{}] Done.", name());
    }
}
