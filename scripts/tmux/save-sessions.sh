#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
# SAVE TMUX SESSION LAYOUTS
# ═══════════════════════════════════════════════════════════════
# Captures the current tmux session topology so it can be
# restored after reboot. Saves: session names, window names,
# pane commands, working directories, and pane titles.
#
# Usage: save-sessions.sh [output-dir]
# Default output: state/tmux/

set -euo pipefail

COGGY_DIR="${COGGY_DIR:-/home/uprootiny/coggy}"
OUT_DIR="${1:-${COGGY_DIR}/state/tmux}"
mkdir -p "$OUT_DIR"

SESSIONS_FILE="${OUT_DIR}/sessions.sh"

echo "#!/usr/bin/env bash" > "$SESSIONS_FILE"
echo "# Generated: $(date -Iseconds)" >> "$SESSIONS_FILE"
echo "# Restore with: bash $SESSIONS_FILE" >> "$SESSIONS_FILE"
echo "" >> "$SESSIONS_FILE"

saved=0

for sess in jacobson traceboard coggy; do
  if ! tmux has-session -t "$sess" 2>/dev/null; then
    echo "# session '$sess' not running — skipped" >> "$SESSIONS_FILE"
    continue
  fi

  echo "" >> "$SESSIONS_FILE"
  echo "# ── $sess ──" >> "$SESSIONS_FILE"
  echo "tmux kill-session -t '$sess' 2>/dev/null || true" >> "$SESSIONS_FILE"

  first_window=true
  tmux list-windows -t "$sess" -F '#{window_index}|#{window_name}|#{window_layout}' | while IFS='|' read -r widx wname wlayout; do
    # Get first pane's directory
    pane0_dir=$(tmux display-message -t "${sess}:${widx}.0" -p '#{pane_current_path}' 2>/dev/null || echo "/home/uprootiny")

    if [ "$first_window" = true ]; then
      echo "tmux new-session -d -s '$sess' -n '$wname' -c '$pane0_dir'" >> "$SESSIONS_FILE"
      first_window=false
    else
      echo "tmux new-window -t '$sess' -n '$wname' -c '$pane0_dir'" >> "$SESSIONS_FILE"
    fi

    # Save pane titles
    tmux list-panes -t "${sess}:${widx}" -F '#{pane_index}|#{pane_title}|#{pane_current_path}|#{pane_current_command}' | while IFS='|' read -r pidx ptitle pdir pcmd; do
      if [ "$pidx" != "0" ]; then
        echo "tmux split-window -t '${sess}:${widx}' -c '$pdir'" >> "$SESSIONS_FILE"
      fi
      if [ -n "$ptitle" ] && [ "$ptitle" != "$pcmd" ]; then
        echo "tmux select-pane -t '${sess}:${widx}.${pidx}' -T '$ptitle'" >> "$SESSIONS_FILE"
      fi
    done

    # Restore layout
    echo "tmux select-layout -t '${sess}:${widx}' '$wlayout' 2>/dev/null || true" >> "$SESSIONS_FILE"
  done

  # Styling
  echo "tmux set -t '$sess' pane-border-status top 2>/dev/null || true" >> "$SESSIONS_FILE"
  echo "tmux set -t '$sess' pane-border-format ' #{pane_title} ' 2>/dev/null || true" >> "$SESSIONS_FILE"

  saved=$((saved + 1))
done

chmod +x "$SESSIONS_FILE"
echo "saved $saved sessions to $SESSIONS_FILE"
