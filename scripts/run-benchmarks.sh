#!/usr/bin/env bash
set -euo pipefail

PORT="${PORT:-8451}"
ENDPOINT="http://localhost:${PORT}"
LOG_DIR="$(cd "$(dirname "$0")/.." && pwd)/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/benchmarks-$(date --iso-8601=seconds).log"

SCENARIOS=(
  "legal:penguin is-a bird"
  "math:cat is-a mammal"
  "formal:inheritance chain"
)

{
  echo "=== Benchmark run $(date --iso-8601=seconds) on port ${PORT} ==="
  curl -s "${ENDPOINT}/api/health"
  for scenario in "${SCENARIOS[@]}"; do
    name="${scenario%%:*}"
    input="${scenario#*:}"
    echo
    echo "--${name}--"
    echo "input=${input}"
    curl -s "${ENDPOINT}/api/trace" --get --data-urlencode "input=${input}"
  done
  echo "Benchmark run complete."
} | tee "$LOG_FILE"

echo "Benchmarks logged to $LOG_FILE"
