package dev.prefix.controller;

import dev.prefix.service.SubAgentWorkflowService;
import dev.prefix.tool.SubAgentTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API fuer die Subagent-Demo.
 * <p>
 * Absichtlich getrennt von {@link WorkflowController}: die bestehende
 * 3-Agenten-Sequenz liefert die Vergleichsbasis, diese Topologie die
 * Gegenprobe. Beide muessen unabhaengig lauffaehig bleiben.
 *
 * <pre>
 *   POST /api/subagents
 *   { "input": "...", "mode": "prefix" | "args" }
 * </pre>
 *
 * {@code mode=prefix} ist der Soll-Fall (Knowledge Base im cachebaren
 * System-Block), {@code mode=args} die Negativprobe (Knowledge Base im
 * dynamischen Suffix). Beide Läufe sind sonst identisch — dieselbe Tool-Liste,
 * dieselben Subagenten, dieselben Prompts. Der einzige Unterschied ist die
 * Position der Daten.
 */
@RestController
@RequestMapping("/api")
public class SubAgentController {

    private final SubAgentWorkflowService service;

    public SubAgentController(SubAgentWorkflowService service) {
        this.service = service;
    }

    @PostMapping("/subagents")
    public ResponseEntity<Map<String, Object>> execute(@RequestBody Map<String, String> request) {
        String userInput = request.get("input");
        if (userInput == null || userInput.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'input' field"));
        }

        // Kein stilles Defaulting: wenn jemand "arg" statt "args" tippt, soll das
        // als Fehler auffallen und nicht als scheinbar erfolgreicher prefix-Lauf.
        String rawMode = request.getOrDefault("mode", "prefix");
        SubAgentTools.Mode mode = switch (rawMode.toLowerCase()) {
            case "prefix" -> SubAgentTools.Mode.VIA_PREFIX;
            case "args" -> SubAgentTools.Mode.VIA_ARGUMENTS;
            default -> null;
        };
        if (mode == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid mode", "given", rawMode, "allowed", "prefix|args"));
        }

        Map<String, Object> result = new LinkedHashMap<>(service.execute(userInput, mode));
        return ResponseEntity.ok(result);
    }
}