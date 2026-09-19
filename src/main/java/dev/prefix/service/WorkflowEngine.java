package dev.prefix.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import dev.prefix.agent.Agent;
import dev.prefix.config.KnowledgePrefixLoader;
import dev.prefix.state.WorkflowSessionState;

import java.util.List;

/**
 * Orchestrator — lädt den shared knowledge prefix und führt alle Agents sequentiell aus.
 * <p>
 * Jeder Agent ist ein {@link Agent}-Interface-Implementierung. Die WorkflowEngine weiß nichts
 * über spezifische Implementierungen — sie ruft nur #name(), #prompt() und #execute() auf.
 * <p>
 * Alle Agents erhalten denselben byte-identischen knowledgePrefix via AgentRunner:
 * <ul>
 *   <li>Agent1: Prefix + beide Tools (analyzeDomain + defineTask)</li>
 *   <li>Agent2: Prefix + keine Tools (nur Prompt-Filterung)</li>
 *   <li>Agent3: Prefix + optionales Tool (defineTask)</li>
 * </ul>
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    /** Liste aller Agents — injiziert via Spring DI, sortiert nach Ausführungsreihenfolge */
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
     * Startet den vollständigen multi-agent workflow.
     */
    public WorkflowSessionState execute(String sessionId, String userInput) {
        log.info("[WorkflowEngine] Starting workflow session={} with {} agents", sessionId, agents.size());
        long start = System.currentTimeMillis();

        var state = new WorkflowSessionState(sessionId);

        if (!knowledgeLoader.isEmpty()) {
            log.info("[WorkflowEngine] Shared knowledge prefix loaded: {} bytes", knowledgeLoader.getPrefix().length());
        }

        // Sequenzielle Ausführung aller Agents
        for (Agent agent : agents) {
            log.info("[WorkflowEngine] === Running {}: {} ===", agent.name(), describeAgent(agent));
            try {
                agent.execute(userInput, state);
            } catch (Exception e) {
                log.error("[WorkflowEngine] Agent {} failed", agent.name(), e);
                throw new RuntimeException("Agent " + agent.name() + " failed: " + e.getMessage(), e);
            }
        }

        long durationMs = System.currentTimeMillis() - start;
        log.info("[WorkflowEngine] Workflow completed in {}ms", durationMs);
        log.info("[WorkflowEngine] All results: {}", state.getAllResults().keySet());

        return state;
    }

    /** Kurze Beschreibung was ein Agent tut (für Logging) */
    private String describeAgent(Agent agent) {
        String p = agent.prompt().toLowerCase();
        if (p.contains("no tools")) return "(prefix only, no tools)";
        if (p.contains("only these") && p.contains("analyze")) return "(prefix + analysis tools)";
        return "(prefix + synthesis)";
    }

    /** Heuristisch Stage-Nummer aus dem Namen extrahieren für Sortierung */
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
