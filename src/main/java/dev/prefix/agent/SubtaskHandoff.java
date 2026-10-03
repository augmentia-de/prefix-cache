package dev.prefix.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strukturierte Übergabe von einem Agenten an den nächsten.
 * <p>
 * Ein Agent beendet seine Arbeit nicht mit Fließtext, sondern ruft das Werkzeug
 * {@link #TOOL_NAME} auf. Die Engine nimmt die Argumente entgegen und hängt sie
 * dem Folgeagenten als eigene Nachricht am <b>Ende</b> seines Verlaufs an.
 *
 * <h2>Warum als Werkzeug und nicht als Text</h2>
 * <ul>
 *   <li><b>Struktur.</b> Der Folgeagent liest Felder, statt Prosa zu parsen.</li>
 *   <li><b>Länge.</b> Das Schema begrenzt den Umfang; unstrukturierte Antworten
 *       werden sonst tendenziell länger und kosten Tokens im dynamischen Suffix.</li>
 *   <li><b>Determinismus.</b> Feste Feldreihenfolge statt Modell-Auswahl der Reihenfolge.</li>
 * </ul>
 *
 * <h2>Was das Schema ausdrücklich NICHT leistet</h2>
 * Der Cache-Schutz kommt <b>nicht</b> vom Schema, sondern von der Position:
 * der Handoff wird ans <b>Ende</b> der Message-Liste gehängt, und weil kausale
 * Attention nur auf Token zurueckblickt, bleibt alles davor cachebar — bei
 * strukturiertem und bei unstrukturiertem Ergebnis gleichermassen. Die
 * eigentliche Gefahr ist die Umkehrung: rendert man den Handoff in den
 * System-Block, ist der gesamte Prefix dahinter wertlos. Davor schuetzt kein
 * JSON-Schema, nur die Position.
 *
 * <h2>Warum keine ToolResultMessage</h2>
 * Die Übergabe wird bewusst als <b>UserMessage</b> transportiert, nicht als
 * {@code role: "tool"}. Eine Tool-Nachricht ohne vorangehenden
 * {@code assistant}-Tool-Call ist verwaist und damit schema-widrig — der
 * openCode-Zen-Gateway lehnt genau das mit
 * {@code 400 invalid_request} ab, während OpenRouter/DeepSeek es tolerieren.
 *
 * <h2>Kanonische Serialisierung</h2>
 * {@link #renderAsMessage(String)} ist byte-deterministisch: feste
 * Schluesselreihenfolge ({@link LinkedHashMap}), Findings in der vom Modell
 * gelieferten Reihenfolge, keine Zeitstempel. Das ist Teil der Cache-Invariante —
 * ein JSON, dessen Schluesselreihenfolge zwischen zwei Laeufen schwankt,
 * invalidiert den Prefix.
 */
public record SubtaskHandoff(String agent,
                             String status,
                             List<String> keyFindings,
                             String nextActionRecommendation,
                             String rawJson) {

    /** Name des Werkzeugs, mit dem ein Agent seinen Handoff abgibt. */
    public static final String TOOL_NAME = "submit_subtask_summary";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Erzeugt eine deterministische Tool-Call-ID fuer Logs und Korrelation.
     * <p>
     * Nicht mehr Teil der Message: die Uebergabe ist eine UserMessage, es gibt
     * also keinen verwaisten {@code tool_call_id} mehr, auf den sich eine ID
     * beziehen muesste. Fuer die Zuordnung im Log bleibt sie nuetzlich.
     */
    public static String toolCallId(String agent) {
        return "handoff-" + agent;
    }

    /**
     * Der Inhalt, der als eigene Nachricht angehaengt wird.
     * <p>
     * Feste Schluesselreihenfolge, keine Zusatzfelder. Der {@code agent}-Name steht
     * bewusst NICHT im JSON, weil er schon in der Ueberschrift steht und der
     * Folgeagent die Zuordnung darueber sieht.
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
            // Darf nicht passieren: die Map ist serialisierbar. Wenn doch, ist
            // ein leerer, aber stabiler Inhalt besser als eine Exception mitten
            // im Workflow.
            return "{\"status\":\"error\",\"key_findings\":[],"
                    + "\"next_action_recommendation\":\"\"}";
        }
    }

    /**
     * Die vollstaendige Nachricht, die dem Folgeagenten angehaengt wird.
     * <p>
     * Byte-deterministisch: feste Ueberschrift, kanonisches JSON, keine
     * Zeitstempel und keine Zufallswerte. Genau das macht sie fuer den
     * Prefix-Cache unschaedlich — waere sie es nicht, wuerde jeder Lauf den
     * gecachten Bereich dahinter invalidieren.
     */
    public static String renderAsMessage(String agent, String canonicalJson) {
        return "## HANDOFF FROM " + agent + " (" + TOOL_NAME + ") ##\n" + canonicalJson;
    }

    /** Wie {@link #renderAsMessage(String, String)}, fuer dieses Handoff. */
    public String renderAsMessage() {
        return renderAsMessage(agent, toToolResult());
    }

    /**
     * Parst die Tool-Argumente eines Handoff-Aufrufs.
     * <p>
     * Tolerant gegen Teil-Mengen: ein Modell, das {@code next_action_recommendation}
     * weglässt, ist kein Grund, den Workflow abzubrechen — das Feld wird leer
     * gefuellt, damit das Ergebnis weiterhin schema-konform serialisiert.
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
            // Rohes JSON behalten: es ist besser, die Original-Argumente zu
            // sehen als sie zu verlieren.
        }
        return new SubtaskHandoff(agent, status, List.copyOf(findings), next, raw);
    }

    /** Kurze, fluss-taugliche Zusammenfassung fuer Logs. */
    @Override
    public String toString() {
        return "SubtaskHandoff[" + agent + " status=" + status
                + " findings=" + (keyFindings == null ? 0 : keyFindings.size()) + "]";
    }
}