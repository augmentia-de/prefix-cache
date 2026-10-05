package dev.prefix.agent;

import dev.prefix.tool.ToolExecutor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executor that only allows a terminal tool after the
 * prerequisites have been met.
 *
 * <h2>Why this is needed</h2>
 * Real finding from the full run: after {@code submit_subtask_summary}
 * was made terminal, the model called <b>only</b> the
 * handoff tool and skipped the actual analysis in 3 of 4 answers. The prompt
 * said "exactly once, as the last action" — that prevented repetition, but
 * not the skipping of the work. A prompt cannot reliably prevent an
 * optimization by the model.
 *
 * <p>So it is solved structurally: the call is rejected as long as the
 * prerequisites are missing, and the handoff counts as done only once the
 * call succeeded. {@link AgentRunner} terminates the loop only on a
 * <b>successful</b> handoff.
 *
 * <p>The state lives per thread, not in a singleton field: the
 * tool calls of one workflow run sequentially on one thread, and a
 * shared counter would mix two concurrent runs.
 */
public class GatedToolExecutor implements ToolExecutor {

    private final ToolExecutor delegate;
    private final String terminalTool;
    private final Set<String> prerequisites;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /**
     * @param terminalTool   the tool that terminates the agent
     * @param prerequisites  tools that MUST have been called beforehand;
     *                       empty means "may come immediately"
     */
    public GatedToolExecutor(ToolExecutor delegate, String terminalTool, List<String> prerequisites) {
        this.delegate = delegate;
        this.terminalTool = terminalTool;
        this.prerequisites = new LinkedHashSet<>(prerequisites);
    }

    @Override
    public String execute(String toolName, String arguments) {
        if (terminalTool.equals(toolName)) {
            Set<String> missing = new LinkedHashSet<>(prerequisites);
            missing.removeAll(seen);
            if (!missing.isEmpty()) {
                // Exception, NOT error text as a return value. AgentRunner
                // distinguishes success via the ok flag, which is only set in
                // the exception branch — a returned
                // "[REJECTED]" text would be a SUCCESSFUL call for it
                // and would terminate the agent despite the rejection.
                throw new IllegalStateException(terminalTool + " rejected — you must first use "
                        + missing + ". Do the actual work before submitting your summary.");
            }
        }
        seen.add(toolName);
        return delegate.execute(toolName, arguments);
    }

    /** Only for tests/diagnostics: which tools were actually used. */
    public Set<String> seen() {
        return Set.copyOf(seen);
    }
}