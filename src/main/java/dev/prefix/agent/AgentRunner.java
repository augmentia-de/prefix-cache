package dev.prefix.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import dev.prefix.config.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.prefix.tool.ToolExecutor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generischer Agent-Runner — führt den ReAct Loop mit Tool Calling aus.
 * <p>
 * Öffnet OpenAI-spezifische Response-Metadaten für Token-Nutzungssichtbarkeit:
 * inputTokens, outputTokens, cachedTokens, totalTokens pro Request und insgesamt.
 */
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /** Maximale Tool Calls pro Iteration */
    private static final int MAX_TOOL_CALLS = 5;

    /**
     * Maximale Verschachtelungstiefe verschachtelter run()-Aufrufe.
     * Orchestrator laeuft auf Tiefe 1, seine Subagenten auf Tiefe 2 — Tiefe 3
     * (Subagent ruft Subagent) wird abgelehnt. Der ReAct-Loop selbst ist
     * iterativ und erhoeht die Tiefe NICHT, nur echte Subagent-Aufrufe tun das.
     */
    private static final int MAX_NESTING_DEPTH = 2;

    private static final ThreadLocal<Integer> NESTING_DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * System-Block fuer Subagenten OHNE Basiskontext.
     * <p>
     * Bewusst eine eigene byte-identische Konstante statt eines leeren
     * Knowledge-Blocks: der Bare-Subagent darf den Shared-Block nicht bekommen,
     * sonst bezahlt er ~1,1k Tokens fuer Daten, die er nicht nutzt. Gemessen
     * gegenueber der KB-Variante: 728 statt 1732 Input-Tokens bei identischer
     * Tool-Liste. Gleichzeitig ensteht ein eigener, kurzer Cache-Block — ein
     * fehlender Hit ist hier billiger als ein bezahlter.
     */
    static final String NO_KNOWLEDGE_SYSTEM =
            "## SHARED KNOWLEDGE BASE ##\n\n(no knowledge base available for this subagent)\n\n--- END OF SHARED KNOWLEDGE ---";

    private final ChatModel chatModel;
    private final String knowledgePrefix;
    private final String headerKey;

    public AgentRunner(ChatModel chatModel, String knowledgePrefix) {
        this(chatModel, knowledgePrefix, null);
    }

    /**
     * @param knowledgePrefix der geteilte Knowledge-Prefix, oder {@code null} /
     *                        leer fuer Subagenten, die keinen Basiskontext
     *                        bekommen duerfen (siehe {@link #NO_KNOWLEDGE_SYSTEM})
     * @param headerKey       Sticky-Routing-Header des Providers, oder
     *                        {@code null} wenn der Provider keins unterstuetzt
     */
    public AgentRunner(ChatModel chatModel, String knowledgePrefix, String headerKey) {
        this.chatModel = chatModel;
        this.knowledgePrefix = knowledgePrefix;
        this.headerKey = headerKey;
    }

    /**
     * Startet den ReAct Loop für einen Agenten.
     * Protokolliert Token-Nutzung pro Schritt und aggregiert am Ende.
     */
    public RunResult run(
            String agentName,
            String agentPrompt,
            String userInput,
            List<ToolSpecification> toolSpecs,
            ToolExecutor toolExecutor,
            String sessionId) {
        return run(agentName, agentPrompt, userInput, toolSpecs, toolExecutor, sessionId, List.of());
    }

/**
 * Runner-Aufruf mit vorherigen Agenten-Handoffs.
 * <p>
 * {@code priorHandoffs} landen am <b>Ende</b> der Message-Liste, nach dem
 * eigenen User-Prompt. Diese Position ist der eigentliche Cache-Schutz: weil
 * kausale Attention nur auf Token zurueckblickt, aendert beliebiger Inhalt an
 * dieser Stelle nichts an dem Prefix davor — der gecachte Bereich waechst sogar
 * mit. Haette man die Handoffs in den System-Block gerendert, waere der gesamte
 * Prefix dahinter wertlos.
 * <p>
 * Das JSON-Schema der Handoffs bringt Struktur und Laengenbegrenzung, aber
 * <b>keinen</b> Cache-Schutz. Beides zu verwechseln ist der haeufigste Irrtum
 * in diesem Bereich.
 * <p>
 * <b>Format — und warum kein {@code role: "tool"}:</b> Die naheliegende Wahl,
 * den Handoff als {@code ToolResultMessage} zu schicken, erzeugt eine
 * <b>verwaiste</b> Tool-Nachricht: {@code role: "tool"} ohne vorangehende
 * {@code assistant}-Nachricht mit passender {@code tool_call_id}. Das Schema der
 * Chat-Completion-API sieht das nicht vor. Provider sind sich uneinig:
 * <ul>
 *   <li>OpenRouter/DeepSeek: toleriert (verifiziert, 200)</li>
 *   <li>openCode Zen / {@code space-bunny-free}: <b>400 invalid_request</b>
 *       (verifiziert) — dieselbe Nachricht, dieselbe Payload</li>
 * </ul>
 * Ein Feature, das auf einem Provider funktioniert und auf dem anderen mit
 * {@code invalid_request} abbricht, ist kein Feature. Deshalb wird der Handoff
 * als <b>UserMessage</b> transportiert — portabel, und semantisch korrekt:
 * es ist ja kein Ergebnis eines Werkzeugaufrufs <i>in diesem</i> Gespräch,
 * sondern eine uebergebene Nachricht.
 *
 * @see SubtaskHandoff
 */
    public RunResult run(
            String agentName,
            String agentPrompt,
            String userInput,
            List<ToolSpecification> toolSpecs,
            ToolExecutor toolExecutor,
            String sessionId,
            List<SubtaskHandoff> priorHandoffs) {

        int depth = NESTING_DEPTH.get();
        if (depth >= MAX_NESTING_DEPTH) {
            throw new IllegalStateException("Subagent nesting beyond " + (MAX_NESTING_DEPTH - 1)
                    + " level(s) refused for '" + agentName
                    + "' — a subagent must not dispatch further subagents");
        }
        NESTING_DEPTH.set(depth + 1);
        try {
            return runLoop(agentName, agentPrompt, userInput, toolSpecs, toolExecutor,
                    sessionId, priorHandoffs);
        } finally {
            // finally ist Pflicht: der Thread kommt in den Servlet-Pool zurueck,
            // ein stehengebliebener Depth-Wert wuerde den naechsten Request blockieren.
            NESTING_DEPTH.set(depth);
        }
    }

    private RunResult runLoop(
            String agentName,
            String agentPrompt,
            String userInput,
            List<ToolSpecification> toolSpecs,
            ToolExecutor toolExecutor,
            String sessionId,
            List<SubtaskHandoff> priorHandoffs) {

        // System-Message ABSICHTLICH rein statisch (SHARED KNOWLEDGE BASE, byte-identisch
        // ueber alle Agents und Turns): Google benoetigt einen immutable systemInstruction
        // fuer Prefix-Caching; ein dynamischer Agenten-Tail im System-Block verhindert das
        // Caching des Blocks (OpenRouter/Google-Best-Practice: Dynamik ans Ende => User-Message).
        String sharedSystem = buildSharedSystem();

        Map<String, Object> customParameters = null;
        // session_id und prompt_cache_key sind OpenRouter-spezifische Erweiterungen
        // (Provider Sticky Routing: pinnt ALLE Requests eines Workflow-Runs auf denselben
        // Upstream-Endpoint, damit der von agent1 geschriebene gemeinsame Prefix-Block von
        // nachfolgenden Agents als Cache-Hit wiederverwendet wird).
        //
        // Gekoppelt an headerKey: ist der Provider nicht als sticky-faehig bekannt, werden
        // die Felder gar nicht erst gesendet. Ein unbekanntes Gateway lehnt sie sonst mit
        // 400 INVALID_ARGUMENT ab (Gemini) — und ein 400 wuerde den Lauf abbrechen, nicht
        // nur das Routing stilllegen.
        if (sessionId != null) {
            // Der ThreadLocal gilt unabhaengig vom Provider: verschachtelte Subagenten
            // brauchen die Run-ID unabhaengig davon, ob Sticky Routing moeglich ist.
            RequestContext.currentRequestId.set(sessionId);

            if (headerKey != null) {
                customParameters = new HashMap<>();
                customParameters.put("session_id", sessionId);
                customParameters.put("prompt_cache_key", sessionId);
                log.info("[{}] Using sticky session routing: {}={}", agentName, headerKey, sessionId);
            } else {
                log.debug("[{}] Sticky routing off (provider has no known header) — custom parameters withheld",
                        agentName);
            }
        }
        var messages = new ArrayList<ChatMessage>();
        messages.add(new SystemMessage(sharedSystem));
        messages.add(UserMessage.from("Execute the following agent prompt."));
        messages.add(UserMessage.from(buildUserPrompt(agentPrompt, userInput)));

        // Handoffs VORHERIGER Agenten — ans Ende, nie in den System-Block.
        // Reihenfolge ist die Ausfuehrungsreihenfolge der Agenten und damit
        // stabil. Als UserMessage, nicht als ToolResultMessage: eine verwaiste
        // Tool-Nachricht wird von streng pruefenden Providern mit
        // 400 invalid_request abgelehnt (siehe run(...)-Javadoc).
        if (priorHandoffs != null && !priorHandoffs.isEmpty()) {
            for (SubtaskHandoff handoff : priorHandoffs) {
                messages.add(UserMessage.from(handoff.renderAsMessage()));
            }
            log.info("[{}] received {} structured handoff(s) from prior agents: {}",
                    agentName, priorHandoffs.size(),
                    priorHandoffs.stream().map(SubtaskHandoff::agent).toList());
        }

        int toolCallCount = 0;
        int requestCount = 0;
        boolean terminatedByHandoff = false;
        String finalAnswer = null;
        List<ToolCall> toolCalls = new ArrayList<>();
        RunResult result = new RunResult(null, "", toolCalls, 0);

        while (toolCallCount <= MAX_TOOL_CALLS) {

            // LangChain4j 1.13: toolSpecifications + OpenRouter session_id laufen ueber
            // die ChatRequest.parameters (nicht beide separat auf dem ChatRequest).
            OpenAiChatRequestParameters.Builder paramsBuilder = OpenAiChatRequestParameters.builder()
                    .toolSpecifications(toolSpecs);
            if (customParameters != null) {
                paramsBuilder.customParameters(customParameters);
            }
            // Die defaultRequestParameters des Models (temperature etc.) werden via
            // defaultRequestParameters().overrideWith(parameters) automatisch gemerged.
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .parameters(paramsBuilder.build())
                    .build();

            ChatResponse response = chatModel.chat(request);
            TokenUsage tokenUsage = extractTokenUsage(response);
            requestCount++;

            // Token-Nutzung dieses Schrittes protokollieren und aggregieren
            logTokenUsage(agentName, toolCallCount, tokenUsage);
            result = result.add(tokenUsage);

            AiMessage aiMessage = response.aiMessage();

            if (!aiMessage.hasToolExecutionRequests()) {
                finalAnswer = aiMessage.text();
                log.info("[{}] Final answer after {} tool calls", agentName, toolCallCount);
                messages.add(aiMessage);
                break;
            }

            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            log.debug("[{}] {} tool call(s): {}", agentName, requests.size(),
                    requests.stream().map(ToolExecutionRequest::name).toList());

            messages.add(aiMessage);
            for (ToolExecutionRequest req : requests) {
                String res;
                boolean ok = true;
                try {
                    res = toolExecutor.execute(req.name(), req.arguments());
                } catch (Exception e) {
                    res = "[ERROR] " + e.getMessage();
                    ok = false;
                    log.warn("[{}] Tool error: {}", agentName, req.name(), e);
                }
                toolCalls.add(new ToolCall(req.name(), req.arguments(), res, toolCallCount, ok));
                messages.add(ToolExecutionResultMessage.from(req.id(), req.name(), res));
                toolCallCount++;

                // TERMINALES Werkzeug, aber nur bei ERFOLG. submit_subtask_summary
                // beendet den Agenten — der Handoff IST sein Ergebnis. Ein vom
                // GatedToolExecutor abgelehnter Aufruf zaehlt nicht: sonst wuerde
                // ein vorzeitiger Handoff den Agenten beenden, obwohl er noch
                // gar nichts geleistet hat.
                //
                // Ohne diese Abbruchbedingung rief das Modell das Werkzeug 5x
                // hintereinander auf, weil ein Tool-Result mit JSON-Echo kein
                // Stoppsignal ist. Der Prompt konnte das nicht erzwingen.
                if (ok && SubtaskHandoff.TOOL_NAME.equals(req.name())) {
                    terminatedByHandoff = true;
                }
            }

            if (terminatedByHandoff) {
                log.info("[{}] Terminated by {} after {} tool call(s) — the handoff is the result",
                        agentName, SubtaskHandoff.TOOL_NAME, toolCallCount);
                break;
            }
        }

        if (finalAnswer == null && !terminatedByHandoff) {
            log.warn("[{}] Hit max tool calls ({}) — fallback", agentName, MAX_TOOL_CALLS);
            return new RunResult(result.tokenUsage(), "", toolCalls, requestCount);
        }

        log.info("[{}] === COMPLETE === ({} total tokens used, {} tool call(s), {} request(s))",
                agentName, result.totalTokens(), toolCalls.size(), requestCount);
        return new RunResult(result.tokenUsage(), finalAnswer == null ? "" : finalAnswer,
                toolCalls, requestCount);
    }

    /**
     * Leitet einen Runner <b>ohne</b> Knowledge Base ab.
     * <p>
     * Bewusst als Methode und nicht als zweiter Spring-Bean: zwei Beans
     * gleichen Typs machen die Constructor-Injektion mehrdeutig, und ein
     * {@code @Primary} wuerde genau die Verwechslung verdecken, die hier
     * teuer ist — ein Agent, der die falsche Variante bekommt, zahlt
     * stillschweigend ~1k Tokens fuer einen Prefix, den er nicht liest.
     * <p>
     * Der abgeleitete Runner teilt ChatModel und headerKey, unterscheidet sich
     * aber im System-Block und damit in einem eigenen Cache-Block.
     */
    public AgentRunner withoutKnowledgeBase() {
        return new AgentRunner(chatModel, null, headerKey);
    }

    /**
     * Ob dieser Runner die Knowledge Base in den System-Block legt.
     */
    public boolean hasKnowledgeBase() {
        return knowledgePrefix != null && !knowledgePrefix.isBlank();
    }

    /**
     * Baut den byte-identischen System-Block.
     * <p>
     * Mit Knowledge-Prefix: der geteilte Block, cachebar ueber alle Agents und Turns.
     * Ohne Prefix (Subagent, der keinen Basiskontext braucht): eine feste Konstante.
     * Beide Zweige sind deterministisch — die Byte-Identitaet innerhalb eines
     * Zweigs ist die eigentliche Cache-Voraussetzung.
     */
    private String buildSharedSystem() {
        if (knowledgePrefix == null || knowledgePrefix.isBlank()) {
            return NO_KNOWLEDGE_SYSTEM;
        }
        return "## SHARED KNOWLEDGE BASE ##\n\n" + knowledgePrefix + "\n\n--- END OF SHARED KNOWLEDGE ---";
    }

    private String buildUserPrompt(String agentPrompt, String userInput) {
        return agentPrompt + "\n\nUSER INPUT:\n" + userInput;
    }

    /**
     * Extrahiert TokenUsage aus der Response.
     * Bevorzugt OpenAI-spezifische Metadaten, damit cachedTokens sichtbar
     * werden — entscheidend für die Prefix-Cache-Sichtbarkeit.
     */
    private TokenUsage extractTokenUsage(ChatResponse response) {
        var metadata = response.metadata();
        if (metadata == null) return null;
        if (metadata instanceof OpenAiChatResponseMetadata oai) {
            return oai.tokenUsage();
        }
        return metadata.tokenUsage();
    }

    private void logTokenUsage(String agentName, int step, TokenUsage usage) {
        if (usage == null) {
            log.debug("[{}] Step {}: no token usage data", agentName, step);
            return;
        }
        if (usage instanceof OpenAiTokenUsage oai) {
            Integer cached = oai.inputTokensDetails() != null
                    ? oai.inputTokensDetails().cachedTokens() : null;
            Integer reasoning = oai.outputTokensDetails() != null
                    ? oai.outputTokensDetails().reasoningTokens() : null;
            log.info("[{}] Step {}: input={} | cached={} | output={} | reasoning={} | total={}",
                    agentName, step,
                    usage.inputTokenCount(),
                    cached != null ? cached : 0,
                    usage.outputTokenCount(),
                    reasoning != null ? reasoning : 0,
                    usage.totalTokenCount());
        } else {
            log.info("[{}] Step {}: input={} | output={} | total={}",
                    agentName, step, usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
        }
    }

    /** Protokolliert Aufruf einer einzelnen Tool-Funktion im ReAct Loop */
    public record ToolCall(String toolName, String arguments, String result, int step, boolean success) {
        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("tool", toolName);
            m.put("arguments", arguments);
            m.put("result", result);
            m.put("step", step);
            m.put("success", success);
            return m;
        }
    }

    /**
     * Ergebnis eines Runner-Durchlaufs.
     *
     * @param requests echte Anzahl der {@code chatModel.chat(...)}-Aufrufe.
     *                Bewusst gezaehlt statt geschaetzt: die Cache-Wirkung
     *                skaliert mit K-1, und eine geschaetzte K wuerde genau die
     *                Groesse verzerren, um die es geht.
     */
    public record RunResult(TokenUsage tokenUsage, String text, List<ToolCall> toolCalls, int requests) {
        public int inputTokens() {
            return tokenUsage != null ? tokenUsage.inputTokenCount() : 0;
        }
        public int outputTokens() {
            return tokenUsage != null ? tokenUsage.outputTokenCount() : 0;
        }
        public int cachedTokens() {
            if (tokenUsage instanceof OpenAiTokenUsage oai && oai.inputTokensDetails() != null) {
                Integer cached = oai.inputTokensDetails().cachedTokens();
                return cached != null ? cached : 0;
            }
            return 0;
        }
        public int totalTokens() {
            return tokenUsage != null ? tokenUsage.totalTokenCount() : 0;
        }
        public RunResult add(TokenUsage other) {
            if (other == null) return this;
            if (tokenUsage == null) return new RunResult(other, this.text, this.toolCalls, this.requests);
            return new RunResult(tokenUsage.add(other), this.text, this.toolCalls, this.requests);
        }
    }
}
