package dev.prefix.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests für ToolProvider — zentrale Tool-Registry.
 */
class ToolProviderTest {

    @Test
    void getAllReturnsAllTools() {
        List<ToolSpecification> tools = ToolProvider.getAll();
        // 2 Analyse-Tools + das Handoff-Protokoll. Die Subagent-Werkzeuge liegen
        // bewusst in einem getrennten Satz — sonst kaeme agent1 im alten Workflow
        // an die Subagenten und verbrauchte Tool-Iterationen fuer
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
            assertThat(false).isTrue(); // Sollte nicht erreicht werden
        } catch (UnsupportedOperationException e) {
            // Erwartet — Liste ist unveränderlich
        }
    }

    @Test
    void getAllIsConsistentAcrossCalls() {
        // Die Liste muss bei jedem Aufruf gleich sein (byte-identisch für Cache!)
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
