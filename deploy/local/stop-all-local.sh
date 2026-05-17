#!/usr/bin/env bash
set -euo pipefail

SESSION="safespot-local"

if tmux has-session -t "$SESSION" 2>/dev/null; then
  tmux kill-session -t "$SESSION"
  echo "[ok] tmux session '$SESSION' stopped"
else
  echo "[skip] session '$SESSION' not found"
fi
