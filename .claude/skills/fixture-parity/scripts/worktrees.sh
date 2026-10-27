#!/usr/bin/env bash
# Make one detached worktree per agent at HEAD, carrying the main checkout's uncommitted state (staged or not:
# modified, new and deleted files) and staging it, so each agent's `git diff` shows only that agent's own changes.
#
# usage: worktrees.sh <repo> <base-dir> <name>...
#   creates <base-dir>/wt-<name> for each name and <base-dir>/oracle for throwaway tool runs.
set -euo pipefail

repo=$1
base=$2
shift 2
mkdir -p "$base/oracle"
cd "$repo"
for name in "$@"; do
    wt=$base/wt-$name
    git worktree add --detach "$wt" HEAD >/dev/null
    # Against HEAD, so staged changes (a `git mv`) carry over as well as unstaged ones; a path gone here goes there.
    while IFS= read -r -d '' file; do
        if [[ -e $file ]]; then
            mkdir -p "$wt/$(dirname "$file")"
            cp "$file" "$wt/$file"
        else
            rm -f "$wt/$file"
        fi
    done < <(git diff --name-only -z --no-renames HEAD; git ls-files -o --exclude-standard -z)
    [[ -f local.properties ]] && cp local.properties "$wt/"
    git -C "$wt" add -A
    echo "$wt: $(git -C "$wt" diff --cached --name-only | wc -l) baseline files staged"
done
