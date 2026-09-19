package dev.prefix.tool;

/**
 * Führt die tatsächlichen Java-Methoden aus, wenn der LLM einen Tool Call anfragt.
 * <p>
 * Der LLM sendet nur Name + JSON-Argumente. Dieser Executor mappt auf echte Methoden.
 */
public interface ToolExecutor {

    /**
     * @param toolName  vom LLM gewählt (z.B. "analyzeDomain")
     * @param arguments JSON-String der Parameter
     * @return Ergebnis als Text — wird an den LLM zurückgesendet
     */
    String execute(String toolName, String arguments);
}
