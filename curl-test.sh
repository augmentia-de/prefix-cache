#!/usr/bin/env bash
#
# Test the prefix-cache workflow with three tasks that all share the same
# Novaris knowledge prefix. Run start.sh first (the app listens on :8080).
#
# Usage:
#   ./curl-test.sh                 # uses http://localhost:8080
#   BASE_URL=http://host:8080 ./curl-test.sh
#
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ENDPOINT="/api/workflow"

TASKS=(
  "Describe the geography surrounding Novaris: Mount Thorne, the river Ombra and Cerulean Bay."
  "Explain the significance of Xytherium-7 and how the city of Novaris was founded in 847."
  "What is the Lumina festival and who was Engineer Mara Quill?"
)

run_task() {
  local number="$1"
  local input="$2"

  echo "=================================================="
  echo "TASK $number"
  echo ">>> $input"
  echo "--------------------------------------------------"

  local payload
  payload="$(jq -nc --arg input "$input" '{input: $input}')"

  local response
  response="$(curl -sS -X POST "$BASE_URL$ENDPOINT" \
    -H 'Content-Type: application/json' \
    -d "$payload")"

  if command -v jq >/dev/null 2>&1; then
    echo "--- token usage ---"
    echo "$response" | jq '.token_usage'

    echo "--- tool usage ---"
    for agent in agent1 agent2 agent3; do
      echo "$response" | jq -r --arg a "$agent" '
        (.results[$a].tool_calls // []) as $calls |
        if ($calls | length) == 0 then
          "[$a] no tool calls"
        else
          "[$a] " + ($calls | tostring)
        end
      '
    done

    echo "--- agent1: domain / task ---"
    echo "$response" | jq -r '.results.agent1.extracted_domain, .results.agent1.extracted_task'

    echo "--- agent3: completion (first 1200 chars) ---"
    echo "$response" | jq -r '.results.agent3.completion' | head -c 1200
    echo
  else
    echo "$response"
  fi
  echo
}

if ! command -v jq >/dev/null 2>&1; then
  echo "WARN: jq not found — printing raw JSON responses instead" >&2
fi

i=0
for task in "${TASKS[@]}"; do
  i=$((i + 1))
  run_task "$i" "$task"
  break
done

echo "=================================================="
echo "Done — ran $i task(s)."