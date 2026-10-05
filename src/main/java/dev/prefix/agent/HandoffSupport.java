package dev.prefix.agent;

/**
 * Helper for the structured agent handoff.
 * <p>
 * Deliberately its own class instead of triply copied code in the agents: the
 * extraction must be <b>identical</b> in all three places, otherwise Agent3
 * suddenly appends a different format to the conversation history than Agent2 —
 * and exactly that would be a cache buster.
 */
final class HandoffSupport {

    private HandoffSupport() {}

    /**
     * Extracts the handoff from the tool calls of one run.
     *
     * @return the handoff, or {@code null} if the agent did not call the tool.
     *         A missing handoff is not an error — it just
     *         must not be silently disguised as an empty handoff, otherwise
     *         the follow-up agent holds an empty list for a real result.
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