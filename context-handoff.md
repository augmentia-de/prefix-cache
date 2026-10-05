# Context handoff under prefix caching — subagents and workflow steps

Companion to `prefix-caching.md`. That document describes *why* position decides everything.
This document answers a different question: **What is actually handed over from one actor to
the next in this repo, where it lands, and what is still missing.**

All statements were checked against the code and are backed by file:line. Measurements come from
`prefix-caching.md` §4.1 (OpenRouter / `deepseek/deepseek-v4-flash-0731`, warm runs).

---

## 1. Summary

| Question | Answer |
|---|---|
| Is there handoff between workflow steps? | Yes, **structural** — `WorkflowEngine` pushes `List<SubtaskHandoff>` into the state, `AgentRunner` appends them to the end. |
| Is there handoff to tool-based subagents? | Only **textual** — the orchestrator model copies the results via tool argument. |
| Is structural handoff to subagents possible? | Yes. **The mechanism already exists and is not used.** |
| Is it sensible? | Yes, and it is cheaper than the status quo. Details in §5. |
| Is state handoff to subagents possible? | Yes, but it needs a deliberate wiring decision. §6. |

The core tension: **in the 3-agent workflow the handoff is code, in the subagent demo it
is the model.** Both work, but only one of them is free.

---

## 2. The one rule that outranks everything

The mechanism lives in `AgentRunner.runLoop` (`src/main/java/dev/prefix/agent/AgentRunner.java:192-209`):

```java
var messages = new ArrayList<ChatMessage>();
messages.add(new SystemMessage(sharedSystem));                    // Block A — byte-identical
messages.add(UserMessage.from("Execute the following agent prompt."));
messages.add(UserMessage.from(buildUserPrompt(agentPrompt, userInput)));

if (priorHandoffs != null && !priorHandoffs.isEmpty()) {
    for (SubtaskHandoff handoff : priorHandoffs) {
        messages.add(UserMessage.from(handoff.renderAsMessage())); // at the END
    }
}
```

The following rule follows from this, and it governs every handoff:

> **Dynamic context at the end of the message list.** There it invalidates nothing, because
> causal attention only looks backwards. The same material in the system block invalidates
> the entire prefix behind it.

Three errors that arise from this in practice and that every new handoff
must avoid:

| Error | Consequence |
|---|---|
| Handoff into the system block | Every handoff renders the cache block worthless. |
| Handoff as `role: "tool"` without a preceding `assistant` tool call | `400 invalid_request` on `space-bunny-free`; OpenRouter tolerates it. Orphaned message. (`AgentRunner.java:107-123`) |
| Handoff with timestamp or varying key order | Two identical runs produce different bytes → cache miss. Hence `LinkedHashMap` and no timestamps in `SubtaskHandoff.toToolResult()` (`SubtaskHandoff.java:78-93`). |

---

## 3. What already exists: workflow step → workflow step

This is the mature path. It runs in four steps:

**a) The agent submits the handoff as a tool.** `submit_subtask_summary`, defined in
`ToolProvider.buildAllTools()` (`ToolProvider.java:118-141`) with a fixed schema.

**b) Extraction is centralized**, so that all three agents produce the same format.
`HandoffSupport.extract()` (`HandoffSupport.java:23-30`). The rationale is in the comment
and it matters: three copies would mean that Agent3 suddenly appends something different than Agent2
— a cache buster.

**c) The state collects.** `WorkflowSessionState.recordHandoff()` (`WorkflowSessionState.java:55`).
An agent carries exactly one handoff; a second call overwrites the first
(`WorkflowSessionState.java:24-31`) — deliberately, because two contradictory handoffs are worse
than the later one.

**d) The engine decides what is visible.** `WorkflowEngine.execute()`
(`WorkflowEngine.java:64-90`):

```java
List<SubtaskHandoff> accumulated = new ArrayList<>();
for (Agent agent : agents) {
    state.setVisibleHandoffs(accumulated);        // BEFORE the run
    agent.execute(userInput, state);
    SubtaskHandoff own = state.handoffOf(agent.name());
    if (own != null) accumulated.add(own);       // only actually submitted
}
```

Two details you must not lose when extending this:

- **The handoffs are passed along, not read from the state** (`WorkflowEngine.java:60-63`).
  Here the engine knows which agent comes as which. An agent that reads from the state
  would have to rely on ordering — previous code failed at that.
- **A missing handoff is not disguised.** `own == null` means: the next agent gets
  nothing (`WorkflowEngine.java:86-89`). An empty handoff that looks like a result would be
  the worse variant.

That way Agent3 sees the handoffs of Agent1 and Agent2 at the end of its conversation history. That is
measured and tested: `handoffIsAppendedAtTheEndNotIntoThePrefix`,
`handoffDoesNotLeakIntoTheUserMessageOfThePriorAgents` (`SubtaskHandoffTest`).

---

## 4. What is missing: subagent → subagent

### 4.1 The current path: the model copies

`SubAgentTools.execute` (`SubAgentTools.java:97-105`) invokes three subagents as tools.
The third receives the results of the first two like this:

```java
// SubAgentTools.java:112-122
String userInput  = readInput(arguments);
String findings   = readFindings(arguments);      // <- comes from the tool arguments

if (!needsKnowledgeBase) {
    effectivePrompt = prompt + "\n\nFINDINGS FROM THE OTHER SUBAGENTS:\n" + findings;
}
```

And `readFindings` reads exactly what the orchestrator model wrote into `arguments`
(`SubAgentTools.java:190-197`).

That means: **the orchestrator must transcribe the results of `lookupEvidence` and `crossCheck`.**
They are already in its context — as `ToolExecutionResultMessage`. It
re-types them to pass them on.

The orchestrator prompt says so explicitly (`SubAgentWorkflowService.java:162-164`):

> `3. Concatenate the outputs of 1 and 2 and pass them VERBATIM as the findings argument of
> renderSummary`

### 4.2 Why this causes three costs

**Output tokens for something that is already there.** Transcription is the most expensive token kind. The
text is already in the context — it is generated again, only to move it.

**Drift.** A language model that rewrites 400 characters of findings loses characters. The prompt
says „VERBATIM", but that is a request, not a guarantee.

**The findings text passes through token billing twice.** Once as
`ToolExecutionResultMessage` in the orchestrator suffix, once as a tool argument regenerated by
the model, once in the `renderSummary` suffix.

**Verification impossible.** There is no test that could determine whether the findings are still
accurate. `SubAgentPrefixPolicyTest` checks cache position and tool sets, not the
fidelity of the findings.

### 4.3 The key finding: the right API is not used

`AgentRunner` has **two** `run(...)` overloads. The second takes `priorHandoffs`
(`AgentRunner.java:126-133`).

- Agent1, Agent2, Agent3 use them (`Agent1.java:104`, `Agent1.java:118`, `Agent2.java:80`, `Agent3.java:85`).
- `SubAgentTools` uses the **6-arg** variant without handoffs (`SubAgentTools.java:146-153`).

```java
// SubAgentTools.java:146-153 — no handoffs parameter
result = runner.run(
        agentName, effectivePrompt, userInput, toolSpecs,
        NO_FURTHER_TOOLS, RequestContext.currentRequestId.get());
```

**The mechanism that hands over context structurally exists, is cache-safely tested and is
not called by the subagent path.** This is not a design gap that needs a new invention
— it is an unused method.

---

## 5. Handoff to a tool-based subagent: possible and sensible

### 5.1 Is it possible?

Yes, via three paths, in ascending quality:

**Path A — reuse SubtaskHandoff.** `SubAgentTools` collects the returns of its
own subagents and hands them to the 7-arg `run(...)` as `List<SubtaskHandoff>`.
Position at the end, produced by code, no model copy.

**Path B — role-based selection instead of payload.** Instead of
`renderSummary(input, findings)` now only `renderSummary(input, from)`:

```json
{"input": "The original question",
 "from": ["subagent:lookupEvidence", "subagent:crossCheck"]}
```

The model **decides** which sources it needs; **the code** delivers the content. That
separates the one decision the model can make well (which sources?) from the one it makes
badly (exact wording). This is the same argument as with
`renderSummary` without knowledge base: steer need, not content.

**Path C — bound the loop budget.** If the findings are very large, introduce in `runLoop` an
upper bound for the appended block and mark the rest. The loop condition
`toolCallCount <= MAX_TOOL_CALLS` (`AgentRunner.java:218`) is already the right place.

### 5.2 Is it sensible?

Yes — with one condition that is easy to overlook.

> **Dynamic context costs K times, the cached prefix once.**

Every iteration of the ReAct loop sends the **complete** message list again
(`AgentRunner.java:229-232`). An appended block of *n* tokens that a subagent receives with
K iterations costs **n × n** tokens of input. The shared prefix, on the other hand, is cached
after the first time and costs only 1536 tokens once — independent of K.

Therefore:

| | Measurement | K | uncached input |
|---|---|---:|---:|
| `renderSummary` today | 1571 input, 0 cached | 1 | 1571 |
| the same, when the block carries 400 tokens and K=3 | ~3400 input | 3 | ~3400 |

`renderSummary` has K=1 today and is therefore the **cheapest** place for dynamic context
(`prefix-caching.md` §4.1). Precisely for that reason path B is right here and the path „everything to all
subagents" is wrong.

The existing finding supports this: `renderSummary` deliberately does **not** get the knowledge base
and thereby saves ~1000 input tokens. The analogous conclusion for findings: **targeted, not
complete.**

### 5.3 What is explicitly not changed

`ToolProvider.getSubAgentTools()` contains **no** `submit_subtask_summary`, and that is
tested (`handoffIsNotOfferedToTheSubagentDemo`, `SubtaskHandoffTest.java:167-181`).

That stays correct, because with path A/B the subagent does not submit a handoff — it receives one.
A visible handoff tool without a consumer would be a dead end. The test should stay.

---

## 6. Handoff into the workflow state and use in following steps

### 6.1 What is already there

`WorkflowSessionState` (`state/WorkflowSessionState.java`) holds two things:

- `handoffs` / `visibleHandoffs` — the structured agent chain (lines 31-38)
- `agentResults` — a generic map into which every agent writes its result (lines 21, 102-112)
- `cachePrefix` — the shared prefix, lines 41, 87-93

`getAgentResult(name)` is thus the access path for **later workflow steps**, and it is
currently used only for reporting — not to give context to a later step.

### 6.2 The actual blocker

For a **tool subagent** the state is not reachable. Not because the mechanism
is missing, but because the interface does not transport it:

```java
// tool/ToolExecutor.java — full signature
public interface ToolExecutor {
    String execute(String toolName, String arguments);
}
```

No state, no session, no run context. The Javadoc of `SubAgentTools` names that
itself (`SubAgentTools.java:51-54`): *„the tool interface has no run context"*.

It follows: **handoff into the workflow state and use in a tool subagent does not work
via the tool interface.** It has to hang on the subagent object.

### 6.3 The right path: constructor, not interface

`SubAgentTools` is created **per run** (`SubAgentWorkflowService.java:66-67`), not as a
singleton. The constructor can therefore safely accept the state, without two
concurrent runs mixing themselves — exactly the risk that the comment in
`SubAgentTools.java:51-54` warns about.

> **Constructor instead of an interface change.** Rebuilding `ToolExecutor` around an
> `execute(name, args, context)` signature sounds cleaner, but would have a
> rippling effect on `AnalysisTools`, `GatedToolExecutor`, `NO_FURTHER_TOOLS` and all
> tests — for a handoff that needs only one class. `GatedToolExecutor` delegates
> (`GatedToolExecutor.java:65`); with an interface change it would have to pass the context
> through.

### 6.4 The open decision

In the current architecture `SubAgentWorkflowService` returns **no** `WorkflowSessionState`
— that is a deliberate decision (`SubAgentWorkflowService.java:19-22`): the
subagent topology must not change the 3-agent sequence, because its measurements serve as
the baseline.

Whoever wants to connect both must decide which of the two concepts holds:

| Variant | Meaning of the state | Tool set | Comparability |
|---|---|---|---|
| keep separate (today) | none for subagents | own set | complete |
| subagents hang on the same state | one shared sequence | then **one** set for all | tool list changes → cold start |
| second state, only for subagents | separate chain | own set stays | complete, but two states |

The third variant is the honest one if both topologies are to remain measurable
side by side. The second is the simpler one, but costs a cache cold start, because the
serialized tool list changes (`ToolProvider.java:36-42`).

---

## 7. Where it does not make sense

**Handing everything to everything.** The mistake one makes with `scope: all` in classic workflow engines.
The dynamic suffix grows with every step and is paid for K times per step (§5.2).

**Rendering the state into the system block.** Invalidates the prefix. The most frequent mistake, because
it looks locally correct: a system prompt with appended „previous results" looks
correct in content and costs the complete cache block on every run.

**Findings as prose in the orchestrator's system prompt.** The same mistake one level deeper.

**Paying for the findings text twice.** Status quo: the findings are once in the
orchestrator's `ToolExecutionResultMessage`, once as a regenerated tool argument in the
subagent's prompt. Path A/B pays for it only where it belongs.

**Hanging subagents off the prefix without need.** `renderSummary` does not save this by chance,
but because it does not need the data. This decision has to be made per subagent and
belongs to the wiring (`kbRunner` / `bareRunner`), not to the tool list.

---

## 8. Summary of the open items

By urgency, each with its location:

| # | Item | Location | Effort |
|---|---|---|---|
| 1 | Collect subagent results structurally, instead of letting the model copy them | `SubAgentTools.java:146` | small |
| 2 | Append the collected results via the existing 7-arg `run(...)` | `SubAgentTools.java:146-153` | very small |
| 3 | `findings` as a source reference instead of a payload (`from: [...]`) | `ToolProvider.java:183-192` | small |
| 4 | Make selected agent results usable as context in later workflow steps | `WorkflowSessionState.java:110` | medium |
| 5 | Decide: shared or second state for tool subagents | `SubAgentWorkflowService.java:58` | design |
| 6 | Upper bound for the appended block (the `n × n` trap) | `AgentRunner.java:192-209` | medium |

Points 1 and 2 together are the actual finding of this document: **the code handoff
already exists, it is just not used in the subagent path.**

---

## 9. Side finding: a configuration error

`src/main/resources/application.properties:4`:

```properties
langchain4j.open-ai.chat-model.model-name=${LANGCHAIN4J_OPEN_AI_CHAT_MODEL_MODEL_NAME:https://api.openai.com/v1}
```

The default value is a **URL**, not a model name — probably created when copying the line above.
Without the environment variable set, every run sends `model: "https://api.openai.com/v1"`
and fails with 400, before the cache even plays a role. Worth mentioning because the measurements
in §5.2 can only have been produced with the variable set.

---

## 10. Summary in three sentences

Position beats structure: context that changes belongs at the end of the message list, then
it invalidates nothing. The 3-agent workflow does this via code (`WorkflowEngine` → `State` →
`AgentRunner`), the subagent demo has the model transcribe the same text again and newly
type it — more expensive, drift-prone and unverifiable. Where a handoff is needed the code is already there:
`AgentRunner.run(..., List<SubtaskHandoff>)` is used by Agent1/2/3 and not by
`SubAgentTools`.