package dev.prefix.state;

import dev.prefix.agent.SubtaskHandoff;

import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Strukturierte Handoffs pro Agent, in Ausfuehrungsreihenfolge.
     * <p>
     * Ein Agent traegt genau einen Handoff ein; ein zweiter Aufruf (Agent1 macht
     * intern zwei Laeufe) ueberschreibt den ersten. Das ist Absicht: zwei
     * widerspruechliche Handoffs an den Folgeagenten zu geben waere schlechter
     * als der spaetere, der zum finalen Agentenergebnis gehoert.
     */
    private final Map<String, SubtaskHandoff> handoffs = new LinkedHashMap<>();

    /**
     * Was der aktuell laufende Agent als Handoffs VORGELIEGT bekommt.
     * Gesetzt von der {@link dev.prefix.service.WorkflowEngine} direkt vor
     * {@code agent.execute(...)}.
     */
    private List<SubtaskHandoff> visibleHandoffs = List.of();

    /** Byte-identischer Prefix (Block A), der von Agent3 in den LLM-Kontext eingebaut wird */
    private String cachePrefix;

    public WorkflowSessionState(String sessionId) {
        this.sessionId = sessionId;
    }

    // --- Handoffs zwischen den Agenten ---

    /**
     * Hinterlegt den strukturierten Handoff eines Agenten.
     *
     * @param handoff {@code null} wird ignoriert — ein Agent, der das Werkzeug
     *                nicht aufgerufen hat, blockiert den Folgeagenten nicht.
     */
    public void recordHandoff(SubtaskHandoff handoff) {
        if (handoff != null) {
            handoffs.put(handoff.agent(), handoff);
        }
    }

    /** Alle Handoffs in Ausfuehrungsreihenfolge. */
    public List<SubtaskHandoff> allHandoffs() {
        return List.copyOf(handoffs.values());
    }

    /** Handoff eines bestimmten Agenten, oder {@code null}. */
    public SubtaskHandoff handoffOf(String agentName) {
        return handoffs.get(agentName);
    }

    /** Setzt die fuer den aktuell laufenden Agenten sichtbaren Handoffs. */
    public void setVisibleHandoffs(List<SubtaskHandoff> handoffs) {
        this.visibleHandoffs = handoffs == null ? List.of() : List.copyOf(handoffs);
    }

    /** Was der aktuell laufende Agent sehen darf — meist leer beim ersten Agenten. */
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
