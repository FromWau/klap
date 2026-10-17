---
name: fixture-parity
description: >-
  Bring one klap example fixture (example/<tool>: chmod, cp, find, pacman, dd, head, ls, mv, rm, tar, git,
  curl, ssh) to the same behaviour as the real installed tool, end to end: a QA subagent writes the
  testing-matrix tests against the real binary (projection, help, completion, every cursor column and the words
  after it), failing ones become TDD targets, the main session proves them and files them in todo.md, walks the
  owner through each decision, then TDD subagents in worktrees build the fixture and klap changes, and the main
  session merges, verifies and closes the fixture. Use this whenever the user names a fixture and wants parity,
  "same behaviour as the real <tool>", "next fixture is <tool>", "write all tests for example/<tool> with the testing
  matrix", "TDD the <tool> targets", "step through the skip items", or asks to audit a fixture against GNU, even if
  they do not say "skill" or "subagent". Also use it to resume any step of that loop.
---

# Fixture parity

A klap fixture (`example/<tool>`) recreates a real tool's command-line surface; its actions are stubs. A fixture is
**done** when its tests cover the testing matrix with expectations taken from the installed tool, and every target
passes or is a record the owner chose. This skill is the loop that gets it there. Its parts:

| Step | Who | Reference |
|---|---|---|
| 1. Check the ground, settle safe oracles | main | below |
| 2. QA pass: matrix tests, failures as targets | one background agent | `references/qa-brief.md`, `references/testing-matrix.md` |
| 3. Prove the targets and the claims | main | below, `scripts/unignore-check.py` |
| 4. File the targets, walk the owner through them | main, one decision per message | `references/decisions-walkthrough.md` |
| 5. TDD pass: build every decision | two or three agents in worktrees | `references/tdd-brief.md`, `references/merge.md` |
| 6. Merge, verify, close | main | `references/merge.md`, `scripts/` |

Each step waits for the user: say what a step found and ask before starting the next ("Should I step through them?",
"Should I start the agents?", "Should I commit?"). The owner's decisions, not the agents', decide what is built, and
nothing is committed unless the user asks.

## Why the work is split this way

The QA agent may not fix anything, so it has no reason to bend an expectation toward what klap does today: expected
values come from the real binary, actual values from klap (`suggestionsFor`, `ParitySuite`). The owner decides each
target before anything is built, because a target can be fixed in the fixture, in klap's defaults, in new klap API,
or not at all, and those are the owner's calls. The TDD agents then build exactly what was decided, test first. The
main session checks every claim an agent makes before repeating it, because agents' reports, like your own memory,
are sometimes confidently wrong.

## 1. Check the ground

- `example/<tool>` exists and builds as a `klap-fixture` module.
- The real binary is installed: `command -v <tool>` and its version. Without it there is no oracle; stop and say so.
- klap's test API exists (`klap/src/commonMain/kotlin/com/fromwau/klap/testing/Completion.kt`, `ParitySuite` in
  `example/parity`).
- `git status` is clean, or you know what the uncommitted state is: the worktrees later copy it.

**Safe oracles**, decided before spawning and written into the brief:

- Always safe: `--help`, `-h`, `--version`, man and info pages, `/usr/share/doc`, and shell completion files (read).
- Read-only tools (`ls`, `find`, `head`): real runs in a throwaway directory.
- Tools that change files (`chmod`, `cp`, `mv`, `rm`, `dd`, `tar`): only inside a throwaway directory made
  with `mktemp -d -p <base>/oracle`, on files made there, relative paths only, never `..`, `/` or an absolute path.
- Network or system tools (`curl`, `ssh`, `git` remotes, `pacman -S/-R/-U`): no real runs; help, man
  page, and lines the tool refuses while parsing, only when that is certain.
- util-linux `getopt -o <shorts> -l <longs> -- <argv...>` shows how glibc getopt_long reads a line; give `-o ''` when
  there are no shorts, or it reads the first word as the short spec.
- An option ruled out of real runs is ruled out in every spelling: an abbreviation (mv's `--c` is `--context`) or a
  cluster (`-ZZ`) runs it too. Probe abbreviations with `getopt`, not with the tool.
- Never sudo, no network. If you cannot tell whether a run is safe, ask the user.

**This machine's shell traps**, for you and for every brief:

- The Bash tool runs zsh: a word starting with `=` is a command lookup (`=755` fails), so quote it (`'=755'`).
- A line held in one variable is not split into words: pass each argument separately.
- A bare `find` runs bfs; call `/usr/bin/find`.
- Put build directories under `/tmp/claude-1000/`, not the session scratchpad, which can disappear mid-run.

## 2. The QA pass

Fill `references/qa-brief.md` and start **one** background `general-purpose` agent with it. One agent per fixture:
it is a long run, and its value is a single consistent model of the tool. Tell the user it is running, what it may
run, and that it will not commit. If the user asks whether the agent uses the testing matrix, the answer is yes: the
brief's Coverage section makes the matrix rows decide which tests are written, and its report is the matrix's
coverage table.

## 3. Prove the targets and the claims

Do not relay the report on faith. Before telling the user:

1. `./gradlew --offline :example:<tool>:test` passes with the targets skipped (count with `scripts/test-counts.py`
   after deleting `example/<tool>/build/test-results`; quiet Gradle prints no counts). The script reads every
   module's last results, and an earlier `unignore-check.py` run leaves its failing reports behind, so read only
   the tool's row, or delete all `build/test-results` before a full count.
2. `python3 scripts/unignore-check.py /home/fromml/Projects/klap <tool>`: with every TDD target's `@Ignore` gone,
   exactly the targets fail, and only in `*TargetsTest` classes. It restores the files itself.
3. Spot-check the surface claims against the real tool yourself, a dozen or more, in one throwaway directory, and
   quote them: they become the evidence in the walkthrough.
4. Check the coverage table: every input has a row, every applicable cell is pass, target or n/a with a reason. A
   silent cell is not covered; send the agent back for it.
5. No test re-derives klap's rules (`acceptSuggestion`, `tab`, a common-prefix copy); the line after a suggestion is
   pinned once in klap's `CompletionFlowTest`.

Then report: the counts, what you checked, the targets grouped by the layer that owns the fix, and what the agent
could not verify.

## 4. File and walk through

Follow `references/decisions-walkthrough.md`: file one `todo.md` item per decision under a section for the tool,
ask first whether a target is really a klap default that is not GNU, then present one decision per message with the
`decision-walkthrough` format, and record every answer as it arrives.

## 5. The TDD pass

Split the items by owner (`references/merge.md`, "Split by owner"), make the worktrees with
`scripts/worktrees.sh`, fill `references/tdd-brief.md`, and start the agents in one message so they run in
parallel. Each prompt names its worktree, its items, the declarations it owns and must not touch, and the targets
that can pass only once both halves are merged.

## 6. Merge, verify, close

Follow `references/merge.md`: patches with `scripts/agent-patch.sh`, a merge worktree, `git apply --3way`, the
conflicts resolved as a union of the moves, the cross-dependency targets un-ignored, the full check counted with
`scripts/test-counts.py`, and `scripts/copy-merge.sh` into the main checkout. Then:

- In `todo.md`: move each item to **Done** with a `Done <date>:` verdict (what changed, the API as built, which
  tests now pass), and add a closing sentence to the tool's section paragraph with the final counts.
- In `example/README.md`, "Fixture progress": the tool's row becomes
  `done: <N> tests, every target passes[; <M> kept as records (todo ...)]`.
- File anything the agents found but did not build as a new open item, and tell the user.
- Remove the worktrees.
- Report what changed, the undecided details the agents chose (the owner may want to revisit them), any claim of
  yours the build proved wrong, and ask whether to commit. The commit message names the items by number and has no
  attribution trailers.
