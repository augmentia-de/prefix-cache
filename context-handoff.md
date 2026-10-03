# Kontextübergabe unter Prefix-Caching — Subagenten und Workflow-Schritte

Begleitend zu `prefix-caching.md`. Das dort beschreibt *warum* Position alles entscheidet.
Dieses Dokument beantwortet eine andere Frage: **Was wird in diesem Repo tatsächlich von einem
Akteur an den nächsten übergeben, an welcher Stelle es landet, und was fehlt noch.**

Alle Aussagen sind am Code geprüft und mit Datei:Zeile belegt. Messwerte stammen aus
`prefix-caching.md` §4.1 (OpenRouter / `deepseek/deepseek-v4-flash-0731`, warme Läufe).

---

## 1. Kurzfassung

| Frage | Antwort |
|---|---|
| Gibt es Übergabe zwischen Workflow-Schritten? | Ja, **strukturell** — `WorkflowEngine` schiebt `List<SubtaskHandoff>` in den State, `AgentRunner` hängt sie ans Ende. |
| Gibt es Übergabe an toolbasierte Subagenten? | Nur **textuell** — das Orchestrator-Modell kopiert die Ergebnisse per Tool-Argument. |
| Ist eine strukturelle Übergabe an Subagenten möglich? | Ja. **Der Mechanismus existiert bereits und wird nicht benutzt.** |
| Ist sie sinnvoll? | Ja, und sie ist billiger als der Status quo. Details in §5. |
| Ist State-Übergabe an Subagenten möglich? | Ja, aber sie braucht eine bewusste Verdrahtungsentscheidung. §6. |

Die Kernspannung: **im 3-Agenten-Workflow ist die Übergabe Code, in der Subagenten-Demo ist
sie Modell.** Beides funktioniert, aber nur eines davon ist gratis.

---

## 2. Die eine Regel, die über allem steht

Der Mechanismus steckt in `AgentRunner.runLoop` (`src/main/java/dev/prefix/agent/AgentRunner.java:192-209`):

```java
var messages = new ArrayList<ChatMessage>();
messages.add(new SystemMessage(sharedSystem));                    // Block A — byte-identisch
messages.add(UserMessage.from("Execute the following agent prompt."));
messages.add(UserMessage.from(buildUserPrompt(agentPrompt, userInput)));

if (priorHandoffs != null && !priorHandoffs.isEmpty()) {
    for (SubtaskHandoff handoff : priorHandoffs) {
        messages.add(UserMessage.from(handoff.renderAsMessage())); // ans ENDE
    }
}
```

Daraus folgt die einzige Regel, die für jede Übergabe gilt:

> **Dynamischer Kontext ans Ende der Message-Liste.** Dort invalidiert er nichts, weil
> kausale Attention nur rückwärts blickt. Dasselbe Material im System-Block invalidiert
> den gesamten Prefix dahinter.

Drei Fehler, die daraus in der Praxis entstehen und die man bei jeder neuen Übergabe
vermeiden muss:

| Fehler | Folge |
|---|---|
| Übergabe in den System-Block | Jeder Handoff macht den Cache-Block wertlos. |
| Handoff als `role: "tool"` ohne vorangehenden `assistant`-Tool-Call | `400 invalid_request` auf `space-bunny-free`; OpenRouter toleriert es. Verwaiste Nachricht. (`AgentRunner.java:107-123`) |
| Übergabe mit Zeitstempel oder schwankender Schlüsselreihenfolge | Zwei identische Läufe erzeugen verschiedene Bytes → Cache-Miss. Deshalb `LinkedHashMap` und keine Zeitstempel in `SubtaskHandoff.toToolResult()` (`SubtaskHandoff.java:78-93`). |

---

## 3. Was bereits existiert: Workflow-Schritt → Workflow-Schritt

Das ist der ausgereifte Pfad. Er läuft in vier Schritten:

**a) Der Agent gibt den Handoff als Werkzeug ab.** `submit_subtask_summary`, definiert in
`ToolProvider.buildAllTools()` (`ToolProvider.java:118-141`) mit festem Schema.

**b) Die Extraktion ist zentralisiert**, damit alle drei Agenten dasselbe Format erzeugen.
`HandoffSupport.extract()` (`HandoffSupport.java:23-30`). Die Begründung steht im Kommentar
und ist wichtig: drei Kopien bedeuteten, dass Agent3 plötzlich etwas anderes anhängt als Agent2
— ein Cache-Buster.

**c) Der State sammelt.** `WorkflowSessionState.recordHandoff()` (`WorkflowSessionState.java:55`).
Ein Agent trägt genau einen Handoff; ein zweiter Aufruf überschreibt den ersten
(`WorkflowSessionState.java:24-31`) — bewusst, weil zwei widersprüchliche Übergaben schlechter
sind als die spätere.

**d) Die Engine entscheidet, was sichtbar ist.** `WorkflowEngine.execute()`
(`WorkflowEngine.java:64-90`):

```java
List<SubtaskHandoff> accumulated = new ArrayList<>();
for (Agent agent : agents) {
    state.setVisibleHandoffs(accumulated);        // VOR dem Lauf
    agent.execute(userInput, state);
    SubtaskHandoff own = state.handoffOf(agent.name());
    if (own != null) accumulated.add(own);       // nur tatsächlich abgegebene
}
```

Zwei Details, die man nicht verlieren darf, wenn man das erweitert:

- **Die Handoffs werden mitgegeben, nicht aus dem State gelesen** (`WorkflowEngine.java:60-63`).
  Hier weiß die Engine, welcher Agent als welcher kommt. Ein Agent, der aus dem State liest,
  müsste sich auf Bestellung verlassen — daran ist vorheriger Code gescheitert.
- **Ein fehlender Handoff wird nicht getarnt.** `own == null` heißt: der nächste Agent bekommt
  nichts (`WorkflowEngine.java:86-89`). Ein leerer Handoff, der wie ein Ergebnis aussieht, wäre
  die schlechtere Variante.

Damit sieht Agent3 die Handoffs von Agent1 und Agent2 am Ende seines Verlaufs. Das ist
gemessen und getestet: `handoffIsAppendedAtTheEndNotIntoThePrefix`,
`handoffDoesNotLeakIntoTheUserMessageOfThePriorAgents` (`SubtaskHandoffTest`).

---

## 4. Was fehlt: Subagent → Subagent

### 4.1 Der aktuelle Weg: das Modell kopiert

`SubAgentTools.execute` (`SubAgentTools.java:97-105`) ruft drei Subagenten als Werkzeuge auf.
Der dritte bekommt die Ergebnisse der ersten beiden so:

```java
// SubAgentTools.java:112-122
String userInput  = readInput(arguments);
String findings   = readFindings(arguments);      // <- kommt aus den Tool-Argumenten

if (!needsKnowledgeBase) {
    effectivePrompt = prompt + "\n\nFINDINGS FROM THE OTHER SUBAGENTS:\n" + findings;
}
```

Und `readFindings` liest genau das, was das Orchestrator-Modell in `arguments` geschrieben hat
(`SubAgentTools.java:190-197`).

Das bedeutet: **der Orchestrator muss die Ergebnisse von `lookupEvidence` und `crossCheck`
abschreiben.** Sie stehen bereits in seinem Kontext — als `ToolExecutionResultMessage`. Er
tippt sie erneut ab, um sie weiterzugeben.

Der Orchestrator-Prompt sagt das auch ausdrücklich (`SubAgentWorkflowService.java:162-164`):

> `3. Concatenate the outputs of 1 and 2 and pass them VERBATIM as the findings argument of
> renderSummary`

### 4.2 Warum das drei Kosten verursacht

**Output-Tokens für etwas, das schon da ist.** Das Abschreiben ist teuerste Token-Art. Der
Text ist bereits im Kontext — er wird erneut generiert, nur um ihn zu bewegen.

**Drift.** Ein Sprachmodell, das 400 Zeichen Findings umschreibt, verliertZeichen. Der Prompt
sagt „VERBATIM", aber das ist eine Bitte, keine Zusage.

**Der Findings-Text wandert zweimal durch die Tokenabrechnung.** Einmal als
`ToolExecutionResultMessage` im Orchestrator-Suffix, einmal als vom Modell regeneriertes Tool-
Argument, einmal im `renderSummary`-Suffix.

**Verifikation unmöglich.** Es gibt keinen Test, der feststellt könnte, ob die Findings noch
stimmen. `SubAgentPrefixPolicyTest` prüft Cache-Position und Tool-Sätze, nicht die
Findings-Treue.

### 4.3 Der Schlüsselbefund: die passende API wird nicht benutzt

`AgentRunner` hat **zwei** `run(...)`-Überladungen. Die zweite nimmt `priorHandoffs`
(`AgentRunner.java:126-133`).

- Agent1, Agent2, Agent3 benutzen sie (`Agent1.java:104`, `Agent1.java:118`, `Agent2.java:80`, `Agent3.java:85`).
- `SubAgentTools` benutzt die **6-arg**-Variante ohne Handoffs (`SubAgentTools.java:146-153`).

```java
// SubAgentTools.java:146-153 — kein Handoffs-Parameter
result = runner.run(
        agentName, effectivePrompt, userInput, toolSpecs,
        NO_FURTHER_TOOLS, RequestContext.currentRequestId.get());
```

**Der Mechanismus, der Kontext strukturell übergibt, existiert, ist cache-sicher getestet und
wird vom Subagenten-Pfad nicht aufgerufen.** Das ist kein Design-Gap, das eine neue Erfindung
braucht — es ist eine nicht benutzte Methode.

---

## 5. Übergabe an einen toolbasierten Subagenten: möglich und sinnvoll

### 5.1 Ist es möglich?

Ja, auf drei Wegen, in aufsteigender Güte:

**Weg A — SubtaskHandoff wiederverwenden.** `SubAgentTools` sammelt die Rückgaben seiner
eigenen Subagenten und übergibt sie als `List<SubtaskHandoff>` an die 7-arg-`run(...)`.
Position ans Ende, vom Code erzeugt, keine Modellkopie.

**Weg B — Rollen-basierte Auswahl statt Payload.** Statt
`renderSummary(input, findings)` nur noch `renderSummary(input, from)`:

```json
{"input": "Die ursprüngliche Frage",
 "from": ["subagent:lookupEvidence", "subagent:crossCheck"]}
```

Das Modell **entscheidet**, welche Quellen es braucht; **der Code** liefert den Inhalt. Das
trennt die eine Entscheidung, die das Modell gut treffen kann (welche Quellen?) von der, die es
schlecht trifft (exakter Wortlaut). Das ist dieselbe Argumentation wie bei
`renderSummary` ohne Knowledge Base: Bedarf steuern, Inhalt nicht.

**Weg C — Loop-Budget begrenzen.** Falls die Findings sehr groß sind, in `runLoop` eine
Obergrenze für den angehängten Block einführen und den Rest markieren. Die Loop-Bedingung
`toolCallCount <= MAX_TOOL_CALLS` (`AgentRunner.java:218`) ist bereits die richtige Stelle.

### 5.2 Ist es sinnvoll?

Ja — mit einer Bedingung, die leicht übersehen wird.

> **Dynamischer Kontext kostet K-mal, gecachter Prefix einmal.**

Jede Iteration des ReAct-Loops sendet die **komplette** Message-Liste erneut
(`AgentRunner.java:229-232`). Ein angehängter Block von *n* Tokens, den ein Subagent mit
K Iterationen bekommt, kostet **n × n** Token im Input. Der geteilte Prefix dagegen wird nach
dem ersten Mal gecacht und kostet nur einmal 1536 Token — unabhängig von K.

Deshalb:

| | Messung | K | ungecacheter Input |
|---|---|---:|---:|
| `renderSummary` heute | 1571 input, 0 cached | 1 | 1571 |
| dasselbe, wenn der Block 400 Token trägt und K=3 | ~3400 input | 3 | ~3400 |

`renderSummary` hat heute K=1 und ist damit der **günstigste** Ort für dynamischen Kontext
(`prefix-caching.md` §4.1). Genau deshalb ist Weg B hier richtig und Weg „alles an alle
Subagenten" falsch.

Der bestehende Befund stützt das: `renderSummary` bekommt die Knowledge Base bewusst **nicht**
und spart dadurch ~1000 Input-Tokens. Der analoge Schluss für Findings: **gezielt, nicht
vollständig.**

### 5.3 Was ausdrücklich nicht geändert wird

`ToolProvider.getSubAgentTools()` enthält **kein** `submit_subtask_summary`, und das ist
getestet (`handoffIsNotOfferedToTheSubagentDemo`, `SubtaskHandoffTest.java:167-181`).

Das bleibt richtig, denn bei Weg A/B gibt der Subagent keinen Handoff ab — er bekommt einen.
Ein sichtbares Handoff-Werkzeug ohne Abnehmer wäre eine Sackgasse. Der Test sollte bleiben.

---

## 6. Übergabe in den Workflow-State und Nutzung in folgenden Schritten

### 6.1 Was schon da ist

`WorkflowSessionState` (`state/WorkflowSessionState.java`) hält zwei Dinge:

- `handoffs` / `visibleHandoffs` — die strukturierte Agenten-Kette (Zeilen 31-38)
- `agentResults` — eine generische Map, in die jeder Agent sein Ergebnis schreibt (Zeilen 21, 102-112)
- `cachePrefix` — der geteilte Prefix, Zeilen 41, 87-93

`getAgentResult(name)` ist damit der Zugriff für **spätere Workflow-Schritte**, und er wird
aktuell nur zum Reporting verwendet — nicht, um Kontext in einen späteren Schritt zu geben.

### 6.2 Die eigentliche Blockade

Für einen **Tool-Subagenten** ist der Zustand nicht erreichbar. Nicht weil die Mechanismus
fehlt, sondern weil die Schnittstelle ihn nicht transportiert:

```java
// tool/ToolExecutor.java — vollständige Signatur
public interface ToolExecutor {
    String execute(String toolName, String arguments);
}
```

Kein Zustand, keine Session, kein Run-Kontext. Die Javadoc von `SubAgentTools` benennt das
selbst (`SubAgentTools.java:51-54`): *„die Tool-Schnittstelle hat keinen Run-Kontext"*.

Daraus folgt: **Übergabe in den Workflow-State und Nutzung in einem Tool-Subagenten geht nicht
über die Tool-Schnittstelle.** Sie muss am Subagenten-Objekt hängen.

### 6.3 Der richtige Weg: Konstruktor, nicht Interface

`SubAgentTools` wird **pro Lauf** erzeugt (`SubAgentWorkflowService.java:66-67`), nicht als
Singleton. Der Konstruktor kann den Zustand also gefahrlos entgegennehmen, ohne dass zwei
gleichzeitige Läufe sich vermischen — genau das Risiko, vor dem der Kommentar in
`SubAgentTools.java:51-54` warnt.

> **Konstruktor statt Interface-Änderung.** `ToolExecutor` auf eine
> `execute(name, args, context)`-Signatur umzubauen klingt sauberer, hätte aber einen
> wellenförmigen Effekt auf `AnalysisTools`, `GatedToolExecutor`, `NO_FURTHER_TOOLS` und alle
> Tests — für eine Übergabe, die nur eine Klasse braucht. `GatedToolExecutor` delegiert
> (`GatedToolExecutor.java:65`); bei einer Interface-Änderung müsste er den Kontext
> durchreichen.

### 6.4 Die offene Entscheidung

In der aktuellen Architektur liefert `SubAgentWorkflowService` **keinen** `WorkflowSessionState`
zurück — das ist eine bewusste Entscheidung (`SubAgentWorkflowService.java:19-22`): die
Subagenten-Topologie darf die 3-Agenten-Sequenz nicht verändern, weil deren Messwerte als
Vergleichsbasis dienen.

Wer beides verbinden will, muss sich festlegen, welches der beiden Konzepte gilt:

| Variante | State's Bedeutung | Tool-Satz | Vergleichbarkeit |
|---|---|---|---|
| getrennt lassen (heute) | keiner für Subagenten | eigener Satz | vollständig |
| Subagenten hängen an denselben State | ein gemeinsamer Ablauf | dann **ein** Satz für alle | Tool-Liste ändert sich → Kaltstart |
| zweiter State, nur für Subagenten | getrennte Kette | eigener Satz bleibt | vollständig, aber zwei Zustände |

Die dritte Variante ist die ehrliche, wenn beide Topologien nebeneinander messbar bleiben
sollen. Die zweite ist die einfachere, kostet aber einen Cache-Kaltstart, weil sich die
serialisierte Tool-Liste ändert (`ToolProvider.java:36-42`).

---

## 7. Wo es nicht sinnvoll ist

**Alles an alles übergeben.** Der Fehler, den man bei `scope: all` in klassischen Workflow-Engines
macht. Der dynamische Suffix wächst mit jedem Schritt und wird K-mal je Schritt bezahlt (§5.2).

**Den State in den System-Block rendern.** Invalidiert den Prefix. Der häufigste Fehler, weil
er lokal richtig aussieht: ein System-Prompt mit angehängtem „bisherige Ergebnisse" wirkt
inhaltlich korrekt und kostet bei jedem Lauf den kompletten Cache-Block.

**Findings als Fließtext im System-Prompt des Orchestrators.** Derselbe Fehler eine Ebene tiefer.

**Den Findings-Text doppelt zahlen.** Status quo: Findings sind einmal im
`ToolExecutionResultMessage` des Orchestrators, einmal als regeneriertes Tool-Argument im
Prompt des Subagenten. Weg A/B zahlt ihn nur noch an der Stelle, wo er hingehört.

**Subagenten ohne Bedarf an den Prefix hängen.** `renderSummary` spart das nicht zufällig,
sondern weil es die Daten nicht braucht. Diese Entscheidung ist pro Subagent zu treffen und
gehört an die Verdrahtung (`kbRunner` / `bareRunner`), nicht an die Tool-Liste.

---

## 8. Zusammenfassung der offenen Punkte

Nach Dringlichkeit, jeweils mit dem Ort:

| # | Punkt | Ort | Aufwand |
|---|---|---|---|
| 1 | Subagent-Ergebnisse strukturell sammeln, statt das Modell kopieren zu lassen | `SubAgentTools.java:146` | klein |
| 2 | Gesammelte Ergebnisse über die vorhandene 7-arg-`run(...)` anhängen | `SubAgentTools.java:146-153` | sehr klein |
| 3 | `findings` als Quell-Referenz statt Payload (`from: [...]`) | `ToolProvider.java:183-192` | klein |
| 4 | Ausgewählte Agent-Ergebnisse in späteren Workflow-Schritten als Kontext nutzbar machen | `WorkflowSessionState.java:110` | mittel |
| 5 | Entscheiden: gemeinsamer oder zweiter State für Tool-Subagenten | `SubAgentWorkflowService.java:58` | Design |
| 6 | Obergrenze für den angehängten Block (`n × n`-Falle) | `AgentRunner.java:192-209` | mittel |

Punkt 1 und 2 zusammen sind die eigentliche Erkenntnis dieses Dokuments: **die Code-Übergabe
existiert bereits, wird nur im Subagenten-Pfad nicht benutzt.**

---

## 9. Nebenbefund: ein Konfigurationsfehler

`src/main/resources/application.properties:4`:

```properties
langchain4j.open-ai.chat-model.model-name=${LANGCHAIN4J_OPEN_AI_CHAT_MODEL_MODEL_NAME:https://api.openai.com/v1}
```

Der Vorgabewert ist eine **URL**, kein Modellname — vermutlich beim Kopieren der Zeile darüber
entstanden. Ohne gesetzte Umgebungsvariable sendet jeder Lauf `model: "https://api.openai.com/v1"`
und scheitert mit 400, noch bevor der Cache eine Rolle spielt. Erwähnenswert, weil die Messungen
in §5.2 nur mit gesetzter Variable entstanden sein können.

---

## 10. Kurzfassung in drei Sätzen

Position schlägt Struktur: Kontext, der sich ändert, gehört ans Ende der Message-Liste, dann
invalidiert er nichts. Der 3-Agenten-Workflow macht das über Code (`WorkflowEngine` → `State` →
`AgentRunner`), die Subagenten-Demo lässt das Modell denselben Text abschreiben und neu
tippen — teurer, driftanfällig und unprüfbar. Die Übergabe nötig ist der Code bereits da:
`AgentRunner.run(..., List<SubtaskHandoff>)` wird von Agent1/2/3 benutzt und von
`SubAgentTools` nicht.