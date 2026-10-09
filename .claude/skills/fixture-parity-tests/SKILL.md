---
name: fixture-parity-tests
description: >-
  Spawn a subagent that writes fully scoped unit tests for one klap example fixture (example/<tool>, such as
  pacman, cp, git, tar, ls) whose expectations come from the real installed binary: projection, help,
  completion and suggestions, with every cursor position and words after the cursor. Tests that the fixture
  or klap fails today are ignored TDD targets for a follow-up agent. Use this whenever the user names an
  example and asks to audit it against the real tool, to "create tests for example/<tool>", to write TDD
  targets or parity tests for a fixture, or to run a QA agent on an example module, even if they do not say
  "skill" or "subagent".
---

# Fixture parity tests

The user names one example module (`example/<tool>`). You do not write the tests yourself: you prepare a
safe brief and hand it to one background subagent, which audits the fixture against the real `<tool>` and
writes unit tests whose actual values come from the klap library and whose expected values are what the real
binary does. A test the fixture or klap fails today is not a problem to solve here; it is a target, marked
`@Ignore("TDD target: ...")` so `check` stays green, that a later TDD agent makes pass. That split keeps this
run honest: an agent that may not fix anything has no reason to bend an expectation toward the current output.

## 1. Check the ground

- `example/<tool>` exists and builds as a `klap-fixture` module (`build-logic/src/main/kotlin/klap-fixture.gradle.kts`).
- The real binary is installed: `command -v <tool>`, and note its version (`<tool> --version` where that is
  safe). Without the binary there is no oracle; stop and tell the user.
- The public test API is in klap: `klap/src/commonMain/kotlin/com/fromwau/klap/testing/Completion.kt`
  (`Suggestion`, `suggestionsFor`, `acceptSuggestion`, `tab`, `filesAfter`), and `ParitySuite` is in
  `example/parity`. If either is missing, stop and say so rather than letting the subagent invent one.

## 2. Settle the safe oracles for this tool

The subagent learns the truth by running the real binary, and some fixtures recreate tools that delete,
move, overwrite or reach the network. Decide before spawning which invocations are safe, and put that list
in the brief:

- Always safe: `--help`, `-h`, per-subcommand help, `--version`, the man page, `/usr/share/doc`, and a
  completion the shell ships for the tool (read only).
- Read-only tools (`ls`, `find`, `head`, a `pacman -Q` query): real runs are fine, in a throwaway directory
  the subagent creates under the scratchpad or `mktemp -d`.
- Tools that change files (`rm`, `mv`, `cp`, `mkdir`, `chmod`, `dd`, `tar`): only inside a throwaway
  directory holding throwaway files, never on a path outside it, and `dd` only with `if=`/`of=` inside it.
- Tools that touch the network or the system (`curl`, `ssh`, `rsync` to a remote, `git` remote operations,
  `pacman -S/-R/-U/-D/-F` beyond `--help`): no real runs at all. Help, man page, and invocations the tool
  rejects while parsing its arguments, before it does anything, and only when that is certain.
- Never sudo.

If you cannot tell whether an invocation is safe for this tool, ask the user before spawning.

## 3. Spawn the subagent

The subagent works against `references/testing-matrix.md`, an index of one file per table under
`references/matrix/`: the axes and required combinations every pass covers, named by IDs its coverage report uses.
When a pass settles a "needs checking" row or a `Disputed:` line, update that row in its table file. Read `references/subagent-brief.md`, fill in `<TOOL>`, `<MODULE>`
(`example/<tool>`), `<BINARY>` (its path and version) and `<SAFE ORACLES>` (the list from step 2), and launch it as
one background `general-purpose` agent with that brief as its prompt. One agent per example; for several examples,
run them one after another or ask the user before fanning out, since each run is long.

Tell the user it is running, which safe oracles it may use, and that it will not commit.

## 4. When it reports

Do not relay the report on faith. Before telling the user:

- Run `./gradlew :example:<tool>:test` and confirm it passes with the targets skipped. Then comment out every
  `@Ignore` in the targets files, rerun, and confirm each target fails for the reason it states; put the
  annotations back.
- Check the coverage table against `references/testing-matrix.md`: every input has a row, and every applicable
  cell is pass, target or n/a with a reason. A silent cell is not covered; send the agent back for it.
- Confirm no test computes its expectation, or which file a cell lands in, from a model of klap: the actual
  values must come from the library (`suggestionsFor`, `ParitySuite`), the expected ones from the real binary.
- Spot-check a few surface claims against the real binary yourself, using only the safe oracles.
- Reproduce any klap core bug it reports with its failing test.

## 5. File the targets in `todo.md`

klap keeps its open work in `todo.md` at the repo root, so a target found today is still there for the agent
that fixes it. After checking the report, add one item per target under **Fixture parity targets** (a klap
core bug under **Defects**), in the file's format: the next unused number, a bold one-line statement of the
real tool's behaviour, the target test's file and name, the evidence, and the layer that owns the fix. File
only what you confirmed yourself; the subagent does not edit `todo.md`.

## 6. The follow-up TDD agent

When the user hands targets to an agent, it removes a target's `@Ignore`, runs the test to see it fail for the
stated reason, and changes the fixture or klap until the test passes, then moves the `todo.md` item to **Done**
with a verdict: what changed, which test now passes, and the date. A target can itself be wrong: if it does not
state what the real binary does, the agent does not bend the code to it, but reports the test as needing a fix,
with the evidence, and leaves the item open.

Then tell the user what was confirmed, what the targets are and which layer each one points at (fixture or
klap core), which `todo.md` items you filed, and what the subagent could not verify. The targets are input for
a follow-up TDD agent; do not start fixing them unless the user asks.
