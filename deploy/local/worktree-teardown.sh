#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
WORKTREE_BASE="/home/hi/workspace/safespot-worktrees"
DELETE_BRANCHES=false

usage() {
  echo "Usage: $0 [--delete-branches]"
  echo ""
  echo "  --delete-branches   Also delete local worktree/* branches after removal"
  exit 1
}

parse_args() {
  for arg in "$@"; do
    case "$arg" in
      --delete-branches) DELETE_BRANCHES=true ;;
      -h|--help) usage ;;
      *) echo "Unknown option: $arg"; usage ;;
    esac
  done
}

main() {
  parse_args "$@"

  local names=("api-core" "api-public-read" "external-ingestion" "review")

  for name in "${names[@]}"; do
    local path="$WORKTREE_BASE/$name"
    if git -C "$REPO_ROOT" worktree list | grep -q "$path"; then
      git -C "$REPO_ROOT" worktree remove "$path" --force
      echo "[ok]   worktree removed: $path"
    else
      echo "[skip] worktree not found: $path"
    fi
  done

  git -C "$REPO_ROOT" worktree prune
  echo "[ok]   worktree prune done"

  if [ "$DELETE_BRANCHES" = true ]; then
    echo ""
    echo "-- branch deletion (--delete-branches) --"
    local branches=("worktree/api-core" "worktree/api-public-read" "worktree/external-ingestion")
    for branch in "${branches[@]}"; do
      if git -C "$REPO_ROOT" show-ref --verify --quiet "refs/heads/$branch"; then
        git -C "$REPO_ROOT" branch -D "$branch"
        echo "[ok]   branch deleted: $branch"
      else
        echo "[skip] branch not found: $branch"
      fi
    done
  fi
}

main "$@"
