#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
# COGGYCTL — canonical entrypoint for the coggy runtime
# ═══════════════════════════════════════════════════════════════
#
# Usage: coggyctl {up|start|stop|restart|status|logs|smoke|bench|doctor}
#
# "up" is the one-command cold start:
#   1. Environment sanity check
#   2. Start web middleware
#   3. Run smoke checks
#   4. Launch tmux cockpit
#
# Every failure mode has a typed reason and recovery action.

set -euo pipefail

COGGY_DIR="${COGGY_DIR:-/home/uprootiny/coggy}"
COGGY_PORT="${COGGY_PORT:-8421}"
COGGY_PID_FILE="${COGGY_DIR}/state/coggy-${COGGY_PORT}.pid"
COGGY_LOG_FILE="${COGGY_DIR}/state/coggy-${COGGY_PORT}.log"
COGGY_SESSION="${COGGY_SESSION:-coggy-${COGGY_PORT}}"
HYLE_PORT="${HYLE_PORT:-8420}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

# ── Helpers ──────────────────────────────────────────────────

log()   { echo -e "${CYAN}[coggyctl]${RESET} $*"; }
ok()    { echo -e "${GREEN}  ✓${RESET} $*"; }
fail()  { echo -e "${RED}  ✗${RESET} $*"; }
warn()  { echo -e "${YELLOW}  ⚠${RESET} $*"; }
die()   { fail "$*"; exit 1; }

ensure_state_dir() {
  mkdir -p "${COGGY_DIR}/state"
}

health_ok() {
  local h
  h=$(curl -sf --max-time 1 "http://localhost:${COGGY_PORT}/health" 2>/dev/null || true)
  echo "$h" | grep -q '"status":"ok"' 2>/dev/null
}

state_ok() {
  local s
  s=$(curl -sf --max-time 1 "http://localhost:${COGGY_PORT}/api/state" 2>/dev/null || true)
  # Support both modern (:atoms map) and legacy (:atom_count) state shapes.
  echo "$s" | grep -Eq '"atoms"|\"atom_count\"' 2>/dev/null
}

is_running() {
  if [ -f "$COGGY_PID_FILE" ]; then
    local pid
    pid=$(cat "$COGGY_PID_FILE")
    if kill -0 "$pid" 2>/dev/null; then
      return 0
    fi
  fi
  # Runtime checks: health endpoint or compatible state endpoint.
  if health_ok; then
    return 0
  fi
  if state_ok; then
    return 0
  fi
  # Fallback: socket with visible pid metadata
  if ss -tlnp 2>/dev/null | grep ":${COGGY_PORT} " | grep -q "pid=" 2>/dev/null; then
    return 0
  fi
  return 1
}

get_pid() {
  if [ -f "$COGGY_PID_FILE" ]; then
    cat "$COGGY_PID_FILE"
  else
    ss -tlnp 2>/dev/null | grep ":${COGGY_PORT} " | grep -oP 'pid=\K\d+' | head -1 || true
  fi
}

instance_ports() {
  ensure_state_dir
  ls -1 "${COGGY_DIR}"/state/coggy-*.pid 2>/dev/null \
    | sed -E 's#.*/coggy-([0-9]+)\.pid#\1#' \
    | sort -n \
    | uniq
}

# ── Environment Sanity ───────────────────────────────────────

cmd_doctor() {
  log "Doctor — environment sanity check"
  echo ""

  # babashka
  if command -v bb &>/dev/null; then
    ok "babashka: $(bb --version 2>&1 | head -1)"
  else
    fail "babashka: not found (install: curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install && sudo ./install)"
  fi

  # bb.edn
  if [ -f "${COGGY_DIR}/bb.edn" ]; then
    ok "bb.edn: present"
  else
    fail "bb.edn: missing"
  fi

  # .env / API key
  local key=""
  if [ -f "${COGGY_DIR}/.env" ]; then
    key=$(grep -oP 'OPENROUTER_API_KEY=\K.*' "${COGGY_DIR}/.env" 2>/dev/null || true)
  fi
  if [ -z "$key" ]; then
    key="${OPENROUTER_API_KEY:-}"
  fi
  if [ -n "$key" ]; then
    ok "API key: ${key:0:12}..."
  else
    fail "API key: not found (set OPENROUTER_API_KEY or add to .env)"
  fi

  # OpenRouter connectivity
  if curl -sf --max-time 5 "https://openrouter.ai/api/v1/models" >/dev/null 2>&1; then
    ok "OpenRouter: reachable"
  else
    fail "OpenRouter: unreachable"
  fi

  # Hyle
  if command -v hyle &>/dev/null; then
    ok "hyle binary: $(which hyle)"
  else
    warn "hyle binary: not in PATH"
  fi

  if curl -sf --max-time 2 "http://localhost:${HYLE_PORT}/health" >/dev/null 2>&1; then
    ok "hyle API: running on port ${HYLE_PORT}"
  else
    warn "hyle API: not running on port ${HYLE_PORT}"
  fi

  # Coggy process
  if is_running; then
    ok "coggy: running (pid $(get_pid)) on port ${COGGY_PORT}"
  else
    warn "coggy: not running"
  fi

  # Run bb doctor
  echo ""
  log "LLM connectivity test"
  cd "$COGGY_DIR" && bb src/coggy/main.clj doctor 2>&1 | sed 's/^/  /'
}

# ── Start / Stop ─────────────────────────────────────────────

cmd_start() {
  ensure_state_dir
  if is_running; then
    local pid
    pid=$(get_pid)
    if [ -n "${pid}" ]; then
      warn "coggy already running (pid ${pid})"
    else
      warn "coggy already running"
    fi
    return 0
  fi

  log "Starting coggy web server on port ${COGGY_PORT}..."
  cd "$COGGY_DIR"
  nohup bb src/coggy/main.clj serve "$COGGY_PORT" \
    > "$COGGY_LOG_FILE" 2>&1 &
  local pid=$!
  echo "$pid" > "$COGGY_PID_FILE"

  # Wait for startup
  local tries=0
  while [ $tries -lt 15 ]; do
    if health_ok || state_ok; then
      ok "coggy started (pid $pid, port ${COGGY_PORT})"
      return 0
    fi
    sleep 1
    tries=$((tries + 1))
  done

  fail "coggy failed to start within 15s"
  rm -f "$COGGY_PID_FILE"
  if grep -q "Operation not permitted" "$COGGY_LOG_FILE" 2>/dev/null; then
    warn "runtime cannot open listen socket in this environment"
  fi
  tail -20 "$COGGY_LOG_FILE" 2>/dev/null | sed 's/^/  /'
  return 1
}

cmd_stop() {
  if ! is_running; then
    warn "coggy not running"
    rm -f "$COGGY_PID_FILE"
    return 0
  fi

  local pid
  pid=$(get_pid)
  if [ -z "${pid}" ]; then
    warn "could not resolve pid for port ${COGGY_PORT}; removing stale pid file"
    rm -f "$COGGY_PID_FILE"
    return 0
  fi
  log "Stopping coggy (pid $pid)..."
  kill "$pid" 2>/dev/null || true

  local tries=0
  while [ $tries -lt 5 ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
      ok "coggy stopped"
      rm -f "$COGGY_PID_FILE"
      return 0
    fi
    sleep 1
    tries=$((tries + 1))
  done

  kill -9 "$pid" 2>/dev/null || true
  rm -f "$COGGY_PID_FILE"
  ok "coggy killed"
}

cmd_restart() {
  cmd_stop
  sleep 1
  cmd_start
}

# ── Status ───────────────────────────────────────────────────

cmd_status() {
  echo -e "${BOLD}${CYAN}COGGY STATUS${RESET}"
  echo "────────────────────────────────"

  if is_running; then
    local pid
    pid=$(get_pid)
    if [ -n "${pid}" ]; then
      ok "process: running (pid $pid)"
    else
      ok "process: running"
    fi

    # Health check
    local health
    health=$(curl -sf --max-time 2 "http://localhost:${COGGY_PORT}/health" 2>/dev/null || echo '{"status":"unreachable"}')
    ok "health: $health"

    # State
    local state
    state=$(curl -sf --max-time 2 "http://localhost:${COGGY_PORT}/api/state" 2>/dev/null || echo "")
    if [ -n "$state" ]; then
      local atoms links
      atoms=$(echo "$state" | python3 -c "import sys,json; d=json.load(sys.stdin); a=d.get('atoms',{}); print(len(a) if isinstance(a,dict) else d.get('atom_count','?'))" 2>/dev/null || echo "?")
      ok "atoms: $atoms"
    else
      warn "state: unreachable on /api/state"
    fi
  else
    fail "process: not running"
  fi

  # Hyle status
  if curl -sf --max-time 2 "http://localhost:${HYLE_PORT}/health" >/dev/null 2>&1; then
    ok "hyle: running on port ${HYLE_PORT}"
  else
    warn "hyle: not running"
  fi
}

cmd_fleet() {
  echo -e "${BOLD}${CYAN}COGGY FLEET${RESET}"
  echo "────────────────────────────────────────────────────────────────────────"
  printf "%-8s %-8s %-10s %-10s %-7s %s\n" "port" "pid" "alive" "health" "atoms" "model"
  echo "────────────────────────────────────────────────────────────────────────"

  local ports
  ports=$(printf "%s\n%s\n" "$COGGY_PORT" "$(instance_ports)" | awk 'NF' | sort -n | uniq)
  if [ -z "$ports" ]; then
    warn "no known instances"
    return 0
  fi

  while IFS= read -r port; do
    [ -n "$port" ] || continue
    local pid_file pid alive health atoms model state
    pid_file="${COGGY_DIR}/state/coggy-${port}.pid"
    pid=""
    if [ -f "$pid_file" ]; then
      pid=$(cat "$pid_file" 2>/dev/null || true)
    fi

    alive="no"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      alive="yes"
    fi

    health="down"
    atoms="-"
    model="-"
    if curl -sf --max-time 1 "http://localhost:${port}/health" >/dev/null 2>&1; then
      health="up"
      state=$(curl -sf --max-time 1 "http://localhost:${port}/api/state" 2>/dev/null || echo '{}')
      atoms=$(echo "$state" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d.get('atoms',{})))" 2>/dev/null || echo "?")
      model=$(echo "$state" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('model','?'))" 2>/dev/null || echo "?")
    fi

    printf "%-8s %-8s %-10s %-10s %-7s %s\n" "$port" "${pid:--}" "$alive" "$health" "$atoms" "$model"
  done <<< "$ports"
}

cmd_chat() {
  local port="${1:-}"
  shift || true
  local msg="${*:-}"

  [ -n "$port" ] || die "usage: coggyctl chat <port> <message>"
  [ -n "$msg" ] || die "usage: coggyctl chat <port> <message>"

  log "chat → coggy:${port}"
  local payload resp
  payload=$(python3 -c 'import json,sys; print(json.dumps({"message":" ".join(sys.argv[1:])}))' "$msg")
  resp=$(curl -sf --max-time 15 \
    -H "Content-Type: application/json" \
    -d "$payload" \
    "http://localhost:${port}/api/chat" 2>/dev/null || true)

  if [ -z "$resp" ]; then
    die "no response from coggy:${port}"
  fi

  echo "$resp" | python3 -c '
import sys, json
d = json.load(sys.stdin)
print(d.get("content", d.get("error", d)))
' 2>/dev/null || echo "$resp"
}

# ── Smoke Test ───────────────────────────────────────────────

cmd_smoke() {
  log "Running smoke tests..."
  echo ""

  local failures=0
  local started_for_smoke=0

  # Test 1: bb loads
  if bb -e '(println "bb ok")' >/dev/null 2>&1; then
    ok "babashka loads"
  else
    fail "babashka fails to load"
    failures=$((failures + 1))
  fi

  # Test 2: tests pass
  cd "$COGGY_DIR"
  if bb test/coggy/atomspace_test.clj >/dev/null 2>&1; then
    ok "test suite passes"
  else
    fail "test suite fails"
    failures=$((failures + 1))
  fi

  # Test 3: boot ritual
  if bb -e '
    (require (quote [coggy.atomspace :as as])
             (quote [coggy.attention :as att])
             (quote [coggy.boot :as boot]))
    (let [s (as/make-space) b (att/make-bank)]
      (boot/seed-ontology! s b)
      (assert (>= (:atoms (as/space-stats s)) 10)))
    (println "boot ok")
  ' >/dev/null 2>&1; then
    ok "boot ritual seeds ontology"
  else
    fail "boot ritual fails"
    failures=$((failures + 1))
  fi

  # Bring up a temporary server if needed so HTTP smoke is deterministic.
  if ! is_running; then
    warn "web server not running; starting temporary instance for HTTP smoke"
    if cmd_start; then
      started_for_smoke=1
    else
      fail "could not start temporary web server"
      failures=$((failures + 1))
    fi
  fi

  # Test 4: web server (if running)
  if is_running; then
    if health_ok; then
      ok "health endpoint responds"
    elif state_ok; then
      warn "health endpoint missing but state endpoint responds (legacy runtime on port ${COGGY_PORT})"
    else
      fail "health endpoint unreachable"
      failures=$((failures + 1))
    fi

    if curl -sf --max-time 2 "http://localhost:${COGGY_PORT}/api/state" >/dev/null 2>&1; then
      ok "state endpoint responds"
    else
      fail "state endpoint unreachable"
      failures=$((failures + 1))
    fi
  else
    warn "web server not running (skipping HTTP tests)"
  fi

  # Test 5: API key valid
  if bb -e '
    (require (quote [coggy.llm :as llm])
             (quote [clojure.string :as str]))
    (defn file-key []
      (when (.exists (java.io.File. ".env"))
        (some (fn [line]
                (when-let [[_ v] (re-matches #"OPENROUTER_API_KEY=(.+)" (str/trim line))]
                  v))
              (str/split-lines (slurp ".env")))))
    (let [key (or (System/getenv "OPENROUTER_API_KEY") (file-key))]
      (assert (seq key) "missing OPENROUTER_API_KEY")
      (llm/configure! {:api-key key})
      ;; Try configured model first, then known free fallbacks.
      (let [models (distinct (cons (:model @llm/config) llm/free-models))
            tried  (reduce (fn [acc m]
                             (if (:ok acc)
                               acc
                               (let [resp (llm/chat [{:role "user" :content "Say ok"}] :model m)]
                                 (if (:ok resp)
                                   {:ok true :model m}
                                   {:ok false :last resp :model m}))))
                           {:ok false}
                           models)]
        (assert (:ok tried) (str "auth failed: " (select-keys (:last tried) [:status :error :hint])))
        (println "auth ok" (:model tried))))
  ' >/dev/null 2>&1; then
    ok "OpenRouter auth works"
  else
    warn "OpenRouter auth failed (inspect: bb src/coggy/main.clj doctor --json)"
  fi

  if [ "$started_for_smoke" -eq 1 ]; then
    log "Stopping temporary web server"
    cmd_stop || true
  fi

  echo ""
  if [ $failures -eq 0 ]; then
    log "${GREEN}ALL SMOKE TESTS PASSED${RESET}"
  else
    log "${RED}$failures SMOKE TEST(S) FAILED${RESET}"
    return 1
  fi
}

# ── Logs ─────────────────────────────────────────────────────

cmd_logs() {
  if [ -f "$COGGY_LOG_FILE" ]; then
    tail -f "$COGGY_LOG_FILE"
  else
    warn "no log file found"
  fi
}

# ── Cockpit (tmux tri-column) ────────────────────────────────

cmd_cockpit() {
  COGGY_SESSION="${COGGY_SESSION}" \
  COGGY_PORT="${COGGY_PORT}" \
  HYLE_PORT="${HYLE_PORT}" \
  bash "${COGGY_DIR}/scripts/tmux/coggy_tri.sh"
}

# ── Up (the one-command cold start) ──────────────────────────

cmd_up() {
  log "${BOLD}COGGY UP — full cold start${RESET}"
  echo ""

  # 1. Doctor
  cmd_doctor
  echo ""

  # 2. Start middleware
  cmd_start
  echo ""

  # 3. Smoke
  cmd_smoke
  echo ""

  # 4. Launch cockpit
  log "Launching cockpit..."
  cmd_cockpit
}

# ── Main ─────────────────────────────────────────────────────

case "${1:-help}" in
  up)       cmd_up ;;
  start)    cmd_start ;;
  stop)     cmd_stop ;;
  restart)  cmd_restart ;;
  status)   cmd_status ;;
  fleet)    cmd_fleet ;;
  chat)     shift; cmd_chat "$@" ;;
  smoke)    cmd_smoke ;;
  doctor)   cmd_doctor ;;
  logs)     cmd_logs ;;
  cockpit)  cmd_cockpit ;;
  bench)    log "bench not yet implemented" ;;
  help|*)
    echo "Usage: coggyctl {up|start|stop|restart|status|fleet|chat|smoke|doctor|logs|cockpit|bench}"
    echo ""
    echo "  up       — full cold start (doctor + start + smoke + cockpit)"
    echo "  start    — start web middleware"
    echo "  stop     — stop cleanly"
    echo "  restart  — stop + start"
    echo "  status   — show current state"
    echo "  fleet    — show all known coggy instances"
    echo "  chat     — send a message to a coggy instance by port"
    echo "  smoke    — run smoke tests"
    echo "  doctor   — environment sanity check"
    echo "  logs     — tail server logs"
    echo "  cockpit  — launch tmux tri-column layout"
    echo "  bench    — run benchmarks"
    ;;
esac
