package dev.prefix.agent;

/**
 * Hilfsfunktion fuer die strukturierte Agenten-Uebergabe.
 * <p>
 * Bewusst eine eigene Klasse statt dreifach kopiertem Code in den Agents: die
 * Extraktion muss an allen drei Stellen <b>identisch</b> sein, sonst haengt
 * Agent3 plötzlich ein anderes Format an den Verlauf als Agent2 — und genau das
 * waere ein Cache-Buster.
 */
final class HandoffSupport {

    private HandoffSupport() {}

    /**
     * Zieht den Handoff aus den Tool-Calls eines Laufs.
     *
     * @return der Handoff, oder {@code null} wenn der Agent das Werkzeug nicht
     *         aufgerufen hat. Ein fehlender Handoff ist kein Fehler — er darf
     *         nur nicht stillschweigend als leerer Handoff getarnt werden, sonst
     *         haelt der Folgeagent eine leere Liste fuer ein echtes Ergebnis.
     */
    static SubtaskHandoff extract(String agentName, AgentRunner.RunResult result) {
        for (AgentRunner.ToolCall call : result.toolCalls()) {
            if (SubtaskHandoff.TOOL_NAME.equals(call.toolName())) {
                return SubtaskHandoff.from(agentName, call.arguments());
            }
        }
        return null;
    }
}