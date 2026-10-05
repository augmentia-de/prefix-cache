package dev.prefix.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.prefix.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks down the context policy of the subagent topology:
 * 1 orchestrator, 3 subagents via tools, 2 of them with a base context.
 * <p>
 * What is checked is not tool functionality but <b>where the context
 * lands</b> — because the cache effect depends precisely on that:
 *
 * <ul>
 *   <li>KB subagent and orchestrator share a byte-identical system block
 *       (the same cache block),</li>
 *   <li>the KB subagent carries NO knowledge base in its user message
 *       (otherwise it would be paid for twice — once in the prefix, once in the suffix),</li>
 *   <li>the bare subagent does not get the prefix at all and therefore stays
 *       below the cache threshold,</li>
 *   <li>in args mode the knowledge base moves into the user message — that is
 *       the negative control and must not accidentally be optimized away again here.</li>
 * </ul>
 *
 * The prefix itself is a placeholder with a distinguishable marker
 * ("NOVARIS"); what matters is whether the strings are equal or unequal,
 * not their content.
 */
class SubAgentPrefixPolicyTest {

    private static final String KB = "NOVARIS knowledge base: founded 847, Mount Thorne 3241 m.";

    static final class CapturingChatModel implements ChatModel {
        final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            requests.add(chatRequest);
            return ChatResponse.builder().aiMessage(AiMessage.from("done")).build();
        }

        @Override
        public OpenAiChatRequestParameters defaultRequestParameters() {
            return OpenAiChatRequestParameters.EMPTY;
        }
    }

    private static String systemOf(ChatRequest request) {
        ChatMessage first = request.messages().get(0);
        assertInstanceOf(SystemMessage.class, first, "first message must be a SystemMessage");
        return ((SystemMessage) first).text();
    }

    private static String joinedUserText(ChatRequest request) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : request.messages()) {
            if (m instanceof UserMessage u) {
                sb.append(u.singleText()).append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    void bareRunnerIsDerivedAndCarriesNoKnowledgeBase() {
        CapturingChatModel model = new CapturingChatModel();
        AgentRunner kb = new AgentRunner(model, KB);
        AgentRunner bare = kb.withoutKnowledgeBase();

        assertTrue(kb.hasKnowledgeBase(), "the standard runner must have the knowledge base");
        assertFalse(bare.hasKnowledgeBase(), "the derived runner must not");

        // Shared ChatModel, own system block — and both stable.
        ToolExecutor noop = (n, a) -> "r";
        kb.run("orchestrator", "P", "X", ToolProvider.getSubAgentTools(), noop, null);
        bare.run("subagent:renderSummary", "P", "X", ToolProvider.getSubAgentTools(), noop, null);
        bare.run("subagent:renderSummary", "OTHER", "Y", ToolProvider.getSubAgentTools(), noop, null);

        assertEquals(3, model.requests.size());
        assertEquals(systemOf(model.requests.get(1)), systemOf(model.requests.get(2)),
                "derived runner must still be byte-identical across turns");
        assertNotEquals(systemOf(model.requests.get(0)), systemOf(model.requests.get(1)),
                "the two runners must produce different blocks, otherwise the KB is shared after all");
    }

    @Test
    void knowledgeSubagentSharesTheByteIdenticalPrefix() {
        CapturingChatModel model = new CapturingChatModel();
        AgentRunner orchestrator = new AgentRunner(model, KB);
        AgentRunner kbSubagent = new AgentRunner(model, KB);
        ToolExecutor noop = (n, a) -> "r";

        orchestrator.run("orchestrator", "ORCH_PROMPT", "input X", ToolProvider.getSubAgentTools(), noop, null);
        kbSubagent.run("subagent:lookupEvidence", "SUB_PROMPT", "input X", ToolProvider.getSubAgentTools(), noop, null);

        assertEquals(2, model.requests.size());
        assertEquals(systemOf(model.requests.get(0)), systemOf(model.requests.get(1)),
                "KB subagent must produce the byte-identical system block — that is the shared cache block");
        assertTrue(systemOf(model.requests.get(0)).contains("NOVARIS"));
    }

    @Test
    void bareSubagentGetsNoKnowledgeBase() {
        CapturingChatModel model = new CapturingChatModel();
        AgentRunner bare = new AgentRunner(model, null);
        ToolExecutor noop = (n, a) -> "r";

        bare.run("subagent:renderSummary", "SUB_PROMPT", "input X", ToolProvider.getSubAgentTools(), noop, null);

        String system = systemOf(model.requests.get(0));
        assertFalse(system.contains("NOVARIS"),
                "bare subagent must not receive the knowledge base — it would pay ~1k tokens for data it cannot use");
        assertEquals(AgentRunner.NO_KNOWLEDGE_SYSTEM, system);
    }

    @Test
    void bareSubagentStaysByteIdenticalAcrossTurns() {
        CapturingChatModel model = new CapturingChatModel();
        AgentRunner bare = new AgentRunner(model, null);
        ToolExecutor noop = (n, a) -> "r";

        bare.run("subagent:renderSummary", "SUB_PROMPT", "input X", ToolProvider.getSubAgentTools(), noop, null);
        bare.run("subagent:renderSummary", "OTHER_PROMPT", "input Y", ToolProvider.getSubAgentTools(), noop, null);

        assertEquals(systemOf(model.requests.get(0)), systemOf(model.requests.get(1)),
                "even the bare system block must be byte-identical across turns");
    }

    @Test
    void knowledgeBaseNeverAppearsInTheUserMessageInPrefixMode() {
        CapturingChatModel model = new CapturingChatModel();
        AgentRunner kbSubagent = new AgentRunner(model, KB);
        ToolExecutor noop = (n, a) -> "r";

        kbSubagent.run("subagent:lookupEvidence", "SUB_PROMPT", "input X", ToolProvider.getSubAgentTools(), noop, null);

        assertFalse(joinedUserText(model.requests.get(0)).contains("NOVARIS"),
                "the knowledge base lives in the prefix; repeating it in the dynamic tail pays for it twice");
    }

    @Test
    void subagentToolContractIsComplete() {
        // renderSummary is the only subagent WITHOUT a knowledge base — instead it
        // gets the findings of the others as a mandatory argument. If this
        // argument is missing, the orchestrator cannot teach it anything and the
        // subagent would have neither KB nor input: a silent total failure.
        var subTools = ToolProvider.getSubAgentTools();
        var render = subTools.stream()
                .filter(t -> t.name().equals("renderSummary")).findFirst()
                .orElseThrow(() -> new AssertionError("renderSummary tool missing"));

        var params = dev.langchain4j.internal.JsonSchemaElementUtils.toMap(render.parameters());
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) params.get("required");
        assertTrue(required.contains("input"), "renderSummary needs the original request");
        assertTrue(required.contains("findings"), "renderSummary needs the findings it is supposed to rewrite");

        assertTrue(ToolProvider.hasTool("lookupEvidence"), "KB subagent 1 missing");
        assertTrue(ToolProvider.hasTool("crossCheck"), "KB subagent 2 missing");
        assertTrue(ToolProvider.hasTool("renderSummary"), "no-KB subagent missing");
        assertEquals(3, subTools.size());
    }

    /**
     * Regression test for an actually observed error.
     * <p>
     * As long as access control runs exclusively through the prompt, any agent can
     * call any tool in its list. After the subagent tools were added to the shared
     * registry, agent1 called {@code lookupEvidence} in the old 3-agent
     * workflow and got {@code [ERROR] Unknown tool} back — two of five permitted
     * tool iterations wasted on an error. That is why the two sets are kept separate.
     */
    @Test
    void subagentToolsAreNotVisibleToTheAnalysisWorkflow() {
        var analysis = ToolProvider.getAll().stream().map(ToolSpecification::name).toList();
        assertFalse(analysis.contains("lookupEvidence"),
                "agent1/2/3 must NOT see the subagent tools — prompt-only gating does not protect");
        assertFalse(analysis.contains("crossCheck"));
        assertFalse(analysis.contains("renderSummary"));

        var sub = ToolProvider.getSubAgentTools().stream().map(ToolSpecification::name).toList();
        assertFalse(sub.contains("analyzeDomain"),
                "the subagent demo does not need the analysis tools");
        assertFalse(sub.contains("defineTask"));
    }

    /** Byte-identity holds per set, not just overall. */
    @Test
    void eachToolSetIsStableAcrossCalls() {
        assertEquals(ToolProvider.getAll(), ToolProvider.getAll());
        assertEquals(ToolProvider.getSubAgentTools(), ToolProvider.getSubAgentTools());
    }

    @Test
    void nestingBeyondOneSubagentLevelIsRefused() {
        // Model that emits a tool call on the first `toolCallTurns` invocations
        // and then returns a finished answer. Without the emitted call the deeper
        // level's executor would never run and the guard would never be triggered.
        //
        // Expected call chain (2 dispatching levels needed so the third
        // one is reached):
        //   call 0: orchestrator (depth 1) -> tool call -> level2
        //   call 1: sub:sub       (depth 2) -> tool call -> level3
        //   level3: wants to run() at depth 3 -> MUST be refused
        final int toolCallTurns = 2;
        var req = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("call-1").name("lookupEvidence").arguments("{\"input\":\"X\"}").build();
        ChatModel emitter = new ChatModel() {
            int calls = 0;

            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                return ChatResponse.builder().aiMessage(
                        calls++ < toolCallTurns
                                ? new AiMessage("delegating", List.of(req))
                                : AiMessage.from("done")).build();
            }

            @Override
            public OpenAiChatRequestParameters defaultRequestParameters() {
                return OpenAiChatRequestParameters.EMPTY;
            }
        };

AgentRunner outer = new AgentRunner(emitter, KB);

        // Three levels. The orchestrator runs at depth 1, its subagent at 2,
        // and a subagent that wants to dispatch in turn needs depth 3 —
        // exactly that is what the guard must stop.
        //
        // The refusal is swallowed one level deeper by AgentRunner as a
        // tool error and is hard to see from outside. Therefore it is
        // recorded directly here instead of checking an end text.
        var level3Ran = new java.util.concurrent.atomic.AtomicBoolean(false);
        var refusal = new java.util.concurrent.atomic.AtomicReference<String>();

        ToolExecutor level3 = (n, a) -> {
            try {
                outer.run("sub:sub:sub", "P", "X", ToolProvider.getSubAgentTools(), (n2, a2) -> "never", null);
                level3Ran.set(true);
                return "level3 ran";
            } catch (IllegalStateException e) {
                refusal.set(e.getMessage());
                return "refused";
            }
        };
        ToolExecutor level2 = (n, a) -> {
            outer.run("sub:sub", "P", "X", ToolProvider.getSubAgentTools(), level3, null);
            return "level2 ran";
        };

        AgentRunner.RunResult result = outer.run("orchestrator", "ORCH_PROMPT", "input X",
                ToolProvider.getSubAgentTools(), level2, null);

        assertFalse(level3Ran.get(),
                "a subagent must NOT be able to dispatch a further subagent");
        assertNotNull(refusal.get(), "the third level must be refused with an IllegalStateException");
        assertTrue(refusal.get().contains("nesting"), "unexpected refusal message: " + refusal.get());

        // Two levels are allowed and must actually be run through.
        assertEquals(1, result.toolCalls().size());
        assertTrue(result.toolCalls().get(0).success(),
                "level 2 is a legal subagent level and must succeed");

        // The depth counter must be reset after the error,
        // otherwise it blocks the next request on the same thread.
        assertDoesNotThrow(() -> outer.run("orchestrator", "ORCH_PROMPT", "input X",
                        ToolProvider.getSubAgentTools(), (n, a) -> "r", null),
                "nesting depth must be released in a finally block");
    }
}