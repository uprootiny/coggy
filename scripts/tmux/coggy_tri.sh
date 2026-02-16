#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
# COGGY TRI-COLUMN COCKPIT
# ═══════════════════════════════════════════════════════════════
#
# Left:   Coggy AtomSpace state + attention focus (live)
# Center: Status ribbon + event stream (woven feed)
# Right:  Hyle / LLM harness stream
#
# The cockpit is the single place you look to understand
# what coggy is doing, why, and how to recover.

set -euo pipefail

COGGY_DIR="${COGGY_DIR:-/home/uprootiny/coggy}"
COGGY_PORT="${COGGY_PORT:-8421}"
HYLE_PORT="${HYLE_PORT:-8420}"
SESSION="coggy"

# Kill existing session
tmux kill-session -t "$SESSION" 2>/dev/null || true

# Create session
tmux new-session -d -s "$SESSION" -c "$COGGY_DIR" -x 220 -y 55

tmux rename-window -t "$SESSION" "cockpit"

# Split: right 33%
tmux split-window -h -t "$SESSION" -c "$COGGY_DIR" -p 33

# Split left half: left 50% of remaining = 33% total
tmux select-pane -t "$SESSION":0.0
tmux split-window -h -t "$SESSION" -c "$COGGY_DIR" -p 50

# Panes: 0=left(atomspace), 1=center(feed), 2=right(hyle)

# ── Left: AtomSpace + Attention ──────────────────────────────

tmux send-keys -t "$SESSION":0.0 "watch -n3 -c 'echo -e \"\\033[1;33m⬡ HYLE — AtomSpace + Attention\\033[0m\"; echo; curl -sf http://localhost:${COGGY_PORT}/api/state 2>/dev/null | python3 -c \"
import sys, json
try:
    d = json.load(sys.stdin)
    atoms = d.get(\\\"atoms\\\", {})
    attn = d.get(\\\"attention\\\", {})
    focus = d.get(\\\"focus\\\", [])
    model = d.get(\\\"model\\\", \\\"?\\\")
    print(f\\\"  model: {model}\\\")
    print(f\\\"  atoms: {len(atoms)}  focus: {len(focus)}\\\")
    print()
    # Focus atoms with STI bars
    for k in focus:
        av = attn.get(k, {})
        sti = av.get(\\\"av/sti\\\", 0)
        bar = \\\"▓\\\" * min(20, int(sti / 2)) + \\\"░\\\" * max(0, 20 - min(20, int(sti / 2)))
        star = \\\"★\\\" if sti > 5 else \\\"·\\\"
        print(f\\\"  {star} {k:20s} {bar} {sti:.1f}\\\")
    print()
    # All atoms
    for name, a in sorted(atoms.items()):
        t = a.get(\\\"atom/type\\\", \\\"?\\\")
        tv = a.get(\\\"atom/tv\\\", {})
        s = tv.get(\\\"tv/strength\\\", 0)
        c = tv.get(\\\"tv/confidence\\\", 0)
        print(f\\\"  {t:18s} {name:16s} (stv {s:.1f} {c:.1f})\\\")
except: print(\\\"  waiting for coggy...\\\")
\" 2>/dev/null || echo \"  coggy not responding on :${COGGY_PORT}\"'" Enter

# ── Center: Status Ribbon + Event Stream ─────────────────────

tmux send-keys -t "$SESSION":0.1 "while true; do
  echo -e '\\033[1;33m◉ COGGY STATUS RIBBON\\033[0m'
  echo '────────────────────────────────────────'

  # Status ribbon
  STATE=\$(curl -sf --max-time 2 http://localhost:${COGGY_PORT}/api/state 2>/dev/null || echo '{}')
  HEALTH=\$(curl -sf --max-time 2 http://localhost:${COGGY_PORT}/health 2>/dev/null || echo '{\"status\":\"down\"}')
  HYLE_UP=\$(curl -sf --max-time 1 http://localhost:${HYLE_PORT}/health 2>/dev/null && echo 'up' || echo 'down')

  echo -e \"  MODE:  \\033[36mcoggy-repl\\033[0m\"
  echo -e \"  ROUTE: \\033[32m\$(echo \$STATE | python3 -c 'import sys,json; print(json.load(sys.stdin).get(\"model\",\"?\"))' 2>/dev/null || echo '?')\\033[0m\"
  echo -e \"  HYLE:  \\033[33m\$HYLE_UP\\033[0m (port ${HYLE_PORT})\"
  echo -e \"  COGGY: \\033[32m\$(echo \$HEALTH | python3 -c 'import sys,json; print(json.load(sys.stdin).get(\"status\",\"?\"))' 2>/dev/null || echo 'down')\\033[0m (port ${COGGY_PORT})\"
  echo ''

  # Logs
  echo -e '\\033[1;33m◈ EVENT STREAM\\033[0m'
  echo '────────────────────────────────────────'
  curl -sf --max-time 2 http://localhost:${COGGY_PORT}/api/logs 2>/dev/null | python3 -c \"
import sys, json
try:
    logs = json.load(sys.stdin)
    for l in (logs if isinstance(logs, list) else [])[:20]:
        ts = l.get('ts','')[:8]
        msg = l.get('msg','')
        print(f'  {ts}  {msg}')
except: pass
\" 2>/dev/null

  sleep 3
  clear
done" Enter

# ── Right: Hyle / LLM stream ────────────────────────────────

tmux send-keys -t "$SESSION":0.2 "echo -e '\\033[1;33m◈ HYLE / LLM HARNESS\\033[0m'; echo; if curl -sf http://localhost:${HYLE_PORT}/health >/dev/null 2>&1; then echo '  hyle API running on :${HYLE_PORT}'; echo; echo '  Use: hyle --free'; echo '  Or:  curl -X POST http://localhost:${HYLE_PORT}/prompt -d \"{\\\"message\\\":\\\"hello\\\"}\"'; else echo '  hyle not running'; echo '  Start: hyle --serve ${HYLE_PORT}'; echo '  Or:    cd /home/uprootiny/dec27/hyle && cargo run -- --serve ${HYLE_PORT}'; fi; echo; echo '  ─── Coggy REPL ───'; echo; cd ${COGGY_DIR} && bb src/coggy/main.clj" Enter

# ── Styling ──────────────────────────────────────────────────

tmux select-pane -t "$SESSION":0.0 -T "⬡ HYLE"
tmux select-pane -t "$SESSION":0.1 -T "◉ FEED"
tmux select-pane -t "$SESSION":0.2 -T "◈ COGGY"

tmux set -t "$SESSION" pane-border-status top
tmux set -t "$SESSION" pane-border-format " #{pane_title} "
tmux set -t "$SESSION" pane-border-style "fg=#30363d"
tmux set -t "$SESSION" pane-active-border-style "fg=#e3b341"

tmux set -t "$SESSION" status-style "bg=#161b22,fg=#c9d1d9"
tmux set -t "$SESSION" status-left " #[fg=#e3b341,bold]COGGY#[default] │ "
tmux set -t "$SESSION" status-right " │ #[fg=#8b949e]%H:%M "
tmux set -t "$SESSION" status-left-length 30

# Focus right pane (coggy repl)
tmux select-pane -t "$SESSION":0.2

# Attach
tmux attach -t "$SESSION"
