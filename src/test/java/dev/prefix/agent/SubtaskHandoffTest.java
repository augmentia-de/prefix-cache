package dev.prefix.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
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
 * Locks down the structured agent handoff: agent1 → agent2 → agent3.
 *
 * <p>The core is a position, not a format. What is checked is that the handoff
 * migrates to the <b>end</b> of the message list and neither modifies the
 * cacheable prefix nor grows in front of it — because only that way the cache
 * hit is preserved. A JSON schema alone would not achieve that.
 */
class SubtaskHandoffTest {

    private static final String KB = "NOVARIS: founded 847, Mount Thorne 3241 m.";

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

    private static SubtaskHandoff handoff(String agent, String... findings) {
        return new SubtaskHandoff(agent, "success", List.of(findings),
                "continue with step " + agent, "{}");
    }

    @Test
    void handoffIsAppendedAtTheEndNotIntoThePrefix() {
        CapturingChatModel model = new CapturingChatModel();
        AgentRunner runner = new AgentRunner(model, KB);
        ToolExecutor noop = (n, a) -> "r";

        // The SAME agent, once without and once with a predecessor handoff.
        // Only that way can "the handoff does not change the prefix" be checked —
        // a comparison of two different agents would fail on their
        // differing prompts instead of on the handoff logic.
        runner.run("agent2", "P2", "input X", ToolProvider.getAll(), noop, "wf-1");
        runner.run("agent2", "P2", "input X", ToolProvider.getAll(), noop, "wf-1",
                List.of(handoff("agent1", "geography", "Mount Thorne 3241 m")));

        assertEquals(2, model.requests.size());
        ChatRequest withoutPrior = model.requests.get(0);
        ChatRequest withPrior = model.requests.get(1);

        // (1) The system block stays byte-identical — the core of the cache invariant.
        assertEquals(((SystemMessage) withoutPrior.messages().get(0)).text(),
                ((SystemMessage) withPrior.messages().get(0)).text(),
                "handoffs must never touch the system block");

        // The runner appends the model's AiMessage to the conversation history. It
        // does NOT belong to the incoming history and must therefore not be
        // compared — otherwise one compares the handoff with the answer.
        int incoming = withoutPrior.messages().size() - 1;

        // (2) Exactly one message was appended.
        assertEquals(withoutPrior.messages().size(), withPrior.messages().size() - 1,
                "exactly one message is appended");

        // (3) All incoming messages are unchanged: the cacheable
        //     prefix does not grow, it stays byte-identical.
        for (int i = 0; i < incoming; i++) {
            assertEquals(withoutPrior.messages().get(i), withPrior.messages().get(i),
                    "incoming message " + i + " must be untouched by the handoff");
        }

        // (4) The handoff is at the end of the incoming history. The last
        //     outgoing message is the model's AiMessage — that the runner
        //     cannot control, the handoff can.
        ChatMessage lastInitial = withPrior.messages().get(withPrior.messages().size() - 2);
        assertInstanceOf(UserMessage.class, lastInitial,
                "the handoff is transported as a UserMessage, not as an orphan role=tool");
        assertInstanceOf(AiMessage.class,
                withPrior.messages().get(withPrior.messages().size() - 1),
                "the model's own reply is appended after the handoff");

        UserMessage handoffMsg = (UserMessage) lastInitial;
        assertTrue(handoffMsg.singleText().contains("Mount Thorne 3241 m"));
        assertTrue(handoffMsg.singleText().startsWith("## HANDOFF FROM agent1"),
                "the envelope must be stable: " + handoffMsg.singleText());
    }

    @Test
    void handoffPayloadIsByteDeterministic() {
        // Flickering key order would itself be a cache buster: the
        // handoff is part of the serialized messages.
        String a = handoff("agent1", "x", "y").toToolResult();
        String b = handoff("agent1", "x", "y").toToolResult();
        assertEquals(a, b);

        // The order of the findings is part of the content and is preserved.
        assertNotEquals(handoff("agent1", "y", "x").toToolResult(), a,
                "finding order is content, not formatting");
    }

    @Test
    void handoffSchemaIsFixedAndOrdered() {
        String json = handoff("agent1", "fact one", "fact two").toToolResult();
        assertTrue(json.startsWith("{\"status\":"), "status must come first: " + json);
        assertTrue(json.indexOf("\"status\"") < json.indexOf("\"key_findings\""));
        assertTrue(json.indexOf("\"key_findings\"") < json.indexOf("\"next_action_recommendation\""));
        assertTrue(json.contains("fact one") && json.contains("fact two"));
    }

    @Test
    void toolCallIdIsStablePerAgent() {
        // Random IDs would falsify the prefix on every run.
        assertEquals(SubtaskHandoff.toolCallId("agent1"), SubtaskHandoff.toolCallId("agent1"));
        assertNotEquals(SubtaskHandoff.toolCallId("agent1"), SubtaskHandoff.toolCallId("agent2"));
    }

    @Test
    void parsingIsTolerantOfPartialToolArguments() {
        // A model that omits a field must not abort the workflow —
        // but the handoff must still serialize schema-conform.
        SubtaskHandoff partial = SubtaskHandoff.from("agent1",
                "{\"status\":\"partial\",\"key_findings\":[\"only one\"]}");
        assertEquals("partial", partial.status());
        assertEquals(List.of("only one"), partial.keyFindings());
        assertEquals("", partial.nextActionRecommendation());
        assertTrue(partial.toToolResult().contains("next_action_recommendation"));
    }

    @Test
    void multipleHandoffsAppearInOrder() {
        CapturingChatModel model = new CapturingChatModel();
        AgentRunner runner = new AgentRunner(model, KB);
        ToolExecutor noop = (n, a) -> "r";

        runner.run("agent3", "P3", "input X", ToolProvider.getAll(), noop, "wf-1",
                List.of(handoff("agent1", "a1"), handoff("agent2", "a2")));

        // system + 2 user + 2 handoffs + the model's AiMessage
        List<ChatMessage> msgs = model.requests.get(0).messages();
        assertEquals(6, msgs.size(), "system + 2 user + 2 handoffs + model reply");

        assertTrue(((UserMessage) msgs.get(3)).singleText().contains("HANDOFF FROM agent1"));
        assertTrue(((UserMessage) msgs.get(4)).singleText().contains("HANDOFF FROM agent2"));
    }

    @Test
    void handoffIsNotOfferedToTheSubagentDemo() {
        // The subagent demo has its own tool list. If the
        // handoff tool were visible there too, a subagent could hand in
        // a handoff that nobody accepts.
        var subTools = ToolProvider.getSubAgentTools().stream()
                .map(ToolSpecification::name).toList();
        assertFalse(subTools.contains(SubtaskHandoff.TOOL_NAME),
                "the handoff protocol belongs to the sequential workflow only");

        var analysisTools = ToolProvider.getAll().stream()
                .map(ToolSpecification::name).toList();
        assertTrue(analysisTools.contains(SubtaskHandoff.TOOL_NAME),
                "all three sequential agents must see the handoff tool — same byte-identical list");
        assertEquals(3, analysisTools.size());
    }

    /**
     * Regression test for an actually observed 400.
     * <p>
     * With {@code role: "tool"} the openCode Zen gateway aborted with
     * {@code 400 invalid_request}, while OpenRouter/DeepSeek accepted the same
     * message — orphaned tool messages are schema-invalid and
     * providers disagree. Here we prevent them from coming back.
     */
    @Test
    void handoffIsNeverSentAsAnOrphanToolMessage() {
        CapturingChatModel model = new CapturingChatModel();
        AgentRunner runner = new AgentRunner(model, KB);
        ToolExecutor noop = (n, a) -> "r";

        runner.run("agent2", "P2", "input X", ToolProvider.getAll(), noop, "wf-1",
                List.of(handoff("agent1", "fact")));

        for (ChatMessage m : model.requests.get(0).messages()) {
            assertFalse(m instanceof ToolExecutionResultMessage,
                    "a handoff from a prior agent must not be a role=tool message — "
                            + "there is no preceding assistant tool_call to answer");
        }
    }

    @Test
    void handoffDoesNotLeakIntoTheUserMessageOfThePriorAgents() {
        CapturingChatModel model = new CapturingChatModel();
        AgentRunner runner = new AgentRunner(model, KB);
        ToolExecutor noop = (n, a) -> "r";

        runner.run("agent2", "P2", "input X", ToolProvider.getAll(), noop, "wf-1",
                List.of(handoff("agent1", "some finding")));

        boolean found = false;
        for (ChatMessage m : model.requests.get(0).messages()) {
            if (m instanceof UserMessage u && u.singleText().contains("HANDOFF FROM")) {
                found = true;
            }
            if (m instanceof UserMessage u && u.singleText().startsWith("P2")) {
                assertFalse(u.singleText().contains("some finding"),
                        "the handoff must not be merged into the agent's own prompt");
            }
        }
        assertTrue(found, "the handoff must be present as its own message");
    }

    /**
     * Regression test for an actually observed error.
     * <p>
     * submit_subtask_summary terminates the agent. Without this rule the model
     * called the tool five times in a row — the prompt said "exactly ONCE",
     * but a tool result with a JSON echo is not a stop signal, and only the runner
     * can break the loop. Consequence: 22 requests instead of 6.
     */
    @Test
    void handoffCallTerminatesTheReactLoop() {
        var req = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("c1").name(SubtaskHandoff.TOOL_NAME)
                .arguments("{\"status\":\"success\",\"key_findings\":[\"a\"],"
                        + "\"next_action_recommendation\":\"next\"}").build();

        // ALWAYS returns a tool call. Without a break the runner
        // would run through to MAX_TOOL_CALLS with it.
        ChatModel alwaysCallsTool = new ChatModel() {
            int calls = 0;

            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                calls++;
                return ChatResponse.builder().aiMessage(new AiMessage("", List.of(req))).build();
            }

            @Override
            public OpenAiChatRequestParameters defaultRequestParameters() {
                return OpenAiChatRequestParameters.EMPTY;
            }
        };

        AgentRunner runner = new AgentRunner(alwaysCallsTool, KB);
        AgentRunner.RunResult result = runner.run("agent1", "P1", "input X",
                ToolProvider.getAll(), (n, a) -> "{}", "wf-1");

        assertEquals(1, result.requests(),
                "the handoff must end the turn after a single request, not run to MAX_TOOL_CALLS");
        assertEquals(1, result.toolCalls().size(),
                "exactly one handoff call, not one per iteration");
    }

    /** Without a handoff the loop may keep running — the break is specific. */
    @Test
    void otherToolCallsDoNotTerminateTheLoop() {
        var req = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("c1").name("defineTask").arguments("{\"input\":\"x\"}").build();

        ChatModel callsOnce = new ChatModel() {
            int calls = 0;

            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                return ChatResponse.builder().aiMessage(
                        calls++ == 0 ? new AiMessage("", List.of(req)) : AiMessage.from("done")).build();
            }

            @Override
            public OpenAiChatRequestParameters defaultRequestParameters() {
                return OpenAiChatRequestParameters.EMPTY;
            }
        };

        AgentRunner.RunResult result = new AgentRunner(callsOnce, KB)
                .run("agent1", "P1", "input X", ToolProvider.getAll(), (n, a) -> "{}", "wf-1");

        assertEquals(2, result.requests(), "a normal tool call must not end the loop");
        assertEquals("done", result.text());
    }

    /**
     * Regression test: the handoff must not cut short the actual work.
     * <p>
     * Actual finding: 3 of 4 answers called ONLY submit_subtask_summary and
     * skipped analyzeDomain/defineTask. The gate rejects the call as long as
     * the prerequisites are missing, and AgentRunner terminates only on SUCCESS.
     */
    @Test
    void prematureHandoffIsRejectedAndDoesNotEndTheLoop() {
        var handoffReq = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("h1").name(SubtaskHandoff.TOOL_NAME)
                .arguments("{\"status\":\"success\",\"key_findings\":[\"a\"],"
                        + "\"next_action_recommendation\":\"n\"}").build();
        var analyzeReq = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("t1").name("analyzeDomain").arguments("{\"input\":\"x\"}").build();
        var taskReq = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("t2").name("defineTask").arguments("{\"input\":\"x\"}").build();

        ChatModel model = new ChatModel() {
            int calls = 0;

            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                // Turn 1: wants to hand in immediately (too early -> rejected)
                // Turn 2: does BOTH analyses and hands in afterwards
                return ChatResponse.builder().aiMessage(switch (calls++) {
                    case 0 -> new AiMessage("", List.of(handoffReq));
                    default -> new AiMessage("", List.of(analyzeReq, taskReq, handoffReq));
                }).build();
            }

            @Override
            public OpenAiChatRequestParameters defaultRequestParameters() {
                return OpenAiChatRequestParameters.EMPTY;
            }
        };

        ToolExecutor gated = new GatedToolExecutor((n, a) -> "result",
                SubtaskHandoff.TOOL_NAME, List.of("analyzeDomain", "defineTask"));

        AgentRunner.RunResult result = new AgentRunner(model, KB).run(
                "agent1", "P1", "input X", ToolProvider.getAll(), gated, "wf-1");

        // The premature handoff must not end the run -> it kept going.
        assertTrue(result.requests() > 1,
                "a rejected handoff must not terminate the loop");
        // As an exception, so that AgentRunner sets ok=false; the model sees
        // the rationale in the tool result.
        assertFalse(result.toolCalls().get(0).success(),
                "the premature handoff must be recorded as failed");
        assertTrue(result.toolCalls().get(0).result().contains("rejected"),
                "the model needs to learn why: " + result.toolCalls().get(0).result());

        // The late handoff must actually go through and end the run.
        assertEquals(2, result.requests(),
                "turn 1 rejected, turn 2 does the analysis and terminates");
        boolean accepted = result.toolCalls().stream()
                .anyMatch(c -> SubtaskHandoff.TOOL_NAME.equals(c.toolName()) && c.success());
        assertTrue(accepted, "the later handoff, after the analysis, must be accepted");
        assertEquals(2, result.toolCalls().stream()
                        .filter(c -> SubtaskHandoff.TOOL_NAME.equals(c.toolName())).count(),
                "two handoff attempts, exactly one accepted");
    }

    @Test
    void gateAllowsImmediateHandoffWhenThereAreNoPrerequisites() {
        ToolExecutor gated = new GatedToolExecutor((n, a) -> "result",
                SubtaskHandoff.TOOL_NAME, List.of());
        assertEquals("result", gated.execute(SubtaskHandoff.TOOL_NAME, "{}"),
                "agent2 and agent3 have no analysis prerequisites");
    }

    @Test
    void gateThrowsUntilAllPrerequisitesAreUsed() {
        GatedToolExecutor gated = new GatedToolExecutor((n, a) -> "result",
                SubtaskHandoff.TOOL_NAME, List.of("analyzeDomain", "defineTask"));

        assertThrows(IllegalStateException.class, () -> gated.execute(SubtaskHandoff.TOOL_NAME, "{}"));
        gated.execute("analyzeDomain", "{}");
        assertThrows(IllegalStateException.class, () -> gated.execute(SubtaskHandoff.TOOL_NAME, "{}"),
                "one of two prerequisites is still missing");
        gated.execute("defineTask", "{}");
        assertEquals("result", gated.execute(SubtaskHandoff.TOOL_NAME, "{}"),
                "once the prerequisites are met the handoff goes through");
    }
}
