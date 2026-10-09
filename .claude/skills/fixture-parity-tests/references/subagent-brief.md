# Subagent brief

Fill in the placeholders and pass the whole text below as the subagent's prompt.

---

You are a QA agent for ONE module of the klap CLI library: `/home/fromml/Projects/klap/<MODULE>`. klap is a
Kotlin Multiplatform CLI parsing library. This module recreates the command-line surface of the real
`<TOOL>` with klap (`<MODULE>/src/main/kotlin/...`); its action bodies are stubs. Read first: the fixture
source, its existing tests (`<MODULE>/src/test/...`), `ParitySuite` in
`example/parity/src/main/kotlin/com/fromwau/klap/fixture/ParitySuite.kt`, `example/README.md`,
`build-logic/src/main/kotlin/klap-fixture.gradle.kts`, the public completion test API in
`klap/src/commonMain/kotlin/com/fromwau/klap/testing/Completion.kt`, and the completion and testing parts of
`docs/guide.md` (search "completeWith", "__complete", "Testing your CLI"). `example/pacman/src/test` is a worked
example of the whole method (tables from the binary's help, generated cells, one file per matrix table); copy
its structure rather than inventing one.

The real binary: `<BINARY>`.

## Goal

Write UNIT tests (no shells, no pseudo-terminals, no network) in `<MODULE>/src/test/kotlin/...` that cover
everything the fixture declares, with expected values taken from how the real `<TOOL>` handles each line.
The tests are input for a follow-up agent that works test-first: a test the fixture or klap fails today is
a target. Mark it `@Ignore("TDD target: <one line: what the fixture or klap must do>")` (kotlin.test.Ignore) so
`check` stays green; the follow-up agent removes the annotation and makes it pass. Do not fix the fixture, do
not fix klap, and do not weaken a test to make it pass.

The actual values always come from the klap library: `suggestionsFor`, `acceptSuggestion`, `tab` and
`ParitySuite` run the fixture through klap itself. Never reimplement or model klap's behaviour in the tests,
not even to decide which cells are targets: group cells into targets by what they test (say, "options of
another operation under -Q"), and run them to see which groups fail.

## The rule that governs every expected value

Expectations reflect the ACTUAL `<TOOL>`, never what the fixture or klap does today.

- Parse and projection: what the real tool accepts or rejects, and how it reads each word (which option
  takes a value, what a repeated letter means, what `--` does, which reading an ambiguous letter has in each
  context, which of two conflicting options wins).
- Completion and suggestions: offer exactly what the real tool would accept at that point of the line,
  nothing it would reject (no second mode where only one is allowed, no option of another mode, no repeat it
  refuses, only choice values in the tool's own case), with the tool's own help wording as descriptions. A
  shell's bundled completion for the tool (`fish -c 'complete -C "<TOOL> ..."'`, a zsh `_<TOOL>`, a
  bash-completion file) is a reference for presentation only; what the binary accepts decides.
- Where you could only read the man page or `--help`, not run the line, say so in the test's comment.
- The command line's structure is what counts: what is accepted or refused, how each word binds, what is offered.
  A refusal is asserted as a refusal, never by its error text, which klap phrases its own way; quote the real
  tool's message in the comment as evidence only.

## Safety (absolute)

Use only these oracles: <SAFE ORACLES>

Anything outside that list you do not run. When in doubt, read the man page instead.

## The completion contract (decided; do not re-litigate)

- A suggestion is the WHOLE word under the cursor, since the shell replaces that word. An attached value
  comes back with its option in front (`--color=al` offers `--color=always`; a glued short value `-ffile1`
  offers `-ffile1.toml`), described by its option's help where it has no description of its own, as
  `cp --backup=<TAB>` lists `--backup=existing  (Make backup of each existing destination file)`.
- A short cluster offers only its continuations, never the typed cluster itself, as `pacman -Q<TAB>` lists
  `-Qb`, `-Qc`, ...: the cluster so far plus each remaining short (without its dash), each with that short's
  own help. A short already in the cluster is not offered again unless the real tool counts it (pacman reads
  `-Qii` as a second `-i`, so `-Qi<TAB>` offers `-Qii`), and nothing follows a value-taking short, whose value
  is the rest of the word.
- Flags are offered once a dash is typed; on no word the slot is the operand or subcommand.
- The word under the cursor is handed over as far as it is typed; the rest of that word is not sent. A
  cursor at a word's first character is the start of that word, typed as far as nothing. The words after the
  cursor are sent too, so a provider or a constraint can read an input typed later on the line.
- A `.file()` slot or `completeFiles()` answers with `filesAfter(head)`, `head` being the part of the word
  that is not the path (`--config=`, dd's `if=`).

## The test API

From `com.fromwau.klap.testing`: `Cli.suggestionsFor(line, cursor)` gives each `Suggestion(value,
description)`, `acceptSuggestion(line, suggestion, cursor)` the line once one is taken,
`Cli.tab(line, cursor)` the line after one Tab press (the one suggestion taken, or what several share), and
`filesAfter(head)` the file directive. Use `ParitySuite` (`binds`, `accepts`, `rejects`, and what else it
offers) for parsing; if it cannot state a claim (say, which help page shows), add a small helper next to it
in `:example:parity` rather than weakening the claim. Where klap and the real tool both refuse a line but for different reasons (an unknown
option against a missing value, an ambiguous value against an invalid one), pass `rejects` a short `differs` note:
the test stays green and every run prints the gap.

## Coverage

Work against the testing matrix in
`/home/fromml/Projects/klap/.claude/skills/fixture-parity-tests/references/testing-matrix.md`. Its completion
index links one file per table under `references/matrix/`: K the slot under the cursor and K7 the short
clusters, each with the cursor columns; V where a value's candidates come from; L the words after the cursor; S
what is already given; G the suggestion list and E what each cell asserts; P projection; X converters. Those
rows and their required cells decide which tests you write. A row marked "needs checking" is settled by your
run against the real tool; a row with a `Disputed:` line gets a test for the side the real tool backs, and your
report says which. Your coverage report is the matrix's coverage table, every applicable cell marked pass, target or `n/a`
with a reason. The points below summarise it.

1. Projection: for EVERY mode, flag, option and operand the fixture declares, in every spelling (long,
   short, repeated or counted, attached `--opt=v`, spaced `--opt v`, glued `-ov`, clusters, `--`), a
   `binds` test of the whole projected record or a `rejects` test. Also what the real tool has and the
   fixture lacks.
2. Completion and suggestions, for EVERY one of those inputs:
   - cursor on no word (after a space), before a word (at its first character), inside a word (the rest of
     the word stays), at the end of a word;
   - at the end of the line, and with words after the cursor, including one a provider or a constraint
     reads;
   - for each cell: the exact suggestions with their descriptions, the line after `acceptSuggestion` of a
     named suggestion, and the line after `tab`;
   - option values in both forms (`--opt=` and `--opt `, short spaced and glued), paths through
     `filesAfter(head)`, choices described by their option's help, short-cluster continuations, and what must
     not be offered.
3. Structure: generate cells from compact tables (each input with its spelling, help text and value kind)
   rather than repeating near-identical functions, and make every failure message name the exact line and
   cursor. One test file per matrix file, named for what it covers: `<Tool>CompletionTest.kt` (K, K7),
   `<Tool>ValueCandidatesTest.kt` (V), `<Tool>LineContextTest.kt` (L, S), `<Tool>SuggestionListTest.kt` (G, E),
   `<Tool>ProjectionTest.kt` (P), `<Tool>ConverterTest.kt` (X). A test goes by the table of the axis it varies, a
   required-cell combination under completion; shared tables and helpers go in support files beside them. Each
   file holds a `<Tool><Name>Test` class for what passes today and a `<Tool><Name>TargetsTest` class for the
   targets, each target with its `@Ignore`, a short comment giving the evidence and what has to change, and a
   one-paragraph KDoc on the class stating the convention. Prove every target: comment out its `@Ignore`, run,
   see it fail for the stated reason, and put the annotation back.
4. Existing tests that assert a behaviour the real tool does not have: replace them with targets stating
   the real behaviour, so a later fix does not have to break a test.

## How to work

- Load and follow these skills via the Skill tool before writing code: `kotlin` (lines of at most 120
  characters, trailing commas, one-line KDoc purpose), `software-design`, `code-comments` (a comment only
  for a why the code cannot carry), `bug-proofing` (reproduce or refute a suspected bug before you write it
  down).
- Verify every claim before you write it: run the real tool (safely) for surface claims, read klap source
  for behaviour claims, run the tests.
- Test names are backticked sentences stating the contract, with no commas (Kotlin/Native rejects them).
- Run from `/home/fromml/Projects/klap`: `./gradlew :<MODULE with colons>:test`. With every target's `@Ignore` in place
  the module compiles and all of its tests pass; without them each target fails for the reason its comment
  states, not for a mistake in the test. This machine's Gradle home is `$XDG_DATA_HOME/gradle`
  (~/.local/share/gradle), not ~/.gradle.
- You may add helpers to `:example:parity`. Do not change the fixture's main source, do not change klap
  (`klap/src`), do not edit `todo.md` (the main session files the targets once it has checked them), do not
  commit, do not create branches.
- Prose (comments, report) in plain sentences; never use the "--" dash as punctuation (option names like
  `--root` are fine).

## Report (your final message)

1. Surface audit: a table of every difference between the fixture and the real `<TOOL>`, each with the
   evidence (the exact safe command and its output, or the man page line), the layer that likely owns the
   fix (fixture or klap core), and the test that states it.
2. klap core bugs: each with its reproduction and the target test that states it.
3. Coverage: the matrix's coverage table, one row per input (and per input the real tool has that the fixture
   lacks), every applicable cell marked pass, target or n/a with a reason.
4. Test files and counts, and the list of targets with the reason each fails today.
5. What you could not verify, said plainly.
6. The result of `./gradlew :<MODULE with colons>:test`: which tests fail, and that these are exactly the
   targets.
