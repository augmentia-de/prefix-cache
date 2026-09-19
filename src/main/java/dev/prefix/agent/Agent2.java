package dev.prefix.agent;

import dev.prefix.tool.AnalysisTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import dev.prefix.state.WorkflowSessionState;
import dev.prefix.tool.ToolExecutor;

import java.util.List;
import java.util.Map;

/**
 * Agent2 — SHARED KNOWLEDGE PREFIX + KEINE Tools (Prompt-Filterung).
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

                CONTEXT: Everything above this line is the SHARED KNOWLEDGE BASE — synthetic facts
                about the world of Novaris: founded in 847 by Dr. Elena Voss on Lake Kaelen, Mount Thorne
                at exactly 3,241 m, the river Ombra flowing 412 km into Cerulean Bay near Port Maris,
                the quantum crystal Xytherium-7, the Lumina festival held each September, Comet Voss
                returning every 52 years, the Veridian library with 14,392 hand-bound books and the
                self-regulating pendulum clock mechanism by Engineer Mara Quill.

                STRICT RULES:
                1. You must NOT use any tools — answer directly from your training + the shared knowledge above.
                2. Ground every claim in at least one concrete fact from the knowledge base
                   (exact names, numbers and years, e.g. "3,241 meters", "year 847", "every 52 years").
                3. This is a fresh request without cached intermediate results — answer from scratch.
                4. Prefer quoting exact figures from the knowledge base over approximations.
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
        log.info("[{}] Starting with shared knowledge, NO tools", name());

        AgentRunner.RunResult result = runner.run(
                name(),
                prompt(),
                userInput,
                ToolProvider.getAll(),   // gleiche Liste wie Agent1 (byte-identisch!)
                executor,
                state.getSessionId()
        );

//        AgentRunner.RunResult result = runner.run(
//                name(),
//                prompt(),
//                userInput,
//                ToolProvider.getAll(),   // gleiche Liste wie Agent1 (byte-identisch!)
//                (ToolExecutor) (toolName, args) -> "[UNREACHABLE]"
//        );

        Map<String, Object> runData = Map.of(
                "direct_answer", result.text(),
                "used_prefix", true,
                "used_tools", false,
                "tool_calls", result.toolCalls().stream().map(AgentRunner.ToolCall::toMap).toList(),
                "token_usage", TokenStatsMapper.toMap(result.tokenUsage())
        );
        state.setAgentResult(name(), runData);

        log.info("[{}] Done.", name());
    }
}
