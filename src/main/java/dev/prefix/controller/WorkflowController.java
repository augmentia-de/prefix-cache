package dev.prefix.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import dev.prefix.agent.SubtaskHandoff;
import dev.prefix.service.WorkflowEngine;
import dev.prefix.state.WorkflowSessionState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API — POST /api/workflow with userInput as JSON body.
 */
@RestController
@RequestMapping("/api")
public class WorkflowController {

    private final WorkflowEngine engine;

    public WorkflowController(WorkflowEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/workflow")
    public ResponseEntity<Map<String, Object>> execute(@RequestBody Map<String, String> request) {
        String sessionId = UUID.randomUUID().toString();
        String userInput = request.get("input");

        if (userInput == null || userInput.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'input' field"));
        }

        WorkflowSessionState state = engine.execute(sessionId, userInput);

        // Aggregate token usage
        int totalInput = 0, totalOutput = 0, totalCached = 0, totalAll = 0;
        for (Map.Entry<String, Object> entry : state.getAllResults().entrySet()) {
            var r = entry.getValue();
            if (r instanceof Map map) {
                var tu = (Map) map.get("token_usage");
                if (tu != null) {
                    totalInput += numVal(tu, "input_tokens");
                    totalOutput += numVal(tu, "output_tokens");
                    totalCached += numVal(tu, "cached_tokens");
                    totalAll += numVal(tu, "total_tokens");
                }
            }
        }

        // Results without token_usage for a cleaner API
        LinkedHashMap<String, Map> cleanResults = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : state.getAllResults().entrySet()) {
            var raw = (Map<?, ?>) entry.getValue();
            Map<String, Object> r = new LinkedHashMap<>(raw.size());
            for (var e : raw.entrySet()) {
                if (!"token_usage".equals(e.getKey())) {
                    r.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            cleanResults.put(entry.getKey(), r);
        }

        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", sessionId);
        response.put("status", "completed");
        response.put("results", cleanResults);

        // The handoff chain: who handed what to whom. Without this one only sees
        // the final results and cannot tell whether the handoff
        // took place at all — a silently missing handoff
        // otherwise looks like a clean run.
        List<Map<String, Object>> handoffChain = new ArrayList<>();
        for (SubtaskHandoff h : state.allHandoffs()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("from", h.agent());
            row.put("tool", SubtaskHandoff.TOOL_NAME);
            row.put("status", h.status());
            row.put("key_findings", h.keyFindings());
            row.put("next_action_recommendation", h.nextActionRecommendation());
            row.put("bytes", h.toToolResult().length());
            handoffChain.add(row);
        }
        response.put("handoff_chain", handoffChain);

        // Token usage separately at the root
        LinkedHashMap<String, Object> aggregatedTotal = new LinkedHashMap<>();
        aggregatedTotal.put("input_tokens", totalInput);
        aggregatedTotal.put("output_tokens", totalOutput);
        aggregatedTotal.put("cached_tokens", totalCached);
        aggregatedTotal.put("total_tokens", totalAll);

        LinkedHashMap<String, Object> tokenUsageRoot = new LinkedHashMap<>();
        tokenUsageRoot.put("agent1", getAgentTokenStats(state, "agent1"));
        tokenUsageRoot.put("agent2", getAgentTokenStats(state, "agent2"));
        tokenUsageRoot.put("agent3", getAgentTokenStats(state, "agent3"));
        tokenUsageRoot.put("aggregated_total", aggregatedTotal);

        response.put("token_usage", tokenUsageRoot);

        return ResponseEntity.ok(response);
    }

    @SuppressWarnings("unchecked")
    private Map getAgentTokenStats(WorkflowSessionState state, String agentName) {
        var result = state.getAgentResult(agentName);
        if (result instanceof Map m) {
            return (Map) m.get("token_usage");
        }
        return Map.of();
    }

    private int numVal(Map<?, ?> map, String key) {
        var v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
