#!/usr/bin/env bash
set -euo pipefail

THRESHOLD_MB="${THRESHOLD_MB:-250}"
LOG_DIR="$(cd "$(dirname "$0")/.." && pwd)/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/oom-tripwire.log"

{
  echo "=== $(date --iso-8601=seconds) ==="
  echo "Threshold: ${THRESHOLD_MB}MB"
  for pid in $(pgrep -f 'target/release/web' || true); do
    if [ -z "$pid" ]; then
      continue
    fi
    mem_kb=$(awk '/VmRSS/ {print $2}' /proc/$pid/status 2>/dev/null || echo 0)
    mem_mb=$((mem_kb / 1024))
    echo "PID $pid → RSS ${mem_mb}MB"
    if [ "$mem_mb" -gt "$THRESHOLD_MB" ]; then
      echo "⚠️ PID $pid exceeded ${THRESHOLD_MB}MB!"
    fi
  done
  if ! pgrep -f 'target/release/web' >/dev/null; then
    echo "No Coggy web processes detected."
  fi
} >> "$LOG_FILE"

echo "Wrote tripwire report to $LOG_FILE"
