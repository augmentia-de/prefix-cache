package dev.prefix.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ToolProvider — central tool registry.
 */
class ToolProviderTest {

    @Test
    void getAllReturnsAllTools() {
        List<ToolSpecification> tools = ToolProvider.getAll();
        // 2 analysis tools + the handoff protocol. The subagent tools deliberately
        // live in a separate set — otherwise agent1 in the old workflow would
        // reach the subagents and burn tool iterations on
        // "[ERROR] Unknown tool".
        assertThat(tools).hasSize(3);

        assertThat(tools.get(0).name()).isEqualTo("analyzeDomain");
        assertThat(tools.get(1).name()).isEqualTo("defineTask");
        assertThat(tools.get(2).name()).isEqualTo("submit_subtask_summary");
    }

    @Test
    void subAgentToolSetIsSeparateAndComplete() {
        List<ToolSpecification> tools = ToolProvider.getSubAgentTools();
        assertThat(tools).hasSize(3);
        assertThat(tools).extracting(ToolSpecification::name)
                .containsExactly("lookupEvidence", "crossCheck", "renderSummary");
    }

    @Test
    void getAllReturnsUnmodifiableList() {
        List<ToolSpecification> tools = ToolProvider.getAll();
        try {
            tools.add(null);
            assertThat(false).isTrue(); // Should not be reached
        } catch (UnsupportedOperationException e) {
            // Expected — the list is immutable
        }
    }

    @Test
    void getAllIsConsistentAcrossCalls() {
        // The list must be the same on every call (byte-identical for the cache!)
        List<ToolSpecification> first = ToolProvider.getAll();
        List<ToolSpecification> second = ToolProvider.getAll();

        assertThat(first).hasSize(second.size());
        for (int i = 0; i < first.size(); i++) {
            assertThat(first.get(i).name()).isEqualTo(second.get(i).name());
            assertThat(first.get(i).description()).isEqualTo(second.get(i).description());
        }
    }

    @Test
    void hasToolReturnsCorrectValues() {
        assertThat(ToolProvider.hasTool("analyzeDomain")).isTrue();
        assertThat(ToolProvider.hasTool("defineTask")).isTrue();
        assertThat(ToolProvider.hasTool("lookupEvidence")).isTrue();
        assertThat(ToolProvider.hasTool("crossCheck")).isTrue();
        assertThat(ToolProvider.hasTool("renderSummary")).isTrue();
        assertThat(ToolProvider.hasTool("nonExistent")).isFalse();
    }
}
