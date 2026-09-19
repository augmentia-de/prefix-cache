package dev.prefix.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Führt die zwei Werkzeuge aus, die Agent1 im ReAct Loop nutzen darf:
 * <ul>
 *   <li>{@code analyzeDomain} — Extrahiert Domäne aus User Input</li>
 *   <li>{@code defineTask} — Formuliert Kernaufgabe</li>
 * </ul>
 */
@Component
public class AnalysisTools implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String execute(String toolName, String arguments) {
        try {
            String input = parseInput(arguments);

            return switch (toolName) {
                case "analyzeDomain" -> detectDomain(input);
                case "defineTask" -> formulateTask(input);
                default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
            };
        } catch (Exception e) {
            log.error("[AnalysisTools] Execution error: {}", e.getMessage());
            return "[ERROR] " + e.getMessage();
        }
    }

    private String detectDomain(String input) {
        String lower = input.toLowerCase();

        if (lower.contains("mountain") || lower.contains("river") || lower.contains("bay")
                || lower.contains("geography") || lower.contains("lake") || lower.contains("island")
                || lower.contains("thorne") || lower.contains("ombra") || lower.contains("kaelen")
                || lower.contains("coast")) {
            return "geography — features like Mount Thorne, the river Ombra, Cerulean Bay and Kaelen Island";
        }
        if (lower.contains("founded") || lower.contains("founding") || lower.contains("heritage")
                || lower.contains("history") || lower.contains("temple") || lower.contains("chronicle")
                || lower.contains("archives") || lower.contains("tribes")) {
            return "history — the founding of Novaris in 847 by Dr. Elena Voss and its heritage";
        }
        if (lower.contains("xytherium") || lower.contains("quantum") || lower.contains("levitation")
                || lower.contains("science") || lower.contains("research") || lower.contains("telescope")
                || lower.contains("comet") || lower.contains("particle") || lower.contains("neutroline")) {
            return "science-and-technology — Xytherium-7, acoustic levitation, the observatory telescope and more";
        }
        if (lower.contains("lumina") || lower.contains("festival") || lower.contains("library")
                || lower.contains("pottery") || lower.contains("luthier") || lower.contains("musician")
                || lower.contains("culture") || lower.contains("artist") || lower.contains("veridian")) {
            return "culture-and-people — the Lumina festival, the Veridian library and Novaris artisans";
        }
        if (lower.contains("fungi") || lower.contains("reef") || lower.contains("orchid")
                || lower.contains("botany") || lower.contains("migration") || lower.contains("mushroom")
                || lower.contains("flora") || lower.contains("species") || lower.contains("wildlife")) {
            return "environment-and-biology — Glowcap fungi, reefs, flora and migration routes of the region";
        }
        if (lower.contains("bridge") || lower.contains("windmill") || lower.contains("filtration")
                || lower.contains("engineer") || lower.contains("pendulum") || lower.contains("clock")
                || lower.contains("engineering") || lower.contains("mechanism")) {
            return "engineering — the Ombra bridge, windmill collective, water filtration and Engineer Mara Quill";
        }

        return "Novaris general knowledge — request touches the shared knowledge base without a clear sub-domain";
    }

    private String formulateTask(String input) {
        String trimmed = input.trim().replaceAll("\\?\\s*$", "");
        int endIdx = Math.min(trimmed.length(), 200);
        int period = trimmed.indexOf('.', endIdx);
        if (period > 0 && period < trimmed.length()) {
            endIdx = period;
        }
        return "Process the following request: " + trimmed.substring(0, endIdx).trim();
    }

    private String parseInput(String arguments) {
        try {
            JsonNode root = MAPPER.readTree(arguments);
            return root.has("input") ? root.get("input").asText("") : "";
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse input arguments", e);
        }
    }
}
