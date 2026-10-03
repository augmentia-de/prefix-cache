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
 * Sichert die Kontext-Politik der Subagent-Topologie ab:
 * 1 Orchestrator, 3 Subagenten ueber Tools, davon 2 mit Basiskontext.
 * <p>
 * Geprueft wird nicht die Tool-Funktionalitaet, sondern <b>wo der Kontext
 * landet</b> — denn genau daran haengt die Cache-Wirkung:
 *
 * <ul>
 *   <li>KB-Subagent und Orchestrator teilen sich einen byte-identischen System-Block
 *       (derselbe Cache-Block),</li>
 *   <li>der KB-Subagent traegt KEINE Knowledge Base in seiner User-Message
 *       (sonst waere sie doppelt bezahlt — einmal im Prefix, einmal im Suffix),</li>
 *   <li>der Bare-Subagent bekommt den Prefix ueberhaupt nicht und bleibt damit
 *       unter der Cache-Schwelle,</li>
 *   <li>im args-Modus wandert die Knowledge Base in die User-Message — das ist
 *       die Negativprobe und darf hier nicht versehentlich wieder wegoptimiert werden.</li>
 * </ul>
 *
 * Der Prefix selbst ist hier ein Platzhalter mit einem unterscheidbaren Marker
 * ("NOVARIS"); entscheidend ist die Gleichheit bzw. Ungleichheit der Strings,
 * nicht deren Inhalt.
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

        // Geteiltes ChatModel, eigener System-Block — und beide stabil.
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
        // renderSummary ist der einzige Subagent OHNE Knowledge Base — er bekommt
        // stattdessen die Findings der anderen als Pflicht-Argument. Fehlt dieses
        // Argument, kann der Orchestrator ihm nichts beibringen und der Subagent
        // haette weder KB noch Input: ein stiller Totalausfall.
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
     * Regressionstest fuer einen real beobachteten Fehler.
     * <p>
     * Solange die Zugangskontrolle ausschliesslich ueber den Prompt laeuft, kann
     * jeder Agent jedes Tool in seiner Liste aufrufen. Nach dem Ergaenzen der
     * Subagent-Tools in die geteilte Registry rief agent1 im alten 3-Agenten-
     * Workflow <code>lookupEvidence</code> auf und bekam
     * {@code [ERROR] Unknown tool} zurueck — zwei von fuenf erlaubten
     * Tool-Iterationen fuer einen Fehler. Deshalb sind die beiden Saetze getrennt.
     */
    @Test
    void subagentToolsAreNotVisibleToTheAnalysisWorkflow() {
        var analysis = ToolProvider.getAll().stream().map(ToolSpecification::name).toList();
        assertFalse(analysis.contains("lookupEvidence"),
                "agent1/2/3 duerfen die Subagent-Tools nicht sehen — Prompt-only-Gating schuetzt nicht");
        assertFalse(analysis.contains("crossCheck"));
        assertFalse(analysis.contains("renderSummary"));

        var sub = ToolProvider.getSubAgentTools().stream().map(ToolSpecification::name).toList();
        assertFalse(sub.contains("analyzeDomain"),
                "die Subagent-Demo braucht die Analyse-Tools nicht");
        assertFalse(sub.contains("defineTask"));
    }

    /** Die Byte-Identitaet gilt je Satz, nicht nur gesamt. */
    @Test
    void eachToolSetIsStableAcrossCalls() {
        assertEquals(ToolProvider.getAll(), ToolProvider.getAll());
        assertEquals(ToolProvider.getSubAgentTools(), ToolProvider.getSubAgentTools());
    }

    @Test
    void nestingBeyondOneSubagentLevelIsRefused() {
        // Modell, das bei den ersten `toolCallTurns` Aufrufen einen Tool-Call
        // emittiert und danach eine fertige Antwort liefert. Ohne das emitierte
        // der Executor der tieferen Ebene nie und die Wache wuerde nie ausgeloest.
        //
        // Erwartete Aufrufkette (2 dispatchende Ebenen noetig, damit die dritte
        // erreicht wird):
        //   call 0: orchestrator (Tiefe 1) -> Tool-Call -> level2
        //   call 1: sub:sub       (Tiefe 2) -> Tool-Call -> level3
        //   level3: will run() auf Tiefe 3 -> MUSS verweigert werden
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

        // Drei Ebenen. Orchestrator laeuft auf Tiefe 1, sein Subagent auf 2,
        // und ein Subagent, der seinerseits dispatchen will, braucht Tiefe 3 —
        // genau die muss die Wache stoppen.
        //
        // Die Verweigerung wird eine Ebene tiefer von AgentRunner als
        // Tool-Fehler geschluckt und ist von aussen nur schwer zu sehen.
        // Deshalb wird hier direkt aufgezeichnet, statt auf einen Endtext zu pruefen.
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

        // Zwei Ebenen sind erlaubt und muessen tatsaechlich durchlaufen.
        assertEquals(1, result.toolCalls().size());
        assertTrue(result.toolCalls().get(0).success(),
                "level 2 is a legal subagent level and must succeed");

        // Der Tiefenzaehler muss nach dem Fehler zurueckgesetzt sein,
        // sonst blockiert er den naechsten Request auf demselben Thread.
        assertDoesNotThrow(() -> outer.run("orchestrator", "ORCH_PROMPT", "input X",
                        ToolProvider.getSubAgentTools(), (n, a) -> "r", null),
                "nesting depth must be released in a finally block");
    }
}