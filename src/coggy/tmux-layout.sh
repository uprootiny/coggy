#!/bin/bash
# COGGY — Three Column tmux Layout
#
# Left:   Hyle / AtomSpace state (watch)
# Center: System logs + file activity + ontology trace
# Right:  Coggy REPL or web server logs
#
# Usage: bash src/coggy/tmux-layout.sh [repl|web]

MODE="${1:-web}"
SESSION="coggy"
COGGY_DIR="/home/uprootiny/coggy"

# Kill existing session if any
tmux kill-session -t "$SESSION" 2>/dev/null

# Create session with center pane
tmux new-session -d -s "$SESSION" -c "$COGGY_DIR" -x 200 -y 50

# Name the window
tmux rename-window -t "$SESSION" "coggy:live"

# Split into three columns: left | center | right
# First split: create right pane (33%)
tmux split-window -h -t "$SESSION" -c "$COGGY_DIR" -p 33
# Split left pane: create left pane (50% of remaining = 33% total)
tmux select-pane -t "$SESSION":0.0
tmux split-window -h -t "$SESSION" -c "$COGGY_DIR" -p 50

# Now panes are: 0=left, 1=center, 2=right

# Left pane: AtomSpace watcher
tmux send-keys -t "$SESSION":0.0 "watch -n2 -c 'curl -s http://localhost:8421/api/state 2>/dev/null | python3 -m json.tool 2>/dev/null || echo \"waiting for coggy...\"'" Enter

# Center pane: logs + activity
tmux send-keys -t "$SESSION":0.1 "while true; do curl -s http://localhost:8421/api/logs 2>/dev/null | python3 -m json.tool 2>/dev/null; sleep 2; done" Enter

# Right pane: start coggy
if [ "$MODE" = "web" ]; then
  tmux send-keys -t "$SESSION":0.2 "cd $COGGY_DIR && bb src/coggy/main.clj serve 8421" Enter
else
  tmux send-keys -t "$SESSION":0.2 "cd $COGGY_DIR && bb src/coggy/main.clj" Enter
fi

# Set pane titles
tmux select-pane -t "$SESSION":0.0 -T "⬡ HYLE — AtomSpace"
tmux select-pane -t "$SESSION":0.1 -T "◉ TRACE — Activity"
tmux select-pane -t "$SESSION":0.2 -T "◈ COGGY — Harness"

# Enable pane titles
tmux set -t "$SESSION" pane-border-status top
tmux set -t "$SESSION" pane-border-format " #{pane_title} "

# Status line
tmux set -t "$SESSION" status-style "bg=#161b22,fg=#c9d1d9"
tmux set -t "$SESSION" status-left " #[fg=#e3b341,bold]COGGY#[default] │ "
tmux set -t "$SESSION" status-right " │ #[fg=#8b949e]%H:%M "

# Focus the right pane (coggy)
tmux select-pane -t "$SESSION":0.2

# Attach
tmux attach -t "$SESSION"
