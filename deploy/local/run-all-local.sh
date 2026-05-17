#!/usr/bin/env bash
set -euo pipefail

SESSION="safespot-local"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
GRADLEW="$REPO_ROOT/gradlew"

# gradlew 존재 확인
if [ ! -f "$GRADLEW" ]; then
  echo "[error] gradlew not found at $GRADLEW"
  exit 1
fi

# 기존 세션 종료
if tmux has-session -t "$SESSION" 2>/dev/null; then
  echo "[info] existing session '$SESSION' found — killing it"
  tmux kill-session -t "$SESSION"
fi

# 세션 생성 (api-core 창)
tmux new-session -d -s "$SESSION" -n "api-core" -x 220 -y 50
tmux send-keys -t "$SESSION:api-core" "cd '$REPO_ROOT' && SPRING_PROFILES_ACTIVE=local ./gradlew :services:api-core:bootRun" Enter

# api-public-read 창
tmux new-window -t "$SESSION" -n "api-public-read"
tmux send-keys -t "$SESSION:api-public-read" "cd '$REPO_ROOT' && SPRING_PROFILES_ACTIVE=local ./gradlew :services:api-public-read:bootRun" Enter

# external-ingestion 창
tmux new-window -t "$SESSION" -n "external-ingestion"
tmux send-keys -t "$SESSION:external-ingestion" "cd '$REPO_ROOT' && SPRING_PROFILES_ACTIVE=local ./gradlew :services:external-ingestion:bootRun" Enter

# monitor 창
tmux new-window -t "$SESSION" -n "monitor"
tmux send-keys -t "$SESSION:monitor" "
echo '================================================'
echo '  safespot-local services'
echo '================================================'
echo '  api-core          → http://localhost:8080'
echo '  api-public-read   → http://localhost:8081'
echo '  external-ingestion → http://localhost:8082'
echo '================================================'
echo ''
echo 'Waiting 15s for services to start...'
sleep 15
echo ''
echo '--- port status ---'
ss -tlnp 2>/dev/null | grep -E '808[0-2]' || lsof -i :8080 -i :8081 -i :8082 2>/dev/null || echo '(ss/lsof not available)'
" Enter

echo ""
echo "[ok] tmux session '$SESSION' started with 4 windows"
echo ""
echo "Attach:          tmux attach -t $SESSION"
echo "Switch windows:  Ctrl-b + 0~3  or  Ctrl-b + w"
echo "Detach:          Ctrl-b + d"
echo "Stop all:        ./deploy/local/stop-all-local.sh"
