package dev.prefix.agent;

import dev.prefix.tool.ToolExecutor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executor, der ein terminales Werkzeug erst zulässt, nachdem die
 * Voraussetzungen erfüllt sind.
 *
 * <h2>Warum das nötig ist</h2>
 * Realer Befund aus dem Komplettlauf: nachdem {@code submit_subtask_summary}
 * terminal gemacht wurde, rief das Modell in 3 von 4 Antworten <b> nur</b> das
 * Handoff-Werkzeug auf und übersprang die eigentliche Analyse. Der Prompt
 * sagte "genau einmal, als letzte Aktion" — das hinderte die Wiederholung, aber
 * nicht das Überspringen der Arbeit. Ein Prompt kann eine Optimierung des
 * Modells nicht zuverlässig verhindern.
 *
 * <p>Also wird es strukturell gelöst: Der Aufruf wird abgelehnt, solange die
 * Voraussetzungen fehlen, und der Handoff gilt erst dann als erfolgt, wenn der
 * Aufruf erfolgreich war. {@link AgentRunner} beendet die Schleife nur bei
 * einem <b>erfolgreichen</b> Handoff.
 *
 * <p>Der Zustand liegt pro Thread, nicht in einem Singleton-Feld: die
 * Werkzeug-Aufrufe eines Workflows laufen sequenziell auf einem Thread, und ein
 * geteilter Zähler würde zwei gleichzeitige Läufe vermischen.
 */
public class GatedToolExecutor implements ToolExecutor {

    private final ToolExecutor delegate;
    private final String terminalTool;
    private final Set<String> prerequisites;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /**
     * @param terminalTool   das Werkzeug, das den Agenten beendet
     * @param prerequisites  Werkzeuge, die vorher aufgerufen worden sein MÜSSEN;
     *                       leer heißt „darf sofort kommen"
     */
    public GatedToolExecutor(ToolExecutor delegate, String terminalTool, List<String> prerequisites) {
        this.delegate = delegate;
        this.terminalTool = terminalTool;
        this.prerequisites = new LinkedHashSet<>(prerequisites);
    }

    @Override
    public String execute(String toolName, String arguments) {
        if (terminalTool.equals(toolName)) {
            Set<String> missing = new LinkedHashSet<>(prerequisites);
            missing.removeAll(seen);
            if (!missing.isEmpty()) {
                // Exception, KEIN Fehlertext als Rückgabewert. AgentRunner
                // unterscheidet Erfolg über das ok-Flag, das nur im
                // Exception-Zweig gesetzt wird — ein zurückgegebener
                // "[REJECTED]"-Text wäre für ihn ein ERFOLGREICHER Aufruf
                // und würde den Agenten trotz der Ablehnung beenden.
                throw new IllegalStateException(terminalTool + " rejected — you must first use "
                        + missing + ". Do the actual work before submitting your summary.");
            }
        }
        seen.add(toolName);
        return delegate.execute(toolName, arguments);
    }

    /** Nur für Tests/Diagnose: welche Werkzeuge wurden tatsächlich benutzt. */
    public Set<String> seen() {
        return Set.copyOf(seen);
    }
}