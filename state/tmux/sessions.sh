#!/usr/bin/env bash
# Generated: 2026-02-17T03:44:04+01:00
# Restore with: bash /home/uprootiny/coggy/state/tmux/sessions.sh


# ── jacobson ──
tmux kill-session -t 'jacobson' 2>/dev/null || true
tmux new-session -d -s 'jacobson' -n 'router' -c '/home/uprootiny/coggies'
tmux select-pane -t 'jacobson:0.0' -T 'hyle.hyperstitious.org'
tmux select-layout -t 'jacobson:0' '5db1,120x44,0,0,31' 2>/dev/null || true
tmux new-window -t 'jacobson' -n 'monitor' -c '/home/uprootiny/coggies'
tmux select-pane -t 'jacobson:1.0' -T 'hyle.hyperstitious.org'
tmux select-layout -t 'jacobson:1' '5db2,120x44,0,0,32' 2>/dev/null || true
tmux new-window -t 'jacobson' -n 'oracle' -c '/home/uprootiny/coggies'
tmux select-pane -t 'jacobson:2.0' -T 'hyle.hyperstitious.org'
tmux select-layout -t 'jacobson:2' '5db3,120x44,0,0,33' 2>/dev/null || true
tmux new-window -t 'jacobson' -n 'librarian' -c '/home/uprootiny/coggies'
tmux select-pane -t 'jacobson:3.0' -T 'hyle.hyperstitious.org'
tmux select-layout -t 'jacobson:3' '5db4,120x44,0,0,34' 2>/dev/null || true
tmux new-window -t 'jacobson' -n 'actor' -c '/home/uprootiny/coggies'
tmux select-pane -t 'jacobson:4.0' -T 'hyle.hyperstitious.org'
tmux select-layout -t 'jacobson:4' '5db5,120x44,0,0,35' 2>/dev/null || true
tmux new-window -t 'jacobson' -n 'simulator' -c '/home/uprootiny/coggies'
tmux select-pane -t 'jacobson:5.0' -T 'hyle.hyperstitious.org'
tmux select-layout -t 'jacobson:5' '5db6,120x44,0,0,36' 2>/dev/null || true
tmux new-window -t 'jacobson' -n 'instrument' -c '/home/uprootiny/coggies'
tmux select-pane -t 'jacobson:6.0' -T 'hyle.hyperstitious.org'
tmux select-layout -t 'jacobson:6' '5957,117x41,0,0,37' 2>/dev/null || true
tmux new-window -t 'jacobson' -n 'partner' -c '/home/uprootiny/coggies'
tmux select-pane -t 'jacobson:7.0' -T 'hyle.hyperstitious.org'
tmux select-layout -t 'jacobson:7' '5cb8,120x53,0,0,38' 2>/dev/null || true
tmux new-window -t 'jacobson' -n 'organism' -c '/home/uprootiny/coggies'
tmux select-pane -t 'jacobson:8.0' -T 'hyle.hyperstitious.org'
tmux select-layout -t 'jacobson:8' '5cb9,120x53,0,0,39' 2>/dev/null || true
tmux new-window -t 'jacobson' -n 'logician' -c '/home/uprootiny/coggies'
tmux select-pane -t 'jacobson:9.0' -T 'hyle.hyperstitious.org'
tmux select-layout -t 'jacobson:9' 'dcb0,120x53,0,0,40' 2>/dev/null || true
tmux set -t 'jacobson' pane-border-status top 2>/dev/null || true
tmux set -t 'jacobson' pane-border-format ' #{pane_title} ' 2>/dev/null || true

# ── traceboard ──
tmux kill-session -t 'traceboard' 2>/dev/null || true
tmux new-session -d -s 'traceboard' -n 'trace' -c '/home/uprootiny/idem'
tmux select-pane -t 'traceboard:0.0' -T 'hyle-pulse'
tmux split-window -t 'traceboard:0' -c '/home/uprootiny/idem'
tmux select-pane -t 'traceboard:0.1' -T 'hyle-git'
tmux split-window -t 'traceboard:0' -c '/home/uprootiny/idem'
tmux select-pane -t 'traceboard:0.2' -T 'coggy-inference'
tmux select-layout -t 'traceboard:0' '426d,117x41,0,0{38x41,0,0,47,38x41,39,0,48,39x41,78,0,49}' 2>/dev/null || true
tmux set -t 'traceboard' pane-border-status top 2>/dev/null || true
tmux set -t 'traceboard' pane-border-format ' #{pane_title} ' 2>/dev/null || true

# ── coggy ──
tmux kill-session -t 'coggy' 2>/dev/null || true
tmux new-session -d -s 'coggy' -n 'claude' -c '/home/uprootiny/coggy'
tmux select-pane -t 'coggy:0.0' -T '✳ User Interface Design'
tmux select-layout -t 'coggy:0' 'b245,117x41,0,0,8' 2>/dev/null || true
tmux new-window -t 'coggy' -n 'node' -c '/home/uprootiny/coggy'
tmux select-pane -t 'coggy:1.0' -T 'hyle.hyperstitious.org'
tmux select-layout -t 'coggy:1' 'ddb5,120x44,0,0,45' 2>/dev/null || true
tmux new-window -t 'coggy' -n 'bash' -c '/home/uprootiny/coggy'
tmux select-pane -t 'coggy:2.0' -T 'hyle.hyperstitious.org'
tmux select-layout -t 'coggy:2' 'ddb6,120x44,0,0,46' 2>/dev/null || true
tmux set -t 'coggy' pane-border-status top 2>/dev/null || true
tmux set -t 'coggy' pane-border-format ' #{pane_title} ' 2>/dev/null || true
