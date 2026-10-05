package dev.prefix.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured handoff from one agent to the next.
 * <p>
 * An agent does not end its work with prose, but calls the tool
 * {@link #TOOL_NAME}. The engine accepts the arguments and appends them
 * to the follow-up agent as its own message at the <b>end</b> of its history.
 *
 * <h2>Why a tool and not text</h2>
 * <ul>
 *   <li><b>Structure.</b> The follow-up agent reads fields instead of parsing prose.</li>
 *   <li><b>Length.</b> The schema limits the scope; unstructured answers
 *       tend to be longer otherwise and cost tokens in the dynamic suffix.</li>
 *   <li><b>Determinism.</b> Fixed field order instead of the model choosing the order.</li>
 * </ul>
 *
 * <h2>What the schema explicitly does NOT deliver</h2>
 * The cache protection comes <b>not</b> from the schema, but from the position:
 * the handoff is appended to the <b>end</b> of the message list, and because causal
 * attention only looks back at tokens, everything in front of it stays cacheable — for a
 * structured and for an unstructured result alike. The
 * real danger is the reverse: if you render the handoff into the
 * system block, the entire prefix behind it is worthless. No
 * JSON schema protects against that, only the position does.
 *
 * <h2>Why no ToolResultMessage</h2>
 * The handoff is deliberately transported as a <b>UserMessage</b>, not as
 * {@code role: "tool"}. A tool message without a preceding
 * {@code assistant} tool call is orphaned and thus schema-violating — the
 * openCode Zen gateway rejects exactly that with
 * {@code 400 invalid_request}, while OpenRouter/DeepSeek tolerates it.
 *
 * <h2>Canonical serialization</h2>
 * {@link #renderAsMessage(String)} is byte-deterministic: fixed
 * key order ({@link LinkedHashMap}), findings in the order delivered by the model,
 * no timestamps. That is part of the cache invariant —
 * JSON whose key order fluctuates between two runs
 * invalidates the prefix.
 */
public record SubtaskHandoff(String agent,
                             String status,
                             List<String> keyFindings,
                             String nextActionRecommendation,
                             String rawJson) {

    /** Name of the tool with which an agent hands in its handoff. */
    public static final String TOOL_NAME = "submit_subtask_summary";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Produces a deterministic tool call ID for logs and correlation.
     * <p>
     * No longer part of the message: the handoff is a UserMessage, so there is
     * no orphaned {@code tool_call_id} left that an ID would have to refer to.
     * For the mapping in the log it is still useful.
     */
    public static String toolCallId(String agent) {
        return "handoff-" + agent;
    }

    /**
     * The content that is appended as its own message.
     * <p>
     * Fixed key order, no extra fields. The {@code agent} name deliberately
     * does NOT appear in the JSON, because it is already in the heading and the
     * follow-up agent sees the assignment from it.
     */
    public String toToolResult() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status == null ? "" : status);
        m.put("key_findings", keyFindings == null ? List.of() : keyFindings);
        m.put("next_action_recommendation",
                nextActionRecommendation == null ? "" : nextActionRecommendation);
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            // Must not happen: the map is serializable. If it does, empty but
            // stable content is better than an exception right
            // in the middle of the workflow.
            return "{\"status\":\"error\",\"key_findings\":[],"
                    + "\"next_action_recommendation\":\"\"}";
        }
    }

    /**
     * The complete message that is appended to the follow-up agent.
     * <p>
     * Byte-deterministic: fixed heading, canonical JSON, no
     * timestamps and no random values. Precisely that makes it harmless
     * for the prefix cache — otherwise every run would invalidate the
     * cached region behind it.
     */
    public static String renderAsMessage(String agent, String canonicalJson) {
        return "## HANDOFF FROM " + agent + " (" + TOOL_NAME + ") ##\n" + canonicalJson;
    }

    /** Like {@link #renderAsMessage(String, String)}, for this handoff. */
    public String renderAsMessage() {
        return renderAsMessage(agent, toToolResult());
    }

    /**
     * Parses the tool arguments of a handoff call.
     * <p>
     * Tolerant of subsets: a model that omits {@code next_action_recommendation}
     * is no reason to abort the workflow — the field is filled
     * empty so that the result still serializes in a schema-conformant way.
     */
    public static SubtaskHandoff from(String agent, String arguments) {
        String status = "";
        String next = "";
        List<String> findings = new ArrayList<>();
        String raw = arguments == null ? "" : arguments;
        try {
            JsonNode root = MAPPER.readTree(arguments);
            status = root.path("status").asText("");
            next = root.path("next_action_recommendation").asText("");
            JsonNode arr = root.path("key_findings");
            if (arr.isArray()) {
                arr.forEach(n -> findings.add(n.asText("")));
            }
        } catch (Exception e) {
            // Keep the raw JSON: it is better to see the original arguments
            // than to lose them.
        }
        return new SubtaskHandoff(agent, status, List.copyOf(findings), next, raw);
    }

    /** Short summary suitable for logs. */
    @Override
    public String toString() {
        return "SubtaskHandoff[" + agent + " status=" + status
                + " findings=" + (keyFindings == null ? 0 : keyFindings.size()) + "]";
    }
}