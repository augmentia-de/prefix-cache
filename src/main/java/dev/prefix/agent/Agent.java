package dev.prefix.agent;

import dev.prefix.state.WorkflowSessionState;

/**
 * Interface for all agents in the workflow.
 * <p>
 * Every agent implements this interface and differs only in:
 * <ul>
 *   <li>{@code #name()} — unique name (e.g. "agent1", "agent2")</li>
 *   <li>{@code #prompt()} — system prompt that decides WHAT THE LLM MAY do</li>
 *   <li>{@code #execute(...)} — agent-specific execution logic</li>
 * </ul>
 * <p>
 * Tool access control happens ONLY through the prompt.
 * The {@link ToolProvider} provides ALL tools uniformly —
 * byte-identical for every agent. Which tool the LLM may actually
 * use is stated solely in this agent's prompt.
 *
 * <h2>Structured handoff</h2>
 * Every agent calls {@link SubtaskHandoff#TOOL_NAME} at the end of its work and
 * deposits the result via {@code WorkflowSessionState.recordHandoff(...)}.
 * The {@code WorkflowEngine} presents the handoffs of the previous agents to the
 * follow-up agent, where they are appended as {@code ToolResultMessage} to the <b>end</b> of the
 * prompt — never into the system block. This position is the
 * cache protection; the JSON schema provides structure and length limiting.
 */
public interface Agent {

    /**
     * @return unique name of the agent (used as a key in WorkflowSessionState)
     */
    String name();

    /**
     * The agent-specific system prompt.
     * <p>
     * This prompt tells the LLM what it should do AND which tools it MAY use.
     * Example: "MAY use analyzeDomain and defineTask" vs "USE NO TOOLS".
     * <p>
     * The {@code AgentRunner} automatically prepends the shared knowledgePrefix BEFORE this prompt.
     * That makes the header byte-identical across all agents.
     */
    String prompt();

    /**
     * Performs the actual work of the agent.
     * <p>
     * Here the AgentRunner is called with the configured parameters.
     * The result is written into the WorkflowSessionState.
     *
     * @param userInput  the text entered by the user
     * @param state      global state of all agent results
     */
    void execute(String userInput, WorkflowSessionState state);
}
