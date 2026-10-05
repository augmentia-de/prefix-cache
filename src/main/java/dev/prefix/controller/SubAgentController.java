package dev.prefix.controller;

import dev.prefix.service.SubAgentWorkflowService;
import dev.prefix.tool.SubAgentTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API for the subagent demo.
 * <p>
 * Deliberately kept separate from {@link WorkflowController}: the existing
 * 3-agent sequence provides the baseline, this topology the
 * negative control. Both must remain independently runnable.
 *
 * <pre>
 *   POST /api/subagents
 *   { "input": "...", "mode": "prefix" | "args" }
 * </pre>
 *
 * {@code mode=prefix} is the intended case (knowledge base in the cacheable
 * system block), {@code mode=args} the negative control (knowledge base in the
 * dynamic suffix). Both runs are otherwise identical — the same tool list,
 * the same subagents, the same prompts. The only difference is the
 * position of the data.
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

        // No silent defaulting: if someone types "arg" instead of "args", that
        // should stand out as an error and not as an apparently successful prefix run.
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