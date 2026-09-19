package dev.prefix.state;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Globaler Workflow-Zustand — hält den Prefix UND die Ergebnisse aller Agenten
 * als generische Map. Jeder Agent schreibt sein Ergebnis unter seinem Namen in die Map.
 * <p>
 * Im Vergleich zu quad-core: Statt {@code AgentSessionState} mit festem Schema
 * ({@code findings}, {@code sagaLog}, etc.) ist hier alles über die flexible
 * {@code agentResults}-Map erreichbar. Der Prefix wird separat gehalten, da er
 * eine besondere Rolle im Cache-Mechanismus hat.
 */
public class WorkflowSessionState {

    private final String sessionId;
    private final Map<String, Object> agentResults = new LinkedHashMap<>();

    /** Byte-identischer Prefix (Block A), der von Agent3 in den LLM-Kontext eingebaut wird */
    private String cachePrefix;

    public WorkflowSessionState(String sessionId) {
        this.sessionId = sessionId;
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

    // --- Agent Results (generische Map) ---

    /**
     * Schreibt das Ergebnis eines Agenten in die globale Map.
     * Aufruf: state.setAgentResult("agent1", extractedData);
     */
    @SuppressWarnings("unchecked")
    public <T> void setAgentResult(String agentName, T result) {
        agentResults.put(agentName, result);
    }

    /**
     * Liest das Ergebnis eines Agenten aus der Map.
     */
    @SuppressWarnings("unchecked")
    public <T> T getAgentResult(String agentName) {
        return (T) agentResults.get(agentName);
    }

    /**
     * Alle Agent-Ergebnisse — ungefiltert, aber sortiert nach Einfüg-Reihenfolge.
     */
    public Map<String, Object> getAllResults() {
        return Map.copyOf(agentResults);
    }

    /**
     * Prüft ob ein Agent Ergebnis geschrieben hat.
     */
    public boolean hasResult(String agentName) {
        return agentResults.containsKey(agentName);
    }
}
