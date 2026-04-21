#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
WORKTREE_BASE="/home/hi/workspace/safespot-worktrees"

main() {
  git -C "$REPO_ROOT" fetch origin

  # review/codex-cross-review: track remote if exists, else base on main
  if git -C "$REPO_ROOT" ls-remote --exit-code origin review/codex-cross-review &>/dev/null; then
    _create_branch review/codex-cross-review origin/review/codex-cross-review
  else
    _create_branch review/codex-cross-review origin/main
  fi

  _create_branch worktree/api-core            origin/main
  _create_branch worktree/api-public-read     origin/main
  _create_branch worktree/external-ingestion  origin/main

  mkdir -p "$WORKTREE_BASE"

  _add_worktree api-core           worktree/api-core
  _add_worktree api-public-read    worktree/api-public-read
  _add_worktree external-ingestion worktree/external-ingestion
  _add_worktree review             review/codex-cross-review

  git -C "$REPO_ROOT" worktree list
}

_create_branch() {
  local branch="$1" base="$2"
  if git -C "$REPO_ROOT" show-ref --verify --quiet "refs/heads/$branch"; then
    echo "[skip] branch already exists: $branch"
  else
    git -C "$REPO_ROOT" branch "$branch" "$base"
    echo "[ok]   branch created: $branch (base: $base)"
  fi
}

_add_worktree() {
  local name="$1" branch="$2"
  local path="$WORKTREE_BASE/$name"
  if [ -d "$path/.git" ] || git -C "$REPO_ROOT" worktree list | grep -q "$path"; then
    echo "[skip] worktree already exists: $path"
  else
    git -C "$REPO_ROOT" worktree add "$path" "$branch"
    echo "[ok]   worktree added: $path ($branch)"
  fi
}

main "$@"
