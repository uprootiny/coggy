#!/usr/bin/env bash
set -euo pipefail

PORT_LIST="${PORT_LIST:-8421 8431 8451}"
LOG_DIR="$(cd "$(dirname "$0")/.." && pwd)/logs"
mkdir -p "$LOG_DIR"

for port in $PORT_LIST; do
  LOG_FILE="$LOG_DIR/web-$port.log"
  if ss -ltnp | grep -q ":$port "; then
    echo "[$(date --iso-8601=seconds)] Port $port already bound; skipping startup" | tee -a "$LOG_FILE"
    continue
  fi

  echo "[$(date --iso-8601=seconds)] Launching Coggy web on port $port" | tee -a "$LOG_FILE"
  (
    cd "$(dirname "$0")/.."
    COGGY_PORT="$port" nohup ./scripts/run-web.sh >> "$LOG_FILE" 2>&1 &
  )
done

echo "Launched Coggy web instances on: $PORT_LIST"
