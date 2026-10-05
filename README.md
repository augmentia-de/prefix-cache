# prefix-cache

A Spring Boot + LangChain4j reference implementation for **provider-side prompt prefix caching**, built around one
claim that is easy to state and easy to get wrong:

> **Where** you put context decides whether it is cacheable. Not how much, not how well formatted — where.

Dynamic context at the **end** of the message list invalidates nothing (causal attention only looks backwards), and
the cached region in front of it keeps growing. The same material rendered into the **system block** invalidates the
entire prefix behind it.

Two runnable topologies demonstrate this against the same shared knowledge base:

| Topology | Endpoint | Handoff mechanism |
|---|---|---|
| **3-agent workflow** — `agent1` → `agent2` → `agent3` | `POST /api/workflow` | **Structural.** `WorkflowEngine` pushes `List<SubtaskHandoff>` into the state, `AgentRunner` appends it at the end of the message list. |
| **Subagent demo** — 1 orchestrator + 3 tool-based subagents | `POST /api/subagents` | **Textual.** The orchestrator model copies the results into a tool argument. Two modes (`prefix` vs `args`) that differ *only* in the position of the knowledge base. |

The 3-agent workflow is the baseline; the subagent demo is the negative control. Both stay independently runnable on
purpose — comparing them is the point.

---

## Requirements

- Java 21
- Maven 3.9+
- An OpenAI-compatible endpoint with an API key (OpenAI, OpenRouter, Vercel AI Gateway, …)
- `jq` and `curl` for the demo scripts (optional)

## Quick start

```bash
cp .env.example .env          # then fill in OPENAI_API_KEY
./start.sh                    # sources .env, runs mvn spring-boot:run on :8080
```

`start.sh` also attaches a JDWP agent on port `5005`, so you can attach a debugger right away.
It reads `ENV_FILE` if you want a different env file (e.g. `ENV_FILE=.env.openrouter ./start.sh`).

Verify:

```bash
curl -sS -X POST http://localhost:8080/api/workflow \
  -H 'Content-Type: application/json' \
  -d '{"input":"Describe the geography around Novaris: Mount Thorne, the river Ombra and Cerulean Bay."}' | jq
```

> The application itself reads **environment variables**, not `.env` — the shell scripts are what source the file.

---

## API

### `POST /api/workflow`

Three-stage agent workflow over the shared Novaris knowledge prefix.

```bash
curl -sS -X POST http://localhost:8080/api/workflow \
  -H 'Content-Type: application/json' \
  -d '{"input":"What is Xytherium-7 and how was Novaris founded?"}'
```

| Agent | Prefix | Tools |
|---|---|---|
| `agent1` | shared KB | `analyzeDomain`, `defineTask`, `submit_subtask_summary` |
| `agent2` | shared KB | `submit_subtask_summary` only (analysis tools filtered out by prompt) |
| `agent3` | shared KB | `submit_subtask_summary` (+ optional `defineTask`) |

Response:

```jsonc
{
  "sessionId": "…",
  "status": "completed",
  "results": { "agent1": { "Domain": …, "Task": …, "Connection": …, "tool_calls": […] }, … },
  "handoff_chain": [                                  // who handed what to whom
    { "from": "agent1", "tool": "submit_subtask_summary", "status": "success",
      "key_findings": […], "next_action_recommendation": "…", "bytes": 812 }
  ],
  "token_usage": {
    "agent1": { "input_tokens": …, "cached_tokens": …, "output_tokens": …, "total_tokens": … },
    "agent2": { … },
    "agent3": { … },
    "aggregated_total": { … }
  }
}
```

`handoff_chain` is not decoration: without it you cannot tell whether a handoff happened at all — a silently missing
handoff looks exactly like a clean run.

### `POST /api/subagents`

One orchestrator delegating to three subagents behind tools.

```bash
curl -sS -X POST http://localhost:8080/api/subagents \
  -H 'Content-Type: application/json' \
  -d '{"input":"What is the Lumina festival and what did Mara Quill invent?","mode":"prefix"}'
```

| Subagent | Knowledge base | Purpose |
|---|---|---|
| `lookupEvidence` | yes | Finds the concrete facts (exact names, numbers, years) |
| `crossCheck` | yes | Reports what is supported, contradicted, or uncovered |
| `renderSummary` | **no** | Rewrites the findings into one short paragraph |

`mode` controls **only** where the knowledge base sits:

- `prefix` — system block, cacheable. The intended case.
- `args` — user message, i.e. the dynamic suffix. The negative control: same data, same tool list, different position.

Anything else returns `400` with `allowed: "prefix|args"`.

Response highlights: `answer`, `subagents[]` (per-agent `input_tokens`, `cached_tokens`, `uncached_tokens`,
`hit_percent`, `knowledge_base_via`), `tool_calls[]`, and `token_usage` with real (not estimated) model-call counts.

---

## Demo scripts

```bash
./curl-test.sh                 # three tasks against /api/workflow
./subagents-demo.sh            # both modes, 2 tasks each — the prefix-vs-args comparison

MODES=prefix ./subagents-demo.sh   # one mode only
TASKS=3 ./subagents-demo.sh        # repeat tasks (cache warming)
BASE_URL=http://host:8080 ./curl-test.sh
```

`subagents-demo.sh` prints a token table per mode and ends with notes on how to read it — read those before drawing
conclusions from a single run.

---

## Standalone replay (no Spring)

Replays the exact chat calls of `agent1`, `agent2` and `agent3` with no dependency on the project code — useful for
A/B-ing a provider or a model without restarting the app.

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
javac -cp "$(cat /tmp/cp.txt)" -d /tmp/replay src/main/java/dev/prefix/standalone/StandaloneReplay.java
java -cp "/tmp/replay:$(cat /tmp/cp.txt)" \
     dev.prefix.standalone.StandaloneReplay [knowledgeFile] [userInput]
```

It reads the same `.env` and the same knowledge file as the app, and accepts `--help`.

---

## Configuration

`src/main/resources/application.properties`:

| Property | Env var | Default |
|---|---|---|
| `langchain4j.open-ai.chat-model.api-key` | `OPENAI_API_KEY` | `your-api-key-here` |
| `langchain4j.open-ai.chat-model.base-url` | `OPENAI_BASE_URL` | `https://api.openai.com/v1` |
| `langchain4j.open-ai.chat-model.model-name` | `LANGCHAIN4J_OPEN_AI_CHAT_MODEL_MODEL_NAME` | ⚠️ see below |
| `langchain4j.open-ai.chat-model.temperature` | `LANGCHAIN4J_OPEN_AI_CHAT_MODEL_TEMPERATURE` | `0.7` |
| `prefix.knowledge-file` | — | `classpath:prefix-data/synthetic-knowledge.txt` |
| `prefix.logging.dir` | — | `logs` |
| `server.port` | — | `8080` |

⚠️ **The shipped default for `model-name` is a URL, not a model name.** Without
`LANGCHAIN4J_OPEN_AI_CHAT_MODEL_MODEL_NAME` the request fails with `400` long before caching plays any role. Set it.

The knowledge prefix itself is `src/main/resources/prefix-data/synthetic-knowledge.txt` — 52 lines of synthetic
facts about the invented world of Novaris. Any byte-stable text works; the point is that it never changes.

Request/response pairs are written to `logs/` as `req-<timestamp>.log` / `resp-<timestamp>.log`.

### Sticky routing

Cache hits require landing on the same backend. `LangChain4jConfig.stickyHeaderFor()` sends a session header where
the provider supports one:

| Base URL | Header |
|---|---|
| `https://openrouter.ai/api/v1` | `x-session-id` |
| `https://ai-gateway.vercel.sh/v1` | `x-session-affinity` |
| anything else (incl. `api.openai.com`) | none sent |

---

## Gotchas that will otherwise cost you an afternoon

1. **Cold start.** The first request after process start — or after any tool-list change — reports `cached_tokens=0`
   for the writing request. Repeat the task before reading numbers.
2. **Byte-identity is the whole game.** `ToolProvider` builds tool lists once, deterministically
   (`LinkedHashMap`, no timestamps in `SubtaskHandoff.toToolResult()`). A `HashMap` or a `toString()`-based schema
   silently destroys every cache hit. `ToolDeterminismTest` guards this.
3. **A JSON schema is not cache protection.** The handoff schema gives you structure and length limits; the *position*
   is what keeps the prefix alive. Conflating the two is the most common mistake here.
4. **Do not transport handoffs as `role: "tool"`.** That creates an orphaned tool message (no preceding
   `assistant` message with a matching `tool_call_id`). OpenRouter/DeepSeek tolerates it; other providers answer
   `400 invalid_request`. Handoffs travel as a `UserMessage` — portable and semantically correct.
5. **`mode=args` is a weak negative control.** A *constant* position in the tail is still cacheable by definition, so
   the mode shows that the prefix path is cheaper — not that suffixes are never cacheable. Run-to-run variance exceeds
   the mode effect; compare over several runs.

---

## Project layout

```
src/main/java/dev/prefix/
├── agent/        AgentRunner (ReAct loop, handoff placement), Agent1-3, GatedToolExecutor,
│                 SubtaskHandoff, ToolProvider (the two deterministic tool sets)
├── config/       LangChain4jConfig (model + sticky headers), KnowledgePrefixLoader,
│                 ChatExchangeFileLogger (req/resp files), RequestContext
├── controller/   WorkflowController (/api/workflow), SubAgentController (/api/subagents)
├── service/      WorkflowEngine (3-agent orchestration), SubAgentWorkflowService (subagent demo)
├── state/        WorkflowSessionState (prefix + per-agent results + handoffs)
├── tool/         AnalysisTools, SubAgentTools (subagents behind tools), ToolExecutor
└── standalone/   StandaloneReplay — the same calls without Spring
```

The two tool sets are deliberately kept apart: `ToolProvider.getAll()` (analysis) and
`ToolProvider.getSubAgentTools()` (subagents). Merging them once let `agent1` call a subagent tool in the 3-agent
workflow and burn two of five tool iterations on `[ERROR] Unknown tool`.

Two more guards worth knowing about, both structural rather than prompt-based, because prompts cannot be relied on:

- `GatedToolExecutor` rejects `submit_subtask_summary` until its prerequisites have run. Without it the model used
  the handoff as a shortcut and skipped the analysis in 3 of 4 answers.
- `AgentRunner` caps subagent nesting at depth 2 (orchestrator → subagent). Depth 3 is refused with an
  `IllegalStateException`; the ReAct loop itself does not increase depth.

---

## Tests

```bash
mvn test
```

36 tests, no network required — they use a capturing `ChatModel` fake. Two classes (`ApiKeySmokeTest`,
`GeminiCacheThresholdProbeTest`) are guarded by `@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY")` and skip
unless a key is present; the second is `@Tag("measure")` and does a real token-threshold sweep, so its filler text is
load-bearing — do not shorten it.

| Test | Guards |
|---|---|
| `SystemMessageStaticTest` | The system block is byte-identical across requests |
| `ToolDeterminismTest` | Identical tool specs serialize identically (no `HashMap`, no `toString()` schema) |
| `ToolProviderTest` | The tool registry is immutable and stable across calls |
| `SubtaskHandoffTest` | Handoff **position**, agent1 → agent2 → agent3 |
| `SubAgentPrefixPolicyTest` | Knowledge base position per topology, tool-set separation, nesting refusal |
| `KnowledgePrefixLoaderTest` | Prefix loading (no Spring context needed) |

---

## Measured results

From the subagent demo (see the notes at the bottom of `subagents-demo.sh`):

- KB subagents (`lookupEvidence`, `crossCheck`): **88–95 % cache hit**
- `renderSummary` without a KB: constantly **0 % hit — and that is the point**: ~1550 instead of ~4000 input tokens,
  because it never requests the data. A missing cache hit is cheaper here than a paid one.
- `mode=args`: **0 cache tokens at ~1741 input tokens** — same data, same tool list, different position, and the saving
  disappears.

Further reading in this repo:

- **`context-handoff.md`** — what is actually handed from actor to actor, where it lands, and what is still missing.
  Start with §2 (the one rule), then §5 (handoff to a tool-based subagent) and §8 (open items).
- **`prefix-caching.md`** — the measurements behind the claims above.