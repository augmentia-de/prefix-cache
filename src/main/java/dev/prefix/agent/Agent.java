package dev.prefix.agent;

import dev.prefix.state.WorkflowSessionState;

/**
 * Interface für alle Agenten im Workflow.
 * <p>
 * Jeder Agent implementiert diese Schnittstelle und unterscheidet sich nur durch:
 * <ul>
 *   <li>{@code #name()} — eindeutiger Name (z.B. "agent1", "agent2")</li>
 *   <li>{@code #prompt()} — SystemPrompt der entscheidet WAS DER LLM tun darf</li>
 *   <li>{@code #execute(...)} — agentspezifischer Execution-Logik</li>
 * </ul>
 * <p>
 * Die Tool-Zugriffskontrolle erfolgt NUR durch den Prompt.
 * Der {@link ToolProvider} stellt ALLE Tools einheitlich zur Verfügung —
 * byte-identisch für jeden Agent. Welches Tool DER LLM tatsächlich
 * verwenden darf, steht allein im Prompt dieses Agents.
 */
public interface Agent {

    /**
     * @return eindeutiger Name des Agents (wird als Key in WorkflowSessionState verwendet)
     */
    String name();

    /**
     * Der agentspezifische SystemPrompt.
     * <p>
     * Dieser Prompt sagt dem LLM was es tun soll UND welche Tools es verwenden DARF.
     * Beispiel: "DARF analyzeDomain und defineTask nutzen" vs "KEINE TOOLS verwenden".
     * <p>
     * Der {@code AgentRunner} prepends automatisch den shared knowledgePrefix VOR diesem Prompt.
     * Dadurch ist der Header byte-identisch über alle Agents hinweg.
     */
    String prompt();

    /**
     * Führt die eigentliche Arbeit des Agents aus.
     * <p>
     * Hier wird der AgentRunner mit den konfigurierten Parametern aufgerufen.
     * Das Ergebnis wird in den WorkflowSessionState geschrieben.
     *
     * @param userInput  der vom User eingegebene Text
     * @param state      globaler Zustand aller Agent-Ergebnisse
     */
    void execute(String userInput, WorkflowSessionState state);
}
