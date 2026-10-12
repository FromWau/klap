# TDD brief

Fill in the placeholders, write it to `<BASE>/brief.md`, and start each TDD agent with a short prompt that points at
it and names the agent's worktree, its items, and the declarations it owns (see `merge.md`, "Split by owner").
`<TOOL>` and `<VERSION>` are the real tool, `<BASE>` the build directory, `<ITEMS>` the todo range, `<SECTION>` the
todo section heading, `<SAFE ORACLES>` the list from the QA brief.

---

# TDD brief: build the owner's <TOOL> decisions (todo items <ITEMS>)

klap is a Kotlin Multiplatform CLI parsing library whose standard is GNU getopt_long; its defaults align with GNU
coreutils, and special behaviour is an opt-in. The module `example/<TOOL>` recreates <VERSION> `<TOOL>` with klap;
its action bodies are stubs. A QA pass wrote its tests against the real binary; tests the fixture or klap fails today
are targets, marked `@Ignore("TDD target: ...")`. The owner decided every item. Several agents work at once, each on
its own items, named in its prompt. Build yours, test first, so the targets pass.

## Where things are

- Your worktree is a full copy of klap with the uncommitted fixture tests and the decided `todo.md` in it. Its git
  index holds that starting state, so `git diff` shows exactly your changes. Work, edit and run Gradle only there;
  never write to `/home/fromml/Projects/klap` or another agent's worktree. Run Gradle with `--offline`; several
  builds share this machine, so a build may wait on a lock. Do not run `git add`, `git commit`, `git stash`, a `git
  checkout` of files you did not change, or create branches, and do not edit `todo.md` (the main session closes
  items).
- The decisions: `todo.md`, section "<SECTION>", items <ITEMS>. Each item names its target tests (as `Class: test`,
  the class without the tool prefix and `TargetsTest`), its evidence, and a sub-bullet **Owner, <DATE>:** with the
  decision. Read your items word for word; the owner's line overrides the item text where they differ.
- The fixture: `example/<TOOL>/src/main/kotlin/...`; its tests: `example/<TOOL>/src/test/kotlin/...`. `example/cp`,
  `example/find` and `example/chmod` are finished fixtures to copy idioms from (`builtins { }`, `Help.ByCommand(...)`,
  `Version.Of(description = )`, `Builtin.Off`, several longs on one input, `lastWins`, `atMostOne`,
  `.dashLed(gather) { }`).
- klap: `klap/src/commonMain/kotlin/com/fromwau/klap/` (public API at the top level); internals under `internal/`.
  Tests in `klap/src/commonTest`. The guide: `docs/guide.md`.

## The loop, for each target

1. Remove its `@Ignore`. Run it and see it fail for the reason its comment states.
2. Change the code you own until it passes. For a klap change, write klap tests first in `klap/src/commonTest` that
   state the behaviour in the library's own terms, see them fail, then implement.
3. Run the module's suite, and for a klap change `./gradlew --offline check`; keep everything green.
4. A target that does not state what the real tool does is not bent to: leave its `@Ignore` and report it with the
   evidence. A test anywhere that your change breaks either states behaviour your item replaces (restate it, with
   the real tool's evidence, and list it in the report) or is a regression (fix your change).
5. When a target passes, move it from its `*TargetsTest` class into the passing class of the same file; delete a
   targets class left empty. A target the owner turned into a record stays as `@Ignore("Will not do (todo N): ...")`.

## Rules

- Verify before you claim. Oracles, safe runs only: <SAFE ORACLES>
- Public klap API only as decided. Anything else public you think is needed, report as a need instead of adding it.
- Do not weaken a test, and do not change an expected value unless a real tool backs the new one.
- Load and follow these skills via the Skill tool before writing code: `kotlin`, `software-design`, `code-comments`,
  `bug-proofing`; when done, `deslop` over your own `git diff`, file by file, docs included.
- KDoc on every public declaration you add or change (one-line purpose, and the GNU line that motivates it); the
  guide sections that describe changed behaviour, restated rather than appended to; `example/README.md` where it
  states counts or behaviour you changed (but not the fixture's "done" row: the main session marks that).
- Lines of at most 120 characters, trailing commas, test names as backticked sentences without commas. In
  `klap/src/commonTest` an ignored test is a bare `@Ignore` under a `// reason` comment.
- Prose in plain sentences; never use "--" as a dash (option names like `--help` are fine).

## Report (your final message)

1. Per item: what changed (files and gist; public API as built, with signatures), the tests added, the targets that
   now pass, which still have `@Ignore` and why, and every detail you chose that the decision left open.
2. Tests anywhere you restated, with evidence.
3. Needs or problems with a decided shape (stop and report rather than inventing another shape), and any target
   that can pass only once another agent's work is in.
4. The deslop pass: what changed and what you left.
5. The last `./gradlew --offline check` (or the module's `test` for a fixture-only run) result with counts.
