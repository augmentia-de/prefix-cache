package dev.prefix.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.List;
import java.util.Map;

/**
 * Zentrale Tool-Registry — stellt ALLE verfügbaren Tools für JEDEN Agent bereit.
 * <p>
 * WICHTIG: Dies ist NICHT eine Filterung nach "welche Tools darf dieser Agent".
 * Stattdessen enthält {@code #getAll()} IMMER DIESELBE Liste aller Tools.
 * Welche Tools ein Agent tatsächlich verwenden DARF, wird ausschließlich durch
 * dessen SystemPrompt gesteuert.
 * <p>
 * Dadurch ist der ChatRequest-Header (toolSpecifications) über ALLE Agents hinweg
 * byte-identisch — essentiell für Provider-seitiges Prefix-Caching (OpenAI CacheControl).
 * <p>
 * Pattern aus quad-core: {@code CachePrefixGuard.requireIdenticalTools(a, b)} stellt sicher,
 * dass alle Agents exakt dieselbe Tool-Liste serialisieren.
 */
public class ToolProvider {

    /**
     * IMMER GLEICHE Liste — Byte-Identität über alle Requests und Agents hinweg.
     */
    private static final List<ToolSpecification> ALL_TOOLS = buildAllTools();

    // --- Public API ---

    /**
     * Gibt die vollständige Liste aller definierten Tools zurück.
     * Diese Liste ist unveränderlich und immer identisch.
     */
    public static List<ToolSpecification> getAll() {
        return ALL_TOOLS;
    }

    /**
     * Prüft ob ein Tool mit dem gegebenen Namen existiert.
     */
    public static boolean hasTool(String toolName) {
        return ALL_TOOLS.stream().anyMatch(t -> t.name().equals(toolName));
    }

    /**
     * Konstruiert die statische Tool-Liste beim Klassenladen.
     * Alle hier definierten Tools sind in jedem ChatRequest enthalten.
     */
    private static List<ToolSpecification> buildAllTools() {
        return List.of(
                // Tool 1: analyzeDomain — von Agent1 verwendbar
                ToolSpecification.builder()
                        .name("analyzeDomain")
                        .description("Extract the broader domain context from user input. "
                                + "Returns a concise domain description suitable for reuse across similar requests.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("input", "The full user input text to analyze")
                                .required(List.of("input"))
                                .build())
                        .build(),

                // Tool 2: defineTask — von Agent1 UND Agent3 verwendbar
                ToolSpecification.builder()
                        .name("defineTask")
                        .description("Formulate the core task definition from user input. "
                                + "Returns a concise 1-2 sentence task definition.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("input", "The full user input text to analyze")
                                .required(List.of("input"))
                                .build())
                        .build()
        );
    }
}
