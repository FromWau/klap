#!/usr/bin/env bash
# Write one agent's changes as a binary patch: its worktree's unstaged diff against the staged baseline, new files
# included, todo.md left out (the main session owns it).
#
# usage: agent-patch.sh <worktree> <patch-file>
set -euo pipefail

wt=$1
out=$2
git -C "$wt" add -N .
git -C "$wt" diff --binary -- . ':!todo.md' ':!local.properties' > "$out"
echo "$out: $(git -C "$wt" diff --stat -- . ':!todo.md' ':!local.properties' | tail -1)"
