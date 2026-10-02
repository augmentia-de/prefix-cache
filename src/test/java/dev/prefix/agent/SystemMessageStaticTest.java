package dev.prefix.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/**
 * Guard fuer das Gemini-Caching-Requirement: die System-Message muss
 * (a) byte-identisch ueber alle Agents und alle ReAct-Turns sein und
 * (b) ausschliesslich den statischen SHARED KNOWLEDGE BASE Block enthalten —
 * KEINEN dynamischen Agenten-Prompt. Der Agenten-Prompt gehoert in die
 * USER-Message (immutable systemInstruction + statischer Prefix = cache-bar).
 */
class SystemMessageStaticTest {

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

    @Test
    void systemMessageIsStaticAcrossAgentsAndTurns() {
        String sharedPrefix = "## SHARED KNOWLEDGE BASE ##\n\n# fact one\n # fact two\n\n--- END OF SHARED KNOWLEDGE ---";
        CapturingChatModel model = new CapturingChatModel();
        AgentRunner runner = new AgentRunner(model, "# fact one\n # fact two");
        ToolExecutor noop = (name, args) -> "result";

        runner.run("agent1", "PROMPT_AGENT_ALPHA", "input X", ToolProvider.getAll(), noop, "wf-run-1");
        runner.run("agent2", "PROMPT_AGENT_BETA", "input Y", ToolProvider.getAll(), noop, "wf-run-1");
        runner.run("agent3", "PROMPT_AGENT_ALPHA", "input Z", ToolProvider.getAll(), noop, "wf-run-1");

        String firstSystem = null;
        for (ChatRequest req : model.requests) {
            ChatMessage first = req.messages().get(0);
            assertTrue(first instanceof SystemMessage, "first message must be a SystemMessage");
            String sys = ((SystemMessage) first).text();
            if (firstSystem == null) {
                firstSystem = sys;
            }
            assertEquals(firstSystem, sys, "SystemMessage must be byte-identical across requests");
            assertTrue(sys.contains("SHARED KNOWLEDGE BASE"));
            assertTrue(sys.contains("END OF SHARED KNOWLEDGE"));
        }

        // byte-identisch zum statisch erwarteten Block (inkl. Agenten-PROMPT-frei)
        assertEquals(sharedPrefix, firstSystem);
        assertFalse(firstSystem.contains("PROMPT_AGENT_ALPHA"), "agent prompt must NOT appear in system");
        assertFalse(firstSystem.contains("PROMPT_AGENT_BETA"), "agent prompt must NOT appear in system");
    }

    @Test
    void agentPromptLivesInUserMessage() {
        CapturingChatModel model = new CapturingChatModel();
        AgentRunner runner = new AgentRunner(model, "prefix");
        ToolExecutor noop = (name, args) -> "result";

        runner.run("agent1", "PROMPT_AGENT_ALPHA", "input X", ToolProvider.getAll(), noop, "wf-run-1");

        ChatRequest req = model.requests.get(0);
        UserMessage user = (UserMessage) req.messages().get(req.messages().size() - 2);
        String text = user.singleText();
        assertTrue(text.contains("PROMPT_AGENT_ALPHA"), "agent prompt must be part of the user message");
        assertTrue(text.contains("USER INPUT:"), "user message must carry the input marker");
        assertTrue(text.contains("input X"), "user message must carry the actual user input");
    }
}