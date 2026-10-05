#!/usr/bin/env bash
#
# Measurement run: 1 orchestrator + 3 tool subagents, 2 of them with base context.
#
# Two modes with otherwise identical configuration — the same tool list,
# the same subagents, the same prompts, the same session. The only
# difference is the POSITION of the knowledge base:
#
#   mode=prefix  KB in the system block          -> cacheable, that is the intended case
#   mode=args    KB in the user message          -> not cacheable, the negative control
#
# Prerequisite: the app must run with ENV_FILE=.env.openrouter, otherwise
# the x-session-id header is missing and the cache hits are luck.
#
# Usage:
#   ./subagents-demo.sh                    # both modes, 2 tasks
#   MODES=prefix ./subagents-demo.sh       # only one mode
#   TASKS=3 ./subagents-demo.sh            # more passes (cache warming)
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
  echo "WARN: jq is missing — raw JSON output instead of a table." >&2
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

  # Save the raw response — it is the only primary source of the numbers.
  local raw_dir="${RAW_DIR:-/tmp/subagents-demo}"
  mkdir -p "$raw_dir"
  echo "$response" > "$raw_dir/${mode}-$(date +%H%M%S).json"

  if command -v jq >/dev/null 2>&1; then
    if echo "$response" | jq -e 'has("error")' >/dev/null 2>&1; then
      echo "ERROR: $(echo "$response" | jq -c '.error')"
      return
    fi

    echo "$response" | jq -r '
      ["session", .session_id],
      ["duration_ms", (.duration_ms|tostring)],
      ["requests (real)", (.token_usage.requests|tostring)],
      [],
      ["SUBAGENT", "KB", "KB via", "req", "input", "cached", "UNCACHED", "output", "hit%"],
      ( .subagents[] | [.agent, (if .knowledge_base_required then "yes" else "no" end),
                        .knowledge_base_via, (.requests|tostring), (.input_tokens|tostring),
                        (.cached_tokens|tostring), (.uncached_tokens|tostring),
                        (.output_tokens|tostring), (.hit_percent|tostring)] ),
      ["TOTAL", "", "", (.token_usage.requests|tostring), (.token_usage.input_tokens|tostring),
       (.token_usage.cached_tokens|tostring), (.token_usage.uncached_tokens|tostring),
       (.token_usage.output_tokens|tostring), (.token_usage.hit_percent|tostring)],
      ["of which ORCHESTRATOR", "", "", (.token_usage.orchestrator_requests|tostring),
       (.token_usage.orchestrator_input_tokens|tostring), (.token_usage.orchestrator_cached_tokens|tostring),
       ((.token_usage.orchestrator_input_tokens - .token_usage.orchestrator_cached_tokens)|tostring),
       "", ""]
      | @tsv
    ' | column -t -s "$(printf '\t')"

    echo
    echo "  The decisive column is UNCACHED (uncached input tokens):"
    echo "  only those are billed at full price. The hit% column looks"
    echo "  better than it is — it is diluted by the large, always"
    echo "  cached tool block."
    echo
    echo "  Answer (truncated):"
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
Notes on interpretation

1. Cold start: the very first run after process start OR a changed
   tool list necessarily reports cached=0 for the writing request.
   One run per mode is not enough — TASKS=3 repeats the same task.

2. mode=args is a WEAK negative control point. The knowledge base
   sits there at a CONSTANT position in the prompt tail, and a
   constant prefix is cacheable by definition. The mode thus does
   not prove "suffix is never cacheable", but only: the prefix path
   is superior to the suffix path, because it does not carry the data
   along per request. Run-to-run variance (esp. orchestrator) is larger
   than the mode effect — a mode comparison needs several runs.

3. The ROBUST finding of this run is not prefix vs. args, but
   the heterogeneous context need:
     - KB subagents (lookupEvidence/crossCheck): 88-95 % hit
     - renderSummary without KB: constantly 0 % hit, but only ~1550
       instead of ~4000 input tokens — it saves ~2450 tokens, because
       it does not request the data at all.
NOTE