package dev.prefix.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Protokolliert jeden LLM-Request und jede Response in getrennte, zeitgestempelte Dateien
 * (Pendant zu quarkus.langchain4j.openai.log-requests/log-responses=true — aber als Dateien).
 * <p>
 * Pro Model-Call entstehen zwei Dateien im Logverzeichnis:
 * <ul>
 *   <li>{@code req-<timestamp>.log} — Request-Payload (model, messages, tools)</li>
 *   <li>{@code resp-<timestamp>.log} — Response-Payload (id, aiMessage, tokenUsage, finishReason)</li>
 * </ul>
 * Die Anfrage legt den Timestamp als Korrelations-ID in den Listener-attributes ab; die
 * Response nutzt denselben Timestamp, damit req/resp zueinander passen.
 */
@Component
public class ChatExchangeFileLogger implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(ChatExchangeFileLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss.SSS");
    private static final String ATTR_TS = "chat-exchange-logger.timestamp";

    private final Path logDir;

    public ChatExchangeFileLogger(@Value("${prefix.logging.dir:logs}") String logDir) {
        this.logDir = Path.of(logDir).toAbsolutePath();
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        String ts = LocalDateTime.now().format(TS);
        context.attributes().put(ATTR_TS, ts);
        context.attributes().put("ATTR", context.attributes().toString());
        context.attributes().put("M", context.modelProvider().toString());
        write("req", ts, requestToMap(context, ts));
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        String ts = readTs(context.attributes());
        write("resp", ts, responseToMap(context.chatResponse(), ts));
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        String ts = readTs(context.attributes());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", ts);
        payload.put("error", String.valueOf(context.error()));
        write("resp", ts, payload);
    }

    private String readTs(Map<Object, Object> attributes) {
        Object ts = attributes.get(ATTR_TS);
        return ts != null ? ts.toString() : LocalDateTime.now().format(TS);
    }

    private Map<String, Object> requestToMap(ChatModelRequestContext context, String ts) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", ts);
        map.put("ATTR", context.attributes().toString());
        map.put("M", context.modelProvider().toString());

        ChatRequest request = context.chatRequest();
        map.put("model", request.modelName());
        if (request.temperature() != null) {
            map.put("temperature", request.temperature());
        }
        if (request.parameters() instanceof OpenAiChatRequestParameters oai
                && oai.customParameters() != null
                && oai.customParameters().get("session_id") != null) {
            map.put("session_id", oai.customParameters().get("session_id"));
        }
        map.put("messages", request.messages().stream().map(this::messageToMap).toList());
        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            map.put("tools", request.toolSpecifications().stream().map(this::toolSpecToMap).toList());
        }
        return map;
    }

    private Map<String, Object> responseToMap(ChatResponse response, String ts) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", ts);
        if (response.id() != null) map.put("id", response.id());
        if (response.modelName() != null) map.put("model", response.modelName());
        if (response.finishReason() != null) map.put("finish_reason", response.finishReason().name());

        AiMessage aiMessage = response.aiMessage();
        if (aiMessage != null) {
            map.put("ai_message", aiMessageToMap(aiMessage));
        }

        TokenUsage usage = tokenUsage(response);
        Map<String, Object> usageMap = new LinkedHashMap<>();
        if (usage == null) {
            usageMap.put("input_tokens", 0);
            usageMap.put("output_tokens", 0);
            usageMap.put("total_tokens", 0);
            usageMap.put("cached_tokens", 0);
            usageMap.put("reasoning_tokens", 0);
        } else {
            usageMap.put("input_tokens", usage.inputTokenCount());
            usageMap.put("output_tokens", usage.outputTokenCount());
            usageMap.put("total_tokens", usage.totalTokenCount());
            usageMap.put("cached_tokens", cachedTokens(usage));
            usageMap.put("reasoning_tokens", reasoningTokens(usage));
        }
        map.put("token_usage", usageMap);

        return map;
    }

    private TokenUsage tokenUsage(ChatResponse response) {
        if (response.metadata() == null) return null;
        return response.metadata().tokenUsage();
    }

    private int cachedTokens(TokenUsage usage) {
        if (usage instanceof OpenAiTokenUsage oai && oai.inputTokensDetails() != null
                && oai.inputTokensDetails().cachedTokens() != null) {
            return oai.inputTokensDetails().cachedTokens();
        }
        return 0;
    }

    private int reasoningTokens(TokenUsage usage) {
        if (usage instanceof OpenAiTokenUsage oai && oai.outputTokensDetails() != null
                && oai.outputTokensDetails().reasoningTokens() != null) {
            return oai.outputTokensDetails().reasoningTokens();
        }
        return 0;
    }

    private Map<String, Object> aiMessageToMap(AiMessage message) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (message.text() != null) map.put("content", message.text());
        if (message.hasToolExecutionRequests()) {
            map.put("tool_calls", message.toolExecutionRequests().stream().map(this::toolCallToMap).toList());
        }
        return map;
    }

    private Map<String, Object> messageToMap(ChatMessage message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", switch (message.type()) {
            case SYSTEM -> "system";
            case USER -> "user";
            case AI -> "assistant";
            case TOOL_EXECUTION_RESULT -> "tool";
            default -> message.type().name().toLowerCase();
        });

        if (message instanceof SystemMessage sm) {
            map.put("content", sm.text());
        } else if (message instanceof UserMessage um) {
            map.put("content", um.singleText());
        } else if (message instanceof AiMessage am) {
            if (am.text() != null) map.put("content", am.text());
            if (am.hasToolExecutionRequests()) {
                map.put("tool_calls", am.toolExecutionRequests().stream().map(this::toolCallToMap).toList());
            }
        } else if (message instanceof ToolExecutionResultMessage tr) {
            map.put("tool_name", tr.toolName());
            if (tr.text() != null) map.put("content", tr.text());
        }
        return map;
    }

    private Map<String, Object> toolSpecToMap(ToolSpecification spec) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", spec.name());
        if (spec.description() != null) map.put("description", spec.description());
        if (spec.parameters() != null) {
            // Exakt wie LangChain4j auf der Leitung: JsonSchemaElementUtils.toMap()
            // liefert eine LinkedHashMap in deterministischer Key-Reihenfolge und
            // als echtes JSON-Objekt — NICHT als toString()-String.
            map.put("parameters", JsonSchemaElementUtils.toMap(spec.parameters()));
        }
        return map;
    }

    private Map<String, Object> toolCallToMap(ToolExecutionRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (request.id() != null) map.put("id", request.id());
        map.put("name", request.name());
        map.put("arguments", request.arguments());
        return map;
    }

    private void write(String prefix, String ts, Map<String, Object> payload) {
        try {
            Files.createDirectories(logDir);
            Path file = logDir.resolve(prefix + "-" + ts + ".log");
            String body = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            Files.writeString(file, body, StandardCharsets.UTF_8);
            log.info("[ChatExchangeFileLogger] Wrote {}", file);
        } catch (Exception e) {
            log.warn("[ChatExchangeFileLogger] Failed to write {} file", prefix, e);
        }
    }
}