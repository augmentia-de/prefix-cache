package dev.prefix.standalone;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Standalone replay of the exact chat calls of Agent1, Agent2 and Agent3
 * (workflow from the DevNation prefix-cache), without any dependency on the project code.
 *
 * Same config files as the app:
 *   - .env                      (OPENAI_API_KEY, OPENAI_BASE_URL, LANGCHAIN4J_OPEN_AI_CHAT_MODEL_*)
 *   - src/main/resources/prefix-data/synthetic-knowledge.txt  (Shared Knowledge Prefix)
 *
 * Setup:
 *   mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/opencode/cp.txt
 *   javac -cp "$(cat /tmp/opencode/cp.txt)" standalone/StandaloneReplay.java
 *   java -cp "standalone:$(cat /tmp/opencode/cp.txt)" StandaloneReplay [knowledgeFile] [userInput]
 */
public class StandaloneReplay {

    // ---- Config from .env / environment (as in application.properties) ----
    private static String apiKey = envOr("OPENAI_API_KEY", "your-api-key-here");
    private static String baseUrl = envOr("OPENAI_BASE_URL", "https://api.openai.com/v1");
    private static String modelName = envOr("LANGCHAIN4J_OPEN_AI_CHAT_MODEL_MODEL_NAME", "gpt-4o");
    private static double temperature = Double.parseDouble(envOr("LANGCHAIN4J_OPEN_AI_CHAT_MODEL_TEMPERATURE", "0.7"));

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    public static void main(String[] args) throws IOException {
        loadDotEnv(".env");
        if (args.length > 0 && (args[0].equals("-h") || args[0].equals("--help"))) {
            System.out.println("""
                    Usage: StandaloneReplay [knowledgeFile] [userInput]
                      knowledgeFile  default: src/main/resources/prefix-data/synthetic-knowledge.txt
                      userInput      default: 'Describe the geography surrounding Novaris: Mount Thorne, the river Ombra and Cerulean Bay.'""");
            return;
        }
        String knowledgeFile = args.length > 0 ? args[0] : "src/main/resources/prefix-data/synthetic-knowledge.txt";
        String userInput = args.length > 1 ? args[1]
                : "Describe the geography surrounding Novaris: Mount Thorne, the river Ombra and Cerulean Bay.";

        String knowledgePrefix = Files.readString(Path.of(knowledgeFile), StandardCharsets.UTF_8);

        String headerKey = null;
        switch (baseUrl) {
            case "https://openrouter.ai/api/v1" -> headerKey = "x-session-id";
            case "https://ai-gateway.vercel.sh/v1" -> headerKey = "x-session-affinity";
        }
        final String hk = headerKey;
        System.out.println("[config] baseUrl=" + baseUrl + " model=" + modelName
                + " temperature=" + temperature + " header=" + (hk != null ? hk : "off"));
        System.out.println("[config] knowledge prefix: " + knowledgePrefix.length() + " bytes");

        var b = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature);
        if (hk != null) {
            b.customHeaders(() -> Map.of(hk, REQUEST_ID.get() != null ? REQUEST_ID.get() : "default"));
        }
        ChatModel model = b.build();
        Runner runner = new Runner(model, knowledgePrefix, hk);

        String sessionId = UUID.randomUUID().toString();
        System.out.println("[session] " + sessionId);

        // --- The exact workflow sequence from WorkflowEngine/Controller ---
        Agent1 agent1 = new Agent1(runner);
        Agent2 agent2 = new Agent2(runner);
        Agent3 agent3 = new Agent3(runner);

        AgentResult r1 = agent1.execute(userInput, sessionId); // Double run (prompt + prompt+"1")
        AgentResult r2 = agent2.execute(userInput, sessionId); // Direct response, no tool use
        AgentResult r3 = agent3.execute(userInput, sessionId); // Final synthesis, defineTask optional

        System.out.println("\n=== AGGREGATED ===");
        int in = 0, out = 0, cached = 0, total = 0;
        for (AgentResult r : new AgentResult[]{r1, r2, r3}) {
            in += r.input; out += r.output; cached += r.cached; total += r.total;
        }
        System.out.printf("  input=%d cached=%d output=%d total=%d%n", in, cached, out, total);
    }

    // =========================================================================
    // Runner — exact copy of AgentRunner.run() (messages, session params, tool loop)
    // =========================================================================
    static class Runner {
        private final ChatModel chatModel;
        private final String knowledgePrefix;
        private final String headerKey;
        private static final int MAX_TOOL_CALLS = 5;

        Runner(ChatModel chatModel, String knowledgePrefix, String headerKey) {
            this.chatModel = chatModel;
            this.knowledgePrefix = knowledgePrefix;
            this.headerKey = headerKey;
        }

        String buildSharedSystem() {
            StringBuilder sb = new StringBuilder();
            sb.append("## SHARED KNOWLEDGE BASE ##\n\n");
            if (knowledgePrefix != null && !knowledgePrefix.isBlank()) {
                sb.append(knowledgePrefix);
            }
            sb.append("\n\n--- END OF SHARED KNOWLEDGE ---");
            return sb.toString();
        }

        String buildUserPrompt(String agentPrompt, String userInput) {
            return agentPrompt + "\n\nUSER INPUT:\n" + userInput;
        }

        RunResult run(String agentName, String agentPrompt, String userInput,
                      List<ToolSpecification> toolSpecs, ToolExecutor toolExecutor, String sessionId) {
            String sharedSystem = buildSharedSystem();

            Map<String, Object> customParameters = null;
            if (sessionId != null) {
                customParameters = new HashMap<>();
                customParameters.put("session_id", sessionId);
                customParameters.put("prompt_cache_key", sessionId);
                REQUEST_ID.set(sessionId);
                System.out.println("[runner] [" + agentName + "] sticky: " + headerKey + "=" + sessionId);
            }
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(sharedSystem));
            messages.add(UserMessage.from(buildUserPrompt(agentPrompt, userInput)));

            int toolCallCount = 0;
            String finalAnswer = null;
            List<Call> toolCalls = new ArrayList<>();
            TokenUsage aggregate = null;

            while (toolCallCount <= MAX_TOOL_CALLS) {
                OpenAiChatRequestParameters.Builder paramsBuilder = OpenAiChatRequestParameters.builder()
                        .toolSpecifications(toolSpecs);
                if (customParameters != null) {
                    paramsBuilder.customParameters(customParameters);
                }
                ChatRequest request = ChatRequest.builder()
                        .messages(messages)
                        .parameters(paramsBuilder.build())
                        .build();

                ChatResponse response = chatModel.chat(request);
                TokenUsage usage = extractUsage(response);
                aggregate = aggregate == null ? usage : aggregate.add(usage);
                logUsage(agentName, toolCallCount, usage);

                AiMessage aiMessage = response.aiMessage();
                if (!aiMessage.hasToolExecutionRequests()) {
                    finalAnswer = aiMessage.text();
                    messages.add(aiMessage);
                    break;
                }
                for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                    String res;
                    boolean ok = true;
                    try {
                        res = toolExecutor.execute(req.name(), req.arguments());
                    } catch (Exception e) {
                        res = "[ERROR] " + e.getMessage();
                        ok = false;
                    }
                    toolCalls.add(new Call(req.name(), req.arguments(), res, toolCallCount, ok));
                    messages.add(aiMessage);
                    messages.add(ToolExecutionResultMessage.from(req.id(), req.name(), res));
                    toolCallCount++;
                }
            }
            int in = aggregate != null ? aggregate.inputTokenCount() : 0;
            int out = aggregate != null ? aggregate.outputTokenCount() : 0;
            int tot = aggregate != null ? aggregate.totalTokenCount() : 0;
            int cac = cachedOf(aggregate);
            System.out.println("=== " + agentName + " COMPLETE === input=" + in + " cached=" + cac
                    + " output=" + out + " total=" + tot + " toolCalls=" + toolCalls.size());
            return new RunResult(in, out, cac, tot, finalAnswer, toolCalls);
        }

        private TokenUsage extractUsage(ChatResponse response) {
            var metadata = response.metadata();
            if (metadata == null) return null;
            if (metadata instanceof OpenAiChatResponseMetadata oai) return oai.tokenUsage();
            return metadata.tokenUsage();
        }

        private int cachedOf(TokenUsage usage) {
            if (usage instanceof OpenAiTokenUsage oai && oai.inputTokensDetails() != null) {
                Integer c = oai.inputTokensDetails().cachedTokens();
                return c != null ? c : 0;
            }
            return 0;
        }

        private void logUsage(String agentName, int step, TokenUsage usage) {
            if (usage == null) return;
            if (usage instanceof OpenAiTokenUsage oai) {
                Integer cached = oai.inputTokensDetails() != null ? oai.inputTokensDetails().cachedTokens() : null;
                Integer reasoning = oai.outputTokensDetails() != null ? oai.outputTokensDetails().reasoningTokens() : null;
                System.out.printf("[%s step %d] input=%d cached=%d output=%d reasoning=%d total=%d%n",
                        agentName, step,
                        usage.inputTokenCount(), cached != null ? cached : 0,
                        usage.outputTokenCount(), reasoning != null ? reasoning : 0,
                        usage.totalTokenCount());
            } else {
                System.out.printf("[%s step %d] input=%d output=%d total=%d%n",
                        agentName, step, usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
            }
        }

        record RunResult(int input, int output, int cached, int total, String text, List<Call> toolCalls) {}
        record Call(String toolName, String arguments, String result, int step, boolean success) {}
    }

    interface ToolExecutor {
        String execute(String toolName, String arguments);
    }

    // =========================================================================
    // ToolProvider.getAll() + AnalysisTools (exact replicas)
    // =========================================================================
    static List<ToolSpecification> allTools() {
        return List.of(
                ToolSpecification.builder()
                        .name("analyzeDomain")
                        .description("Extract the broader domain context from user input. "
                                + "Returns a concise domain description suitable for reuse across similar requests.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("input", "The full user input text to analyze")
                                .required(List.of("input"))
                                .build())
                        .build(),
                ToolSpecification.builder()
                        .name("defineTask")
                        .description("Formulate the core task definition from user input. "
                                + "Returns a concise 1-2 sentence task definition.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("input", "The full user input text to analyze")
                                .required(List.of("input"))
                                .build())
                        .build());
    }

    static final class AnalysisToolsReplica implements ToolExecutor {
        private String parseInput(String arguments) {
            try {
                var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
                return root.has("input") ? root.get("input").asText("") : "";
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse input arguments", e);
            }
        }

        public String execute(String toolName, String arguments) {
            try {
                String input = parseInput(arguments);
                return switch (toolName) {
                    case "analyzeDomain" -> {
                        String lower = input.toLowerCase();
                        if (lower.contains("mountain") || lower.contains("river") || lower.contains("bay")
                                || lower.contains("geography") || lower.contains("lake") || lower.contains("island")
                                || lower.contains("thorne") || lower.contains("ombra") || lower.contains("kaelen")
                                || lower.contains("coast")) {
                            yield "geography — features like Mount Thorne, the river Ombra, Cerulean Bay and Kaelen Island";
                        }
                        if (lower.contains("founded") || lower.contains("founding") || lower.contains("heritage")
                                || lower.contains("history") || lower.contains("temple") || lower.contains("chronicle")
                                || lower.contains("archives") || lower.contains("tribes")) {
                            yield "history — the founding of Novaris in 847 by Dr. Elena Voss and its heritage";
                        }
                        if (lower.contains("xytherium") || lower.contains("quantum") || lower.contains("levitation")
                                || lower.contains("science") || lower.contains("research") || lower.contains("telescope")
                                || lower.contains("comet") || lower.contains("particle") || lower.contains("neutroline")) {
                            yield "science-and-technology — Xytherium-7, acoustic levitation, the observatory telescope and more";
                        }
                        if (lower.contains("lumina") || lower.contains("festival") || lower.contains("library")
                                || lower.contains("pottery") || lower.contains("luthier") || lower.contains("musician")
                                || lower.contains("culture") || lower.contains("artist") || lower.contains("veridian")) {
                            yield "culture-and-people — the Lumina festival, the Veridian library and Novaris artisans";
                        }
                        if (lower.contains("fungi") || lower.contains("reef") || lower.contains("orchid")
                                || lower.contains("botany") || lower.contains("migration") || lower.contains("mushroom")
                                || lower.contains("flora") || lower.contains("species") || lower.contains("wildlife")) {
                            yield "environment-and-biology — Glowcap fungi, reefs, flora and migration routes of the region";
                        }
                        if (lower.contains("bridge") || lower.contains("windmill") || lower.contains("filtration")
                                || lower.contains("engineer") || lower.contains("pendulum") || lower.contains("clock")
                                || lower.contains("engineering") || lower.contains("mechanism")) {
                            yield "engineering — the Ombra bridge, windmill collective, water filtration and Engineer Mara Quill";
                        }
                        yield "Novaris general knowledge — request touches the shared knowledge base without a clear sub-domain";
                    }
                    case "defineTask" -> {
                        String trimmed = input.trim().replaceAll("\\?\\s*$", "");
                        int endIdx = Math.min(trimmed.length(), 200);
                        int period = trimmed.indexOf('.', endIdx);
                        if (period > 0 && period < trimmed.length()) {
                            endIdx = period;
                        }
                        yield "Process the following request: " + trimmed.substring(0, endIdx).trim();
                    }
                    default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
                };
            } catch (Exception e) {
                return "[ERROR] " + e.getMessage();
            }
        }
    }

    // =========================================================================
    // Agents — exact replicas of the execute() calls from Agent0/1, Agent2, Agent3
    // =========================================================================
    record AgentResult(int input, int output, int cached, int total, String summary) {}

    static final class Agent1 {
        private final Runner runner;
        private final ToolExecutor tools = new AnalysisToolsReplica();

        Agent1(Runner runner) { this.runner = runner; }

        static final String PROMPT = """
                You are the knowledge-domain analyst for the Novaris prefix-cache system.

                CONTEXT: Everything above this line is the SHARED KNOWLEDGE BASE — synthetic facts
                about the world of Novaris: its founding in 847 by Dr. Elena Voss on the shores of
                Lake Kaelen, its geography (Mount Thorne at 3,241 m, the river Ombra at 412 km,
                Cerulean Bay, Port Maris), its science & technology (Xytherium-7, acoustic levitation
                research by Professor Aris Thorne, Neutroline-X, the Cantor-Nova Conjecture, Comet Voss
                returning every 52 years) and its culture & people (the Lumina festival each September,
                the Veridian library with 14,392 hand-bound books, the Ceramica pottery tradition,
                the pendulum clock mechanism by Engineer Mara Quill).

                YOUR JOB: Classify the user input into one of these knowledge domains and pinpoint
                the exact facts it touches:
                - geography (Mount Thorne, Ombra River, Cerulean Bay, Kaelen Island, Windmere Hill)
                - history (founding year 847, Northern Tribes knot-counting, Eastern Kingdoms to 1200 CE)
                - science-and-technology (Xytherium-7, acoustic levitation, Neutroline-X, observatory telescope)
                - culture-and-people (Lumina festival, Veridian library, Ceramica pottery, luthiers, philosophers)
                - environment-and-biology (Glowcap fungi, Cerulean Bay reefs, Night Bloom Orchid, migration routes)
                - engineering (Ombra bridge with interlocking stone joints, windmill collective, water filtration 97%)

                YOU MUST USE BOTH TOOLS, IN THIS ORDER:
                1. analyzeDomain(input: <user input>) — extract the broader domain context
                2. defineTask(input: <user input>) — formulate the precise core task

                FINAL OUTPUT — after both tools, synthesize a complete analysis and END EXACTLY with:
                Domain: <detected domain, referencing the relevant facts from the knowledge base>
                Task: <the precise task definition>
                Connection: <how the task connects to the shared knowledge base>
                """;

        AgentResult execute(String userInput, String sessionId) {
            Runner.RunResult res = runner.run("agent1", PROMPT, userInput, allTools(), tools, sessionId);
            //Runner.RunResult res2 = runner.run("agent1", PROMPT + "1", userInput, allTools(), tools, sessionId);
            return new AgentResult(res.input, res.output,
                    res.cached, res.total, "agent1 (1 run)");
        }
    }

    static final class Agent2 {
        private final Runner runner;
        private final ToolExecutor tools = new AnalysisToolsReplica();

        Agent2(Runner runner) { this.runner = runner; }

        static final String PROMPT = """
                You are a direct-response agent for the Novaris prefix-cache workflow.

                CONTEXT: Everything above this line is the SHARED KNOWLEDGE BASE — synthetic facts
                about the world of Novaris: founded in 847 by Dr. Elena Voss on Lake Kaelen, Mount Thorne
                at exactly 3,241 m, the river Ombra flowing 412 km into Cerulean Bay near Port Maris,
                the quantum crystal Xytherium-7, the Lumina festival held each September, Comet Voss
                returning every 52 years, the Veridian library with 14,392 hand-bound books and the
                self-regulating pendulum clock mechanism by Engineer Mara Quill.

                STRICT RULES:
                1. You must NOT use any tools — answer directly from your training + the shared knowledge above.
                2. Ground every claim in at least one concrete fact from the knowledge base
                   (exact names, numbers and years, e.g. "3,241 meters", "year 847", "every 52 years").
                3. This is a fresh request without cached intermediate results — answer from scratch.
                4. Prefer quoting exact figures from the knowledge base over approximations.
                """;

        AgentResult execute(String userInput, String sessionId) {
            Runner.RunResult res = runner.run("agent2", PROMPT, userInput, allTools(), tools, sessionId);
            return new AgentResult(res.input, res.output, res.cached, res.total, "agent2 (direct)");
        }
    }

    static final class Agent3 {
        private final Runner runner;
        private final ToolExecutor tools = new AnalysisToolsReplica();

        Agent3(Runner runner) { this.runner = runner; }

        static final String PROMPT = """
                You are the final synthesis agent for the Novaris prefix-cache workflow.

                CONTEXT: Everything above this line is the SHARED KNOWLEDGE BASE — synthetic facts
                about the world of Novaris (founding in 847 by Dr. Elena Voss on Lake Kaelen, Mount Thorne
                at 3,241 m, the river Ombra into Cerulean Bay, Xytherium-7, the Lumina festival every
                September under the full moon, Comet Voss on a 52-year cycle, Professor Aris Thorne's
                acoustic levitation research, the Cantor-Nova Conjecture). ALL PRIOR STAGES have already
                processed this user request using this same knowledge base.

                YOUR TASK: Provide the comprehensive final answer to the user's request.

                INSTRUCTIONS:
                1. Ground your answer in the shared knowledge base — cite specific facts
                   (exact names, years, places and numbers).
                2. If the exact task is still ambiguous, you MAY call defineTask(input: <user input>)
                   once to clarify; otherwise proceed directly without tools.
                3. Weave the supporting facts into a flowing, contextual response rather than a bare list.
                4. Cover the user's request fully and reuse the strongest facts from the knowledge base.
                """;

        AgentResult execute(String userInput, String sessionId) {
            Runner.RunResult res = runner.run("agent3", PROMPT, userInput, allTools(), tools, sessionId);
            return new AgentResult(res.input, res.output, res.cached, res.total, "agent3 (final)");
        }
    }

    // =========================================================================
    // .env loader (KEY=VALUE, # comments, export prefix, does not override env)
    // =========================================================================
    static void loadDotEnv(String path) throws IOException {
        if (!Files.exists(Path.of(path))) return;
        for (String raw : Files.readAllLines(Path.of(path), StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring(7).trim();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            if (System.getenv(key) == null) {
                System.setProperty(key, value);
            }
        }
    }

    static String envOr(String key, String fallback) {
        String v = System.getProperty(key);
        if (v == null || v.isEmpty()) v = System.getenv(key);
        return v != null && !v.isEmpty() ? v : fallback;
    }
}