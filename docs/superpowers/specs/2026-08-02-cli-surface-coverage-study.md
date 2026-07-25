# CLI surface coverage study: what klap can and cannot express

**Status:** No longer observational. Fifteen of the limits recorded here have been closed, one more is half
closed, and four have been declared permanent non-goals; each is marked in place below. **Date:**
2026-08-02 (original observations), updated 2026-08-02 as the first fixes landed and 2026-08-03 as the
gap-closure branch landed.

> ### Read this before citing anything below
>
> **Every file path and line number in this document is historical.** The ten study stubs it cites lived
> in `example/src/commonMain/kotlin/com/fromwau/example/study/`; that directory no longer exists. Each
> tool is now its own Gradle module under `example/<tool>/`, with a parity test suite that executes the
> claims this document makes in prose. Four more tools (`ls`, `head`, `mv`, `pacman`) were added that this
> study never analysed.
>
> The analysis and the verdicts stand. The citations do not, and are deliberately left unrewritten: they
> are what the tree looked like when the observations were made. **The gap rankings have since moved** —
> the four gap-closure phases of 2026-08-02 resolved §5.2's option-value half, §5.3, §5.5, §5.11, §5.12
> and §5.4's flag half, and the gap-closure branch of 2026-08-03 resolved §5.4's option half, §5.8, §5.10,
> §5.13a, §5.14 and §5.17's completion sub-gap, and half of §5.13b. Each is marked at its own section and
> in the ranking table below.
>
> **The fixtures are now the live record.** Where this document and a parity test disagree, the test wins —
> it runs. Several claims here were already found stale by executing them; those are marked below.
>
> **Its `README.md:<line>` citations need one more hop.** On 2026-08-03 the README was split: it is now a
> 73-line overview, and the reference material those citations point into moved verbatim to
> [`docs/guide.md`](../../guide.md), section titles unchanged.
>
> | closed | was | by |
> |---|---|---|
> | §5.1 | a variadic positional could never bind an empty list | `db15fd4` |
> | §5.3 | every option and flag must have a long name | `2119bed` |
> | §5.4a | order between two OPTIONS is discarded | `bb34c38`, `a4ca460` |
> | §5.5 | one holder cannot carry a second spelling | `2119bed` |
> | §5.6 | no optional-value options (`--opt[=VALUE]`) | `ad54f26`, `db4a315`, `18c1d12`, `39e6d67`, `460ab22` |
> | §5.7 | built-in names seized tree-wide, no opt-out | `b0d437a` |
> | §5.8 | negation is mechanical, long-form-only, symmetric | `e9332a0`, `92b633d`, `556ab0d` |
> | §5.9 | no mutual-exclusion or required-one-of construct | `faf5b91`, `65b34d8` |
> | §5.10 | long options match by exact equality, no GNU prefix abbreviation | `c794fad`, `1db3089`, `d2e41ad`, `0ea2cc9`, `ddf8b00` |
> | §5.11 | digit shorts impossible in both directions | `b89eeec`, `6ed0a08` |
> | §5.12 | a variadic must be last, and it takes everything | `a63c705` |
> | §5.13a | an operand's arity cannot depend on which options were given | `9feefcd`, `c6a7ca4` |
> | §5.14 | option parsing never stops at a positional | `bc4269c`, `31a3406` |
> | §5.15 | no custom value placeholder (metavar) | `60ab662` |
> | §5.17 | a `.completeWith` provider could not reach file completion | `922271e`, `4e0aaf5` |
>
> | non-goal or superseded | ruling | by |
> |---|---|---|
> | §5.2 operand half | a dash-led token is never a positional OPERAND; guideline 14 makes klap's reading the conforming one | **non-goal**, `908300e` |
> | §5.4b | order between an option and a POSITIONAL | **non-goal**, 2026-08-03 |
> | §5.16 | single-dash multi-character options | **non-goal**, `78a926f` |
> | §5.17 shape | a named `key=value` operand as a third input kind | **non-goal**, 2026-08-03 |
> | §5.18 | no terminator-delimited capture | **superseded by §5.14**, `bc4269c` |
>
> §5.13b (`cp`'s three alternative operand layouts) is the one entry that is neither: the `-t DIR SOURCE...`
> half is closed and `-T`'s exactly-two cap is not. See §5.13.
>
> One correction the fixtures forced, recorded here because it changes what §5.11 *means*: klap does not
> *reject* a digit short, it **silently mis-binds** it. `head -5 f` parses and binds a file named `-5`.
> A silent wrong answer is a worse gap than the loud rejection this document implies.
**Subject tree:** originally `master` @ `46de18f`; re-verified against `master` @ `db15fd4`.
**Artifacts:** ten stub programs in `/home/fromml/Projects/klap/example/src/commonMain/kotlin/com/fromwau/example/study/`,
committed in `f9ff0f8` and updated in place for `db15fd4`.

**What `db15fd4` changed.** `bindPositionals` guarded the `Cardinality.Multiple` branch with
`if (slice.isEmpty() || slice.size < c.min)`; it is now `if (slice.size < c.min)`
(`internal/parse/Parser.kt:265`). `multiple()` with its default `min = 0` therefore accepts zero operands and
binds an empty list, while a declared `min >= 1` is enforced exactly as before. Separately, `argSummary`
renders an optional variadic as `[name...]` and a mandatory one as `<name>...`
(`internal/render/Help.kt:56`). Regression tests: `klap/src/commonTest/kotlin/com/fromwau/klap/ParsePositionalsTest.kt`,
class `VariadicPositionalArityTest` (`:187-217`). Measured effect: §3.1.

## 1. What this is

Ten agents each took one real command-line tool and tried to reproduce its argument surface in klap as a
**dry, non-functional stub**: every input declared with its real converter chain, cardinality, help text and
completion, but an `action { }` that only prints what it would have done, so the study measures
*declarability* and never runtime behaviour. The ten tools (`mkdir`, `rm`, `cp`, `chmod`, `tar`, `dd`,
`find`, `ssh`, `curl`, `git`) were chosen for **shape diversity**, not popularity: a flat flags-plus-operands
tool, a variadic-then-scalar operand list, a scalar-then-variadic one, an option-arity stress test, a
key=value operand grammar, an expression grammar, a wrapper with an opaque tail, a wide flat option surface,
and a multi-level dispatcher. The ten stubs are kept in the repo and **must keep compiling**: they are the
regression surface for every API change this document proposes, and `./gradlew :example:compileKotlinLinuxX64`
is the gate.

Each agent wrote the limits it hit as `KLAP-GAP` comments in its own file (89 markers at the time of the
original pass; 75 after `db15fd4`) and as a structured record; those records are consolidated in
`findings.json`.

**Recounted 2026-08-03, against the fourteen fixture modules the ten stubs became.** The number was
**48**, measured with `grep -rnE "^\s*(//|\*)\s*KLAP-GAP" example/*/src/`, of which 46 sat in fixture main
sources and 2 in parity tests. A looser `grep -rn "KLAP-GAP" example/*/src/` returned 57, the extra 9 being
prose that *refers* to a marker rather than being one ("see the `-d` note", "see the closing KLAP-GAP
block"); this document's counts have always meant markers, so 48 was the figure to cite.

**Revalidated and recounted 2026-08-03 (second pass).** Every one of the 48 was checked against the
current API rather than re-read as prose, and **36 were wrong**: 17 asserted a limit that no longer
exists, 14 carried citations pointing at unrelated code after `Parser.kt` split into
`internal/parse/Parser.kt`, 4 were part-right, and 1 was a bare cross-reference. Two root causes account
for nearly all of it — `flag`/`option`/`globalOption` taking `vararg names` (which made short-only and
multi-alias spellings expressible, invalidating 12 markers claiming a mandatory long name) and the parser
file split (which rotted every line-number citation that could rot, 14 of 14). The stale markers are
deleted, the survivors compressed and re-cited **by symbol rather than by line number**, so the next
refactor cannot silently rot them again. The inventory now stands at **30** strict markers (38 loose),
and every remaining one has been confirmed true against the API as it is today. The separate
ergonomics-study stub under `example/src/commonMain/.../study/ergo/` carried 14 more, which belonged to
that study and were never part of this count. Per fixture: `find` 10, `cp` 8, `git` 7+1, `curl` 5,
`pacman` 3, `rm` 3+1, `chmod` 2, `mv` 2, `ssh` 2, `tar` 2, `dd` 1, `mkdir` 1, `head` 0, `ls` 0 (the `+n`
is that fixture's parity test).

> **Drifted twice since that recount, and is back at 48 by coincidence.** Read the arithmetic, not the
> total: the same command returned **45** for most of 2026-08-03, because `chmod` went 2 to 0 and `rm` 4 to
> 3 after the recount was written. It reads **48** again only because a fifteenth fixture, `rsync`, was
> added late that day carrying 3 new markers of its own. The 48-based statements below are left as they
> were and still follow from the figure they were written against, but they are not about the same 48.
>
> Per fixture as of the `rsync` addition: `find` 10, `cp` 8, `git` 7+1, `curl` 5, `pacman` 3, `rm` 3,
> `rsync` 3, `mv` 2, `ssh` 2, `tar` 2, `dd` 1, `mkdir` 1, `chmod` 0, `head` 0, `ls` 0.
>
> The ergonomics-study corpus that held the separate 14 was **deleted on 2026-08-03** along with the rest
> of `study/ergo/`; the showcase it sat beside is now `example/task-manager/`. The command above still
> means "fixtures only" after that move, because `task-manager` carries no markers.
>
> **The fixtures no longer look like this document describes them.** Every one was rebuilt on `cliOf` the
> same day: a fixture declares each handle as an ordinary `val`, ends its block in `projection { }`
> returning a `data class` of resolved values, and its parity test asserts a whole invocation with one
> `binds(argv, expected = NOTHING_BOUND.copy(...))`. The `<Tool>Inputs` classes of `lateinit var` handles
> this document's citations pass through are gone. The claims about what klap can and cannot *express* are
> unaffected, which is what this study is about.

One of the 48 was retired by this pass rather than by a code change: `example/chmod`'s marker for `-h`
claimed "klap cannot express it", which stopped being true when §5.8 landed. Both of the reasons it gave
are gated on `builtins.helpShort`, so declining that built-in frees the letter and the fixture could spell
real chmod's `-h` exactly. It keeps `-h` as help deliberately, which is a fixture choice and not an
inventory entry, so the marker became ordinary prose. A separate compile gate then tried to separate genuine klap
limits from author error. This document is the result after a second, independent verification pass in which
every claim still marked `blocked` was re-checked against the klap source, followed by a third pass after
`db15fd4` in which every stub was re-examined and every "now works" / "still blocked" claim was executed
(§3.1) rather than only read.

## 2. Method limitations

Recorded in full, because a study that hides its own instrumentation failure is not trustworthy.

### 2.1 The study's own JSON schema destroyed one agent's output

The `tar` agent's structured output was **rejected five times by the study's own schema**, which marked the
`awkward` array `required`. The agent had nothing it wanted to file there, omitted the key rather than
sending `[]`, and was killed by the retry cap. Its findings were recovered from its transcript and are
present in `findings.json`, but the damage is visible in the artifact: the `tar` record's `summary` string
still carries the raw `</summary><parameter name="awkward">[…]` fragment from the recovery, with four
awkward items embedded inside the summary text, alongside a separate top-level `"awkward": []`.

Two consequences:

1. `tar`'s findings **did not pass through the compile gate's reclassification**, so they deserve *more*
   scrutiny, not less. This pass therefore re-verified every `tar` claim from source; all of them hold. See
   §5.1, §5.2 and §7.
2. The premise that the agent "had nothing awkward to report" is contradicted by the recovered record, which
   contains four awkward items. The instrumentation, not the agent, lost them.

### 2.2 The mandated compile gate had essentially no discriminating power

All ten files compiled as written, on the first invocation, with zero errors. The gate confirmed the gate was
real (it verified `package_com.fromwau.example.study` is present in the emitted klib linkdata, and injected a
deliberate type error into `Mkdir.kt` to watch the build fail, then restored it byte-identically). But
compilation cannot catch anything here, because **klap enforces almost all of its structural rules with
runtime `require()`** in `internal/builder/BuilderValidation.kt` and `Converters.kt`, which fire when `cli { }`
constructs the tree, not when Kotlin compiles it.

Every judgement in the gate's report therefore came from two gates the gate agent added on its own
initiative: a construction probe (all ten `cli()` builders return successfully) and a behavioural probe
(real argv through each CLI, compared against what each file's comments claim). The study's *designed*
control was inert.

### 2.3 No adversarial verification of individual claims

The gate verified roughly twenty load-bearing behavioural claims and found exactly one wrong, and it was of a
different species than expected: a **correct limit illustrated with a wrong example** (`Ssh.kt` claimed
`ssh web1 ls -la` fails with `unknown option '-l'`; in fact that CLI declares `-l` itself, so `-la` binds
`login=a` silently and exits 0, which is strictly worse than the claim). The surrounding limit was left
standing. The remaining nine files' worked examples are **unaudited to that standard**. This pass re-verified
every `blocked` claim's *mechanism* against the source; it did not re-run every *illustration*.

### 2.4 Other confidence caveats

- **Self-report.** Each agent judged its own reproduction. The strongest evidence in this study is
  convergence: several agents that could not see each other's work reporting the same wall with the same
  `file:line`. A limit reported by exactly one tool is the weakest evidence here, and is labelled as such.
- **Ten tools is a shape sample, not a census.** Nothing in the sample exercises config-file/env-var
  precedence, interactive prompting, an external plugin command space (`git-<foo>`), or a compound
  short-option value grammar (`ps -o pid,comm`). Absence of a limit below is not evidence of its absence.
- **Small instrumentation nit.** The gate reports "91 markers"; the files contain 89, and the gate's own
  per-file breakdown sums to 89. Its prose numbers were not all machine-checked.
- ~~**The stubs are currently untracked.** Nothing enforces that they keep compiling until they are committed
  and wired into CI.~~ **Closed:** the ten stubs were committed in `f9ff0f8`, one commit before the fix, so
  `db15fd4`'s effect on them is a reviewable diff. The compile task is still not wired into CI.
- **The stubs are still not *executed* by any repo task** (`:example:allTests` is `NO-SOURCE`; nothing outside
  `study/` references the ten `internal fun …Cli()` factories). The post-`db15fd4` pass worked around this by
  building a throwaway probe against the linuxX64 executable target, running every claim in §3.1 through
  `Cli.run`, and deleting it. That verification is reproducible but not permanent: a behavioural regression in
  these ten surfaces would still not fail the build.

## 2.5 POSIX conformance, and what klap adds on top

**Added 2026-08-02 as a standing requirement.** klap must be POSIX compliant; anything beyond that is
sugar. The rule that makes "compliant" testable rather than aspirational:

> **klap must never change the meaning of a command line the guidelines DO define. Sugar may only assign
> meaning to input the guidelines leave undefined.**

The reference is POSIX.1-2024 (IEEE Std 1003.1-2024, The Open Group Base Specifications Issue 8), XBD
chapter 12 "Utility Conventions", section 12.2 "Utility Syntax Guidelines" — fourteen numbered guidelines.
The frozen edition is cited deliberately, since the maintained URL absorbs corrigenda in place and this
document's discipline is "what was true when observed":
<https://pubs.opengroup.org/onlinepubs/9799919799.2024edition/basedefs/V1_chap12.html>

`klap/src/commonTest/kotlin/com/fromwau/klap/PosixConformanceTest.kt` **executes** every guideline below,
quoting each one at the test that pins it. It is part of the ordinary test gate, so a change that breaks
conformance fails the build rather than being noticed in review.

### Conforming

| Guideline | How klap satisfies it |
|---|---|
| **3** an option name is a single alphanumeric character; multi-digit options should not be allowed | a short is exactly one character, enforced at construction; klap has no multi-character short to offer |
| **4** options preceded by `-` | enforced at DECLARATION since spellings became explicit tokens — a name without a dash cannot declare an option |
| **5** options group behind one `-`, at most one taking an argument, last | the short-cluster walk is this guideline |
| **6** each option and option-argument a separate argument | supported, with the attached forms as an extension |
| **7** option-arguments should not be optional | an option's argument is required unless the option itself opts out via `.optionalValue()` — sugar, see below |
| **8** multiple option-arguments as one comma/blank separated argument | the value reaches the converter whole; klap never splits it |
| **10** `--` ends options, and what follows is an operand "even if it begins with `-`" | `END_OF_OPTIONS`; only the FIRST one is structural |
| **11** option order should not matter, unless documented as overriding | `lastWins` is the guideline's own escape clause, and it documents itself in `--help` |
| **12** operand order may matter | positional order is the utility's to interpret |
| **13** a lone `-` is an operand meaning stdin | `isFlagLike()` excludes it |
| **14** an argument identifiable as an option, or as a group of options, should be treated as one | a dash-led token is an option token; an undeclared one is an ERROR, never demoted to an operand |

Guidelines **1** and **2** constrain a utility's NAME rather than its argument parsing, and are the tool
author's to keep.

### The sugar, and why each is additive

| Extension | Guideline it steps outside | Why it cannot disturb a conforming line |
|---|---|---|
| long options (`--verbose`) | 3 — POSIX option names are one character, so long options are outside its model entirely | a single `-` still introduces shorts and a bare `--` is still the delimiter; only `--<name>` reaches the long form |
| operand permutation (`app a -v b`) | 9 — all options should precede operands | a line that already puts every option first has no token for this rule to reach, so no conforming invocation changes meaning |
| non-alphanumeric shorts (`curl -:`) | 3 — alphanumeric | it claims a character no conforming option name could have used |
| `numericAlias` (`head -20`) | 3 — multi-digit options should not be allowed | guideline 14 goes first: the alias only claims a number that no COMPLETE cluster reading covers |
| attached long value (`--config=cfg`) | 6 — separate arguments | the separate form binds identically |
| repeated occurrences (`-I a -I b`) | 8 — one comma-separated argument | the guideline-8 form still arrives whole for the converter to split |
| optional option-arguments (`--color[=<WHEN>]`) | 7 — option-arguments should not be optional | opt-in per option; a tool that does not call `.optionalValue()` is bit-for-bit unaffected, which `PosixConformanceTest` pins |
| explicit negation spellings (`cp -L`/`-P`) | 3 — nothing; the guidelines describe no negation at all | both halves are ordinary short options as far as guideline 3 is concerned, and a line naming only the positive half has no token for the rule to reach |
| long-option prefix abbreviation (`mkdir --par`) | 3 — an option name is one character, so `--`-led names are outside the model entirely | a single `-` still introduces shorts and a bare `--` is still the delimiter; the short cluster on a conforming line binds identically whether or not an abbreviation appears elsewhere on it |

**And one that runs the other way, added 2026-08-03.** `optionsEndAtFirstOperand = true` is not sugar: it
turns the guideline-9 permutation extension **off** and leaves the behaviour the guideline describes, where
all options precede the operands. It is the only item in this document that makes klap more conforming
rather than less, and `guideline9_theSwitchRestoresTheConformingReading` pins it beside the test that pins
permutation as the extension.

### What this requirement decided

Three rulings elsewhere in this document are no longer judgement calls; the standard settles them.

- **§5.2's operand half** (`chmod -w f`) is not merely a defensible non-goal — guideline 14 makes klap's
  behaviour the CONFORMING one, and guideline 10 spells out `chmod -- -w f` as the answer.
- **§5.16** (single-dash multi-character options, `find -name`) is ruled out by guideline 3.
- **`lastWins`** is not an invention: guideline 11's "unless ... documented to override any incompatible
  options preceding it" describes it exactly.

And one open gap turned out to have the same shape as long options above, not a risk to the must-have:

- **§5.6** (optional-value options, `--color[=<WHEN>]`) asks to step outside **guideline 7**, which exists
  precisely because `--color auto` is ambiguous between a value and an operand. `.optionalValue(whenBare)`
  resolves it the way the sugar table resolves guideline 3 for long options: opt-in per option, so a tool
  that never calls it stays conforming. **RESOLVED** — see §5.6.

The conformance suite also found a real defect the day it was written: `numericAlias` gated on the first
digit alone, so a tree declaring `flag("-2")` rejected `-20` instead of aliasing it. Guideline 14 gives the
correct rule — the alias claims a number only when the token is not identifiable as a complete option
group — and that is what ships.

## 3. Verdict table

**No tool's verdict label changed after `db15fd4`, and that is the honest result.** Nine tools were already
`partly-blocked` and all nine are still `partly-blocked`; `find` was `not-expressible` and is still
`not-expressible`. The fix removed one blocker from seven tools, but every one of those seven still has at
least one *other* limit that keeps its real surface out of reach, so no reproduction became faithful. What
did move is the **reason** column, and the Δ column below records which tools moved at all. Per-tool detail,
with executed evidence, is in §3.1.

**Nor did any label change after the gap-closure branch of 2026-08-03**, and that is the honest result a
second time. Six limits closed and `ssh` lost the last thing this study called a structural blocker, yet
every tool still carries at least one unreachable piece of its real surface. What changed is the shape of
what is left: 37 of the 108 measured divergences are now klap's own injected built-in surface reaching a
tool that never had it, which is a different kind of gap from a parsing limit. Measured at §3.2. The 2026-08-03 column below says what moved.

| Tool | Shape it probes | Verdict | Δ `db15fd4` | Δ 2026-08-03 | One-line reason |
|---|---|---|---|---|---|
| `mkdir` | flat flags + one greedy `DIR...` | partly-blocked | **unchanged** | improved | The whole assigned surface fell out in five lines. ~~only `-Z` (short-only), `--context[=CTX]` (optional value) and GNU prefix abbreviation (§5.10) are unreachable~~ All three are reachable now: `-Z` since §5.3, `--context[=CTX]` via `.optionalValue("default")`, and `mkdir --par d` since §5.10. Declares `.multiple(min = 1)`, which real mkdir also requires, so §5.1 never applied. What is left is one fixture narrowing, not a klap limit: this stub's MODE converter is octal-only by study brief. |
| `chmod` | scalar operand + trailing variadic (`MODE FILE...`) | partly-blocked | **unchanged** | improved | The operand shape binds exactly; a leading-dash mode (`chmod -w f`, `chmod -755 f`) never reaches the positional and never will, since §5.2's operand half is now a declared non-goal, with `chmod -- -w f` as the answer. ~~and `--reference=RFILE` cannot remove the MODE slot~~ `.absentWhen(reference)` removes it (§5.13a), so `chmod --reference=r notes.txt` binds the file rather than swallowing it into MODE, and `chmod --re 700 d` reports GNU's own ambiguity (§5.10). The four dash-led-mode lines are chmod's whole remaining divergence. |
| `rm` | flags + one variadic operand list | partly-blocked | improved | improved | Token machinery is exact. ~~`-r`/`-R` on one holder is still not expressible, and short-only `-i`/`-I` and `--interactive[=WHEN]` are still not expressible~~ `-i`, `-I` and `--interactive[=WHEN]` still cannot combine into one shared-state holder, since `-i` is a flag and `--interactive` an optional-value option; `--preserve-root=all` is the same missing combination, which is why one `rejects` line survives. `rm -f` with zero operands parses and exits 0 silently, and `.requiredUnless(force)` now *declares* that rule instead of the action re-implementing it (§5.13a). |
| `tar` | mode-flag exclusivity + bundled value-taking short + `[FILE...]` | partly-blocked | improved | unchanged | Bundling (`-cvf out.tar`) is perfect and ~~`[FILE...]` blocks `tar -tf a.tar` outright~~ is fixed: that invocation now parses and usage reads `[file...]`. ~~"Exactly one of `-c`/`-x`/`-t`" has had a construct since §5.9 closed, but **`example/tar` never adopted it**~~ is also fixed: the fixture now declares `requireExactlyOne(create, extract, listContents)` and `requireAtMostOne(gzip, bzip2)`, both hand-rolled `action { }` checks are deleted, and both conflicts are caught at parse time. Its one measured divergence is a consequence of §5.10 rather than a gap: `--excl` is unambiguous against the spellings this fixture declares and ambiguous against real tar's. |
| `cp` | variadic-then-scalar (`SOURCE... DEST`) | partly-blocked | **unchanged** | improved | Every flag and option was clean and fast. ~~The operand shape is still rejected at construction~~: §5.12 closed on 2026-08-02, so `SOURCE... DEST` is declarable, and ~~cp still cannot use it, because `-t DIR` and `-T` vary the operand SHAPE and a positional spec is fixed at build time (§5.13)~~ cp uses it now: `argument("source").multiple(min = 1)` plus `argument("dest").absentWhen(targetDirectory)` covers the `-t DIR SOURCE...` layout, and `cp --help` reads `usage: cp [-i|-n] [-L|-H] <SOURCE>... [<DEST>] [options]`. Only `-T`'s exactly-two cap is left, and it is a *maximum*, which no cardinality carries (§5.13b). Three of five optional-value options use `.optionalValue()`; `--preserve` and `--context` stayed value-required on purpose. |
| `curl` | wide flat option surface, per-transfer semantics | partly-blocked | improved | unchanged | Choice/repeatable/negatable/range all land, and `curl --url URL` with no bare operand now parses (§7.2 is retired). Digit shorts closed with §5.11a. curl's own `--json` and value-taking `--help`, per-URL `-o` pairing (§5.4b, now a non-goal), `--next` sectioning and `-V` still do not. It is also the one tool in the corpus that does **not** want §5.10: real curl matches long options exactly, so `curl --loc` is `acceptsLoosely` here. |
| `ssh` | options then `HOST` then an opaque verbatim tail | partly-blocked | improved | **improved most** | `-o KEY=VALUE`, counting `-v` and the required host are clean, and `DESTINATION [COMMAND...]` is two honest slots. ~~The defining feature, an *opaque* pass-through tail, is still unreachable: option parsing never stops at a positional (§5.14)~~ `optionsEndAtFirstOperand = true` is the whole feature: `ssh web1 ls -la` passes `ls -la` through, `ssh web1 tar -C /src` no longer has `-C` stolen, and `ssh web1 grep -x pat` no longer errors. Every structural blocker this study named for ssh is gone. What remains is klap's own surface: `-V` reaches no built-in, the invented long spellings the fixture keeps as its pre-fix record, and a post-destination `--` that real ssh consumes and klap forwards. |
| `git` | multi-level dispatcher with position-dependent globals | partly-blocked | improved | unchanged | Two-level routing, aliases, hybrid parents and globals-at-any-depth are exactly klap's shape, and `commit`'s `[<pathspec>...]`, `add`'s zero-operand form and `log`'s trailing `<path>...` are now faithful. Git's option *arity* vocabulary is still what fails, and it is the tool that pays most for a tree-wide name space: `-p` cannot mean paginate at the root and patch below it, `-v` cannot reach `--version`, and an option cannot sit between a parent and its subcommand. §5.8 gave it the real `--paginate`/`--no-pager` pair, which is the one thing that moved. |
| `dd` | 14 named, order-independent `key=value` operands, no flags at all | partly-blocked | improved | improved | Bare `dd` (copy stdin to stdout) parses and usage reads `[operand...]`. The tokens still parse only as positionals: klap has no named-operand input kind, so all 14 collapse into one anonymous slot plus ~80 lines of hand-written grammar, **and that is now a declared non-goal rather than a pending gap** (§5.17). Its one cheap sub-gap did close: `CompletionScope.completeFiles(nonPathPrefix)` makes `dd if=/dev/ze<TAB>` complete, which `.file()` could never express. |
| `find` | ordered boolean expression over single-dash multi-char predicates | **not-expressible** | improved (verdict unchanged) | improved (verdict unchanged) | Bare `find` and every predicate-only line parse. The literal target line still dies at `unknown option '-n'`: `-name` is decomposed into the cluster `-n -a -m -e`, a permanent non-goal (§5.16). Nothing about find's *grammar* is in klap's model, which is what fixes the verdict at `not-expressible`. §5.14 did give find the seam this document always said was the right answer for it: `optionsEndAtFirstOperand` hands the expression over intact from the starting point, with no `--`, which is what supersedes §5.18. The fixture deliberately keeps its transliterated shape, because switching would model a different tool than the one measured. |

### 3.1 What `db15fd4` bought, measured

Every line in this subsection was **executed**, not reasoned about: the ten `…Cli()` factories were driven
through `Cli.run(argv, recordingTerminal)` from a throwaway probe built against `:example:linkDebugExecutableLinuxX64`,
and the exit code plus rendered output were captured. The probe was deleted afterwards (see §2.4).

**Invocations that parse now and did not before.** Each was `error: missing required argument …`, exit 2,
against `46de18f`.

| Invocation | Now | Real tool's behaviour |
|---|---|---|
| `tar -tf a.tar` | exit 0, `would -t a.tar (… operands=0)` | lists the whole archive |
| `tar -xf a.tar` | exit 0, operands=0 | extracts everything |
| `tar -tzf backup.tar.gz` | exit 0, compression=gzip | same |
| `tar -xf a.tar --exclude '*.log' --exclude '*.tmp'` | exit 0, excludes=2, operands=0 | same |
| `rm -f` | exit 0, **no output** | GNU `rm -f` exits 0 silently |
| `rm` | exit 1, `error: missing operand` | `rm: missing operand`, exit 1 |
| `dd` | exit 0, "would copy … stdin to stdout" | copies stdin to stdout |
| `find` | exit 0, walks `.` | findutils treats it as `find .` |
| `find --type f` / `find --maxdepth 3 --print` / `find --` | exit 0 | predicate-only lines need no starting point |
| `ssh web1` | exit 0, destination=`web1`, command=`[]` | opens a login shell |
| `ssh` (no argv) | exit 2, `missing required argument <destination> for 'ssh'` | names the right slot; was `operands` |
| `git commit -m x a.txt b.txt` | exit 0, both paths bind | was `TooManyArguments` under the stub's `.optional()` workaround |
| `git commit -- a.txt b.txt` | exit 0, both paths bind | workaround could hold at most one |
| `git add` | exit 0, `Nothing specified, nothing added.` | git 2.55.0's real exit-0 message |
| `git log main..HEAD -- src/` | exit 0, rev=`main..HEAD`, path=`[src/]` | was `TooManyArguments` |
| `git log main..HEAD src/ doc/` | exit 0, rev + two paths | was `TooManyArguments` |
| `curl --url https://example.com` | exit 0, one URL, no bare operand | curl treats `--url` and operands as one list |
| `curl --url https://a --url https://b -sL -o page.html` | exit 0 | same |

**Usage lines that stopped advertising an unparseable shape** (`argSummary`, `Help.kt:56`):

```
tar --help          usage: tar [file...] [options]                     (was  tar <file>... [options])
rm --help           usage: rm [file...] [options]                      (was  rm <file>... [options])
dd --help           usage: dd [operand...]                             (was  dd <operand>...)
ssh --help          usage: ssh <destination> [command...] [options]    (was  ssh <operands>... [options])
curl --help         usage: curl [url...] [options]                     (was  curl <url>... [options])
git commit --help   usage: git commit [pathspec...] [options]
git log --help      usage: git log [revision-range] [path...] [options]
```

**Declarations that became honest.** The delta is not only "an invocation that failed now works" — several
stubs were carrying a *lossy workaround* whose cost was help text, error text and per-slot validation:

- `ssh` collapsed `DESTINATION` and `COMMAND...` into one `argument("operands").multiple(min = 1)` and split
  it with `.first()`/`.drop(1)` in the action. It is now two slots, so a missing host names
  `<destination>`, and the host can carry its own `.validate` without that check also running on every
  remote-command token.
- `git commit` declared `pathspec` as a single `.optional()` scalar, so `git commit -m x` did parse — but
  `git commit -m x a.txt b.txt` did not. It is now `.file().multiple()`.
- `git log` could declare only one of its two operand groups; it now declares an optional scalar followed by
  a trailing variadic, a shape §5.1 previously claimed was impossible ("an `.optional()` scalar declared in
  front does not help"). That claim was an artefact of the bug and is corrected in §7.5.
- `curl` declared `.multiple(min = 1)`, making the operand mandatory even though `--url` can supply the URL.
  It is now `.multiple()` plus an `Err(CliError.MissingArgument("curl", "url"))` in the action, which renders
  byte-identically (`Runner.kt:31` and `:75` both reach `ErrorRendering.kt:80`): exit 2,
  `error: missing required argument <url> for 'curl'`.

**Three tools got nothing, and it is not an accident.**

- **`cp` — the important one.** cp is *not* an §5.1 tool and never was. Its block is §5.12: `SOURCE... DEST`
  is a variadic followed by a **required** positional, which is a different problem from an empty variadic.
  `db15fd4` changed how *many* operands a trailing variadic may bind; cp needs a variadic that is **not
  trailing** — one that stops short and leaves a token for a later slot. No `min` value expresses that, and
  `validatePositionals` still refuses the shape at construction. Executed against the current tree:
  `argument("source").multiple(min = 1)` followed by `argument("dest")` still throws
  `command 'cptest': a variadic (multiple) argument must be the last positional`
  (`internal/builder/BuilderValidation.kt:32-34`), `cp --help` still prints `usage: cp <operands>... [options]`,
  and bare `cp` still reports `missing required argument <operands>`. A reader who assumes the fix helped cp
  is wrong. Separately, `min = 0` would never have been truthful for cp anyway: GNU coreutils 9.11 answers
  `cp: missing file operand` for both bare `cp` and `cp -t /tmp`, so cp's real minimum is 1 in every form.
- **`chmod`.** Declares `.multiple(min = 1)` for `FILE...`, and the `min >= 1` path is byte-identical before
  and after. Verified at every arity: `chmod 755` still exits 2 with `missing required argument <file>`,
  `chmod 755 a b` still binds, and usage still reads `<mode> <file>...` because the bracket branch only fires
  at `min == 0`. That is correct: GNU chmod 9.11 rejects `chmod 755` with `chmod: missing operand after '755'`.
- **`mkdir`.** Same story — `DIR...` is genuinely mandatory in real mkdir, so `min = 1` was and remains the
  truthful declaration. `mkdir` with no argv still exits 2; usage still reads `<directory>...`.

**Residue the fix leaves behind.** Small, cosmetic, and new — recorded so it is not rediscovered as a finding:

- The usage line and the Arguments block now disagree. `argSummary` renders `[file...]`, but the per-row
  renderer hard-codes `<${it.name}>` (`Help.kt:156`), so `tar --help` prints `usage: tar [file...] [options]`
  above a row that reads `<file>   file or archive member … (repeatable)`. Filed in §5.19.
- The per-row hint cannot distinguish an optional variadic from a mandatory one either: `metaHint`
  (`Help.kt:107`) emits `(repeatable)` vs `(repeatable, min N)`, so only the usage line carries the
  distinction the fix added.
- Bracketing is keyed on `min` alone, so a `min = 0` variadic *always* renders `[name...]`. Real `git add -h`
  prints `<pathspec>...` while accepting zero operands, and real dd writes `dd [OPERAND]...` (ellipsis
  outside the bracket, uppercase metavar) where klap writes `[operand...]`. Same meaning, different
  typography; not worth a construct.
- `Arg<T>.multiple(min: Int = 0)` (`Converters.kt:209`) **still carries no KDoc**, which is one of the four
  documentation sources §5.1 cited. The behaviour now matches the README and the `Opt.multiple` KDoc; the
  missing `Arg` KDoc was not added.

### 3.2 Fixture divergences, recounted 2026-08-03

Rebuilt from what the fourteen parity suites actually say, not from any plan's projection. The vocabulary
is `ParitySuite`'s own (`example/parity/.../ParitySuite.kt`), and only three of its five calls can
carry a divergence:

- **`acceptsLoosely`**: klap binds a line the real tool rejects. Always a divergence, by definition of the
  call. **47.**
- **`rejects`**: klap rejects. Only a divergence when the `because` names a klap limit or a fixture
  decision rather than the real tool's own answer; the other 117 pin *agreement*, which is the bulk of the
  `rejects` corpus and the reason a raw grep of `rejects(` is not a divergence count. **24** (18 naming a
  klap limit, 6 naming a fixture's own narrowing).
- **`shortCircuits`**: a built-in swallows the line. A divergence except where the real tool has the same
  built-in (`find --help`, `find --version`, `git -h` twice, `pacman -h`, `pacman --version`,
  `pacman -Qq --version`). **37 of 44.**

`accepts` and `showsHelp` pin agreement, with one class of exception noted under the table.

**Total: 108 divergent lines.** The command the plan gave for this step,
`grep -rn "acceptsLoosely\|rejects(" example/*/src/test/kotlin/ | wc -l`, returns **188**; 71 of those 188
are divergent (47 + 24) and the remaining 37 divergences are `shortCircuits` lines that grep does not see.

| Tool | `acceptsLoosely` | `rejects` naming a klap limit | `rejects` naming a fixture choice | divergent `shortCircuits` | total |
|---|---|---|---|---|---|
| `chmod` | 0 | 4 | 0 | 0 | **4** |
| `cp` | 5 | 0 | 2 | 5 | **12** |
| `curl` | 5 | 1 | 0 | 5 | **11** |
| `dd` | 2 | 0 | 0 | 5 | **7** |
| `find` | 19 | 5 | 1 | 5 | **30** |
| `git` | 4 | 5 | 1 | 4 | **14** |
| `head` | 1 | 0 | 0 | 2 | **3** |
| `ls` | 1 | 0 | 0 | 2 | **3** |
| `mkdir` | 0 | 0 | 2 | 0 | **2** |
| `mv` | 1 | 0 | 0 | 2 | **3** |
| `pacman` | 2 | 1 | 0 | 1 | **4** |
| `rm` | 1 | 1 | 0 | 0 | **2** |
| `ssh` | 5 | 1 | 0 | 6 | **12** |
| `tar` | 1 | 0 | 0 | 0 | **1** |
| **total** | **47** | **18** | **6** | **37** | **108** |

**No fixture reached zero, and the plan never claimed one would.** The three that came closest, with the
line that survived and why:

- **`tar`, 1.** `acceptsLoosely("--excl", "*.log", "-cf", "a.tar")`. §5.10 closed, and this is what closing
  it costs: klap judges ambiguity against the spellings a tree *declares*, and this fixture declares
  `--exclude` without real tar's `--exclude-from`, so the same prefix reaches one option here and several
  there. The rule agrees with GNU; the surface it runs over does not.
- **`mkdir`, 2.** Both `rejects` on a dash-led symbolic mode (`--mode -w d`, `-m -w d`). §5.2's option-value
  half closed, so the value now *reaches* `mode`; the line is turned down one layer later by this fixture's
  own octal-only validator, whose scope the study brief fixed. A fixture narrowing, not a klap limit.
- **`rm`, 2.** One invented long (`--interactive-once`, which real rm has no spelling for at all), and one
  `rejects` on `--preserve-root=all`: `.negatable()` and `.optionalValue()` are declared on different
  handle types, so a negatable optional-value option is a call that cannot be made. That is the smaller gap
  §5.6's resolution deliberately left open, not §5.6 itself.

`find`'s 30 is not a regression and is the honest number for the tool: 19 of them are the transliterated
`--name`/`--type` predicates the fixture invented so that *something* could be measured, and 5 are the
permanent §5.16 non-goal. Its verdict stays `not-expressible`.

**One class this table cannot count.** Several lines sit inside a `knownDivergence*` block as ordinary
`accepts` because both tools accept them and only the *binding* differs: `curl`'s per-URL `-o` pairing and
`--next` sectioning (4 lines), `git`'s `--exec-path` print-and-exit, `git log -- src/` and
`git log main dev` (3), `pacman -Rns` and `-Qc` binding the wrong letter (2), and `ssh`'s post-destination
`--` plus its `--json` strip (3). Twelve lines, invisible to any accept-or-reject comparison, which is
exactly why they carry a comment naming what each one binds. `rm`'s exit-code divergence (real rm exits 1
on a missing operand, klap 2) is a thirteenth of the same kind: both reject, and `ParitySuite` deliberately
never compares exit codes.

## 4. What klap does well

Grouped by capability, and listed only where **more than one tool independently confirmed it**.

### 4.1 GNU/POSIX token mechanics, for free and byte-exact

Confirmed by `mkdir`, `cp`, `rm`, `tar`, `curl`, `ssh`, `chmod`, `find`. Nothing has to be declared for any
of it:

- All four value spellings of a value-taking option (`-m 755`, `-m755`, `--mode 755`, `--mode=755`).
  `Parser.kt:457-461` splits a long token at its **first** `=` only, so `--option=Key=Value` keeps
  `Key=Value` as the value (`ssh`); `Parser.kt:546` gives a short option the rest of its cluster token.
- Short clustering in any order, including a cluster ending in a value-taking short: `-pv`, `-rf`, `-cvf out.tar`,
  `-sLo page.html`, `-qvp 2222`, `-vvp2222` (`Parser.kt:518-563`).
- `--` end-of-options exactly as POSIX specifies (`Parser.kt:447-450`), and a bare `-` stays an operand
  (`Parser.kt:36`), which is what makes `tar -cf - src` work.
- Options accepted **after** operands, matching GNU getopt's permutation, because `sift` walks the whole
  segment instead of stopping at the first positional.
- Cluster errors at the right granularity: `rm -foo` reports `unknown option '-o'` after consuming `-f`
  (`Parser.kt:600-619`), which is exactly rm's own `invalid option -- 'o'`.
- Last-occurrence-wins for a repeated option (`Parser.kt:186`, `raws.lastOrNull()`).

### 4.2 The converter chain as a declaration-site grammar

Confirmed by `chmod`, `dd`, `find`, `curl`, `ssh`, `git`, `cp`.

- `.convert { }` / `.map { }` (`Converters.kt:156-159`, `:166-169`) let one operand carry a whole sub-grammar
  with an author-controlled message: chmod's octal/symbolic/comma-split MODE, dd's `key=value` token,
  find's `+7` / `-100c` / `[-/]MODE` comparison forms. A thrown exception becomes a clean `BadValue`
  (`Parser.kt:326-334`), so the never-throw contract holds even for a misused chain.
- `.choice()` gives validation, an `InvalidChoice` listing the set, a did-you-mean suggestion **and** shell
  completion from one call (`curl -X`, `cp --sparse`, `git --cleanup`, `find -regextype`).
- `.int()/.double().range()` echoes the bound into the help row (`Converters.kt:232-236`, `:341-345`).
- `.file()` (`Converters.kt:218-221`, `:354-357`) buys shell path completion at zero cost, which the real
  tools have to hand-write in their own completion scripts.
- `.multiple()` on an **option** genuinely means zero-or-more (`Parser.kt:142-153`) and preserves occurrence
  order, which is exactly right for `-H`, `-o`, `--exclude`, `-m`, `-i`.

### 4.3 Loud, correct construction-time validation

Confirmed by `ssh`, `chmod`, `curl`, `git`, and the gate's counterfactual probes. Duplicate shorts, digit
shorts, multi-char shorts, reserved names, case-colliding choice sets and illegal cardinality combinations
all throw at `cli { }` with a message naming the offender, rather than misbehaving at parse time. Two agents
called this out unprompted as the reason their reproduction was trustworthy at all.

### 4.4 Negatable flags where the tool's convention already matches

Confirmed by `rm`, `chmod`, `curl`, `git`. `--preserve-root`/`--no-preserve-root`,
`--dereference`/`--no-dereference`, curl's `--no-<option>` house style, and git's `--pager`/`--no-pager` are
one-to-one hits for `.negatable(default = …)`, including the differing defaults and last-occurrence-wins
between the two spellings, rendered as `--[no-]name (default: on)`.

### 4.5 Subcommand trees

Confirmed by `git` (the only tool in the sample with one, but confirmed against several real shapes within
it). Two-level routing, `aliases` on a command, a hybrid parent with both its own action and children, and
`globalOption`/`globalFlag` read from any depth all worked with no contortion. `git -C /tmp status`,
`git status -C /tmp` and `git remote add -C /tmp o U` all bind the same holder.

### 4.6 Help scaffolding that tracks the parser

Confirmed by `cp`, `curl`, `git`, `chmod`, `find`, `ssh`. `group(...)`, `example(...)`, `epilogue`, `author`,
`hidden`, and the automatic `(required)` / `(repeatable, min N)` / `(default: …)` / `(one of: …)` hints
reproduced the real tools' documentation structure without a second source of truth. The one place this
breaks down (dd's operand table, retyped into `epilogue` and therefore unvalidated) is called out in §5.

## 5. Confirmed limits

One subsection per **distinct limit**. Subsection **numbers are stable** (they are cross-referenced from §3.1,
§7 and §8, and from the `KLAP-GAP` markers in the stubs), so §5.1 keeps its slot even though it is now
resolved. The **ranking** is the list below, not the numbering.

**Ranking after `db15fd4`**, by how many of the ten tools each open limit blocks:

| Rank | Limit | Tools | Was |
|---|---|---|---|
| — | §5.1 a variadic positional can never be empty | ~~7~~ **0** | **RESOLVED in `db15fd4`** (was rank 1) |
| — | ~~§5.2 a dash-led token is never a positional OPERAND~~ | 1 | option-value half **RESOLVED in `908300e`**; operand half **OUT OF SCOPE** (was rank 1) |
| — | ~~§5.7 built-in names seized tree-wide, no opt-out~~ | ~~8 touched / 4 blocked~~ **0** | **RESOLVED in `b0d437a`** (was rank 2) |
| — | ~~§5.3 every option and flag must have a long name~~ | ~~6~~ **0** | **RESOLVED in `2119bed`** (was rank 3) |
| — | ~~§5.4a order between two OPTIONS is discarded~~ | ~~3~~ **0** | flag half **RESOLVED in `44bdb48`**, option half **RESOLVED in `bb34c38`, `a4ca460`** (was rank 4) |
| — | ~~§5.4b order between an option and a POSITIONAL~~ | 2 | **OUT OF SCOPE** (was part of rank 4) |
| — | ~~§5.5 one holder cannot carry a second spelling~~ | ~~5~~ **0** | **RESOLVED in `2119bed`** (was rank 5) |
| — | ~~§5.6 no optional-value options (`--opt[=VALUE]`)~~ | ~~4~~ **0** | **RESOLVED in `ad54f26`, `db4a315`, `18c1d12`, `39e6d67`** (was rank 6) |
| — | ~~§5.8 negation is mechanical, long-form-only, symmetric~~ | ~~4~~ **0** | **RESOLVED in `e9332a0`, `92b633d`, `556ab0d`** (was rank 7) |
| — | ~~§5.9 no mutual-exclusion or required-one-of construct~~ | ~~4~~ **0** | **RESOLVED in `faf5b91`, `65b34d8`** (was rank 8) |
| — | ~~§5.10 no GNU prefix abbreviation~~ | ~~3~~ **0** | **RESOLVED in `c794fad`, `1db3089`, `d2e41ad`, `0ea2cc9`, `ddf8b00`** (was rank 9) |
| — | ~~§5.11 digit shorts impossible in both directions~~ | ~~3~~ **0** | **RESOLVED in `b89eeec`, `6ed0a08`** (was rank 10) |
| — | ~~§5.12 a variadic must be last, and it takes everything~~ | ~~3~~ **0** | **RESOLVED in `a63c705`** (was rank 11); `cp`'s blocker was then §5.13 |
| — | ~~§5.13a an operand's PRESENCE or MINIMUM cannot depend on an option~~ | ~~3~~ **0** | **RESOLVED in `9feefcd`, `c6a7ca4`** (was rank 12) |
| **13a** | §5.13b an operand's MAXIMUM cannot depend on an option (`cp -T`) | 2 | **new**, split out of rank 12; `cp` and `mv` only |
| — | ~~§5.14 option parsing never stops at a positional~~ | ~~2~~ **0** | **RESOLVED in `bc4269c`, `31a3406`** (was rank 13) |
| — | ~~§5.15 no custom value placeholder (metavar)~~ | ~~2~~ **0** | **RESOLVED in `60ab662`** (was rank 14) |
| — | ~~§5.16 single-dash multi-character options~~ | 1 | **OUT OF SCOPE** (was rank 15) |
| — | ~~§5.17 no named `key=value` operands~~ | 1 | completion sub-gap **RESOLVED in `922271e`, `4e0aaf5`**; the SHAPE is **OUT OF SCOPE** (was rank 16) |
| — | ~~§5.18 no terminator-delimited capture~~ | 1 | **SUPERSEDED by §5.14**, `bc4269c` (was rank 17) |

Two things the fix did *not* redistribute are worth stating, because they are easy to misread. The count for
§5.12 did not drop even though `git`'s `<revision-range> [--] <path>...` **arities** are now expressible: the
group *separator* is still inexpressible (§5.19) and `git log main dev` still needs a non-last variadic. And
§5.13's count did not drop even though `rm`'s rule is now enforceable in the action: the rule still cannot be
**declared**, so `--help` and completion still present the operand list as unconditionally optional and the
diagnostic is klap's `error: missing operand` rather than rm's `rm: missing operand` plus its `Try …` hint.
*(The second of those is now itself out of date: §5.13a closed on 2026-08-03 and `rm` declares the rule.)*

**Updated 2026-08-03.** The table above is the state after the gap-closure branch. **One row is open**, and
it is a new one: §5.13b, split out of §5.13 because the two halves turned out to need different things. The
walkthrough that planned this branch closed all of §5.13's `cp` case as a non-goal on the theory that three
alternative operand layouts need alternative signatures, and **that was too broad**: the `-t DIR SOURCE...`
half, shape-dependent minimum included, is expressible with `.absentWhen()` and both `cp` and `mv` now use
it. What is left is `-T`'s exactly-two cap, which is a *maximum*, and no cardinality carries one. Two tools.

Every mechanism below was re-read in the source during this pass; line references were corrected where the
original findings were stale, and again after `db15fd4` shifted `Parser.kt` by +4 lines below `bindPositionals`
and `Help.kt` by +2 below `argSummary`. **The `klap/` line references below are stale again after
2026-08-03**: `internal/parse/Parser.kt` gained the abbreviation resolution and `LongMatch.kt` is a new file
beside it. They are left as they are, for the same reason the rest of this document's citations are.

### 5.1 A variadic positional can never be empty (`argument(...).multiple(min = 0)` is a lie) — **RESOLVED**

> **RESOLVED in `db15fd4`, 2026-08-02.** `internal/parse/Parser.kt:265` now reads `if (slice.size < c.min)`,
> and `internal/render/Help.kt:56` renders `[name...]` for `Multiple(0)` / `<name>...` for `Multiple(min >= 1)`.
> Regression tests: `ParsePositionalsTest.kt:187-217`, class `VariadicPositionalArityTest` — four cases
> (`multipleWithMinZeroAcceptsZeroOperands`, `multipleWithMinZeroBindsAnEmptyList`,
> `multipleWithMinOneStillRejectsZeroOperands`, `helpDistinguishesAnOptionalVariadicFromAMandatoryOne`).
> `:klap:allTests` is green at 1072 tests, 0 failures. Measured effect on the ten tools: §3.1.
>
> **The analysis below is kept in full and unedited except where marked**, because it is the most instructive
> part of the study: it is the worked example of a limit that looked like a design decision, was diagnosed as
> a bug from the disagreement between the code and four independent documentation sources, and cost one line
> to fix. The blocked/works claims in it are now **historical** — they describe `46de18f`, not the current
> tree.

**Hit by 7 of 10 (historical):** `tar`, `rm`, `dd`, `find`, `ssh`, `git` reported it as blocked; `curl`
proposed it as a workaround without realising it does not work (see §7.2, itself now retired). This was the
most-converged finding in the study, and the six blocking reports were written by agents that could not see
each other. **Today the count is 0 of 10.**

**Real-world syntax that cannot be expressed** — *every line below parses today; see §3.1 for the executed
evidence:*

```
tar -tf a.tar          # list everything: no FILE operand
tar -xf a.tar          # extract everything
rm -f                  # exits 0 silently with no operands
dd                     # copies stdin to stdout
find                   # findutils treats it as `find .`
ssh web1               # the single most common ssh invocation there is
git commit -m "x"      # [<pathspec>...] is normally absent
ls, cat, …             # the entire `[FILE...]` family
```

Two of those lines were imprecise as descriptions of the *stubs*, though correct about the natural
declaration; the correction is in §7.5. `ssh web1` and `git commit -m "x"` did in fact exit 0 at `46de18f`,
because both stubs had already paid a lossy workaround. What was unexpressible was the natural spelling.

**Mechanism** (the code as it stood at `46de18f`; the live guard is now `if (slice.size < c.min)` at
`Parser.kt:265`). `klap/src/commonMain/kotlin/com/fromwau/klap/internal/parse/Parser.kt:257-266`, in
`Command.bindPositionals`:

```kotlin
is Cardinality.Multiple -> {
    val slice = values.drop(i)
    // Zero given reads as a fully-absent mandatory argument; one or more but short of the
    // minimum gets the same count-aware error the analogous option's Multiple branch reports.
    if (slice.isEmpty() || slice.size < c.min) {
        val tooFew =
            if (slice.isEmpty()) CliError.MissingArgument(name, spec.name)
            else CliError.TooFewOccurrences(spec.name, c.min, slice.size)
        if (policy != BindPolicy.Lenient) return Result.Error(tooFew)
    }
```

The `slice.isEmpty()` disjunct at `Parser.kt:261` fires **regardless of `c.min`**, so `multiple(min = 0)` is
indistinguishable from `multiple(min = 1)` at parse time and the user sees
`missing required argument <file> for 'tar'` (`ErrorRendering.kt:37`), exit 2.

**This is a bug, not a design decision.** The asymmetry is documented in four independent places, all of
which say the opposite of what the code does:

| Source | What it says |
|---|---|
| `Converters.kt:321` | `Opt<T?>.multiple` KDoc: "min = 0 (default) stays zero-or-more; min >= 1 is enforced in bind as TooFewOccurrences." |
| `Converters.kt:209` | `Arg<T>.multiple(min: Int = 0)` carries **no KDoc at all**. |
| `Parser.kt:143` | The **option** branch tests only `if (raws.size < c.min)` and binds an empty list otherwise. It is 118 lines above the positional branch that contradicts it. |
| `BuilderValidation.kt:37-44` | The builder classifies `Multiple(min >= 1)` as mandatory and therefore `Multiple(0)` as **not** mandatory, and `commonTest/.../CommandBuilderTest.kt:259-265` (`hiddenOptionalVariadicPositionalIsAllowed`) asserts you may `.hidden()` a `multiple()` positional. That rule exists precisely so a mandatory slot is never invisible. |
| `README.md:254`, `README.md:312-315` | The cardinality table lists `.multiple(min = 0)` for **argument and option** with "`min` is enforced", and the prose says a `.multiple()` holder "defaults to an empty list on its own. Reach for `.multiple(min = 1)` (or any `min > 0`) instead if you want a required, non-empty list." |

Read together with the code comment at `Parser.kt:259-260` ("Zero given reads as a fully-absent **mandatory**
argument"), the diagnosis is precise: the disjunct was written to choose *which error* to report for a
mandatory variadic, and accidentally became *the trigger* for erroring at all.

**What a CLI author had to do then.** Nothing worked. `.optional()` and `.default()` are both rejected in
combination with `.multiple()` at build time (`Converters.kt:175`, `:185`, `:202`, `:211`); ~~an `.optional()`
scalar declared in front does not help, because when it binds `null` the cursor is not advanced
(`Parser.kt:290-298`) and the variadic's slice is still empty~~; and dropping the positional entirely turns
any operand into `TooManyArguments` (`Parser.kt:319-321`). ~~Help cannot even document the shape: `Help.kt:54`
renders a `Multiple` as `<name>...` unconditionally, never `[name]...`.~~ The only escape was to abandon
unbounded arity, which changes the tool.

> **Two struck claims, corrected.** The optional-scalar-then-variadic sentence was an artefact of the bug, not
> an independent limit: the slice was empty because the guard rejected *any* empty slice, not because the
> cursor failed to advance. Post-fix that shape is exactly what `git log` uses — `argument("revision-range").optional()`
> followed by `argument("path").file().multiple()` — it passes `validatePositionals`' ordering rule
> (`BuilderValidation.kt:45-53`) and binds correctly (§3.1). And the help claim is simply obsolete:
> `Help.kt:56` now renders `[name...]`. Both are restated in §7.5.

**Fix cost, as estimated.** One line, plus one help-render line, and it breaks **zero existing tests**
(verified). Changing `Parser.kt:261` to `if (slice.size < c.min)` while keeping `MissingArgument` for the
`slice.isEmpty() && c.min > 0` case preserves both tests that pin the current behaviour:
`ParsePositionalsTest.kt:52-56` (`variadicMinEnforced`, min = 1, zero values, expects `MissingArgument`) and
`ParseOptionsTest.kt:367-378` (`multiplePositionalMinAbsentIsStillMissingArgument`, min = 2, zero values,
same). No test in the suite exercises an `argument(...).multiple()` with `min = 0` and zero operands at all.

**Fix as landed (`db15fd4`).** The estimate held exactly. The diff is 10 lines in `Parser.kt` (one changed
condition, six replacing a two-line comment) and 6 in `Help.kt` (one changed expression, two comment lines,
one KDoc line), plus 46 lines of new test. Both pinned tests still pass unchanged —
`variadicMinEnforced` is still at `ParsePositionalsTest.kt:54` and
`multiplePositionalMinAbsentIsStillMissingArgument` at `ParseOptionsTest.kt:368` — because `MissingArgument`
is still what a `min >= 1` variadic reports when the slice is empty; only the *trigger* moved. The four new
cases in `VariadicPositionalArityTest` (`ParsePositionalsTest.kt:187-217`) cover the gap the study identified:
`min = 0` with zero operands now succeeds and binds `[]`, `min = 1` with zero operands still fails, and
`argSummary` is pinned to `[file...]` vs `<file>...`. Suite result: 1072 tests, 0 failures. The one thing
`db15fd4` did **not** do is add the missing `Arg<T>.multiple` KDoc (`Converters.kt:209`), so one of the four
documentation sources tabulated above is still silent — now merely incomplete rather than contradicted.

### 5.2 A dash-led, non-numeric token is never a value — **SPLIT: option values RESOLVED, operands OUT OF SCOPE**

> **The OPTION-VALUE half is RESOLVED in `908300e`, 2026-08-02.** Every value-consuming site takes the
> next token whatever it looks like, as GNU getopt and git's parse-options do; only a missing next token
> and the `--` marker are exempt. `mkdir --mode -w`, `cp -S -old`, `git commit -m -weird`,
> `git add --chmod -x`, `curl -d -foo=1`, `tar --exclude -weird` and `find --perm -u+w` all bind, and the
> `example/curl`, `example/tar`, `example/git` and `example/find` divergences flipped to binding
> assertions with it. The accepted cost is that `-m --verbsoe` binds the typo instead of reporting an
> unknown option.
>
> **The OPERAND half is declared out of scope on 2026-08-02.** Not "not yet"; klap will not do this.
> `chmod -w file` and `chmod -755 file` have no option in them — the dash-led token is a *positional*.
> Four reasons, in order of weight.
>
> **Every available fix costs more than the gap.** No general rule works: `chmod -w f` wants an operand
> exactly where `mkdir -w f` wants an error, which is the same finding that split §5.11, so it would have
> to be a tool-declared opt-in. Gate that opt-in on nothing and a mistyped `chmod --zzz f` silently binds
> as a mode — the exact silence `6ed0a08` spent a phase removing. Gate it on a declared pattern and the
> mode grammar lives in two places (the routing pattern and `parseChmodMode`) and can drift. Gate it on
> the converter, which is the only non-duplicating answer, and it cannot run: routing happens in `sift`,
> before binding, and completion is forbidden from running a consumer's converters on a Tab press
> (`internal/render/Completion.kt:89`). There is no cheap version of this.
>
> **A standard escape already exists, and it is POSIX's own answer to this ambiguity.** `chmod -- -w f`
> binds correctly today and `example/chmod` pins it. klap already took the identical decision one section
> over: `seq -5 5`'s negative *operand* is deliberately unsupported because `seq -- -5 5` expresses it.
>
> **One tool of ten, and none of the other nine.** Every other dash-led case in the corpus was an option
> VALUE, which the resolved half above now handles. `chmod` is alone.
>
> **The audience it would serve does not exist.** chmod's mode grammar is standard and documented —
> `[ugoa...][[-+=][perms...]...]`, with the `who` prefix optional, so `-w` is that grammar with the prefix
> omitted. It is not a quirk; it predates the "a token starting with `-` is an option" convention it
> collides with. Nobody designing a NEW CLI invents a dash-led operand, so the gap is invisible to the
> people klap is for and only reachable by someone reproducing a pre-getopt interface byte for byte.
>
> **Consequence, recorded so it is not re-opened:** `example/chmod`'s four dash-led-mode lines are a
> PERMANENT divergence, marked as such rather than as a pending gap. `-755` used to be the one that
> worked, via the digit exemption `6ed0a08` removed for making `ls -5` silent; chmod's dash-led grammar is
> now uniformly unavailable and loud instead of half-available and silent, which is the better of the two
> shapes it could have had.
> The analysis below is the pre-fix record and describes both halves together.

**Hit by 7 of 10:** `mkdir`, `cp`, `git` (blocked); `curl`, `tar`, `find` (awkward, with a workaround);
`chmod` hits the same classifier at a different site. With §5.1 resolved this is **rank 1**, and it is the
only remaining limit that seven of the ten tools hit. Untouched by `db15fd4`, which changed
`bindPositionals` and `argSummary` only: `isDashLedValue` (`Parser.kt:32`) is above the edit and is
byte-identical.

**Real-world syntax that cannot be expressed** (each verified against the real tool by the agent that
reported it):

```
mkdir -m -w symtest        # coreutils 9.11 creates symtest with mode r-xrwxrwx
cp -S -old a b             # -old is the backup suffix
git commit -m -weird       # git 2.55.0 really does commit with subject "-weird"
git add --chmod -x file
curl -d -foo=1 URL
tar -xf a.tar --exclude -weird
find . -perm -u+w ; find . -name -foo
chmod -w file              # the operand variant: a leading-dash SYMBOLIC mode
```

**Mechanism.** `Parser.kt:32` classifies a dash-led token as a value only when the second character is a
digit or `.`:

```kotlin
internal fun String.isDashLedValue(): Boolean = length > 1 && (this[1].isDigit() || this[1] == '.')
internal fun String.isFlagLike(): Boolean =
    startsWith("-") && !isDashLedValue() && this != END_OF_OPTIONS && this != "-"
```

Both value-consuming sites then refuse a flag-like next token: `Parser.kt:489-490` (long form) and
`Parser.kt:547-549` (short cluster), each `?.takeUnless { it.isFlagLike() || it == END_OF_OPTIONS }`, leaving
`value == null` and reporting `MissingOptionValue` at `Parser.kt:504-506` / `:550-552`. GNU getopt and git's
parse-options both hand a required-argument option the next argv element unconditionally.

The `chmod` variant is the same classifier at the positional site: `-w` is flag-like, so it is routed into
the short-cluster walk (`Parser.kt:518-563`), matches nothing, and is recorded as `UnknownOption`
(`Parser.kt:542-544`). It never reaches `Parser.kt:452-455`. Note the resulting split, which no user will
predict: `chmod -755 f` parses (digit exemption) and `chmod -w f` does not, so half of chmod's documented
`[-+=]` mode grammar is reachable and half is not. `ArgumentSpec`/`ValueSpec` carry no opt-out
(`HolderSpec.kt:25-46`).

**What a CLI author must do today.** Tell users to type the attached form (`--mode=-w`, `--suffix=-old`,
`--exclude=-weird`, `-d-foo=1`) or a leading `--`. Both are different command lines from the ones users and
scripts actually type, and the error message points at the option without explaining the escape.

### 5.3 Every option and flag must have a long name — **RESOLVED**

> **RESOLVED in `2119bed`, respelled in `3a1927e`, 2026-08-02.** A named input declares a list of
> spellings, so `option("-Z")` is a short-only `-Z` with no long form. Pinned live by `example/rm`,
> `example/chmod` and `example/cp`.
> The analysis below is the pre-fix record.
>
> **The spelling model changed once more after `2119bed`.** That commit inferred long-versus-short from a
> name's LENGTH; `3a1927e` replaced the inference with explicit tokens (`flag("--recursive", "-r", "-R")`),
> which keeps this gap closed while also making `--h` and one-character longs expressible and turning a
> positionally-passed `help` string into a construction error.


**Hit by 6 of 10:** `mkdir` (`-Z`), `cp` (`-b -d -H -p -u -Z`), `rm` (`-i -I`), `chmod` (`-H -L -P`),
`ssh` (every single one; ssh has no long options at all), `git` (`-C`, `-c`, `log -L`).

**Mechanism.** `CommandBuilder.kt:32-33` and `CliBuilder.kt:19,22` all take `long: String` as a mandatory
non-null first parameter with `short: String? = null` as the optional one, which is the exact inverse of what
these tools need. `HolderSpec.kt:124-126` (`requireValidName`) rejects a blank name, so `flag("", "Z")` throws
at construction; the gate confirmed the message is `invalid flag name '': must not be blank`.

**What a CLI author must do today.** Invent a long spelling the real tool rejects, making the reproduction a
strict **superset** of the original's accepted tokens: `--Z`, `--interactive-once`, `--traverse-arg-links`,
`--directory`, `--recursive-r`. `--help` and the generated docs then advertise a command line that does not
exist. `.hidden()` is not an escape, because it is per-spec: hiding the bogus `--Z` also hides `-Z`.

Note the mirror case already works: `Help.kt:76` renders `    --long` with a blank short column when `short`
is null, so the rendering half of short-only support is already there.

### 5.4 Order between *different* holders is discarded before anything binds — **SPLIT: §5.4a RESOLVED, §5.4b OUT OF SCOPE**

> **The FLAG half is RESOLVED in `44bdb48`, 2026-08-02.** `lastWins(a, b, ...)` declares that a set of
> flags override each other and the one written last holds — the override rule the corpus kept reaching
> for `requireAtMostOne` to stand in for, which would have rejected lines every one of those tools
> accepts. Order comes from the line and reads inside a cluster (`-if` differs from `-fi`), because a flag
> occurrence records `tokenIndex * 1000 + charIndex`. `example/find` (`-P`/`-L`/`-H`), `example/ls` (the
> sort shorts), `example/cp` and `example/mv` (`-f`/`-i`/`-n`) all adopted it and deleted their
> hand-written precedence.
>
> **The OPTION half (§5.4a) is RESOLVED in `bb34c38` and `a4ca460`, 2026-08-03.** `lastWins` takes
> `vararg inputs: Input`, so a set may hold options and may mix them with flags. `sift` now records an
> `optionPositions` map beside `flagPositions`, written at all three sites that append to `optionValues`
> and using the same `tokenIndex * 1000 + charIndex` encoding, so a flag and an option on one line compare
> correctly and an option inside a cluster (`-c5`) compares against a flag in the same cluster.
> `head -c 5 -n 3` counts lines and `head -n 3 -c 5` counts bytes; `ls -S --sort=time` sorts by time.
>
> The resolution rule that made it possible: a loser binds **what it would have bound had the user never
> written it**, per kind: `false` for a plain flag, `0` for a `.count()` flag, its declared default for a
> `.negatable()` flag, and its `.default()` or `null` for an option. That keeps a loser indistinguishable
> from an absent input, which is the whole point of the rule.
>
> Two members are rejected at construction rather than resolved, both because they have no such value: a
> positional (it binds by position, so there is no occurrence to order) and a `.required()` or
> `.multiple()` option (a loser would leave its accessor with nothing to return, which the first review
> found as a `NullPointerException` inside the consumer's own action). The check runs in
> `validateLastWinsMembers` at `build()` rather than inline, because `.required()` may legally be called
> *after* the `lastWins` line.
>
> **The OPTION-vs-POSITIONAL half (§5.4b) is declared out of scope on 2026-08-03.** `find`'s expression
> grammar (`-o`/`-a`/`,`) and `curl`'s per-URL `-o` pairing need order between an option and an OPERAND,
> which no set-shaped rule reaches. Closing it means giving `Sifted` one interleaved occurrence stream in
> place of the three spec-keyed containers at `Parser.kt:420-423`, which is a change to the parser's data
> model that every bind path reads, to serve two tools, and it does not even unblock the headline one,
> since `find` needs an expression parser and not an ordering (see §5.16 and §8's closing list).
> `example/curl` pins the cost as two `accepts` lines whose comment states that both bind identically and
> only the real tool tells them apart.
> The analysis below is the pre-fix record and describes both halves together.

**Hit by 6 of 10:** `cp` (`-f`/`-i`/`-n`, `-L`/`-P`/`-H`), `chmod` (`-H`/`-L`/`-P`), `rm` (`-f`/`-i`),
`ssh` (`-A`/`-a`), `find` (the `-o`/`-a`/`,` operators), `curl` (per-URL `-o` pairing).

**Real-world syntax that cannot be expressed:**

```
rm -i -f a       # removes without prompting
rm -f -i b       # prompts                      <- klap cannot distinguish these two
chmod -R -H -L d vs chmod -R -L -H d            # chmod --help: "only the final one takes effect"
curl -o a.html URL1 -o b.html URL2              # pairs each -o with the URL at the same ordinal
find . \( -mtime +7 -o -size +1M \)             # an operator between two neighbouring predicates
```

**Mechanism.** `sift` accumulates into three spec-keyed containers with no sequence between them
(`Parser.kt:420-423`): `flagCounts: MutableMap<FlagSpec, Int>`, `optionValues: MutableMap<OptionSpec, MutableList<String>>`,
`positionals: MutableList<String>`. `hit()` (`Parser.kt:432-435`) only increments a count. `Sifted`
(`Parser.kt:805-811`) exposes those three independently, with nothing tying them together, and
`bindFlagsAndOptions` collapses each flag in isolation at `Parser.kt:134` (`else -> sink[spec] = hits > 0`),
which for a plain flag also destroys the occurrence *count*.

The only ordering klap keeps is *within one holder*: `raws.lastOrNull()` at `Parser.kt:186` (last value wins
for a repeated option) and the negation polarity at `Parser.kt:434` (last wins between `--x` and `--no-x`
of the *same* spec).

**What a CLI author must do today.** Hard-code a precedence in `action { }`. That changes behaviour, not just
rendering: `rm -i -f a` and `rm -f -i b` become the same command, and `--help` cannot show that the flags are
related at all.

### 5.5 One holder cannot carry a second spelling — **RESOLVED**

> **RESOLVED in `2119bed`, respelled in `3a1927e`, 2026-08-02.** The same change: one input carries any
> number of spellings, so `flag("--recursive", "-r", "-R")` is a single declaration. Pinned live by
> `example/rm` and `example/cp`.
> The analysis below is the pre-fix record.
>
> **The spelling model changed once more after `2119bed`.** That commit inferred long-versus-short from a
> name's LENGTH; `3a1927e` replaced the inference with explicit tokens (`flag("--recursive", "-r", "-R")`),
> which keeps this gap closed while also making `--h` and one-character longs expressible and turning a
> positionally-passed `help` string into a construction error. See the
> gap-closure plan's Phase 1b.


**Hit by 5 of 10:** `cp` and `rm` (`-R, -r, --recursive`), `chmod` (`-f, --silent, --quiet`),
`find` (`-xdev`/`-mount`, `-o`/`-or`, `-a`/`-and`), `git` (`--since`/`--after`, `--until`/`--before`,
`--pretty`/`--format`, `--mailmap`/`--use-mailmap`).

**Mechanism.** `NamedSpec` (`HolderSpec.kt:49-54`) exposes exactly one `name: String` and one
`short: String?`; `FlagSpec` (`HolderSpec.kt:98-103`) likewise. Lookup is a single equality on each
(`Parser.kt:569-573` for flags, `:579-580` for options). `aliases` exists only on the *command* builder
(`CommandBuilder.kt:23`), never on a holder. Declaring a second holder that reuses the long name is rejected
by `validateDuplicateOptionFlagNames` (`BuilderValidation.kt:85-95`), and reusing the short is rejected the
same way; the gate confirmed `two options/flags share the short name '-r'`.

**What a CLI author must do today.** Declare a second, differently-named holder and OR it back together in
the action, which leaks an invented long spelling (`--R`, `--recursive-r`) into the accepted surface and
renders two help rows where the real tool renders one. Verified against the real tool: `rm --R c` answers
`rm: unrecognized option '--R'`.

### 5.6 No optional-value options (`--opt[=VALUE]`) — **RESOLVED**

> **RESOLVED in `ad54f26`, `db4a315`, `18c1d12`, `39e6d67` and `460ab22`, 2026-08-02**, carried through six
> fixtures by `b5b62b4` and `103f6a7`. `option(...).optionalValue(whenBare)` declares the value a bare
> occurrence binds: `--opt=given` binds `given`, a bare `--opt` binds `whenBare`, and **the space form never
> binds** — `--opt foo` binds `whenBare` and leaves `foo` an operand. That is the only unambiguous reading
> available, since an optional-value option cannot tell its own value from the next operand, and it is what
> GNU does. The help row renders `--opt[=<VALUE>]` with a trailing `bare: <value>` note, and completion
> treats the word after a bare occurrence as an operand, matching the parser.
>
> **It is an opt-in, not a change of default.** Guideline 7 says option-arguments should not be optional;
> `.optionalValue()` steps one option outside it, knowingly, and a tool that never calls it is bit-for-bit
> unaffected — `PosixConformanceTest` pins both halves as a paired test.
>
> **`rm --preserve-root` and `git log --decorate` did not flip.** `.optionalValue()` is declared on `Opt<T>`;
> `.negatable()` yields a `Flag`. There is no holder both calls apply to, so a negatable optional-value
> option (`--decorate[=short]` plus `--no-decorate`) is not this feature with one more call — it is a
> separate, smaller gap, left where §6.4 records it.
> The analysis below is the pre-fix record.

**Hit by 4 of 10:** `mkdir` (`--context[=CTX]`), `cp` (five: `--backup`, `--preserve`, `--reflink`,
`--update`, `--context`), `rm` (`--interactive[=WHEN]`, `--preserve-root[=all]`), `git` (`-S[<keyid>]`,
`--exec-path[=<path>]`, `--mirror[=…]`, `--decorate[=…]`, `-u[<mode>]`).

**Mechanism.** `Cardinality` (`Cardinality.kt:4-9`) is `Required | Optional | Default | Multiple`, and every
one of those describes how many **occurrences** a holder takes, never whether an occurrence carries a value.
klap's two shapes are all-or-nothing: a flag rejects any inline value (`Parser.kt:468-475`,
`FlagTakesNoValue`) and an option demands one (`Parser.kt:488-490` then `:504-506`). Declaring a flag and an
option with the same long name to cover both halves is rejected at construction
(`BuilderValidation.kt:85-95`).

**This one is worse than a missing feature, because the space form silently mis-parses.** With the option
declared as value-required:

```
cp --backup a b        # klap eats `a` as CONTROL, then reports a missing operand; GNU copies a to b
mkdir --context newdir # klap eats `newdir` as the context, then errors; GNU creates the directory
git --exec-path log    # klap eats `log` as the path, then reports an unknown subcommand
```

**What a CLI author must do today.** Pick a side and lose the other. `git commit -S` had to become a boolean
flag (losing `-S<keyid>`); `--exec-path` and `--mirror` had to stay value-required (losing the bare form).
`git --decorate` was the one clean escape, as `.negatable()` covers `--decorate`/`--no-decorate` exactly and
loses only the value form.

### 5.7 Built-in names are seized tree-wide, and the injected surface has no opt-out — **RESOLVED**

> **RESOLVED in `b0d437a`, 2026-08-02.** A root-only `builtins { }` block declines injected built-ins and frees their names. `example/ls` needs it twice over (real `ls -h` is `--human-readable`, real `ls --color` is its own option) and `example/pacman` needs it for `--color`.
> The analysis below is the pre-fix record.


**Touched by 8 of 10**, at blocked severity by 4 (`curl`, `git`, `ssh`, `chmod`) and at awkward severity by 4
(`mkdir`, `cp`, `rm`, `dd`).

**Real-world syntax that cannot be expressed:**

```
curl --json '{"a":1}' https://x   # curl 7.82+ posts a JSON body. klap's own --json steals it,
                                  # forces JSON output, and leaves {"a":1} to bind as a URL.
curl --help http                  # curl's --help takes a VALUE
curl -V / ssh -V / git -v         # the near-universal short for --version
chmod -h 600 link                 # the -h short of --no-dereference
git log --color[=<when>]          # `color` is reserved tree-wide
```

**Mechanism.** `BuilderValidation.kt:12` and `:15`:

```kotlin
private val RESERVED_LONG = setOf("help", "help-all", "version", "json", "completion", "docs", "color")
private val RESERVED_SHORT = setOf("h")
```

enforced with a hard `require` at `BuilderValidation.kt:291-297`. The built-ins themselves are recognized
position-independently in `klap/Parser.kt`: `--json` at `:71` and stripped at `:77`, `--color` validated and
stripped at `:86-99`, `--version` as the literal whole token at `:101` (no short, no way to add one),
`--completion`/`--docs` at `:110-132` gated on `Cli.kt:89` (`metaOptions = action != null`, i.e. every
single-command tool), `--help-all` at `:150-160` and `-h`/`--help` at `:161`. A hidden `__complete`
subcommand is injected unconditionally (`CliBuilder.kt:33-35`) and routed before positional binding
(`klap/Parser.kt:140-145` then `:172`). `CliBuilder` (`CliBuilder.kt:8-23`) exposes nothing to disable any of
it.

**What a CLI author must do today.** Accept that the reproduction is a strict superset of the real tool's
token set, and that an operand literally named `__complete`, `--json` or `--color` needs a `--` escape the
real tool does not require (documented at `README.md:370-381`). For `rm`, `cat`, `grep` and friends, whose
operands are arbitrary filenames, these names silently claim files. For `curl` the collision is
unrecoverable: `--json` is real curl surface and klap owns the token.

### 5.8 Negation is mechanical, long-form-only, and symmetric — **RESOLVED**

> **RESOLVED in `e9332a0`, `92b633d` and `556ab0d`, 2026-08-03.**
> `.negatable(vararg negativeSpellings: String, default: Boolean = true)` names the spellings the negative
> half answers to, each written as the token it is, so a **short** can turn a flag off and an asymmetric
> pair keeps both real names: `flag("--dereference", "-L").negatable("--no-dereference", "-P")`,
> `flag("--forward-agent", "-A").negatable("--no-forward-agent", "-a")`, and git's
> `flag("--paginate", "-p").negatable("--no-pager", "-P")`.
>
> **The explicit list REPLACES the generated form rather than adding to it**, which is the decision that
> makes the feature faithful rather than merely permissive: a tool that spells its negative half
> differently also *rejects* the generated one, and git 2.55.0 answers to neither `--pager` nor
> `--no-paginate`. Answering to both would have made klap looser than the tool it models. Write the
> generated spelling out when you want it kept, as the `cp` line does.
>
> A negative short clusters like any other (`-vP`), each spelling is validated exactly like a positive one
> and may not collide with a declared spelling or another flag's negation, and help lists every spelling
> the flag answers to, shorts first and positives before negatives: `-L, -P, --dereference,
> --no-dereference`. The `--[no-]x` rendering survives only for a generated negation, since an explicit
> negative half is not a prefix of the positive one and `--[no-]x` cannot state it.
>
> `example/cp` and `example/ssh` each collapsed two independent flags into one holder and deleted the
> marker that said the pair "loses the last-occurrence ordering"; `example/git` gained the real
> `--paginate`/`--no-pager` pair. `example/chmod` is the one adopter this could not serve: chmod spells its
> negative half `-h`, and `builtins.helpShort` claims that letter. Both halves of that block are gated on
> the same switch, so `builtins { helpShort = false }` would free it, which makes chmod's `-h` a fixture
> choice rather than a klap limit. Its `KLAP-GAP` marker was retired to ordinary prose on that reading.
>
> **A real pre-existing bug the brief's own test code exposed**, recorded because it was found rather than
> planned: `resolveLastWins` forced a winner to `true` unconditionally, which corrupted a negatable flag
> that won *via its negative spelling*. Nothing before this feature could produce that line, so nothing
> caught it. Fixed in the same task, and preserved through §5.4a's rewrite of the same function.
> The analysis below is the pre-fix record.

**Hit by 4 of 10:** `cp` (`-L`/`-P`), `ssh` (`-A`/`-a`), `chmod` (`-h` for `--no-dereference`),
`git` (`-p`/`--paginate` vs `-P`/`--no-pager`; `-n` for `--no-verify`).

**Mechanism.** `.negatable()` sets one boolean (`Converters.kt:379-384`) and its own KDoc states the limit at
`Converters.kt:376`: "(short negation is not supported)". `findNegatedFlag` (`Parser.kt:576-577`) matches
only the literal generated token `--no-<own name>`, and it is consulted only from the `token.startsWith("--")`
branch (call site `Parser.kt:463`); the short-cluster walk never reaches it.

Two things follow. A short cannot attach to the negative half, so `-P`, `-a`, `-n`, `-h` are unreachable
as negations. And the pair is always spelled symmetrically, so git's `--paginate`/`--no-pager` (git 2.55.0
rejects both `--pager` and `--no-paginate`) cannot both survive.

**What a CLI author must do today.** Declare two independent flags, which keeps both shorts but loses
last-occurrence-wins (§5.4), so `ssh -aA host` binds both to true where real ssh resolves to the later one.

### 5.9 No mutual-exclusion or required-one-of construct — **RESOLVED**

> **RESOLVED in `faf5b91`, `65b34d8`, 2026-08-02.** `requireExactlyOne` / `requireAtMostOne` exist and reach `--help`, the usage line and completion. `example/pacman` reproduces both of real pacman's own operation errors with no hand-rolled check. **Caveat found by the fixtures:** it is the wrong tool for a last-wins set — real `head -c 5 -n 3` and real `find -L -P` are legal, so a constraint there would make klap stricter than the tool. That needs §5.4.
> The analysis below is the pre-fix record.


**Hit by 4 of 10:** `tar` (exactly one of `-c`/`-x`/`-t`, and at most one of `-z`/`-j`), `find`
(`-P`/`-L`/`-H`), `chmod` and `cp` (their override groups, which also need §5.4).

**Mechanism.** A grep across `klap/src/commonMain` for `exclusive|mutually|conflicts|oneOf` finds exactly two
hits, both the `.count()`/`.negatable()` guard (`Converters.kt:370`, `:380`). `group(title) { }`
(`CommandBuilder.kt:46`, `BuilderImpl.kt:86-91`) is a help **heading** and imposes no arity.

**What a CLI author must do today.** Hand-write the rule at the top of `action { }`, which costs three
things. The check runs *after* parsing rather than as a usage error. `--help`, `--docs` and completion
advertise the flags as independent booleans, with no notation for `-c|-x|-t`. And the author must remember to
pass `USAGE_ERROR_EXIT` by hand, because `CliError.Failure` defaults to exit 1 (`CliError.kt:43`) while every
parse error is fixed at 2 (`CliError.kt:4-8`). This is the single most characteristic thing about tar's
surface, and klap has no vocabulary for it.

### 5.10 Long options match by exact equality: no GNU prefix abbreviation — **RESOLVED**

> **RESOLVED in `c794fad`, `1db3089`, `d2e41ad`, `0ea2cc9` and `ddf8b00`, 2026-08-03.** An unambiguous
> prefix resolves and an ambiguous one is a usage error naming every possibility, in GNU's own wording:
> `option '--re' is ambiguous; possibilities: '--recursive' '--reference'`.
>
> **This section's closing note asked for a policy statement instead**, and the answer went the other way:
> it is a capability, on by default, with no switch. The README now documents it beside the
> command-line-forms paragraph, which is what that note asked for either way.
>
> `internal/parse/Parser.kt`'s three independent exact lookups became one resolution through
> `internal/parse/LongMatch.kt`: exact wins outright (so a pool holding both `--sort` and `--sort-by`
> keeps the shorter reachable), a prefix reaching one candidate resolves, a prefix reaching several is
> `CliError.AmbiguousOption`, and duplicates collapse before the count because one spelling can reach the
> pool from two sources. Shorts never abbreviate (a one-dash token is a cluster) and neither do subcommand
> names. A negatable flag's negative half abbreviates too, which is why §5.8 had to land first.
>
> **Ambiguity is judged against ONE pool**, everything the token can reach at that point: the command's
> own inputs, hidden ones included, plus globals plus built-ins. Hidden inputs take part because hiding
> removes an input from help, not from the parser, and resolving past a hidden spelling would bind a
> different option than the same line binds on a tree where nothing is hidden.
>
> **Two review rounds, one class of defect from both sides.** Round 1 found the pre-strip pool omitted
> command-declared longs, so `head --ver f` printed the version where real head reports an ambiguity, and
> `--col never sub` silently ate an operand. The fix reopened the same class from the other side: a
> dispatcher declaring `--header` anywhere lost `--h` everywhere. Resolved by splitting the pool along
> where each built-in is actually resolved. `--help`/`--help-all` are matched *after* the subcommand walk
> and resolve against the reached command's pool; `--version`/`--json`/`--color`/`--completion`/`--docs`
> are matched before it and keep a conservative tree-wide decline, with the per-command pool a superset so
> a declined token never returns a false `UnknownOption`.
>
> **The one exception to that split, because it runs before the walk:** `hasHelpRequest` (`Parser.kt`)
> resolves `--help`/`--help-all` against the **tree-wide** pool, not the reached command's, because it
> gates the `--completion`/`--docs` short-circuit and those are themselves matched before the walk knows
> its command. It only decides *precedence* (a help request outranks a meta-option), never what binds, so
> the worst it can do is decline an abbreviation on a hybrid root given `--h` alongside
> `--completion <shell>` on one line. The `--help`/`--help-all` that actually *renders* still resolves
> against the reached command's pool, as the paragraph above says.
>
> **A decision the walkthrough did not anticipate, recorded because a reader will hit it:** `--help-all`
> takes part in **exact matching only**, never prefix resolution. klap injects it rather than the author
> declaring it, and letting it claim the space it shares with `--help` would cost every klap CLI its
> `--h`. This was deliberately **not** generalised into "a strict prefix owns the shared space", which
> would diverge from GNU, since real `ls --so` reports a genuine ambiguity between `--sort` and `--sort-by`.
>
> **And the carve-out is deliberately not extended to `--color`/`--completion`/`--docs`**, which sit in
> the abbreviation pool with no exemption at all: an app declaring `--config` finds `--co` ambiguous. The
> two cases differ in what the injected name *is*. `--help-all` is a strict extension of `--help`, so the
> only thing it can shadow is klap's own built-in, and an ambiguity there tells the user nothing. The
> other three are genuinely different options from the app's own `--config`, so an ambiguity is exactly
> the answer GNU gives, and silently preferring either spelling would bind an option the user did not
> name. An author who wants the letters back declines the built-in through `builtins { }` (§5.7), which
> frees the name outright; `example/ls` and `example/pacman` both do.
>
> **The stated price of the one-pool rule:** a long declared anywhere in the tree declines an abbreviation
> on behalf of its siblings. `app sub --ver` is an ambiguity even where `sub` declares no `--verbose`, and
> a sibling's `--sort-by` makes `sub1 --sor` ambiguous even where `sub1` alone is not. Both were probed
> and both are an error in place of a mis-binding, which is the trade.
>
> Eight fixtures pin it as ordinary behaviour (`chmod`, `mkdir`, `mv`, `pacman`, `ls`, `head`, `cp`,
> `tar`), and `chmod` pins the ambiguity half too, since real chmod has both `--recursive` and
> `--reference`. `curl` is the one tool in the corpus that does not want the rule: real curl matches long
> options exactly, so `curl --loc` is `acceptsLoosely` there.
> The analysis below is the pre-fix record.

**Hit by 3 of 10** (`mkdir`, `chmod`, `rm`), all verified against coreutils 9.11, and it applies to every GNU
tool by inheritance.

```
mkdir --par -v ab/cd     # accepted: creates ab and ab/cd
chmod --recu 700 d       # accepted
chmod --re 700 d         # "option '--re' is ambiguous; possibilities: '--recursive' '--reference'"
rm --rec d               # accepted
```

**Mechanism.** `Parser.kt:570` and `:579-580` are single `firstOrNull { it.name == long }` equality checks.
There is no prefix matching and therefore no ambiguity diagnosis; an abbreviation falls through to the
unknown-option path and gets a did-you-mean suggestion, which makes a supported GNU convention look like a
typo. Hand-declaring every unambiguous prefix is not a workaround: it collides with
`validateDuplicateOptionFlagNames`, and there is no holder-level alias (§5.5).

**Note:** this may be a legitimate policy choice rather than a gap. If so, it should be stated in the README
next to the command-line-forms paragraph, because the current did-you-mean output does not read as a policy.

### 5.11 Digit shorts are impossible in both directions — **RESOLVED**

> **RESOLVED in `b89eeec` and `6ed0a08`, 2026-08-02**, along the split this section proposed.
>
> **§5.11a** — `flag("-4")` is an ordinary declaration, and `isFlagLike` no longer looks past the dash at
> all, so the token reaches the option matcher. `example/curl` and `example/ssh` drop their invented
> long-only spellings for `-4`/`-6`, and `example/ls` declares real ls's one digit short, `-1`.
>
> **§5.11b** — `numericAlias(option)` declares that `-<NUM>`, for any N, is shorthand for an option the
> command already has; the digits become its value and run through its converter. `example/head` and
> `example/git`'s `log` both use it, and `head -5 f` binds `lines = 5` instead of a file named `-5`.
>
> **The default became an error**, which is what made `ls` need no special case: a dash-led number means
> what a tool declares it to mean, and a tool that declares nothing gets `UnknownOption`, exactly as real
> `ls -5` and `sleep -1` do. The cost, decided deliberately: a negative *operand* now needs `app -- -100`,
> where the digit exemption used to let it through. A negative option *value* needs no escape, since §5.2
> takes the next token whatever it looks like. `chmod -755` is the one corpus line that paid for this;
> see §5.2's operand half.
> The analysis below is the pre-fix record.

> **CORRECTED 2026-08-02 by the `head` and `ls` fixtures.** This section reads as though klap *rejects* a
> digit short. It does not. The declaration side throws at construction, so no fixture can declare one —
> but on the parse side `isDashLedValue` routes the token to a **positional**, so the invocation succeeds
> with a wrong binding and no diagnostic anywhere. `head -5 f` binds `files = ["-5", "f"]`.
>
> A silent wrong answer is a worse defect than the loud rejection described below, and it changes what a
> fix has to do: it is not enough to permit the declaration, the token must stop being claimed as an
> operand. The two directions this section names are therefore not symmetric.
>
> ### There is no correct global rule — verified 2026-08-02
>
> All three possible meanings of `-<digits>` are live in real tools on this machine:
>
> | invocation | result | `-5` is |
> |---|---|---|
> | `seq -5 5` | exit 0, prints `-5 -4 … 5` | a negative **operand** |
> | `head -5 f` | exit 0 | an **option** (obsolete `-n 5`) |
> | `ls -5` | exit 2, `invalid option -- '5'` | an **error** |
> | `sleep -1` | exit 1, `invalid option -- '1'` | an **error** |
>
> So the tool must declare which it means; klap cannot pick one. Error is the right default, since it is
> what the majority do and what a tool that says nothing should get.
>
> ### The gap is really two gaps, with different fixes
>
> Conflating them is why this section reads as one hard problem. It is one easy one and one small one.
>
> **§5.11a — a literal digit short.** `curl -4`/`-6`/`-0`..`-3`, `ssh -4`/`-6`. These are ordinary shorts
> whose character happens to be a digit; nothing about them is numeric. **This needs no feature of its
> own.** Under the explicit-token spelling model (see the note on §5.3/§5.5 below) `flag("-4")` simply
> declares it, and the parse side is already covered by the planned change that consults the tree's own
> declared shorts before classifying a dash-led token. Two of this section's three tools fall out free.
>
> **§5.11b — a numeric pattern.** `head -5`, `tail -20`, `git log -5`, where `-<N>` for *any* N is
> shorthand for another option. This is the part that needs a concept, and it is faithfully modelled as an
> alias onto an existing option rather than a new holder kind, because that is literally what it is:
>
> ```kotlin
> val lines = option("--lines", "-n").int()
> numericAlias(lines)        // -<N> binds here
> ```
>
> **`ls` then needs no special case.** It declares no `-5` and no numeric alias, so `-5` is an unknown
> option — which is what real `ls` says. The guard rail stops being a guard rail and becomes a consequence
> of the model. `example/ls` and `example/head` pin the two directions and must both still hold.
>
> The `seq` behaviour (a negative *operand*) is deliberately not supported: no corpus tool needs it, and
> `seq -- -5 5` expresses it. Revisit only if a second tool wants it.

**Hit by 3 of 10:** `curl` (`-4`, `-6`, `-0`, `-1`, `-2`, `-3`, all present in curl 8.21.0), `ssh` (`-4`,
`-6`), `git` (`git log -5` as shorthand for `-n 5`).

**Mechanism.** Blocked twice, which is why it cannot be papered over. The builder throws at construction
(`BuilderValidation.kt:71-73`), and even without that check the parser could never route the token, because
`Parser.kt:32` classifies `-4` as a dash-led *value* and `Parser.kt:452-455` files it into `positionals`. In
`git log -5` the effect is silent: `-5` binds as the `<revision-range>` operand instead of erroring.

**What a CLI author must do today.** Declare long-only (`--ipv4`) and lose the real spelling. Any fix has to
change both halves: relax the builder check **and** make `isDashLedValue` consult the command's declared
shorts. `-1`/`-2`/`-4`/`-6` also appear in `tar`, `xz`, `gzip`, `sort`, `diff` and `scp`.

### 5.12 A variadic positional must be last, and it takes everything — **RESOLVED**

> **RESOLVED in `a63c705`, 2026-08-02.** A variadic may be followed by required positionals, which bind
> from the end: `cp SOURCE... DEST` is two declarations and `cp a b c` gives `[a, b]` and `c`. Only
> required slots may follow — an optional one after a greedy one is genuinely ambiguous.
>
> **Neither `cp` nor `mv` can use it, and that is the finding this leaves behind.** Their operand SHAPE
> depends on the options: `-t DIR` makes every operand a source with no DEST, `-T` forces exactly two. A
> positional spec is fixed at build time, so declaring the two slots would break `cp -t DIR a`, a line
> real cp accepts. Both fixtures now record §5.13 as their blocker instead of this section.
> The analysis below is the pre-fix record.

**Hit by 3 of 10** — unchanged by `db15fd4`, and now **rank 11** and `cp`'s only structural blocker: `cp`
(`SOURCE... DEST`, its headline shape), `find` (`PATH... EXPRESSION...`), `git` (`git log main dev`, where
`<revision-range>` itself wants to be variadic).

> **`db15fd4` did not help `cp`, and the two limits are easy to confuse.** §5.1 was about how *many* operands
> a **trailing** variadic may bind; loosening it to zero is a change in `min` handling. §5.12 is about a
> variadic that is **not trailing** — one that must stop short and leave a token for a later slot. No value of
> `min` expresses that, and the guard `db15fd4` touched is never even reached, because
> `validatePositionals` refuses the declaration at construction. Re-verified by execution against the current
> tree: `argument("source").multiple(min = 1)` followed by `argument("dest")` throws
> `command 'cptest': a variadic (multiple) argument must be the last positional` from `cli { }`; `cp --help`
> prints `usage: cp <operands>... [options]`; bare `cp` reports `missing required argument <operands>`,
> exit 2. All three are byte-identical to `46de18f`. See also §3.1.

**Mechanism.** Rejected at **construction**, not at parse. `BuilderValidation.kt:28-34`:

```kotlin
require(positionals.count { it.cardinality is Cardinality.Multiple } <= 1)
require(multipleIndex < 0 || multipleIndex == positionals.lastIndex)
```

The gate reproduced the exact message: `command 't': a variadic (multiple) argument must be the last positional`.
The behaviour that check protects is the unconditional greed at `Parser.kt:258` (`val slice = values.drop(i)`)
followed by `Parser.kt:290` (`i = values.size`), so any later spec sees `values.getOrNull(i) == null`
(`Parser.kt:294`) and reports `MissingArgument` (`Parser.kt:300`). *(Post-`db15fd4` numbering; the original
findings cited `:286` / `:290` / `:296`, correct for `46de18f`.)*

Note that the **opposite** direction works perfectly: chmod's `MODE FILE...` binds exactly right, because
scalar slots fill left-to-right at `Parser.kt:293-315` and only the trailing variadic takes a greedy slice.
So this is specifically the trailing-required case. `db15fd4` widened what the *trailing* slot may hold —
`git log`'s `[<revision-range>] <path>...` is a working optional-scalar-then-variadic today — but it left the
ordering rule untouched.

**What a CLI author must do today.** Collapse the operands into one variadic and split by hand
(`ops.dropLast(1)` / `ops.last()`). Costs: `--help` advertises `<operands>...` instead of `SOURCE... DEST`;
the "missing destination file operand after 'X'" diagnostic becomes a hand-written `CliError.Failure`; and
completion sees one homogeneous list, so it cannot offer directories-only for the final word.

`SOURCE... DEST` is the most common non-trivial operand shape in POSIX tooling (`cp`, `mv`, `ln`, `install`,
`rsync`); today none of them can be declared.

### 5.13 Positional arity cannot depend on which options were given — **SPLIT: §5.13a RESOLVED, §5.13b PARTLY closed**

> **§5.13a is RESOLVED in `9feefcd` and `c6a7ca4`, 2026-08-03.** Two converters, deliberately separate
> because they are different operations:
>
> - `.absentWhen(input)` **removes** the operand slot entirely when `input` was supplied, so the operands
>   after it keep their own positions. `chmod --reference=RFILE FILE...` has no MODE operand at all, and
>   the accessor widens to nullable because the slot genuinely binds nothing on those lines. This is the
>   trap the section below describes: `.optional()` builds cleanly and then swallows the first FILE.
> - `.requiredUnless(input)` **keeps** the slot and drops its declared minimum to zero, so nothing shifts.
>   `rm` errors with no operand and `rm -f` exits 0 with none, declared rather than re-implemented.
>
> All three of this section's surviving costs are paid. `--help` and the usage line carry the rule
> (`usage: chmod [<mode>] <file>...`, with the row reading `[<mode>]  (absent with --reference)`; and
> `usage: rm [file...]` with `<file>  (optional with --force; repeatable, min 1)`), the diagnostic is a
> real parse-time `CliError`, and no exit code is chosen by hand. `example/chmod` and `example/rm` both
> deleted their hand-written checks. The one thing that did not change is `rm`'s exit code: real rm answers
> a missing operand with 1 and klap with 2, pinned as its own `rejects` line.
>
> **§8's closing list recommended NOT closing this**, on the grounds that "a `requiredUnless(opt)`
> combinator would put option state into the positional binder, which is a dependency direction worth
> refusing". That recommendation was overruled and the direction it warned about is exactly what shipped:
> `bindPositionals` takes the sift and reads `supplied(trigger, sifted)`. The reason it is defensible is
> that `supplied()` is the same predicate the constraint checks and the completion planner already use, so
> no second definition of "the user actually wrote this" entered the tree.
>
> **Both guards had to move out of `Converters.kt`.** Written inline, a chain in the other order
> (`argument("file").absentWhen(ref).multiple(min = 1)`) built cleanly and then made the whole variadic
> vanish. They run in `validateConditionalOperandTriggers` at `build()` now, which is the same ordering
> hazard §5.4a's member rules hit and the same fix.
>
> **§5.13b is PARTLY closed, and the walkthrough that planned this branch got it wrong.** That walkthrough
> declared all of `cp`'s case a non-goal, reasoning that three alternative operand layouts need alternative
> signatures. **Too broad.** The `-t DIR SOURCE...` half, its shape-dependent minimum included, is
> expressible with `.absentWhen()`: `example/cp` and `example/mv` declare
> `argument("source").multiple(min = 1)` followed by `argument("dest").absentWhen(targetDirectory)`, both
> deleted their hand-written "missing destination file operand" checks, and `cp --help` reads
> `usage: cp [-i|-n] [-L|-H] <SOURCE>... [<DEST>] [options]`.
>
> **What is left of §5.13b, in two tools:**
>
> 1. **`-T`'s exactly-two cap.** A variadic carries a minimum and no maximum, and neither half can be
>    conditioned on an option, so `cp -T a b c` and `mv -T a b c` parse where the real tools answer
>    `extra operand 'c'`. Both fixtures pin it as `acceptsLoosely` and both now re-check it by hand in
>    `action { }`. This is a genuinely different shape from §5.13a: a *maximum* is not a cardinality klap
>    has, where a presence and a minimum both are.
> 2. **Directory-only completion for DEST.** `.file()` offers files and directories alike, where the real
>    tools' last word can only be a directory once more than one SOURCE is given. Nothing carries that.
>
> The analysis below is the pre-fix record and describes both halves together.

**Hit by 3 of 10:** `cp` (`-T SOURCE DEST` is exactly two; `-t DIR SOURCE...` is one or more; plain
`SOURCE... DEST` is two or more), `chmod` (`--reference=RFILE FILE...` removes the MODE operand entirely),
`rm` (`rm` errors, `rm -f` exits 0).

**Mechanism.** `bindPositionals` opens with `val args = arguments` (`Parser.kt:252`) and iterates that fixed
list (`Parser.kt:254`) with no reference to which options bound; the flag/option bind has already run and
shares nothing but the `sink` (`Parser.kt:93` then `:101`). `Cardinality` has no conditional variant
(`Cardinality.kt:4-9`), and the whole bind runs before any action can read a flag.

The chmod case is the sharpest, because both available workarounds are wrong. Leaving `mode` `Required` makes
`chmod --reference=r notes.txt` report `invalid value 'notes.txt' for mode` (gate-verified). Marking it
`.optional()` is strictly worse and **silently** so: `BuilderValidation.kt:45-53` permits optional-then-variadic,
so the build succeeds, and then the first FILE is swallowed into the `mode` slot even in the ordinary
`chmod 755 a b` case.

**What a CLI author must do today.** Declare the loosest arity and re-implement the real rule as a runtime
`CliError.Failure` in the action. ~~For `rm -f` that is not even possible, because the parse-time error fires
first and the action is unreachable at zero operands (this one is unblocked by §5.1).~~

**Updated after `db15fd4`.** The struck sentence is now wrong, and `rm` is the worked example of the
workaround rather than an exception to it: the action *is* reachable at zero operands, so `rm` declares
`.file().multiple()` and returns `Err(CliError.Failure("missing operand"))` when the list is empty and `-f`
was not given. Executed: bare `rm` exits 1 with `error: missing operand`, `rm -f` exits 0 with no output at
all, both matching GNU rm. **The count stays at 3**, because the limit was never "you cannot enforce the
rule" — it is that you cannot *declare* it. Three costs survive for `rm`, and all three still apply to `cp`
and `chmod`:

1. `--help`, `--docs` and completion present the operand list as unconditionally optional, since the
   declaration says `Multiple(0)` and nothing records the `-f` dependency.
2. The diagnostic is klap's, not the tool's: `ErrorRendering.kt:99` renders `error: $rendered`, so the user
   sees `error: missing operand` where real rm prints `rm: missing operand` followed by
   `Try 'rm --help' for more information.`
3. The check runs after the whole parse rather than as a usage error, and the author must choose the exit
   code by hand (`CliError.Failure` defaults to 1, `CliError.kt:43`).

### 5.14 Option parsing never stops at a positional — **RESOLVED**

> **RESOLVED in `bc4269c` and `31a3406`, 2026-08-03.** `optionsEndAtFirstOperand = true` on a command
> makes every token after its first operand an operand verbatim, dash-led or not. `example/ssh` sets it,
> and all three lines this section calls out are ordinary `accepts` now: `ssh web1 ls -la` passes `ls -la`
> through, `ssh web1 tar -C /src` no longer has `-C` stolen as the local compression flag, and
> `ssh web1 grep -x pat` no longer errors. **The silent theft this section names as the worse half is
> gone**, which is the point of the feature.
>
> **This is the one item in this document that makes klap MORE conforming, not less.** Guideline 9 puts
> every option before the operands and a strict POSIX `getopt` stops at the first one; klap's default is
> GNU's permutation, which the sugar table at §2.5 lists as an extension. The switch gives that extension
> back. It is off by default and per command, since a subcommand that wraps another program (`git bisect
> run`) sits beside siblings that must keep permuting.
>
> Implementation is one line in `sift`'s positional branch, set *after* the append so the operand itself
> still binds. `siftGlobals` is deliberately untouched: it runs before the walk knows which command it
> will reach, so it cannot honour a per-command switch.
>
> **Guideline 10 is undamaged**, which the reviewer checked directly: a `--` before any operand is still
> fully structural, and a `--` after one is an operand because options had already ended, which is the same rule
> `guideline10_onlyTheFirstDelimiterIsStructural` already pins for a second `--`.
>
> **Three limitations, each found by review and each recorded rather than smoothed over:**
>
> 1. **A MIXED short cluster after the first operand is swallowed.** A long global and an all-global short
>    cluster are still claimed wherever they sit, which is the documented trade a global always makes. A
>    cluster mixing a global character with a local one (`-fs`, local `f` plus global `s`) goes the other
>    way: `siftGlobals` leaves such a cluster whole for the reached command's own `sift` to split, and that
>    split never runs once an earlier operand has ended options, so the whole cluster binds as a literal
>    operand and the global silently keeps its default. Acceptable for a wrapper, whose tail is meant to
>    stay untouched; the behaviour is kept, the KDoc states it, and a test pins it.
> 2. **klap's own position-independent built-ins still reach through.** `--json`, `--color`, `--help`,
>    `--version`, `--completion` and `--docs` are stripped before the tree knows which command it reaches,
>    so a tail carrying one literally still needs its own `--`. `example/ssh` pins this as a separate
>    `accepts` line and names it as the global trade rather than a §5.14 divergence.
> 3. **A post-operand `--` diverges from real ssh.** The reviewer probed OpenSSH_10.4p1 and **disproved a
>    parity claim this fixture had been making**: `ssh host -- -p abc` skips the `Bad port` that
>    `ssh host -p abc` triggers, so real ssh restarts its own option scan after the destination and
>    *consumes* that `--`. klap treats the destination as ending all further interpretation, so the `--`
>    binds as a literal token in the tail. Corrected and moved into `knownDivergenceFromRealSsh`. The same
>    probe confirmed the headline feature: `ssh host ls -p abc` produces no `Bad port`, so real ssh does
>    stop at its first operand.
>
> `example/find` was deliberately **not** rewritten. The switch would let it own its expression grammar
> with no `--` at all, which is what supersedes §5.18, but adopting it would model a different tool than
> the one this study measured. `FindParityTest` records that the shape is now reachable and the fixture
> keeps its declared shape.
> The analysis below is the pre-fix record.

**Hit by 2 of 10**, but they are the two tools whose entire identity depends on it: `ssh` (an opaque remote
command) and `find` (its expression). It generalizes to every wrapper: `sudo`, `env`, `xargs`, `time`,
`nice`, `docker run`, `git bisect run`.

**Real-world syntax that cannot be expressed:**

```
ssh host ls -la          # everything after the host is passed through verbatim
find . -name '*.kt'      # the tool wants to own its own tokenizer
```

> **After `db15fd4` this is `ssh`'s *sole* remaining structural blocker.** The §3 verdict row used to say
> ssh's optional pass-through tail was "unreachable in two independent ways" — an optional trailing variadic
> did not exist (§5.1), *and* option parsing does not stop at a positional. The first half is gone: the stub
> now declares `argument("destination")` followed by `argument("command").multiple()`, `ssh web1` binds
> `destination = "web1"` with `command = []`, and `ssh web1 -- ls -la /var/log` splits into the two slots
> correctly. What is below is the half that survives, and it is the one that defines the tool.

**Mechanism** *(line numbers refreshed for `db15fd4`; the original findings' `:438` / `:447-450` / `:452-455`
were correct for `46de18f`)*. `sift` carries a single `optionsEnded` boolean (`Parser.kt:442`) that is set in
exactly one place, `token == END_OF_OPTIONS` (`Parser.kt:451-454`). The positional branch (`Parser.kt:456-459`)
appends and moves on without flipping it. `Cardinality` has no raw/passthrough/trailing variant
(`Cardinality.kt:4-9`). And there is no pre-sift seam to escape into: an unrecognized dash token is recorded
and **dropped, never demoted to a positional** (`Parser.kt:496-506`, with the comment at `:503-504` saying
so; `Parser.kt:546-548` for the short form), and `bind` raises that error before binding anything
(`Parser.kt:92`). `Cli.parse` is not an escape either, since it routes through the same `sift`
(`klap/Parser.kt:185`).

**What a CLI author must do today.** Require users to type `--`, which changes the tool's syntax. And the
failure mode without it is not merely loud: the gate verified that an undeclared short fails loudly
(`ssh web1 grep -x pat` gives `unknown option '-x'`, exit 2) but a short the CLI *does* declare is **stolen
silently** (`ssh web1 ls -la` binds `login=a` and exits 0; `ssh web1 tar -C /src` eats `-C` as the local
compression flag). Silent theft is the worse half, and it is invisible in testing. Re-executed against the
current tree with the two-slot declaration in place: `ssh web1 ls -la` still exits 0 and still prints
`would run 'ls' on web1 [login=a]` — the `-la` cluster is consumed locally and never reaches the `command`
slot, so the honest operand shape has made the silent theft *more* visible without making it less real.

### 5.15 No custom value placeholder (metavar) — **RESOLVED**

> **RESOLVED in `60ab662`, 2026-08-02** (renamed `.metavar` -> `.placeholder` later the same day).
> `.placeholder(...)` sets the placeholder shown in help and usage.
> The analysis below is the pre-fix record.


**Hit by 2 of 10** at severity (`curl` blocked, `dd` awkward), but it degrades help for every options-heavy
tool.

**Mechanism.** `Help.kt:59`:

```kotlin
internal fun ValueSpec.valuePlaceholder(): String = choices?.joinToString("|") ?: "value"
```

consumed at `Help.kt:77`. An option's placeholder is its choice list or the literal string `value`, full
stop. Six of the seven options in `Curl.kt` render as `<value>` where real curl shows `<header/@file>`,
`<file>`, `<seconds>`, `<user:password>`, `<url/file>`, `<data>`. `.range()`'s `valueHint` does not help: it
lands in the trailing parenthetical via `Help.kt:94`, not in the placeholder.

There is a second-order effect worth recording, because it is non-obvious. `helpText` sizes **one shared
signature column** from the longest row across all sections (`Help.kt:308`). A choice-restricted option
inlines its full choice list into that signature, so `-X, --request <GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS|TRACE>`
(`Curl.kt:56-57`) is a 60-character signature; gutter = 2 + 60 + 2 = 64, so at `COLUMNS=80` the description budget is 16, below
`WRAP_FLOOR = 20` (`Help.kt:215`, `:266`), and the **entire** help page flips to the stacked narrow layout
(`Help.kt:273-280`). One option's choice list silently reformats every other row. The only workaround is to
drop `.choice()`, i.e. give up the validation.

Note also the existing asymmetry: `Help.kt:93` gives an `ArgumentSpec` a trailing `(one of: …)` hint, while an
`OptionSpec` gets its choices inlined into the signature instead. Options are the inconsistent case.

### 5.16 Single-dash multi-character options — **OUT OF SCOPE**

> **Declared out of scope on 2026-08-02.** Not "not yet"; klap will not do this. Three reasons, in order of
> weight.
>
> **The grammar is ambiguous, and klap's parser is deliberately not.** `-name` and the cluster `-n -a -m -e`
> are the same bytes. Telling them apart requires consulting the declared input set mid-walk, so a token's
> *shape* would stop determining its *meaning* — and a tool is free to declare both, which makes even that
> lookup ambiguous rather than merely late. Every klap token decision today is lexical, which is what lets
> `siftGlobals` split globals off before a command is even resolved (`Parser.kt:678` vs `:730`) and lets
> completion walk a half-typed line without binding anything. Adopting single-dash-long trades that property
> away everywhere to serve one token shape.
>
> **The blast radius is the whole parser, not a branch.** The long-form test at `Parser.kt:457`, the
> short-cluster walk at `:518-563`, both arms of the `siftGlobals` split, the `short.length == 1` guard at
> `BuilderValidation.kt:68`, plus completion candidate generation and every help/usage/docs renderer that
> prints an option's spelling.
>
> **It does not unblock its own headline tool.** `find` needs an ordered boolean expression with operator
> precedence, grouping and short-circuit evaluation. §5.16 is the *first* thing that stops it, not the last:
> the verdict row at §3 puts it correctly — "nothing about find's grammar is in klap's model, which is what
> fixes the verdict at `not-expressible`". (The "it is the reason" phrasing this section opened with was too
> strong; §3 is the accurate statement.) Fixing §5.16 alone would move find from failing at `-name` to
> failing at `-o`. The rest of the family — `java -jar`, `javac -classpath`, `openssl` — are argument
> conventions predating GNU long options, and a klap-built tool has no compatibility reason to adopt one.
>
> **The escape hatch, if you need this shape:** §6.3's passthrough — a trailing `.multiple()` positional
> receives the tail verbatim in source order, for a hand-written recursive-descent parser. That is the same
> seam §5.14, §5.17 and §5.18 want, and it stays the answer here.

**Hit by 1 of 10** (`find`). The pre-decision analysis follows.

**Mechanism.** klap recognizes exactly two token shapes and neither is `-name`. A multi-char short is
rejected at construction (`BuilderValidation.kt:68`, `require(short == null || short.length == 1)`), and at
parse time `Parser.kt:457` makes `token.startsWith("--")` the **only** long-form branch, so every other
flag-like token goes into the short-cluster walk (`Parser.kt:518-563`), which iterates
`token.removePrefix("-")` one character at a time (`Parser.kt:522`, `:526`). `-name` is read as `-n -a -m -e`
and fails at `unknown option '-n'`. `siftGlobals` has the identical two-shape split (`Parser.kt:678` vs
`:730`), so a global cannot escape it.

This is the whole of find's surface, and it also rules out `java -jar`, `javac -classpath`, `openssl` and the
rest of the single-dash-long family.

### 5.17 No named `key=value` operands — **SPLIT: the completion sub-gap RESOLVED, the SHAPE OUT OF SCOPE**

> **The completion sub-gap is RESOLVED in `922271e` and `4e0aaf5`, 2026-08-03.**
> `CompletionScope.completeFiles(nonPathPrefix: String = "")` hands one slot to the shell's own filesystem
> completion, so a `.completeWith` provider can answer "complete files here, after this literal prefix".
> `example/dd` uses it and `dd if=/dev/ze<TAB>` completes to `dd if=/dev/zero`.
>
> **The reason this section gives is now wrong on its own terms**, and is left below as the pre-fix record.
> `COMPLETE_FILES` is still `internal`, and that was never the thing to change: the public surface is the
> *function*, not the sentinel string, so the wire format stays klap's to change. Which it promptly did.
>
> **The wire format changed, and the review is why.** The first shape took no argument, and running the
> real shells found the feature's headline case working in only two of four: `dd if=/dev/ze<TAB>` yielded
> nothing in zsh or PowerShell, because both hand the whole token to native completion and no file is named
> `if=/dev/ze`. Closed by carrying the non-path prefix on the directive itself, which each script peels off
> before completing and puts back on whatever it inserts. `COMPLETE_FILES` is now `" klap:files:"` with the
> prefix as the line remainder, and no tab, because the bash script strips descriptions at the first one.
> Allowed because klap is unreleased and the constant is internal.
>
> The call is exclusive by construction: it discards anything `candidate()`/`candidates()` collected before
> it and drops anything added after, because every generated script maps a *lone* directive line to native
> completion and treats any other line as a literal candidate.
>
> **Verification, stated exactly.** bash, zsh and fish were driven with real ptys and actual Tab presses:
> all three complete `dd if=/dev/ze` to `dd if=/dev/zero`, and all three yield nothing with the prefix
> strip removed, so the assertion discriminates. **PowerShell was NOT run.** `pwsh` is not installed on the
> subject machine, so its half of the change is inspection-only and is pinned by script-text assertions
> alone. A reader should treat the PowerShell path as unverified.
>
> The same task fixed a latent planner bug this feature would have activated: a hybrid parent's subcommand
> names could join a directive into a multi-line answer that no script recognizes.
>
> **The named-operand SHAPE is declared out of scope on 2026-08-03.** Not "not yet"; klap will not do this.
> A dash-less, named third input kind would sit beside the two shapes klap has, and it would serve **one
> tool in the corpus of fourteen**. The parsing is already recovered by `.convert { }` plus
> `.completeWith { }`, and what is genuinely lost is per-key help rows and per-key error names, a
> documentation cost rather than a parsing one, and one `epilogue` covers it at the price of not being validated
> against the parser. The escape hatch stays §6.3's passthrough, the same one §5.16 and §5.18 point at.
> Revisit only if a second tool with this shape appears. `example/dd`'s marker records the ruling in place.
> The analysis below is the pre-fix record and describes both halves together.

**Hit by 1 of 10** (`dd`), whose entire surface is 14 named, order-independent, all-optional `key=value`
operands and zero flags.

**Mechanism.** klap has exactly two input shapes, dash-led named and anonymous positional, with nothing in
between (`CommandBuilder.kt:31-33`). Name dispatch happens only after a `--` prefix or inside a `-` cluster
(`Parser.kt:441-464`), with no configurable prefix anywhere. Declaring `argument("if")`, `argument("of")`, …
is wrong because positionals bind strictly by declaration order (`Parser.kt:247-323`), so `dd count=1 if=x`
would bind `count=1` to the `if` slot, and every operand is optional so no order can be assumed. Declaring
`option("if")` is wrong because it produces `--if=FILE`.

**What a CLI author must do today.** One anonymous `.multiple()` slot with a hand-written token grammar (~80
lines in `Dd.kt`). The parser machinery holds up (a `key=value` token needs no escaping, and a converter
failure surfaces as a clean per-token `BadValue`), but the *declarative* payoff is gone: one help row, one
error name, one completion provider and one converter for all 14 operands. `.int()`, `.choice()`, `.enum<E>()`
and `.range()` are all unusable, because the token is `count=10`, not `10`.

One concrete sub-gap is cheap to close: `dd if=/dev/ze<TAB>` cannot complete, because `.file()` marks the
whole spec as a path (`Converters.kt:218-221`) and the planner then answers the sentinel for the entire slot
(`Completion.kt:126`, `:187`), and `COMPLETE_FILES` is `internal` (`Completion.kt:92`) so a `.completeWith`
provider cannot emit it either.

### 5.18 No terminator-delimited capture — **SUPERSEDED by §5.14**

> **SUPERSEDED, not declined, in `bc4269c`, 2026-08-03.** The distinction matters, because "declined"
> would say the shape is unreachable and it is not.
>
> `find -exec CMD [ARG...] ';'` was never an *option* grammar. It is find's own grammar, and this document
> has said so throughout: §8's closing list calls find's expression "not a parser configuration; they are a
> parser", and names item 9 (a passthrough seam) plus item 6 as the right answer for it. §5.14 landed item
> 9. `optionsEndAtFirstOperand = true` hands find every token after its starting point, in source order,
> verbatim, the `;` terminator and all, with no `--` required, which is precisely the seam this section
> wanted. A tool that needs terminator-delimited capture writes its own recursive-descent parse over that
> tail, which is what a tool with its own grammar was always going to do.
>
> So there is nothing left here for klap to add. A sentinel variant of `Cardinality` would be a second way
> to express what the seam already expresses, for one shape in one tool, and it would put a fragment of
> find's grammar inside klap's binder, the property §5.16 spends its whole block refusing to give up.
>
> `example/find` deliberately keeps its transliterated `--exec`-per-word declaration rather than adopting
> the switch, because rewriting it would model a different tool than the one this study measured. The
> parity suite states that in place.
> The analysis below is the pre-fix record.

**Hit by 1 of 10** (`find -exec CMD [ARG...] ';'`).

**Mechanism.** An option occurrence consumes exactly one token (`Parser.kt:509-512`), and the only greedy
slot in the model, a trailing `Multiple` positional, takes the entire remainder unconditionally
(`Parser.kt:258`, `:286`) with no way to stop at a sentinel. `Cardinality.kt:4-9` has no sentinel variant.
The same shape appears in `xargs -I{}` and `git submodule foreach`.

### 5.19 Smaller confirmed limits

Each hit by one tool, each verified, none worth its own subsection:

Line references in this table are current as of `db15fd4`; where the original findings cited a
pre-fix `Parser.kt` line the old number is given in parentheses.

| Limit | Tool | Mechanism |
|---|---|---|
| Case-sensitive choice sets (`find -type d` vs `-type D`) throw at construction | `find` | `Converters.kt:32-39` lowercases before the distinctness check; `:65` matches case-insensitively; `.enum<E>()` is identical (`:46-56`) |
| A parent's own option between the parent and its subcommand (`git remote -v add up URL`) | `git` | The walk breaks at the first token that does not name a child (`klap/Parser.kt:137-141`; the original findings and the `Git.kt` marker both cite `:140-145`, which is off by three and predates `db15fd4`), so routing ends permanently; only a global survives that position |
| The same short at two levels (`git -p` = paginate, `git log -p` = patch) | `git` | A global reserves its short tree-wide (`BuilderValidation.kt:258-262`, the `require` at `:259`), which follows necessarily from globals being stripped before the walk (`klap/Parser.kt:135`) |
| No position-dependent globals | `git` | klap globals are recognized anywhere, so `git remote add origin URL --no-pager` parses here and real git rejects it |
| Two operand groups split by `--` (`git log <rev> -- <path>...`) | `git` | Post-`--` tokens go into the same flat list as pre-`--` ones (`Parser.kt:446-449`, was `:442-445`, vs `:456-459`, was `:452-455`). **`db15fd4` changed the failure mode, not the limit:** the two groups can now both be *declared*, so `git log -- src/` no longer errors with `TooManyArguments` — it silently binds `src/` as the `<revision-range>` (executed: `would show src/`). A silent misbind is the worse diagnostic of the two |
| Usage-error exit code fixed at 2 | `rm`, `tar` | `CliError.kt:4-8`; several classic tools exit 1, and `CliError.Failure` defaults to 1 (`:43`), which is backwards for a hand-enforced usage rule |
| Usage line always renders `<args> [options]` | `curl`, `tar`, `dd` | `Help.kt:126-133` (was `:124-131`) hard-orders `argSummary()` then `[options]`; real curl and tar document `[OPTION...]` first. `db15fd4` fixed tar's bracket *notation* (`[file...]`, matching `[FILE]...`) but not the ordering |
| **The usage line and the Arguments block disagree about an optional variadic** *(new, introduced by `db15fd4`)* | `tar`, `rm`, `dd`, `curl`, `git`, `find`, `ssh` | `argSummary` renders `[file...]` (`Help.kt:56`) but the per-row renderer hard-codes `<${it.name}>` (`Help.kt:156`) and `metaHint` emits a min-blind `(repeatable)` (`Help.kt:107`), so `tar --help` prints `usage: tar [file...] [options]` above a row reading `<file>  … (repeatable)`. Cosmetic, and cheap to close alongside the metavar work (§8 item 2) |
| Traditional dashless bundling (`tar cvf a.tar`) | `tar` | The tokenizer keys entirely on a leading dash (`Parser.kt:456-459`, was `:452-455`; plus `:35-36`); a bare `cvf` becomes an operand. Unchanged by `db15fd4` — executed, `tar cvf a.tar src` now dies one error later, at `error: missing required option --file` instead of at the operand bind |
| `-5` accepted as an operand where the real tool rejects it | `cp`, `rm` | `Parser.kt:32`; real rm answers `invalid option -- '5'` with the hint `Try 'rm ./-5'`. klap is quietly *more* permissive on exactly the filenames rm goes out of its way to protect |

Three of these rows are untouched by the 2026-08-03 branch and stay exactly as written: the `-5` operand
row above, and the two usage-line rows (`Help.kt:126-133`'s hard ordering, and the usage line disagreeing
with the Arguments block about an optional variadic).

**Added 2026-08-03**, found while recounting the `KLAP-GAP` inventory rather than by reproducing a tool:

| Limit | Tool | Mechanism |
|---|---|---|
| ~~**Two stale `KLAP-GAP` markers, counted as open gaps that are not**~~ **Fixed** | `tar` | Both markers stated "klap has no construct for a required, mutually exclusive set … nothing matching /exclusive\|conflicts\|requires/ exists in the public API", which was **false since §5.9 closed in `faf5b91`/`65b34d8`**. `example/tar` has now adopted `requireExactlyOne(create, extract, listContents)` and `requireAtMostOne(gzip, bzip2)`; both hand-rolled checks are gone from `action { }`, both markers are deleted, and the two conflicts are enforced at parse time ahead of every bind, so `tar -c -x` reports the mode conflict rather than the missing `--file` — GNU tar's own order. `TarParityTest.rejectsTheModeAndCompressionConflicts` pins all four cases |

## 6. Awkward but possible

Friction with a real workaround. None of these blocks a surface.

### 6.1 `group(title) { }` returns `Unit`, so every grouped holder needs a `lateinit var` hoist

**Reported independently by 6 of 10** (`cp`, `curl`, `ssh`, `chmod`, `git`, `find`), which makes it the most
converged *ergonomic* finding in the study.

`CommandBuilder.kt:46` declares `group(title, block): Unit` and `BuilderImpl.kt:86-91` implements it as a
save/restore of `currentSection`, so a `val` captured inside the block is not visible to the enclosing
`action { }`. The documented workaround is to hoist each holder as a `lateinit var` above the block with its
converted type written out by hand.

The cost scales with the number of grouped inputs and is worst exactly where grouping matters most: `Curl.kt`
needs a 14-line block of `lateinit var method: Opt<String?>` / `lateinit var maxTime: Opt<Double?>`
declarations, and the ungrouped version of the same file is 14 lines shorter with no type annotations at
all. It also inverts what the fluent chain buys you: you restate by hand the exact type `.choice().multiple()`
was supposed to infer, and changing `.int()` to `.double()` silently breaks a declaration 80 lines away. In
`Chmod.kt` it forced the whole `chmodCli()` function from an expression body to a block body. Grouping is
therefore a cost/benefit call rather than a free readability win.

### 6.2 `argument()` inside `group { }` is silently unsectioned

`BuilderImpl.kt:49-54` never reads `currentSection`, unlike `option`/`flag` at `:56-72`. The positional just
renders in the unlabeled block with no error and no warning. Reported by `curl`, which called it a "silent
no-op"; see §7.3 for the correction on *why* it happens.

### 6.3 The `--` escape works, and is load-bearing more often than expected

`find`'s agent confirmed the escape hatch is real: after `--`, `sift` sets `optionsEnded` and every token
becomes a positional verbatim, so a trailing `Multiple` receives `[".", "-name", "*.kt", "-o", "-size",
"+1M", "-print"]` in source order, ready for a hand-written recursive-descent parser. That makes a
transliterated find *possible*, at the price of a syntax its users never asked for. `ssh` reached the same
conclusion. Note the sharp edge both found: the first `--` is consumed by `sift` and never reaches the
operands, so a passed-through command that itself begins with `--` needs a second one.

### 6.4 `.negatable()` as a substitute for an optional-value option

`git --decorate[=short|full|auto|no]` alongside `--no-decorate` was expressed as
`flag("decorate").negatable(default = true)`. That gets both real spellings exactly right and loses only the
value form (which now errors as `FlagTakesNoValue`). Declaring it as a value-taking option instead would have
been strictly worse: bare `--decorate`, the common form, would become `MissingOptionValue`, and
`--no-decorate` would become an unknown option. Worth knowing as a general rule when §5.6 bites.

### 6.5 Repeatable options recover more than expected

`option(...).multiple()` preserves occurrence order within one holder, which is enough for `-H`, `--exclude`,
`-m`, `-i`, `-o KEY=VALUE` and find's repeatable predicates. It is only *across* holders that ordering dies
(§5.4). `ssh`'s `option("option", "o").convert(::sshOptionKeyValue).multiple()` yielding
`Opt<List<Pair<String,String>>>` with per-occurrence `BadValue` is the study's cleanest single declaration.

### 6.6 `.completeWith { }` recovers per-key completion for a hand-rolled grammar

`Dd.kt` gets `bs=`, `if=`, `status=` before the `=` and `status=progress`, `conv=notrunc` after it, by
reading `current` off `CompletionScope`, and the planner keeps serving the trailing `Multiple` at every
positional index (`Completion.kt:121-124`), so it works on the fifth operand as well as the first. A
surprising amount of what §5.17 loses comes back this way.

## 7. Corrected claims

Not empty. Four corrections from the original passes, in descending order of consequence, plus one section
(§7.5) added after `db15fd4`.

### 7.1 Zero claimed limits turned out to be author error

This deserves stating plainly because it is the unusual result. The gate built a counterfactual harness
specifically to catch a "klap cannot do X" that klap can in fact do, constructed each claimed-impossible
shape, and observed the rejection: variadic-then-required, two variadics, variadic-then-optional,
zero-or-more positional, digit shorts, short-only options, one option with two shorts, multi-char shorts,
reserved long names, case-colliding choice sets, and the illegal cardinality combinations. It caught nothing.
This pass then re-read every `blocked` claim's cited mechanism in the source, including all of `tar`'s, which
the gate never saw. **Every structural limit claim in §5 verifies.**

### 7.2 `curl`'s stated workaround for the zero-operand case does not work — **RETIRED by `db15fd4`**

> **Retired, not withdrawn.** This correction was right about `46de18f` (the `slice.isEmpty() ||` disjunct is
> visible in the commit diff) and is obsolete about the current tree. curl's proposed escape route now works
> exactly as its agent described, so the workaround is real and curl is **no longer** a tool hit by §5.1.
> That is what takes §5.1's count from 7 of 10 to 0 of 10 rather than 6 of 10 plus one near-miss.

`Curl.kt`'s findings say that the alternative to `argument("url").multiple(min = 1)` is
"`.multiple(min = 0)` plus a hand-written emptiness check in `action { }`". That was wrong: `multiple(min = 0)`
never reached the action with zero operands (`Parser.kt:261` as it then stood), so the check would have been
unreachable. curl was therefore a **seventh** tool hit by §5.1, not a tool with a workaround. This was exactly
the failure mode the gate warned about — a correct observation attached to an untested escape route — and it
survived because no one ran it.

Two residual details, now that the route is live. The workaround loses *less* than the marker claimed: the
error is **not** lost, because an action may return `Err(CliError.MissingArgument("curl", "url"))` and both
paths converge on the same renderer — `Runner.kt:31` calls `renderError` (`ErrorRendering.kt:80`) directly,
and `Runner.kt:75` calls `renderActionError` (`:60`), which forwards an `ActionError.Failed` to the same
function at `:62`. The text `error: missing required argument <url> for 'curl'` and the exit code 2 are
therefore byte-identical either way (executed). What *is* lost is the declaration: `--help` drops from `(repeatable, min 1)` to
`(repeatable)` (`Help.kt:107`), and there is still no construct for curl's actual rule, "at least one URL
across `--url` and the operands", because no constraint in `BuilderValidation.kt` spans two holders.

### 7.3 `argument()` ignoring `currentSection` is deliberate, not an oversight

`Curl.kt` reports it as a "silent no-op", implying a bug. `BuilderImpl.kt:50` carries an explicit comment:
"Positionals never take a section: they always render in the unlabeled block." The *behaviour* is as
reported and the *recommendation* still stands (reject it at construction, or document it in the public API),
but calling it an oversight would misrepresent the code.

### 7.4 Stale or imprecise line references, corrected

The substance holds in every case; only the citation moved.

| Claimed | Actual | Where |
|---|---|---|
| `BuilderValidation.kt:86-90` / `:91-94` (duplicate long / short) | `:87-90` / `:92-94`; function spans `:85-95` | `cp`, `rm`, `chmod` |
| `Parser.kt:469-477` (flag + inline value) | `:468-475` | `rm` |
| `Parser.kt:481` (findNegatedFlag call site) | `:463`; `:481` is inside the negated branch body | `ssh` |
| `Parser.kt:441-451` (post-`--` positional append) | `:442-445`, sharing the sink with `:452-455` | `git` |
| `Cardinality.kt:5-10` | The file is 9 lines; the four cases are `:5-8` | `chmod` |
| `Parser.kt:247-254` (bindPositionals signature) | `:247-252`, with `val args = arguments` at `:252` | `cp` |
| `Parser.kt:118` ("has already run") | Defined at `:118`, **called** at `:93` | `cp` |
| Gate: "91 KLAP-GAP markers" | 89; the gate's own per-file breakdown sums to 89 | gate |

### 7.5 Corrections found while verifying `db15fd4`

Three of these are the study correcting itself; the fourth is bookkeeping. None changes a verdict.

**a. §5.1's example list conflated "the natural declaration fails" with "the stub fails."** Two of the eight
lines were true of the natural spelling and false of the committed stub, which had already paid a workaround:

- `ssh web1` **exited 0 at `46de18f`.** `Ssh.kt` had collapsed `DESTINATION` and `COMMAND...` into one
  `argument("operands").multiple(min = 1)` and split it in the action. What the bug made unexpressible was
  the two-slot declaration — and therefore the usage line, the error message naming `<destination>`, and
  per-slot `.validate` — not the invocation.
- `git commit -m "x"` **exited 0 at `46de18f`.** `Git.kt` had declared `pathspec` as a single `.optional()`
  scalar. What actually failed was `git commit -m x a.txt b.txt`, with `TooManyArguments`.

The other six lines (`tar -tf`, `tar -xf`, `rm -f`, `dd`, `find`, and the `[FILE...]` family) failed exactly
as described. Worth stating so the delta is not read as "seven tools were broken end to end": several were
*lossy*, and the loss was in help, diagnostics and validation rather than in whether a command ran.

**b. §5.1's "an `.optional()` scalar declared in front does not help" was an artefact of the bug, not a
second limit.** The reasoning given — that a null bind fails to advance the cursor — described the right code
but drew the wrong conclusion; the slice was empty because the guard rejected *every* empty slice.
Optional-scalar-then-trailing-variadic is a legal shape (`BuilderValidation.kt:45-53` permits it) and is what
`git log` uses today: `git log main..HEAD src/ doc/` binds `revision-range = main..HEAD` and
`path = [src/, doc/]` (executed).

**c. §5.1's "Help cannot even document the shape" is obsolete.** `Help.kt:56` renders `[name...]` for
`Multiple(0)`. The narrower version of that complaint survives and is now filed in §5.19: the *usage line*
learned the distinction, the *Arguments block* did not.

**d. Line-number drift, repo-wide.** `db15fd4` added a net 4 lines to `Parser.kt` inside `bindPositionals`
and 2 to `Help.kt` inside `argSummary`. Every `Parser.kt` citation **below line 258** in this document and in
the ten stubs is therefore 4 too low, and every `Help.kt` citation below line 54 is 2 too low; citations above
those points (`Parser.kt:32`, `:35`, `:92`, `:134`, `:143`, `:186`, `:252-258`) are still exact. This document
has been renumbered where a mechanism was re-read (§5.12, §5.13, §5.14, §5.19, §8). **The `KLAP-GAP` markers
in the stubs have deliberately not been swept**, because a mechanical renumbering pass across all ten files is
a separate change from re-verifying the claims, and mixing the two would make the diff unreviewable. Verified
shift examples, for whoever does the sweep: `sift` `:416`→`:420`; the `--` branch `:447-450`→`:451-454`; the
positional branch `:452-455`→`:456-459`; the long-form branch `:457`→`:461`; "never demoted"
`:492-502`→`:496-506`; long-form `MissingOptionValue` `:504-506`→`:508-511`; the short-cluster walk
`:518-563`→`:522-566`; `clusterCharError` `:542-544`→`:546-548`; `findFlag` `:569-573`→`:573-577`;
`findNegatedFlag` `:576-577`→`:580-581`; `Sifted` `:805-811`→`:809-815`; `siftGlobals`' long branch
`:678`→`:682`; `Help.kt:54`→`:56`, `:124-131`→`:126-133`.

## 8. Suggested API changes

Ranked by how many of the ten tools each unblocks, with invasiveness. "Unblocks" means the tool's real
surface becomes declarable, not merely less awkward. **The ranking below is post-`db15fd4`:** what was item 1
has landed and is kept, out of rank, as item 0 so the estimate can be checked against the outcome; everything
that was 2–15 has moved up one place.

### Tier 0: landed

**0 (was 1). Honour `min` on an empty variadic positional slice. Unblocked 7. Invasiveness: trivial.
LANDED in `db15fd4`, 2026-08-02.**

*As proposed:* change `Parser.kt:261` from `if (slice.isEmpty() || slice.size < c.min)` to
`if (slice.size < c.min)`, keeping `MissingArgument` for the `slice.isEmpty() && c.min > 0` case and
`TooFewOccurrences` otherwise. Then render `[<name>...]` for `Multiple(0)` and `<name>...` for
`Multiple(min >= 1)` at `Help.kt:54`, so the usage line distinguishes the two. **This breaks zero existing
tests** (verified against `ParsePositionalsTest.kt:52-56` and `ParseOptionsTest.kt:353-378`), aligns the
positional branch with the option branch 118 lines above it, and makes the README, the `Opt.multiple` KDoc and
`BuilderValidation.kt:37-44` true again. Nothing else in this list has anything like this ratio.

*As landed:* exactly that, at `Parser.kt:265` and `Help.kt:56`, plus 46 lines of regression test
(`VariadicPositionalArityTest`). Zero existing tests broke. The estimate's one omission is that `Multiple(0)`
now renders `[name...]` in the usage line while the Arguments block still renders `<name>` — see §5.19 — so
the render half of this item is 90% done, not 100%. Measured effect: §3.1. Everything below has moved up one
rank as a result.

### Tier 1: high value, low cost

**1 (was 2). Per-input opt-in for dash-led values. Unblocks 7. Invasiveness: small.**

`.allowDashValue()` on `Opt` setting a boolean on `OptionSpec` that makes the
`takeUnless { it.isFlagLike() }` guards at `Parser.kt:494` and `:551-553` (was `:490` / `:547-549`)
unconditional for that option, plus an `Arg` counterpart (`.allowsLeadingDash()`) that lets a flag-like token
fall through to the positional binder when it matches no declared option or flag. Scoped per-input so it
cannot degrade error messages for the rest of the command. Prefer opt-in over always-on: matching getopt
unconditionally would make every typo'd short option silently become a value.

**This is now the top-ranked open limit.** Seven tools, and the same "correct example, wrong command line"
failure as §5.1: `mkdir -m -w symtest`, `cp -S -old a b`, `git commit -m -weird` and
`chmod -w file` are all things users type, and all four are rejected or misrouted.

**2 (was 3). A custom value placeholder. Unblocks 1 properly, improves help for all 10. Invasiveness: trivial.**

`option("header", "H", help = "…", metavar = "header/@file")` or `.metavar("seconds")`, read at
`Help.kt:59`. This is the difference between curl's help and a column of `<value>`. It also fixes the
whole-page layout blowup in §5.15 for free, by letting a choice-restricted option show `<method>` in the
signature while `.choice()` keeps validating and lists the set in the trailing hint, which `ArgumentSpec`
already does at `Help.kt:93`.

Fold in the §5.19 render gap `db15fd4` left behind while touching this file: the Arguments block builds its
row name as a hard-coded `"<${it.name}>"` (`Help.kt:156`), so it should call `argSummary`'s per-spec logic
instead and render `[url...]` where the usage line does, and `metaHint` (`Help.kt:107`) should distinguish an
optional variadic from a mandatory one rather than emitting a min-blind `(repeatable)`.

**3 (was 4). Extra spellings on one holder. Unblocks 5. Invasiveness: small.**

`flag("recursive", "R").alias(short = "r")`, `option("since").alias(long = "after")`, or
`flag("recursive", shorts = listOf("r", "R"))`. `findFlag`/`findOption` already do a single equality
(`Parser.kt:573-584`, was `:569-580`), so this is a `List<String>` on `NamedSpec` plus a `contains`, an
extension of `validateDuplicateOptionFlagNames` to the full claimed set, and a help row that joins as
`-r, -R, --recursive`. Mirrors `command(...)`'s existing `aliases`, so the vocabulary already exists.

**4 (was 5). Explicit negation spellings. Unblocked 4. Invasiveness: small.
LANDED in `e9332a0`, `92b633d`, `556ab0d`, 2026-08-03.**

*As proposed:* `.negatable(default = true, negative = "no-pager", negativeShort = "P")`. `findNegatedFlag`
(`Parser.kt:580-581`, was `:576-577`) becomes a lookup against a stored string instead of a computed one, and
the short cluster gains one negation check. Covers `-A`/`-a`, `-L`/`-P`, `-n` for `--no-verify`, and the
`--paginate`/`--no-pager` asymmetry.

*As landed:* a `vararg` of full spellings rather than two named parameters,
`.negatable("--no-dereference", "-P")`, so a flag can carry several of each, and each spelling is written
as the token it is, matching the spelling model `3a1927e` introduced. Two things the estimate did not
foresee. The list had to **replace** the generated `--no-<long>` rather than extend it, since a tool with
its own spelling also rejects the generated one; without that, klap would be looser than the tool. And the
help renderer needed real work, not a lookup change: `--[no-]x` cannot state a negative half that is not a
prefix of the positive one, so an explicitly-spelled pair renders every spelling it answers to
(`-L, -P, --dereference, --no-dereference`) while a generated one keeps `--[no-]x`. Validation, completion
and the cluster-error hint all had to follow the same derived lists. Measured effect: §5.8.

### Tier 2: real value, real cost

**5 (was 6). Short-only inputs. Unblocks 6. Invasiveness: moderate.**

Either `flag(short: String, help: String)` / `option(short: String, help: String)` overloads, or making
`long` nullable with `require(long != null || short != null)`. The rendering half already exists
(`Help.kt:76` handles a null short and would mirror), but `name: String` is non-null across `HolderSpec`,
error messages, docs, completion and every validation rule, so the ripple is real. A `hasLong: Boolean` on
`NamedSpec` with a synthesized internal identity is probably cheaper than nullable names.

**6 (was 7). An ordered occurrence stream. Unblocked 6, partially. Invasiveness: moderate.
LANDED as something smaller, in `44bdb48` then `bb34c38`/`a4ca460`, 2026-08-02 and 2026-08-03.**

*As proposed:* add a `List<Occurrence>` (holder plus optional raw value, in argv order) alongside the
existing spec-keyed maps in `Sifted` (`Parser.kt:809-815`, was `:805-811`); `sift` already walks tokens in
order, so only the maps throw the ordering away. Expose it as something narrow on `ActionScope`, e.g.
`lastOf(force, interactive, noClobber): Flag?` for the common last-one-wins case, and a raw `occurrences()`
for the rest. That unblocks every override group (`cp -f/-i/-n`, `rm -f/-i`, `chmod -H/-L/-P`, `ssh -A/-a`)
and curl's per-URL `-o` pairing. It does **not** give find its expression grammar; see below.

*As landed:* **no occurrence stream, and no `ActionScope` surface at all.** Two position maps
(`flagPositions`, then `optionPositions`) beside the existing containers, and one declaration-site rule,
`lastWins(vararg inputs: Input)`, that resolves the set during the bind so an action reads the winner off
its own handle with no precedence logic. Every override group listed above is unblocked; curl's per-URL
pairing is not, and it is the half that genuinely needed the interleaved stream. That half is now §5.4b, a
declared non-goal, and the estimate's "partially" turned out to be the whole of it. Measured effect: §5.4.

**7 (was 8). Optional-value options. Unblocked 4. Invasiveness: moderate.
LANDED in `ad54f26`, `db4a315`, `18c1d12`, `39e6d67`, `460ab22`, 2026-08-02.**

*As proposed:* a dedicated field on `OptionSpec` (not a `Cardinality` case, since `Cardinality` counts
occurrences), set by `.optionalValue(whenBare = "existing")`. The sift rule is exactly getopt's
`optional_argument`: bind `inlineValue` when the `=` form is used (or a short's attached tail), bind
`whenBare` otherwise, and **never** consume the following token. `sift` already distinguishes the two at
`Parser.kt:465` (was `:461`), so the branch is localized. Help renders `--backup[=<control>]`.

*As landed:* essentially that — `OptionSpec.bareValue: String?` (`HolderSpec.kt`) and
`Opt<T>.optionalValue(whenBare: String)` (`Converters.kt`). Two things the estimate did not foresee: the
branch had to be localized at **four** consumption sites, not the one it named (two in `Command.sift`, two
mirrored in `siftGlobals` for a global option), and completion needed its own guard
(`trailingValueOption`) so the word after a bare occurrence completes as an operand rather than the
option's own choices. The help row states the bare value explicitly (`bare: <value>`) beside the
`--opt[=<value>]` signature. Carried through six fixtures by `b5b62b4` and `103f6a7`. Measured effect: §5.6.

**8 (was 9). Trailing-required positionals (`SOURCE... DEST`). Unblocked 3. Invasiveness: small-moderate.
LANDED in `a63c705`, 2026-08-02, and usable by `cp` and `mv` since `9feefcd`, 2026-08-03.**

**It landed, but for eight months of this document's life the estimate carried a caveat that turned out to
be half wrong.** §5.12's own resolution block said neither `cp` nor `mv` could use the new shape, because
their operand layout depends on `-t`/`-T` and a positional spec is fixed at build time. True of `-T`, false
of `-t`: `.absentWhen()` (§5.13a) removes the DEST slot on exactly the `-t` lines, so both fixtures declare
the two slots today and `cp --help` reads `usage: cp [-i|-n] [-L|-H] <SOURCE>... [<DEST>] [options]`. The
estimate below is otherwise accurate about the mechanism.

*As proposed:* in `bindPositionals`, when a `Multiple` spec is not last, reserve `args.size - index - 1` values for the
remaining scalar slots: `values.drop(i).dropLast(reserved)` instead of `Parser.kt:258`'s unconditional
`values.drop(i)`, and advance `i` by `slice.size` instead of `Parser.kt:290`'s (was `:286`) `i = values.size`.
Then relax `BuilderValidation.kt:31-34` from "a variadic must be last" to "at most one variadic, and
everything after it must be `Required`". Worth doing despite the low tool count, because
`cp`/`mv`/`ln`/`install`/`rsync` all share the shape. Note the interaction with item 0: `min` is now honoured,
so `SOURCE... DEST` with `min = 0` would have to mean "zero or more sources, then a required dest", i.e. the
reservation must run *before* the `slice.size < c.min` check rather than after it.

**9 (was 10). A trailing raw/passthrough positional. Unblocked 2, and a whole tool class. Invasiveness: small.
LANDED in `bc4269c` and `31a3406`, 2026-08-03.**

*As proposed:* `argument("command").raw()` (or a command-level `stopAtFirstPositional = true`) that flips
`optionsEnded` in
`sift` when a token is appended to `positionals` and the command opted in. That is mechanically one extra way
to set a boolean that already exists (`Parser.kt:442`, was `:438`). It is what makes `ssh`, `sudo`, `env`,
`xargs`, `time`, `nice`, `docker run` and `git bisect run` expressible without forcing users to type `--`, and
it is also the honest answer to find (see below). ~~Depends on item 1: a passthrough tail is normally
optional.~~ **That dependency is satisfied:** `db15fd4` landed it, and `ssh`'s stub already declares the
optional tail as `argument("command").multiple()`. This item is now the *whole* remaining cost of ssh's
defining feature.

*As landed:* the command-level form (`optionsEndAtFirstOperand = true`), not the per-argument `.raw()`, and
the estimate's "one extra way to set a boolean that already exists" was exactly right: it is one line in
`sift`'s positional branch. Two things it did not foresee, both about what the switch cannot reach: klap's
own position-independent built-ins and long globals are still claimed anywhere in argv, because that scan
runs before the walk knows which command it reaches; and a short cluster that MIXES a global character with
a local one binds whole into the tail rather than reaching the global. Both are recorded at §5.14 with the
tests that pin them. It also framed the item wrongly by calling it an unblocking feature: it is the
*conforming* POSIX reading (guideline 9) being made reachable, which makes it the one item in this list that
moves klap toward the standard rather than past it. Measured effect: §5.14.

**10 (was 11). A declarative exclusivity construct. Unblocks 4. Invasiveness: moderate-high.**

`oneOf(required = true) { flag("create", "c"); flag("extract", "x"); flag("list", "t") }` that scopes
declarations, enforces the arity during the bind as a real `CliError` rather than a hand-written `Failure`,
and renders as `-c|-x|-t` in the usage line, help and docs. `required = false` covers the at-most-one case
(`-z`/`-j`). The parse-time enforcement is the easy half; the rendering and docs integration is the cost.

**11 (was 12). Built-in opt-out. Unblocks 4, cleans up 4 more. Invasiveness: moderate-high.**

`cli("curl") { builtins { json = false; color = false; docs = false; completion = false } }`, with an opted-out
name removed from `RESERVED_LONG` for that tree. The cost is that built-ins are recognized in `Cli.parse`
before the tree is walked, so the configuration has to be threaded through parse, help, completion, docs and
the reserved-name validation together. Worth it: today a real curl option (`--json`) is simply unavailable,
and every tool whose operands are filenames silently loses `__complete`, `--json` and `--color` as filenames.

### Tier 3: judgement calls

**12 (was 13). Digit shorts. Unblocks 3. Invasiveness: moderate, and the risk is not in the code.**

Requires both halves: relax `BuilderValidation.kt:71-73`, and make `isDashLedValue` (`Parser.kt:32`) consult
the resolved command's declared shorts before classifying `-4` as a value. The risk is that the classifier is
currently context-free and used in several places (`sift`, `siftGlobals`, the group-level error path); making
it context-sensitive means `-1` can mean different things at different tree nodes. Doable, but it should be
designed rather than patched.

**13 (was 14). GNU unique-prefix abbreviation, opt-in. Unblocked 3. Invasiveness: small.
LANDED in `c794fad`, `1db3089`, `d2e41ad`, `0ea2cc9`, `ddf8b00`, 2026-08-03.**

*As proposed:* `abbreviateLongOptions = true` on `CliBuilder`, resolving in `findOption`/`findFlag` by
unique prefix and
reporting a new `AmbiguousOption` on a tie. Small change, but it is a **policy** question, not a capability
gap: klap's exact-match-plus-did-you-mean is a defensible design. If the answer is no, say so in the README
next to the command-line-forms paragraph, because the current output makes a supported GNU spelling look like
a typo.

*As landed:* the policy question was answered the other way, **always on, no switch**, so the README
documents the behaviour beside the command-line-forms paragraph rather than documenting its absence. The
"small change" estimate held for the resolver itself (`LongMatch.kt` is 40 lines) and was wrong about
everything around it. Resolving in `findOption`/`findFlag` is precisely what does *not* work: ambiguity is a
property of everything a token can reach, so the three independent lookups had to become one resolution over
one pool, and the built-in pre-strip in `klap/Parser.kt` had to resolve through the same matcher. Two review
rounds were needed to get the pool right, from opposite sides (see §5.8's neighbour, §5.10). Two decisions
the estimate could not have contained: `--help-all` matches exactly and never by prefix, and a long declared
anywhere in a tree declines an abbreviation on behalf of its siblings. Measured effect: §5.10.

**14 (was 15). Expose the file-completion sentinel to providers. Unblocked a sub-case of 1 tool. Invasiveness: trivial.
LANDED in `922271e` and `4e0aaf5`, 2026-08-03.**

*As proposed:* a `CompletionScope.files(prefix: String = "")` call, so a `.completeWith` provider can answer
"complete files
here, after this literal prefix". `COMPLETE_FILES` is `internal` (`Completion.kt:92`) today. That alone
restores `dd if=<TAB>`, and it generalizes to any hand-rolled operand grammar.

*As landed:* `CompletionScope.completeFiles(nonPathPrefix: String = "")`, and the estimate's `prefix`
parameter turned out to be load-bearing rather than a convenience: the first implementation omitted it and
the headline case silently failed in zsh and PowerShell, which hand the whole token to native completion.
The constant stayed `internal` and its value changed, which is the point: the public surface is the
function. "Trivial" held for the klap side and not for verification, which needed real ptys in three shells
and could not cover the fourth. Measured effect: §5.17.

### Gaps I recommend **not** closing

These are legitimate results, not deficiencies in klap. **Two of the five were overruled on 2026-08-03**,
and both are marked below; the recommendation is kept rather than deleted, since being wrong twice about
what to refuse is itself the finding.

- **find's expression grammar.** Ordered boolean operators, parenthesised grouping, prefix negation of a
  *neighbouring* holder, and terminator-delimited capture are not a parser configuration; they are a parser.
  Growing an expression AST into a declarative binder would compromise the property that makes klap's help,
  docs and completion trustworthy, namely that the declaration *is* the grammar. The right answer for find is
  item 9 plus item 6 (was 10 plus 7): give a tool a clean seam to own its own tokenizing, keep klap's typed
  binding for the `-P`/`-L`/`-H`/`-D`/`-O` prefix that does fit, and let the tool parse its own expression.
  Recording find as `not-expressible` is the correct outcome, and `db15fd4` does not disturb it: bare `find`
  and every predicate-only line now parse, but `find . -name '*.kt'` still dies at `unknown option '-n'`.
  **Stands, and the seam it names now exists.** Item 9 landed as `optionsEndAtFirstOperand` (§5.14), so a
  tool can own its own tokenizing with no `--`; item 6 landed only in the `lastWins` shape, which does not
  reach an expression grammar and was never going to. find stays `not-expressible`, for the same reason.
- **§5.16 single-dash multi-character options** — **decided 2026-08-02, out of scope.** This section
  previously ranked it 15th and open. It is now closed as a non-goal, for reasons the find bullet above only
  half-covers: beyond not unblocking find, the shape is *lexically ambiguous* with a short cluster, so
  supporting it would make a token's meaning depend on the declared input set rather than on its own shape.
  That property is what lets globals be stripped before a command is resolved and lets completion walk an
  unbound line. Full reasoning at §5.16.
- **dd's bare `key=value` operands as a third input kind.** A new `operand(...)` declaration matched by key
  would be a substantial addition to the model for a surface that appears in one tool in ten. The
  `.convert { }` plus `.completeWith { }` combination already recovers the parsing and most of the completion;
  what is genuinely lost is per-key help rows and per-key error names, which is a documentation cost, not a
  parsing one. Close the cheap sub-gap (item 14, was 15) and leave the input kind alone unless a second tool
  with the same shape appears. `db15fd4` closed dd's *other* sub-gap for free: bare `dd` now parses and usage
  reads `[operand...]`, matching dd's own `dd [OPERAND]...` in shape if not in typography.
  **Stands, and was executed exactly as written on 2026-08-03:** item 14 landed, the input kind did not, and
  §5.17 now records the shape as a declared non-goal rather than a pending gap. The corpus is fourteen tools
  now rather than ten, and dd is still the only one with this shape.
- **Traditional dashless bundling (`tar cvf a.tar`).** One tool, and GNU tar itself documents the form as
  obsolescent. A `dashlessBundles = true` switch would put a second tokenizer mode into `sift` for a syntax
  that is being retired.
- **Conditional positional arity (§5.13).** ~~The action is the right layer for a cross-input rule, and klap's
  README already says so. The real problem was that the parse-time error fired first, and **`db15fd4` fixed
  the worst instance of that**: `rm` now declares the loosest arity, reaches its action at zero operands, and
  reproduces both `rm` (exit 1, message) and `rm -f` (exit 0, silent) exactly. A `requiredUnless(opt)`
  combinator would put option state into the positional binder, which is a dependency direction worth
  refusing. The residual cost is documentation, not enforcement: `--help` cannot say the operand is
  conditionally required (see §5.13's three surviving costs).~~
  **OVERRULED on 2026-08-03.** `.requiredUnless(input)` shipped, and so did `.absentWhen(input)`, and the
  dependency direction this bullet named is exactly what they introduce: `bindPositionals` now takes the
  sift. Three things the refusal got wrong. The residual cost was *not* only documentation: a conditional
  slot that stays declared is the `chmod --reference=r notes.txt` trap, where `.optional()` builds cleanly
  and then swallows the first FILE, which is a silent wrong binding rather than a missing sentence. The
  dependency direction is narrower than it sounds, because the binder reads `supplied()`, the same predicate
  the constraint checks and the completion planner already used, so no new definition entered the tree. And
  "the action is the right layer" cost `rm`, `chmod`, `cp` and `mv` four hand-written checks that `--help`
  could not see; all four are gone. The bullet was right about one thing, which is why §5.13b is still open:
  a *maximum* (`cp -T`) genuinely does not belong in a cardinality. Full record at §5.13.

## 9. Reproducing this

**As of 2026-08-03**, the ten stubs are fourteen Gradle fixture modules with parity suites, so the gate is
the ordinary test gate and the "compiled but never executed" caveat below is closed:

```
./gradlew check                                          # klap jvmTest + linuxX64Test + all 14 fixtures
grep -rnE "^\s*(//|\*)\s*KLAP-GAP" example/*/src/ | wc -l                    # 30 markers (was 48 before revalidation)
grep -rn "acceptsLoosely\|rejects(" example/*/src/test/kotlin/ | wc -l       # 188 lines, of which 71 diverge
```

Both counts were re-measured on 2026-08-03 and are broken down at §1 and §3.2. The bare
`grep -rn "KLAP-GAP"` the original commands used overcounts, because a marker's own cross-references match
it; the anchored form above counts markers only.

The original commands, kept because they are what the numbers above were originally produced by:

```
./gradlew :example:compileKotlinLinuxX64 --rerun-tasks   # the gate the ten stubs must keep passing
./gradlew build                                          # klap's own jvmTest + linuxX64Test
./gradlew :klap:allTests --rerun-tasks                   # 1072 tests, incl. VariadicPositionalArityTest
grep -rn "KLAP-GAP" example/src/commonMain/kotlin/com/fromwau/example/study/   # 75 markers
```

~~The stubs were committed in `f9ff0f8` and the compile task should still be wired into CI before any of §8
is attempted, since they are the only regression surface for a parser change of this kind. **They are
compiled but never executed** by any repo task, which is why every behavioural claim in §3.1 had to be
produced by a throwaway probe (§2.4). Wiring the ten factories into a real test under the study directory
would turn the whole document from reasoned to demonstrated, and would have caught the pre-`db15fd4` §7.2
error the moment it was written.~~ **Done.** Every tool is a module with a `ParitySuite`, the suites run
under `./gradlew check`, and the total at the close of the gap-closure branch is **1,726 tests green**
(klap 833 per target x 2 targets, fixtures 58, ergonomics study 2).
