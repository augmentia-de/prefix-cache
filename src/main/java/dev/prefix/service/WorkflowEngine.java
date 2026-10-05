package dev.prefix.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import dev.prefix.agent.Agent;
import dev.prefix.agent.SubtaskHandoff;
import dev.prefix.config.KnowledgePrefixLoader;
import dev.prefix.state.WorkflowSessionState;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrator — loads the shared knowledge prefix and runs all agents sequentially.
 * <p>
 * Each agent is an {@link Agent} interface implementation. The WorkflowEngine knows nothing
 * about specific implementations — it only calls #name(), #prompt() and #execute().
 * <p>
 * All agents receive the same byte-identical knowledgePrefix via AgentRunner:
 * <ul>
 *   <li>Agent1: prefix + both tools (analyzeDomain + defineTask)</li>
 *   <li>Agent2: prefix + no tools (prompt filtering only)</li>
 *   <li>Agent3: prefix + optional tool (defineTask)</li>
 * </ul>
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    /** List of all agents — injected via Spring DI, sorted by execution order */
    private final List<Agent> agents;
    private final KnowledgePrefixLoader knowledgeLoader;

    public WorkflowEngine(List<Agent> agents, KnowledgePrefixLoader knowledgeLoader) {
        this.agents = agents.stream()
                .sorted((a1, a2) -> Integer.compare(
                        extractStageNumber(a1.name()),
                        extractStageNumber(a2.name())))
                .toList();
        this.knowledgeLoader = knowledgeLoader;
    }

    /**
     * Starts the complete multi-agent workflow.
     */
    public WorkflowSessionState execute(String sessionId, String userInput) {
        log.info("[WorkflowEngine] Starting workflow session={} with {} agents", sessionId, agents.size());
        long start = System.currentTimeMillis();

        var state = new WorkflowSessionState(sessionId);

        if (!knowledgeLoader.isEmpty()) {
            log.info("[WorkflowEngine] Shared knowledge prefix loaded: {} bytes", knowledgeLoader.getPrefix().length());
        }

        // Sequential execution of all agents.
        //
        // The handoffs are passed along, NOT read from the state: here
        // the engine knows which agent follows which. An agent that
        // reads from the state would have to rely on ordering — and that is exactly
        // where previous code failed (there was no handoff at all).
        List<SubtaskHandoff> accumulated = new ArrayList<>();

        for (Agent agent : agents) {
            log.info("[WorkflowEngine] === Running {}: {} ===", agent.name(), describeAgent(agent));

            state.setVisibleHandoffs(accumulated);
            log.info("[WorkflowEngine] {} receives handoffs from {}",
                    agent.name(), accumulated.isEmpty() ? "(none — first agent)" : accumulated);

            try {
                agent.execute(userInput, state);
            } catch (Exception e) {
                log.error("[WorkflowEngine] Agent {} failed", agent.name(), e);
                throw new RuntimeException("Agent " + agent.name() + " failed: " + e.getMessage(), e);
            }

            // Only pass on handoffs that were actually submitted. An agent that
            // forgot submit_subtask_summary must not supply the next agent
            // with an empty handoff that looks like a real result.
            SubtaskHandoff own = state.handoffOf(agent.name());
            if (own != null) {
                accumulated.add(own);
            } else {
                log.warn("[WorkflowEngine] {} produced NO handoff — the next agent will not see it",
                        agent.name());
            }
        }

        log.info("[WorkflowEngine] Handoff chain: {}",
                state.allHandoffs().stream().map(h -> h.agent() + "[" + h.status() + "]").toList());

        long durationMs = System.currentTimeMillis() - start;
        log.info("[WorkflowEngine] Workflow completed in {}ms", durationMs);
        log.info("[WorkflowEngine] All results: {}", state.getAllResults().keySet());

        return state;
    }

    /** Short description of what an agent does (for logging) */
    private String describeAgent(Agent agent) {
        String p = agent.prompt().toLowerCase();
        if (p.contains("not use the tools")) return "(prefix, no analysis tools)";
        if (p.contains("must use both tools")) return "(prefix + analysis tools)";
        return "(prefix + synthesis)";
    }

    /** Heuristically extract the stage number from the name for sorting */
    private int extractStageNumber(String name) {
        if (name == null) return 99;
        try {
            String digit = name.replaceAll("\\D+", "");
            return digit.isEmpty() ? 99 : Integer.parseInt(digit);
        } catch (NumberFormatException e) {
            return 99;
        }
    }
}
