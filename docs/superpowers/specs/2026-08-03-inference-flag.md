# Draft spec: one switch for prefix inference

**Status:** Implemented on `inference-flag` (commits `269e4c0..6ef6849`, 2026-08-04). What shipped differs
from the draft below in several places; the **Corrections** section records each one, and `docs/guide.md`
carries the current account. Where this document and the shipped code disagree, **the code governs.**
**Motivation:** klap infers long-option prefixes but not subcommand prefixes. One switch should govern both.

---

## Corrections

These were found against the code and against the real tools (coreutils 9.11, git 2.55.0, curl 8.21.0,
rsync 3.4.4) while implementing this spec, and again while writing the documentation it asked for. Where
this document and the shipped code disagree, **the code governs.**

0. **§2.1's evidence for the enum was wrong; the conclusion survived for a different reason.** The table
   there claimed git abbreviates long options, citing `git --vers`. It does not: git's top-level parser
   handles a fixed option set and answers `unknown option: --vers`. Its *subcommand* parser
   (`parse-options`) does full GNU abbreviation with ambiguity reporting instead — `git branch --f`
   answers `error: ambiguous option: f (could be --force or --format)` — while `git stat` is refused with
   `'stat' is not a git command`. git is a genuine subcommand-options-yes / subcommands-no tool, which is
   exactly what the third mode exists for; the enum survives, only the cited evidence needed replacing.
1. **§4.3 was already implemented before this spec was drafted.** It asked for an unmatched `.choice()`
   value to suggest the nearest one. It already did: `CliError.InvalidChoice` carries a `suggestion`
   field, `Parser.kt` populates it with `suggest(raw, choices, ignoreCase = true)`, and
   `ErrorRendering.kt` renders it. No work item followed from this section.
2. **§5.2 overstated the test fallout.** Only `AbbreviationTest.kt` referenced abbreviation directly.
   `ErrorRenderingTest.kt` constructs its `AmbiguousOption` case directly rather than by parsing, so it
   was unaffected; `ParseOptionsTest.kt`, `CommandBuilderTest.kt`, `ParsePositionalsTest.kt` and
   `PosixConformanceTest.kt` exercised no abbreviation, with one exception: `PosixConformanceTest`'s single
   `extension_*` test, which by its own name pins klap's abbreviation *extension* rather than a guideline.
3. **§5.3 understated the fixture fallout badly.** It named only `tar` and `git` as abbreviation-dependent.
   The parity suites that actually asserted an abbreviated spelling were nine: `chmod`, `cp`, `head`, `ls`,
   `mkdir`, `mv`, `pacman`, `rsync`, `tar`. `git` was not among them — its `--vers` mention was a source
   comment, not a test assertion — and `rsync`'s abbreviation-shaped literals turned out, checked against
   the real 3.4.4 binary, to belong to `rejects` assertions: rsync does not abbreviate at all.
4. **§5.1 missed a fourth walk site.** "Completion always offers full spellings" is true of the
   candidates, but `Completion.kt` runs its own subcommand walk to decide which command the cursor sits
   in. Under `All`, an abbreviated subcommand earlier on the line has to route completion to the same
   command the parser reaches, or completion and parsing disagree.
5. **§6.1's claim that users "keep did-you-mean on every miss" was false for short prefixes.** `suggest()`
   bounds edit distance at `1..maxOf(2, candidate.length / 3)`; for `--help` that bound is `1..2`, and the
   edit distance from `--h` to `--help` is 3. Under `None` the single most-typed abbreviation, `--h`, would
   have produced a bare `unknown option '--h'` with no hint at all. Closed by making a prefix that reaches
   exactly one candidate suggest regardless of edit distance (`Suggest.kt`), which is why `--h` on a strict
   CLI now answers *Did you mean --help?* — see the guide's abbreviation section.
6. **The enum shipped as `Inference.Options`, not `LongOptions`.** §2.1 and §3 sketched `LongOptions`; the
   tables in this document are corrected below to the shipped name. Prose elsewhere here that still says
   `LongOptions` is describing the draft, not the API.
7. **§1's claim that "GNU's own documentation warns against abbreviations in scripts" was never verified,
   and did not survive a check.** A search across the GNU coding standards and the coreutils, tar and
   glibc/gnulib manuals while writing the guide found no such passage — the tar manual documents the
   *ambiguity* mechanism but not a scripting warning. The sentence has been removed from §1 below and was
   never carried into the guide; the forward-compatibility argument stands on the mechanism alone.

---

## 1. The inconsistency

Today, against `example/task-manager`:

```console
$ klapExample ls -n 1 --j          # binds --json
[{"id":1,"title":"Buy Beer",...}]

$ klapExample lis
error: unknown subcommand 'lis' for 'klapExample'. Did you mean list?
```

Both lines are "the user typed a partial name". One is inferred, the other is refused with a
suggestion. Nothing in the model explains the difference to a user — it is two separate decisions that
happen to disagree.

### What is actually implemented

- **Long options infer.** `resolveLong` (`internal/parse/NameMatch.kt`) resolves "the way GNU's
  `getopt_long` does": exact spelling wins outright, otherwise a prefix matching exactly one candidate
  resolves, and a prefix matching several is `CliError.AmbiguousOption`.
- **`EXACT_ONLY_LONGS = setOf("help-all")`** is carved out, so `--help-all` never claims the `--h` space
  that `--help` needs.
- **Shorts never infer.** A one-dash token is a cluster (`-jso` is `-j -s -o`), so there is nothing to
  abbreviate. This is not on the table and the spec does not change it.
- **Subcommands never infer.** They get did-you-mean instead, which also catches transpositions
  (`lsit` → `list`) that prefix matching cannot.
- **Both already have did-you-mean** on a miss (`--jsno` → `Did you mean --json?`).

Documented at `docs/guide.md:412` and `:430-431`. The asymmetry is deliberate, not an oversight.

### Not a POSIX question

POSIX guideline 3 makes an option name one character, so `--`-led names lie outside the guidelines
entirely (`docs/guide.md:1372`). Abbreviating them cannot be more or less POSIX-conformant. **Turning
inference off does not make klap "more POSIX"** — it only narrows the surface klap accepts. Any wording
in docs or release notes must avoid implying otherwise.

The real cost of inference is **forward compatibility**: `--j` works today, but adding a `--jobs` option
later breaks every script that used it. That is the honest argument for making it opt-in. (The claim this
paragraph originally made about GNU's own documentation warning against abbreviations in scripts did not
survive a check; see Corrections above.)

---

## 2. Proposal

A single root-level setting governs prefix inference everywhere.

### 2.1 Boolean or enum

The original proposal was `cli(enableInference: Boolean = false)`. **This spec recommends an enum
instead**, because a boolean collapses two axes that real tools genuinely separate:

| Tool | Long options infer? | Subcommands infer? |
|---|---|---|
| `tar`, `cp`, `find` (GNU) | yes | n/a |
| `git` | **no** at the top level (`git --vers` → `unknown option`); **yes** within a subcommand's own options (`git branch --f` → `ambiguous option: f`) | **no** (`git stat` fails) |
| `ip` (iproute2) | yes | **yes** (`ip a` → `ip address`) |

The overwhelmingly common shape is options-yes / subcommands-no — which is exactly klap's current
behaviour. A boolean makes that shape **unexpressible**: `true` forces subcommand inference on, `false`
turns option inference off. A `git`-shaped CLI could pick neither.

That matters concretely here, because `example/` contains fifteen fixtures whose entire purpose is to
reproduce real tools faithfully. Under a boolean, the `git` fixture could not be made accurate.

```kotlin
public enum class Inference {
    /** Nothing infers. A long option and a subcommand must be spelled in full. Misses still suggest. */
    None,

    /** Long options infer, the way GNU `getopt_long` does. Subcommands do not. */
    LongOptions,

    /** Long options, subcommands, and `.choice()` / `.enum<E>()` values all infer. */
    All,
}
```

### 2.2 Where it lives

**Recommendation: a `var` on `CliBuilder`, not a `cli()` parameter.**

```kotlin
cli("tasks") {
    inference = Inference.All
    ...
}
```

Reasons:
- `cli("greet", true)` is a boolean trap at the call site; the enum form would need naming anyway.
- Every other root-level knob in klap is already a `var` in the block: `version`, `author`, `epilogue`,
  `optionsEndAtFirstOperand`. A constructor parameter would be the only one of its kind.
- `cli(name)` currently has exactly one parameter, and keeping it that way keeps the front-page snippet
  in `README.md` unchanged.

**Root-only, no per-command override.** The ambiguity pool already spans siblings — a sibling's
`--sort-by` can make `sub1 --sor` ambiguous even where `sub1` alone is not (`docs/guide.md:461`). If
inference were per-command, the pool's meaning would differ depending on which command you asked, which
is incoherent. One setting for the tree.

### 2.3 Default

**The requested default is `None`** (inference opt-in everywhere).

This is the safest default and it is defensible: it is script-stable, it is explicit, and klap is
pre-release so no migration is owed. Users keep `-h` (the short) and did-you-mean on every miss.

**Resolved as requested — see §6.1.** There was real evidence for defaulting to `Options` instead, weighed
and set aside in favor of the script-safe default.

---

## 3. What `All` infers

| Token kind | `None` | `Options` | `All` |
|---|---|---|---|
| Long option (`--jso`) | no | yes | yes |
| Long option negation (`--no-pag`) | no | yes | yes |
| Subcommand (`lis`) | no | no | **yes** |
| Subcommand alias (an alias is just another spelling in the pool) | no | no | **yes** |
| `.choice()` / `.enum<E>()` value (`--priority hi`) | no | **yes** | yes |
| Short cluster (`-jso`) | never | never | **never** |

The exact-wins rule holds at every level and in every mode: a token that exactly matches a candidate
binds that candidate, even when it is also a prefix of others. That is what makes a pool containing both
`--sort` and `--sort-by` usable, and the same reasoning applies to `list` beside `listen`.

`EXACT_ONLY_LONGS` (`help-all`) keeps its carve-out under `Options` and `All`; under `None` it is inert
because nothing infers.

Note that `.choice()` / `.enum<E>()` already match **case-insensitively** today. Prefix-matching a value
is a genuine extension on top of that, and the two compose: under `Options`, `--priority hi` → `HIGH`.

---

## 4. Error model

### 4.1 New error cases

`CliError.AmbiguousOption` exists. `All` needs siblings:

```kotlin
public data class AmbiguousSubcommand(val typed: String, val possibilities: List<String>) : CliError
public data class AmbiguousValue(val input: String, val typed: String, val possibilities: List<String>) : CliError
```

Rendered in the established shape (`internal/render/ErrorRendering.kt`):

```
error: subcommand 'st' is ambiguous; possibilities: 'stash' 'status'
error: value 'h' for --priority is ambiguous; possibilities: 'high' 'highest'
```

### 4.2 Suggestion still applies, in every mode

Inference and suggestion are complementary, not alternatives:

- **Inference** rescues prefixes only.
- **Suggestion** rescues any near-miss, including transpositions (`lsit` → `list`) that are not prefixes
  of anything.

So the resolution order for an unmatched token is: exact → (prefix, if the mode allows) → suggest. Under
`None` the middle step is skipped and the behaviour is exactly today's subcommand behaviour, applied
uniformly.

### 4.3 Suggestion for values

The original proposal asks for suggestions on arguments too. Today an unmatched `.choice()` value gives
`CliError.InvalidChoice`, which lists the valid choices but does not point at the nearest one. Adding
did-you-mean there is a small, independent improvement that should ship with this work regardless of
mode, since `suggest()` (`internal/parse/Suggest.kt`) already exists and is already used for options and
subcommands. Values are the only one of the three that does not use it.

---

## 5. Impact

### 5.1 Unaffected

- **Completion** always offers full spellings, in every mode. Nothing to change in the emitted scripts or
  in `completeCandidates`.
- **Help and docs rendering** — no rendered text depends on inference.
- **POSIX conformance claims** — see §1. No mode changes conformance; `PosixConformanceTest` should keep
  passing untouched, and if it does not, that is a finding.

### 5.2 Tests that must change

Files that reference abbreviation or ambiguity today:

- `klap/src/commonTest/kotlin/com/fromwau/klap/AbbreviationTest.kt` — the dedicated suite; becomes
  parameterised over the three modes
- `ParseOptionsTest.kt`, `CommandBuilderTest.kt`, `ErrorRenderingTest.kt`, `ParsePositionalsTest.kt`,
  `PosixConformanceTest.kt`

New coverage required: subcommand inference and its ambiguity error, value inference and its ambiguity
error, exact-wins at the subcommand level (`list` beside `listen`), an alias participating in the pool,
and each mode's boundary (that `LongOptions` really does refuse a subcommand prefix).

### 5.3 Fixtures — this is a feature, not a chore

If the default is `None`, every fixture relying on abbreviation must set `inference` explicitly. Known
dependants: `tar` (`TarParityTest` binds `--cr` → `--create`, and `bindsLoosely("--excl", ...)`), `git`
(`--vers`).

**Having to state it is the point.** A fixture's job is to claim "this is what the real tool does", and
the real tools differ: GNU `tar` abbreviates long options, `git` abbreviates options but not
subcommands, `ip` abbreviates both. Making each fixture declare its mode turns an invisible global
assumption into fifteen explicit, testable claims about real software. That is a strict improvement in
what the parity suite proves.

---

## 6. Questions raised during drafting, now resolved

### 6.1 Default mode: `None`

Decided against the evidence in §2.1 that pointed toward defaulting to option inference: `Inference.None`
is opt-in everywhere, matching `Inference.kt`'s own KDoc and the shipped `CliBuilder` default. `-h` still
works; `--h` requires `Options` or `All`. The forward-compatibility argument in §1 was judged to outweigh
the corpus argument — every klap CLI's invocations stay script-stable unless the author opts in.

### 6.2 Values ride with `Options`, not `All`

Not a fourth mode. GNU tools abbreviate an option's *value* through the same mechanism they abbreviate its
*name* (`ls --color=al` is accepted; `ls --sort=n` reports `ambiguous argument`), so splitting the two axes
would draw a line real tools do not draw. `.choice()` / `.enum<E>()` values infer wherever long options do
— under `Options` and under `All` — and never under `None`. See the corrected §3 table.

### 6.3 `numericAlias`: no interaction, pinned by test

Short-side (`head -5` is shorthand for `-n 5`), so untouched by any mode. `InferenceModeTest.kt` runs the
same assertion across every mode (`Inference.entries`) to confirm it stays that way.

---

## 7. Sketch of the work

1. Add `Inference` enum + `var inference: Inference` on `CliBuilder`; thread it into `Command`/`Cli`.
2. Gate `resolveLong`'s prefix half on the mode (`internal/parse/NameMatch.kt`); exact half always runs.
3. Add prefix resolution to the subcommand walk in `Parser.kt`, gated on `All`, reusing `resolveLong`'s
   exact-wins-then-unique-prefix logic rather than reimplementing it.
4. Add `AmbiguousSubcommand` / `AmbiguousValue` + their rendering.
5. Add did-you-mean to `InvalidChoice` (independent of mode).
6. Parameterise `AbbreviationTest` over the modes; add the new coverage in §5.2.
7. Set `inference` explicitly in the fixtures that need it; make each one match the real tool.
8. Update `docs/guide.md` §Abbreviation and `example/README.md`'s lookup table.

Steps 2 and 3 are the only ones touching the parser; everything else is additive.
