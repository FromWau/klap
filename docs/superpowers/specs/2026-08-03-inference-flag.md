# Draft spec: one switch for prefix inference

**Status:** DRAFT, not implemented. Written 2026-08-03.
**Motivation:** klap infers long-option prefixes but not subcommand prefixes. One switch should govern both.

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

- **Long options infer.** `resolveLong` (`internal/parse/LongMatch.kt`) resolves "the way GNU's
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
entirely (`docs/guide.md:1321`). Abbreviating them cannot be more or less POSIX-conformant. **Turning
inference off does not make klap "more POSIX"** — it only narrows the surface klap accepts. Any wording
in docs or release notes must avoid implying otherwise.

The real cost of inference is **forward compatibility**: `--j` works today, but adding a `--jobs` option
later breaks every script that used it, with `ambiguous`. GNU's own documentation warns against
abbreviations in scripts for this reason. That is the honest argument for making it opt-in.

---

## 2. Proposal

A single root-level setting governs prefix inference everywhere.

### 2.1 Boolean or enum

The original proposal was `cli(enableInference: Boolean = false)`. **This spec recommends an enum
instead**, because a boolean collapses two axes that real tools genuinely separate:

| Tool | Long options infer? | Subcommands infer? |
|---|---|---|
| `tar`, `cp`, `find` (GNU) | yes | n/a |
| `git` | yes (`--vers`) | **no** (`git stat` fails) |
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
`--sort-by` can make `sub1 --sor` ambiguous even where `sub1` alone is not (`docs/guide.md:429`). If
inference were per-command, the pool's meaning would differ depending on which command you asked, which
is incoherent. One setting for the tree.

### 2.3 Default

**The requested default is `None`** (inference opt-in everywhere).

This is the safest default and it is defensible: it is script-stable, it is explicit, and klap is
pre-release so no migration is owed. Users keep `-h` (the short) and did-you-mean on every miss.

**Open question — see §6.1.** There is real evidence for defaulting to `LongOptions` instead. Recording
it here rather than deciding unilaterally.

---

## 3. What `All` infers

| Token kind | `None` | `LongOptions` | `All` |
|---|---|---|---|
| Long option (`--jso`) | no | yes | yes |
| Long option negation (`--no-pag`) | no | yes | yes |
| Subcommand (`lis`) | no | no | **yes** |
| Subcommand alias (an alias is just another spelling in the pool) | no | no | **yes** |
| `.choice()` / `.enum<E>()` value (`--priority hi`) | no | no | **yes** |
| Short cluster (`-jso`) | never | never | **never** |

The exact-wins rule holds at every level and in every mode: a token that exactly matches a candidate
binds that candidate, even when it is also a prefix of others. That is what makes a pool containing both
`--sort` and `--sort-by` usable, and the same reasoning applies to `list` beside `listen`.

`EXACT_ONLY_LONGS` (`help-all`) keeps its carve-out under `LongOptions` and `All`; under `None` it is
inert because nothing infers.

Note that `.choice()` / `.enum<E>()` already match **case-insensitively** today. Prefix-matching a value
is a genuine extension on top of that, and the two compose: under `All`, `--priority hi` → `HIGH`.

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

## 6. Open questions

### 6.1 Default mode — `None` or `LongOptions`?

`None` is requested and is the script-safe choice. But the evidence assembled in §2.1 points the other
way, and it should be weighed before implementing:

- Every GNU tool the fixtures model abbreviates long options. `LongOptions` is what the corpus says
  "normal" is.
- The stated problem is *inconsistency*. That is solved by making subcommand inference **available**
  (`All`), not by removing option inference. `None` as a default fixes the inconsistency by taking the
  feature away from everyone.
- Under `None`, `--h` stops reaching `--help` for every klap CLI. `-h` still works, so the loss is small
  — but it is the single most-typed abbreviation there is.

A middle position: default `LongOptions` (no behaviour change, matches the corpus), and document `None`
prominently as the recommended setting for any CLI whose invocations get committed to scripts or CI.

### 6.2 Should `All` really cover values?

Option and subcommand inference resolve a *name* the CLI author declared. A `.choice()` value is closer
to data. Prefix-matching `hi` → `HIGH` may be a surprise too far, and it interacts with the existing
case-insensitive match in ways that need thought (is `--priority h` ambiguous against `HIGH` alone?).
Splitting values into a fourth mode, or dropping them from this spec, are both reasonable.

### 6.3 Does `numericAlias` interact?

`head -5` is shorthand for `-n 5` via `numericAlias`. That is short-side, so it should be untouched — but
it needs an explicit test in each mode to confirm no interaction, because it is the one place a token's
meaning already depends on what the tree declares.

---

## 7. Sketch of the work

1. Add `Inference` enum + `var inference: Inference` on `CliBuilder`; thread it into `Command`/`Cli`.
2. Gate `resolveLong`'s prefix half on the mode (`internal/parse/LongMatch.kt`); exact half always runs.
3. Add prefix resolution to the subcommand walk in `Parser.kt`, gated on `All`, reusing `resolveLong`'s
   exact-wins-then-unique-prefix logic rather than reimplementing it.
4. Add `AmbiguousSubcommand` / `AmbiguousValue` + their rendering.
5. Add did-you-mean to `InvalidChoice` (independent of mode).
6. Parameterise `AbbreviationTest` over the modes; add the new coverage in §5.2.
7. Set `inference` explicitly in the fixtures that need it; make each one match the real tool.
8. Update `docs/guide.md` §Abbreviation and `example/README.md`'s lookup table.

Steps 2 and 3 are the only ones touching the parser; everything else is additive.
