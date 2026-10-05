package dev.prefix.state;

import dev.prefix.agent.SubtaskHandoff;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Global workflow state — holds the prefix AND the results of all agents
 * as a generic map. Each agent writes its result into the map under its own name.
 * <p>
 * Compared to quad-core: instead of {@code AgentSessionState} with a fixed schema
 * ({@code findings}, {@code sagaLog}, etc.), everything here is reachable via the flexible
 * {@code agentResults} map. The prefix is kept separately, because it
 * plays a special role in the cache mechanism.
 */
public class WorkflowSessionState {

    private final String sessionId;
    private final Map<String, Object> agentResults = new LinkedHashMap<>();

    /**
     * Structured handoffs per agent, in execution order.
     * <p>
     * An agent records exactly one handoff; a second call (Agent1 makes
     * two runs internally) overwrites the first. This is deliberate: giving two
     * contradictory handoffs to the next agent would be worse
     * than the later one, which belongs to the final agent result.
     */
    private final Map<String, SubtaskHandoff> handoffs = new LinkedHashMap<>();

    /**
     * What the currently running agent gets PRESENTED with as handoffs.
     * Set by the {@link dev.prefix.service.WorkflowEngine} directly before
     * {@code agent.execute(...)}.
     */
    private List<SubtaskHandoff> visibleHandoffs = List.of();

    /** Byte-identical prefix (block A) that Agent3 builds into the LLM context */
    private String cachePrefix;

    public WorkflowSessionState(String sessionId) {
        this.sessionId = sessionId;
    }

    // --- Handoffs between the agents ---

    /**
     * Stores the structured handoff of an agent.
     *
     * @param handoff {@code null} is ignored — an agent that did not call the tool
     *                does not block the next agent.
     */
    public void recordHandoff(SubtaskHandoff handoff) {
        if (handoff != null) {
            handoffs.put(handoff.agent(), handoff);
        }
    }

    /** All handoffs in execution order. */
    public List<SubtaskHandoff> allHandoffs() {
        return List.copyOf(handoffs.values());
    }

    /** Handoff of a specific agent, or {@code null}. */
    public SubtaskHandoff handoffOf(String agentName) {
        return handoffs.get(agentName);
    }

    /** Sets the handoffs visible to the currently running agent. */
    public void setVisibleHandoffs(List<SubtaskHandoff> handoffs) {
        this.visibleHandoffs = handoffs == null ? List.of() : List.copyOf(handoffs);
    }

    /** What the currently running agent may see — mostly empty for the first agent. */
    public List<SubtaskHandoff> visibleHandoffs() {
        return visibleHandoffs;
    }

    public String getSessionId() {
        return sessionId;
    }

    // --- Prefix Access ---

    public String getCachePrefix() {
        return cachePrefix;
    }

    public void setCachePrefix(String cachePrefix) {
        this.cachePrefix = cachePrefix;
    }

    // --- Agent results (generic map) ---

    /**
     * Writes the result of an agent into the global map.
     * Call: state.setAgentResult("agent1", extractedData);
     */
    @SuppressWarnings("unchecked")
    public <T> void setAgentResult(String agentName, T result) {
        agentResults.put(agentName, result);
    }

    /**
     * Reads the result of an agent from the map.
     */
    @SuppressWarnings("unchecked")
    public <T> T getAgentResult(String agentName) {
        return (T) agentResults.get(agentName);
    }

    /**
     * All agent results — unfiltered, but sorted by insertion order.
     */
    public Map<String, Object> getAllResults() {
        return Map.copyOf(agentResults);
    }

    /**
     * Checks whether an agent has written a result.
     */
    public boolean hasResult(String agentName) {
        return agentResults.containsKey(agentName);
    }
}
