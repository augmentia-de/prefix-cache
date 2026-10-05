package dev.prefix.tool;

/**
 * Runs the actual Java methods when the LLM requests a tool call.
 * <p>
 * The LLM only sends name + JSON arguments. This executor maps them to real methods.
 */
public interface ToolExecutor {

    /**
     * @param toolName  chosen by the LLM (e.g. "analyzeDomain")
     * @param arguments JSON string of the parameters
     * @return result as text — is sent back to the LLM
     */
    String execute(String toolName, String arguments);
}
