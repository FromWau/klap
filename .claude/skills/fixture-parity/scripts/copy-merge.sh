#!/usr/bin/env bash
# Copy a merge worktree's result into the main checkout and prove the two now carry the same change against the main
# checkout's HEAD. `git apply --3way` stages what it applies, so the merge is read against a revision, not as an
# unstaged diff, or most of it is silently left behind. todo.md stays the main checkout's own.
# --no-renames: a renamed file must arrive as a delete plus an add, the two fields the read loop expects.
#
# usage: copy-merge.sh <merge-worktree> <main-checkout>
#   the main checkout's HEAD must be the revision the merge worktree was made from.
set -euo pipefail

merge=$1
main=$2
base=$(git -C "$main" rev-parse HEAD)
git -C "$merge" add -A
while IFS= read -r -d '' status && IFS= read -r -d '' file; do
    if [[ $status == D ]]; then
        rm -f "$main/$file"
    else
        mkdir -p "$main/$(dirname "$file")"
        cp "$merge/$file" "$main/$file"
    fi
done < <(git -C "$merge" diff --cached --name-status --no-renames -z "$base" -- . ':!todo.md' ':!local.properties')
a=$(git -C "$merge" diff --cached --binary --no-renames "$base" -- . ':!todo.md' ':!local.properties' | sha256sum)
git -C "$merge" reset -q
git -C "$main" add -A -- . ':!todo.md'
b=$(git -C "$main" diff --cached --binary --no-renames HEAD -- . ':!todo.md' ':!local.properties' | sha256sum)
git -C "$main" reset -q
if [[ $a == "$b" ]]; then
    echo "main matches the merge (${a%% *})"
else
    echo "MISMATCH: merge ${a%% *}, main ${b%% *}" >&2
    exit 1
fi
