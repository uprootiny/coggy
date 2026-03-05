#!/usr/bin/env bash
set -euo pipefail

PORT="${PORT:-8431}"
ENDPOINT="http://localhost:${PORT}"
TRACE_INPUT="${TRACE_INPUT:-cat is-a pet}"
LOG_DIR="$(cd "$(dirname "$0")/.." && pwd)/logs"
mkdir -p "$LOG_DIR"
DEMO_LOG="$LOG_DIR/demo-${PORT}.log"

{
  echo "=== Demo run $(date --iso-8601=seconds) on port $PORT ==="
  echo "Landing page: ${ENDPOINT}"
  echo "Health endpoint:"
  curl -s "${ENDPOINT}/api/health" | jq .

  echo
  echo "Trace request:"
  echo "  input=${TRACE_INPUT}"
  curl -s "${ENDPOINT}/api/trace" --get --data-urlencode "input=${TRACE_INPUT}" | jq .

  echo
  echo "Focus snapshot:"
  curl -s "${ENDPOINT}/api/focus" | jq .

  echo "Demo run done."
} | tee "$DEMO_LOG"

echo "Saved demo output to $DEMO_LOG"
