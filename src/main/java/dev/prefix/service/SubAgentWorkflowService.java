package dev.prefix.service;

import dev.prefix.agent.AgentRunner;
import dev.prefix.agent.ToolProvider;
import dev.prefix.config.KnowledgePrefixLoader;
import dev.prefix.tool.SubAgentTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ein Orchestrator, drei Subagenten ueber Tools — zwei davon mit Basiskontext.
 * <p>
 * Bewusst <b>kein</b> {@code Agent}-Interface und nicht Teil von
 * {@link WorkflowEngine}: diese Topologie darf die bestehende 3-Agenten-Sequenz
 * nicht veraendern, weil sie deren Messwerte als Vergleichsbasis dient. Ein
 * eigener Pfad haelt beide Laeufe nebeneinander vergleichbar.
 *
 * <h2>Request-Bilanz</h2>
 * <pre>
 *   orchestrator turn 1  System = P (full)          -> schreibt den Cache-Block
 *   lookupEvidence      System = P (byte-identisch) -> HIT
 *   crossCheck          System = P (byte-identisch) -> HIT
 *   renderSummary       System = P' (kurz)          -> eigener, winziger Block
 *   orchestrator turn 2  System = P (byte-identisch) -> HIT
 * </pre>
 * K = 5, davon 4 mit dem geteilten Prefix.
 */
@Service
public class SubAgentWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(SubAgentWorkflowService.class);

    private final AgentRunner kbRunner;
    private final AgentRunner bareRunner;
    private final KnowledgePrefixLoader knowledgeLoader;

    public SubAgentWorkflowService(AgentRunner kbRunner,
                                   KnowledgePrefixLoader knowledgeLoader) {
        this.kbRunner = kbRunner;
        // Abgeleitet statt injiziert — siehe AgentRunner#withoutKnowledgeBase().
        this.bareRunner = kbRunner.withoutKnowledgeBase();
        this.knowledgeLoader = knowledgeLoader;
    }

    /**
     * Fuehrt einen Orchestrator-Lauf mit drei Tool-Subagenten aus.
     *
     * @param mode wie die Knowledge Base an die KB-Subagenten gelangt —
     *             {@link SubAgentTools.Mode#VIA_PREFIX} (Soll-Fall) oder
     *             {@link SubAgentTools.Mode#VIA_ARGUMENTS} (Negativprobe)
     */
    public Map<String, Object> execute(String userInput, SubAgentTools.Mode mode) {
        String sessionId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        log.info("[SubAgentWorkflow] mode={} session={}", mode, sessionId);

        // Pro Lauf eine frische Instanz — sonst wuerden die Metriken
        // konkurrierender Laeufe durcheinandergeraten.
        SubAgentTools subAgents = new SubAgentTools(
                kbRunner, bareRunner, knowledgeLoader.getPrefix(), mode);

        AgentRunner.RunResult orchestrator = kbRunner.run(
                "orchestrator",
                ORCHESTRATOR_PROMPT,
                userInput,
                ToolProvider.getSubAgentTools(),
                subAgents,
                sessionId);

        List<SubAgentTools.SubAgentMetrics> subMetrics = subAgents.metrics();

        int subInput = 0, subCached = 0, subOutput = 0, subRequests = 0;
        for (SubAgentTools.SubAgentMetrics m : subMetrics) {
            subInput += m.inputTokens();
            subCached += m.cachedTokens();
            subOutput += m.outputTokens();
            subRequests += m.requests();
        }
        int totalInput = orchestrator.inputTokens() + subInput;
        int totalCached = orchestrator.cachedTokens() + subCached;

        List<Map<String, Object>> subAgentsOut = subMetrics.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agent", m.agent());
            row.put("knowledge_base_required", m.knowledgeBaseRequired());
            row.put("knowledge_base_via", m.knowledgeBaseViaPrefix() ? "system_prefix"
                    : (m.knowledgeBaseRequired() ? "user_message" : "none"));
            row.put("requests", m.requests());
            row.put("input_tokens", m.inputTokens());
            row.put("cached_tokens", m.cachedTokens());
            row.put("uncached_tokens", m.uncachedTokens());
            row.put("output_tokens", m.outputTokens());
            row.put("total_tokens", m.totalTokens());
            row.put("hit_percent", m.hitPercent());
            if (m.error() != null) row.put("error", m.error());
            return row;
        }).toList();

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("input_tokens", totalInput);
        totals.put("cached_tokens", totalCached);
        totals.put("uncached_tokens", Math.max(0, totalInput - totalCached));
        totals.put("output_tokens", orchestrator.outputTokens() + subOutput);
        totals.put("orchestrator_input_tokens", orchestrator.inputTokens());
        totals.put("orchestrator_cached_tokens", orchestrator.cachedTokens());
        totals.put("orchestrator_requests", orchestrator.requests());
        totals.put("subagent_input_tokens", subInput);
        totals.put("subagent_cached_tokens", subCached);
        totals.put("subagent_uncached_tokens", Math.max(0, subInput - subCached));
        totals.put("subagent_requests", subRequests);
        totals.put("hit_percent", totalInput > 0
                ? Math.round(1000.0 * totalCached / totalInput) / 10.0
                : 0.0);
        // ECHTE Modell-Aufrufe, nicht geschaetzt. Die Cache-Wirkung skaliert
        // mit K-1; eine geschaetzte K verzerrt genau die Groesse, um die es geht.
        totals.put("requests", orchestrator.requests() + subRequests);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", sessionId);
        result.put("mode", mode.name());
        result.put("duration_ms", System.currentTimeMillis() - start);
        result.put("answer", orchestrator.text());
        result.put("subagents", subAgentsOut);
        result.put("tool_calls", orchestrator.toolCalls().stream()
                .map(AgentRunner.ToolCall::toMap).toList());
        result.put("token_usage", totals);

        log.info("[SubAgentWorkflow] done in {}ms — input={} cached={} ({} subagent requests)",
                result.get("duration_ms"), orchestrator.inputTokens() + subInput,
                orchestrator.cachedTokens() + subCached, subMetrics.size());

        return result;
    }

    /**
     * Der Orchestrator-Prompt.
     * <p>
     * Gehoert bewusst in die User-Message: er ist pro Lauf dynamisch
     * (nennt die konkrete Frage) und wuerde im System-Block den Cache
     * invalidieren. Der System-Block bleibt der byte-identische Prefix.
     */
    private static final String ORCHESTRATOR_PROMPT = """
            You are the orchestrator of a multi-agent workflow. You do not do the work
            yourself — you delegate to three subagents and then synthesize their results.

            YOUR SUBAGENTS:
            1. lookupEvidence(input)  — has the shared knowledge base. Finds the concrete
               facts (exact names, numbers, years) that bear on the request.
            2. crossCheck(input)      — has the shared knowledge base. Reports which parts
               of the request are supported, contradicted, or not covered by it.
            3. renderSummary(input, findings) — has NO knowledge base. Rewrites the findings
               you hand it into one short, fluent paragraph.

            HOW TO WORK:
            1. Call lookupEvidence with the user's request.
            2. Call crossCheck with the user's request.
            3. Concatenate the outputs of 1 and 2 and pass them VERBATIM as the
               `findings` argument of renderSummary, together with the original request.
            4. Only after all three have returned, write the final answer to the user —
               prefer the paragraph renderSummary produced; extend it only if it misses
               a point you can support from the findings.

            Do not answer from your own memory of the shared knowledge base: it is your
            subagents' job to read it, and yours to route and combine.
            """;
}