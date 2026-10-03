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
 * Sichert die strukturierte Agenten-Übergabe ab: agent1 → agent2 → agent3.
 *
 * <p>Der Kern ist eine Position, kein Format. Geprüft wird, dass der Handoff
 * ans <b>Ende</b> der Message-Liste wandert und den cachebaren Prefix weder
 * verändert noch davor wächst — denn nur so bleibt der Cache-Hit erhalten.
 * Ein JSON-Schema allein würde das nicht leisten.
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

        // DERSELBE Agent, einmal ohne und einmal mit Vorgänger-Handoff.
        // Nur so laesst sich "der Handoff veraendert den Prefix nicht" pruefen —
        // ein Vergleich zweier verschiedener Agents wuerde an deren
        // unterschiedlichen Prompts scheitern statt an der Handoff-Logik.
        runner.run("agent2", "P2", "input X", ToolProvider.getAll(), noop, "wf-1");
        runner.run("agent2", "P2", "input X", ToolProvider.getAll(), noop, "wf-1",
                List.of(handoff("agent1", "geography", "Mount Thorne 3241 m")));

        assertEquals(2, model.requests.size());
        ChatRequest withoutPrior = model.requests.get(0);
        ChatRequest withPrior = model.requests.get(1);

        // (1) Der System-Block bleibt byte-identisch — Kern der Cache-Invariante.
        assertEquals(((SystemMessage) withoutPrior.messages().get(0)).text(),
                ((SystemMessage) withPrior.messages().get(0)).text(),
                "handoffs must never touch the system block");

        // Der Runner haengt die AiMessage des Modells an den Verlauf an. Sie
        // gehoert NICHT zur eingehenden History und darf deshalb nicht mit
        // verglichen werden — sonst vergleicht man den Handoff mit der Antwort.
        int incoming = withoutPrior.messages().size() - 1;

        // (2) Genau eine Nachricht wurde angehaengt.
        assertEquals(withoutPrior.messages().size(), withPrior.messages().size() - 1,
                "exactly one message is appended");

        // (3) Alle eingehenden Nachrichten sind unveraendert: der cachebare
        //     Prefix waechst nicht, er bleibt byte-identisch.
        for (int i = 0; i < incoming; i++) {
            assertEquals(withoutPrior.messages().get(i), withPrior.messages().get(i),
                    "incoming message " + i + " must be untouched by the handoff");
        }

        // (4) Der Handoff steht am Ende der eingehenden History. Die letzte
        //     Nachricht der ausgehenden ist die AiMessage des Modells — die kann
        //     der Runner nicht kontrollieren, der Handoff schon.
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
        // Flimmernde Schluesselreihenfolge waere selbst ein Cache-Buster: der
        // Handoff ist Teil der serialisierten Messages.
        String a = handoff("agent1", "x", "y").toToolResult();
        String b = handoff("agent1", "x", "y").toToolResult();
        assertEquals(a, b);

        // Reihenfolge der Findings ist Teil des Inhalts und bleibt erhalten.
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
        // Zufaellige IDs wuerden den Prefix bei jedem Lauf verfaelschen.
        assertEquals(SubtaskHandoff.toolCallId("agent1"), SubtaskHandoff.toolCallId("agent1"));
        assertNotEquals(SubtaskHandoff.toolCallId("agent1"), SubtaskHandoff.toolCallId("agent2"));
    }

    @Test
    void parsingIsTolerantOfPartialToolArguments() {
        // Ein Modell, das ein Feld weglässt, darf den Workflow nicht abbrechen —
        // aber der Handoff muss trotzdem schema-konform serialisieren.
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

        // system + 2 user + 2 handoffs + die AiMessage des Modells
        List<ChatMessage> msgs = model.requests.get(0).messages();
        assertEquals(6, msgs.size(), "system + 2 user + 2 handoffs + model reply");

        assertTrue(((UserMessage) msgs.get(3)).singleText().contains("HANDOFF FROM agent1"));
        assertTrue(((UserMessage) msgs.get(4)).singleText().contains("HANDOFF FROM agent2"));
    }

    @Test
    void handoffIsNotOfferedToTheSubagentDemo() {
        // Die Subagent-Demo hat einen eigenen Werkzeug-Satz. Waere das
        // Handoff-Werkzeug auch dort sichtbar, koennte ein Subagent einen
        // Handoff abgeben, den niemand entgegennimmt.
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
     * Regressionstest fuer einen real beobachteten 400er.
     * <p>
     * Mit {@code role: "tool"} brach der openCode-Zen-Gateway mit
     * {@code 400 invalid_request} ab, waehrend OpenRouter/DeepSeek dieselbe
     * Nachricht akzeptierte — verwaiste Tool-Nachrichten sind schema-widrig und
     * Provider sind sich uneinig. Hier wird verhindert, dass sie zurueckkommen.
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
     * Regressionstest fuer einen real beobachteten Fehler.
     * <p>
     * submit_subtask_summary beendet den Agenten. Ohne diese Regel rief das Modell
     * das Werkzeug fuenfmal hintereinander auf — der Prompt sagte "exactly ONCE",
     * aber ein Tool-Result mit JSON-Echo ist kein Stoppsignal, und nur der Runner
     * kann die Schleife abbrechen. Folge: 22 Requests statt 6.
     */
    @Test
    void handoffCallTerminatesTheReactLoop() {
        var req = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("c1").name(SubtaskHandoff.TOOL_NAME)
                .arguments("{\"status\":\"success\",\"key_findings\":[\"a\"],"
                        + "\"next_action_recommendation\":\"next\"}").build();

        // Gibt IMMER einen Tool-Call zurueck. Ohne Abbruch wuerde der Runner
        // damit bis MAX_TOOL_CALLS durchlaufen.
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

    /** Ohne Handoff darf die Schleife weiterlaufen — der Abbruch ist spezifisch. */
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
     * Regressionstest: der Handoff darf die eigentliche Arbeit nicht abkuerzen.
     * <p>
     * Realer Befund: 3 von 4 Antworten riefen NUR submit_subtask_summary auf und
     * uebersprangen analyzeDomain/defineTask. Das Gate lehnt den Aufruf ab, solange
     * die Voraussetzungen fehlen, und AgentRunner beendet nur bei ERFOLG.
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
                // Turn 1: will sofort abgeben (zu frueh -> abgelehnt)
                // Turn 2: macht BEIDE Analysen und gibt danach ab
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

        // Der verfruehte Handoff darf den Lauf nicht beenden -> es gieng weiter.
        assertTrue(result.requests() > 1,
                "a rejected handoff must not terminate the loop");
        // Als Exception, damit AgentRunner ok=false setzt; das Modell sieht
        // die Begruendung im Tool-Result.
        assertFalse(result.toolCalls().get(0).success(),
                "the premature handoff must be recorded as failed");
        assertTrue(result.toolCalls().get(0).result().contains("rejected"),
                "the model needs to learn why: " + result.toolCalls().get(0).result());

        // Der spaete Handoff muss tatsaechlich durchgehen und den Lauf beenden.
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
