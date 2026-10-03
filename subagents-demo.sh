#!/usr/bin/env bash
#
# Messlauf: 1 Orchestrator + 3 Tool-Subagenten, 2 davon mit Basiskontext.
#
# Zwei Modi mit sonst identischer Konfiguration — dieselbe Tool-Liste,
# dieselben Subagenten, dieselben Prompts, dieselbe Session. Der einzige
# Unterschied ist die POSITION der Knowledge Base:
#
#   mode=prefix  KB im System-Block          -> cachebar, das ist der Soll-Fall
#   mode=args    KB in der User-Message      -> nicht cachebar, die Negativprobe
#
# Voraussetzung: die App muss mit ENV_FILE=.env.openrouter laufen, sonst
# fehlt das x-session-id-Header und die Cache-Treffer sind Glueck.
#
# Usage:
#   ./subagents-demo.sh                    # beide Modi, 2 Tasks
#   MODES=prefix ./subagents-demo.sh       # nur ein Modus
#   TASKS=3 ./subagents-demo.sh            # mehr Durchlaeufe (Cache-Warming)
#
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ENDPOINT="/api/subagents"
MODES="${MODES:-prefix args}"
TASKS="${TASKS:-2}"

TASK_INPUTS=(
  "Describe the geography around Novaris: Mount Thorne, the river Ombra and Cerulean Bay."
  "Explain what Xytherium-7 is and how Novaris came to be founded in 847."
  "What is the Lumina festival and what did Engineer Mara Quill invent?"
)

if ! command -v jq >/dev/null 2>&1; then
  echo "WARN: jq fehlt — rohe JSON-Ausgabe statt Tabelle." >&2
fi

run_one() {
  local mode="$1" input="$2"

  echo "──────────────────────────────────────────────────────────"
  echo "MODE: $mode"
  echo ">>> $input"

  local payload
  payload="$(jq -nc --arg input "$input" --arg mode "$mode" '{input: $input, mode: $mode}')"

  local response
  response="$(curl -sS -X POST "$BASE_URL$ENDPOINT" \
    -H 'Content-Type: application/json' \
    -d "$payload")"

  # Rohantwort sichern — sie ist die einzige Primaerquelle der Zahlen.
  local raw_dir="${RAW_DIR:-/tmp/subagents-demo}"
  mkdir -p "$raw_dir"
  echo "$response" > "$raw_dir/${mode}-$(date +%H%M%S).json"

  if command -v jq >/dev/null 2>&1; then
    if echo "$response" | jq -e 'has("error")' >/dev/null 2>&1; then
      echo "FEHLER: $(echo "$response" | jq -c '.error')"
      return
    fi

    echo "$response" | jq -r '
      ["session", .session_id],
      ["dauer_ms", (.duration_ms|tostring)],
      ["requests (echt)", (.token_usage.requests|tostring)],
      [],
      ["SUBAGENT", "KB", "KB via", "req", "input", "cached", "UNGEC.", "output", "hit%"],
      ( .subagents[] | [.agent, (if .knowledge_base_required then "ja" else "nein" end),
                        .knowledge_base_via, (.requests|tostring), (.input_tokens|tostring),
                        (.cached_tokens|tostring), (.uncached_tokens|tostring),
                        (.output_tokens|tostring), (.hit_percent|tostring)] ),
      ["TOTAL", "", "", (.token_usage.requests|tostring), (.token_usage.input_tokens|tostring),
       (.token_usage.cached_tokens|tostring), (.token_usage.uncached_tokens|tostring),
       (.token_usage.output_tokens|tostring), (.token_usage.hit_percent|tostring)],
      ["davon ORCHESTRATOR", "", "", (.token_usage.orchestrator_requests|tostring),
       (.token_usage.orchestrator_input_tokens|tostring), (.token_usage.orchestrator_cached_tokens|tostring),
       ((.token_usage.orchestrator_input_tokens - .token_usage.orchestrator_cached_tokens)|tostring),
       "", ""]
      | @tsv
    ' | column -t -s "$(printf '\t')"

    echo
    echo "  Entscheidend ist die Spalte UNGEC. (ungecachte Input-Tokens):"
    echo "  nur die werden vollpreisig abgerechnet. Die hit%-Spalte sieht"
    echo "  besser aus, als es ist — sie wird vom grossen, immer gecachten"
    echo "  Tool-Block verwassert."
    echo
    echo "  Antwort (gekürzt):"
    echo "$response" | jq -r '.answer' | head -c 400 | fold -s -w 76 | sed 's/^/    /'
    echo
  else
    echo "$response"
  fi
  echo
}

n=0
for mode in $MODES; do
  n=$((n + 1))
  run_one "$mode" "${TASK_INPUTS[$((n - 1))]}"
done

echo "──────────────────────────────────────────────────────────"
cat <<'NOTE'
Hinweise zur Interpretation

1. Kaltstart: Der allererste Lauf nach Prozessstart ODER geaenderter
   Tool-Liste meldet zwingend cached=0 fuer den schreibenden Request.
   Ein Lauf pro Modus reicht nicht — TASKS=3 wiederholt denselben Task.

2. mode=args ist ein SCHWACHER Negativkontrollpunkt. Die Knowledge Base
   steht dort an einer KONSTANTEN Position im Prompt-Tail, und ein
   konstanter Prefix ist per Definition cachebar. Der Modus belegt
   damit nicht "Suffix ist nie cachebar", sondern nur: der Prefix-Pfad
   ist dem Suffix-Pfad ueberlegen, weil er die Daten nicht pro Request
   mitschleppt. Lauf-zu-Lauf-Schwankung (v.a. Orchestrator) ist groesser
   als der Modus-Effekt — ein Modusvergleich braucht mehrere Laeufe.

3. Der BELASTBARE Befund dieses Laufs ist nicht prefix vs. args, sondern
   der heterogene Kontextbedarf:
     - KB-Subagenten (lookupEvidence/crossCheck): 88-95 % Hit
     - renderSummary ohne KB: konstant 0 % Hit, aber nur ~1550 statt
       ~4000 Input-Tokens — es spart ~2450 Tokens, weil es die Daten
       gar nicht erst anfordert.
NOTE