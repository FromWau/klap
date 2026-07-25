# Completion descriptions

**Status:** Implemented on `master` (commits `32bbc64..87b821a`, 2026-07-28). The design below shipped as
written and is kept here as the design of record; the Follow-up sections at the end are the remaining open
work (not implemented).
**Date:** 2026-07-28

## Goal

Let shell-completion candidates carry a human-readable description shown next to the value, so
`klapExample rm <TAB>` can offer `1  Buy Beer` instead of a bare `1`, and `klapExample <TAB>` can offer
`rm  delete a task`. The value is still what gets inserted; the description is display-only.

## Locked decisions

1. **Source of descriptions:** both automatic and explicit. Static subcommand and option/flag candidates
   automatically surface their declared `help` text as the description; dynamic `completeWith` providers can
   attach a description per candidate.
2. **Provider API:** a `candidate()` DSL scope. The `completeWith` lambda becomes a receiver scope on which
   the provider calls `candidate(value, description)`.
3. **bash:** value-only. zsh, fish, and PowerShell render the description; bash shows the value alone
   (its native menu cannot show a per-candidate description without fragile readline hacks). Documented, not
   worked around.

## Design

### 1. Candidate model + wire format

Introduce an internal value type:

```kotlin
internal data class Candidate(val value: String, val description: String? = null)
```

`Cli.completeCandidates(words)` (internal, in `internal/render/Completion.kt`) changes its return type from
`List<String>` to `List<Candidate>`. The value is what the shell inserts and what prefix-filtering matches;
the description is display-only.

The hidden `__complete` builtin prints one candidate per line in a tab-delimited wire format:

- no description: `value`
- with description: `value\tdescription`

The `COMPLETE_FILES` sentinel keeps its current bare-line form (no description). The line format stays
"printed literally, never re-eval'd" for the value; the description is likewise never inserted or executed.

Descriptions are sanitized before emission: any tab or newline in a description is collapsed to a single
space (a description sourced from a multi-line `help` must not break the one-candidate-per-line wire format).
Values already cannot contain whitespace/control characters for names, and provider values are the consumer's
responsibility as today.

### 2. Provider DSL (`CompletionScope`)

The existing `CompletionContext(current, words)` is replaced by a receiver scope that also collects
candidates:

```kotlin
@KlapDsl
public class CompletionScope internal constructor(
    public val current: String,        // the word being completed
    public val words: List<String>,    // all words typed so far (the partial command line)
) {
    /** Offer a candidate: [value] is inserted; [description] is shown beside it on shells that support it. */
    public fun candidate(value: String, description: String? = null)

    /** Convenience: offer several plain (description-less) values. */
    public fun candidates(values: Iterable<String>)
}
```

`completeWith` changes from returning `List<String>` to a `CompletionScope.() -> Unit` builder:

```kotlin
public fun <T> Arg<T>.completeWith(filterByPrefix: Boolean = true, provider: CompletionScope.() -> Unit): Arg<T>
public fun <T> Opt<T>.completeWith(filterByPrefix: Boolean = true, provider: CompletionScope.() -> Unit): Opt<T>
```

Usage:

```kotlin
argument("id").int().completeWith {
    tasks.forEach { candidate(it.id.toString(), it.title) }
}
```

`filterByPrefix` is unchanged in meaning and filters on the candidate **value** (never the description).
The spec's stored provider (`ValueSpec.complete`) changes shape accordingly; the planner runs the provider
against a fresh `CompletionScope`, collects its candidates, then applies prefix filtering.

There is no released version yet, so the old `completeWith` signature and `CompletionContext` are simply
replaced in place: no back-compatible parallel API, no consumer migration. The only in-tree caller is the
example, updated as part of this work.

### 3. Automatic descriptions from `help`

The `completeCandidates` planner attaches descriptions to the static candidates it already produces:

- **Subcommand-name completion:** each subcommand candidate's description is that command's `help`
  (`Display.description`), e.g. `rm  delete a task`. Hidden commands stay excluded, unchanged.
- **Option/flag-name completion** (`--<TAB>`, `-<TAB>` long expansion): each option/flag candidate's
  description is its `help`. Built-in options (`--help`, `--version`, `--json`, `--completion`, `--docs`)
  surface their own help text the same way.
- **Choices / enums:** value-only. A `.choice(...)` value or enum constant has no per-value help today, so
  these carry no description. (Per-choice help is out of scope; see Future.)
- **File slots (`.file()`):** unchanged — the `COMPLETE_FILES` sentinel has no description.

An empty or blank `help` yields no description (bare value line), so nothing regresses for CLIs that don't
set help text.

### 4. Per-shell rendering

Each renderer in `Completion.kt` (root) splits each `__complete` line on the first tab into `value` and
optional `description`, then renders using that shell's native description mechanism:

- **bash:** use `value` only; discard the description (decision 3). The existing `compgen`/`COMPREPLY`
  and `COMPLETE_FILES` mapping are unchanged.
- **zsh:** feed `value` + `description` to `compadd -d` (or `_describe`), rendering `value  -- description`.
- **fish:** fish's completion functions already read `value\tdescription` natively, so the tab line maps
  almost directly; ensure the function emits the tab form.
- **PowerShell:** build a `CompletionResult` with `toolTip = description` (and `listItemText = value`).

The value is still inserted verbatim and never re-evaluated; the description only ever reaches the shell's
display path.

### 5. Components (isolation)

- `Candidate` — internal data type (value + optional description). One responsibility: model a candidate.
- `CompletionScope` — public provider receiver: exposes `current`/`words`, collects candidates via
  `candidate()`/`candidates()`. Replaces `CompletionContext`.
- `completeCandidates(): List<Candidate>` — the planner: produces static candidates (with auto-help
  descriptions) and runs providers; unchanged routing/positional-counting logic, richer return type.
- wire encode: `Candidate -> line` (`value` or `value\tdescription`, sanitized).
- four shell renderers — each decodes `value\tdescription` and renders per shell.

## Public API changes (surface)

- **New:** `CompletionScope` (public class) with `current`, `words`, `candidate(value, description?)`,
  `candidates(values)`.
- **Changed:** both `completeWith` overloads take `CompletionScope.() -> Unit` instead of
  `(CompletionContext) -> List<String>`.
- **Removed:** `CompletionContext` (subsumed by `CompletionScope`).
- No change to `argument`/`option`/`flag`/`choice`/`enum`/`file` signatures; those gain descriptions
  automatically via the planner.

## Edge cases & safety

- Description containing a tab/newline → collapsed to a single space before emission (protects the wire
  format); leading/trailing whitespace trimmed.
- Blank/empty description → treated as absent (bare value line).
- Description is display-only: never inserted, never re-evaluated — the existing "print literally" safety
  for values is preserved; descriptions ride the shell's own description channel.
- `COMPLETE_FILES` and file completion unaffected (no description).
- Prefix filtering matches on `value` only, so a description word never spuriously keeps/drops a candidate.

## Testing

- Wire format: `Candidate("1","Buy Beer")` → `1\tBuy Beer`; `Candidate("1")` → `1`; a description with an
  embedded tab/newline is collapsed.
- Auto-help: subcommand-name completion carries each command's `help`; option/flag-name completion carries
  each option's `help`; built-ins carry theirs; a command with no help yields a bare value.
- Provider DSL: `completeWith { candidate("1","Buy Beer") }` yields the described candidate; `candidates([...])`
  yields description-less ones; prefix filter narrows on value not description; a provider that throws still
  degrades to no candidates (existing runCatching contract).
- Per-shell snapshots: bash renders value-only; zsh/fish/powershell render value + description; the
  `COMPLETE_FILES` path is unchanged on every shell.
- Example: `rm <TAB>` / `done <TAB>` show `id  title`.

## Out of scope / future

- Per-choice / per-enum-constant descriptions (would need a `help` on choice values).
- A bash workaround to display descriptions.
- Rich/colored description formatting.

## Files touched (estimate)

- `internal/render/Completion.kt` — `Candidate`, `completeCandidates` return type, auto-help descriptions,
  wire encode.
- `Completion.kt` (root) — four shell renderers decode + render descriptions.
- `Converters.kt` — `completeWith` overloads take `CompletionScope.() -> Unit`.
- `Completion.kt` (public) / wherever `CompletionContext` lives — introduce `CompletionScope`, remove
  `CompletionContext`.
- `internal/spec/HolderSpec.kt` — `ValueSpec.complete` provider type.
- `Invocation.kt` / `ShowCompleteCandidates` — carry `List<Candidate>` if it holds the rendered list.
- Tests: `CompletionTest.kt` (+ any test asserting `completeCandidates` as `List<String>`).
- `example/.../Main.kt` — migrate providers to `candidate(id, title)`.

## Follow-up: QA pass #5 findings (independent of this feature) — ALL RESOLVED

Six confirmed, adversarially-verified findings from the 8-lens QA sweep. All are now fixed (the two
negation-collision items in commit `a6d074d`; the remaining four in the QA-pass-5-backlog commit).
Kept here for the record.

### Medium (resolved)

- RESOLVED: **Completion misroute at a hidden option's value cursor** (`internal/render/Completion.kt`).
  `matchingValueOption` filtered hidden options out, so at a hidden value-option's cursor it fell through
  to positional completion. Fix: `matchingValueOption` now includes hidden options (a hidden option is
  still parseable, so its value must complete once its name has been typed).
- RESOLVED (`a6d074d`): both `--no-X` negation-collision items, local-negatable-vs-global and
  negatable-global-vs-sibling-global, are rejected at build time by the reworked `validateGlobalCollisions`
  (a single effective-long-token set covers both directions).

### Low (resolved)

- RESOLVED: **`EncodeFailed` `--json` envelope skipped `stripTerminalEscapes`**
  (`internal/render/ErrorRendering.kt`). Now stripped, matching every sibling error path.
- RESOLVED: **`RESERVED_SECTIONS` omitted `"Examples"`** (`BuilderValidation.kt`). `"Examples"` is now
  reserved, so a user section titled `Examples` is rejected at build instead of rendering a duplicate heading.
- RESOLVED: **A `.default(v)` whose `toString()` throws escaped `--help`/`--docs`**
  (`internal/render/Help.kt`). `ValueSpec.display()` now guards `value.toString()` with a `<unprintable>`
  placeholder, upholding the never-throw-except-at-the-action-seam contract.

### Refuted (recorded so they are not re-raised)

- Attached-option `ctx.words.last() != ctx.current`: by design (`current` is the split partial, `words`
  mirrors `COMP_WORDS`); a correct provider filters on `current`. A doc-wording nit at most.
- Narrow-terminal wrap overflow below ~24 columns: the `WRAP_FLOOR(20)` floor is deliberate ("don't wrap
  into a sliver"); bounded overflow is the intended tradeoff.
- Sealed subtype serialized without a `type` discriminator: standard `kotlinx.serialization` plus
  reflection-free behavior; `action<Base> { }` is the documented escape hatch.

## Follow-up: QA pass #6 deferred items (need a larger refactor, not a spot fix)

Two confirmed findings from pass #6 whose correct fix is a real design change, not a surgical edit. Both
were consciously deferred (commit `dc780af` closed the rest of pass #6); recorded here so a repeat sweep
does not re-raise them as fresh, and so the eventual fix has the analysis to hand. The first is now
**fixed**; the second is still open.

- **Negatable global flag polarity is clobbered when its short is in a mixed cluster — FIXED 2026-08-02.**
  (`internal/parse/Parser.kt`, `GlobalAccumulator.hitFlag`). `-fv --no-verbose` with a negatable global
  `verbose`/`v` read `true`, violating last-occurrence-wins (`--no-verbose` is textually last). Root cause:
  `siftGlobals` resolves negations in one positional pass but CANNOT parse mixed clusters (it holds only the
  global specs, not the command's locals), so it defers them; the per-command sift then parses the cluster
  and `hitFlag` unconditionally set polarity `true`, with no knowledge of the cluster's position relative to
  the already-resolved `--no-verbose`. Pathological trigger (negatable *global* short, inside a *mixed*
  local+global cluster, alongside a separate `--no-`/`--` of the same flag).

  The fix threads the argv index through both passes, as the analysis predicted it would have to.
  `siftGlobals` now returns a `GlobalPreStrip` carrying a `positions` list parallel to `cleaned` (a single
  `keep()` helper is the only way to append to either, so they cannot drift), `parse()` drops from it in
  lockstep with the subcommand walk and hands the remainder to `Command.sift`, and a global negatable's
  polarity is stored as `Polarity(on, position)` — `hitFlag` keeps its observation only when its position is
  at or past the stored one. Positions are optional (`null` = unordered, later write wins), so completion,
  which orders nothing, is unaffected. Note two fixes that look right and are not: *skipping* an already-set
  polarity breaks the mirror `--no-verbose -fv` (which must read `true`), and deriving position from the
  segment index cannot work at all, because `siftGlobals` REMOVES the long-form token it consumed — it has no
  segment index to be compared against. `NegatableGlobalPolarityTest` pins both.

- **Shared-holder converter aliasing is type-unsound** (`Converters.kt` / `ConverterScope`). `val b =
  option("v"); b.choice("a","b"); b.int()` compiles, silently overwrites the shared spec's converter, and
  the first-typed accessor's static type no longer matches the bound value (native SIGABRT, JVM silent wrong
  value). It cannot be closed with a simple "converter already applied" guard: converters *compose by design*
  (`.choice().map { }`, `.map { }.map { }`, `.choice().int()` are documented and tested), so that guard is
  indistinguishable from legal chaining. A correct fix needs wrapper-generation tracking: a generation counter
  on `ValueSpec`, bumped by each terminal transform, plus a snapshot threaded through the returned
  `Arg`/`Opt` so a call on a superseded (aliased) wrapper is rejected while a call continuing the fresh chain
  is allowed. That touches `HolderSpec.kt` and `Arg.kt`, not just `Converters.kt`. (Pass #6 did add the
  previously-missing `Opt.required()/default()/multiple()` cardinality aliasing guards, which needed no new
  state.)

## Follow-up: unified color palette — SUPERSEDED, shipped

This section sketched the color work and left `--color` / `--no-color` versus a git-style
`--color=auto|always|never` as an open question. That question was settled in favour of the single
git-style option, and the whole feature shipped. The design of record is
`2026-07-28-color-palette-design.md`; the sketch is removed rather than kept, because it described a
`--no-color` flag that was never adopted and would mislead anyone reading this file first.

One loose end this sketch listed is closed: `NO_COLOR` now disables only on a present AND non-empty value,
per no-color.org.

The other is **only half closed**, and an earlier revision of this paragraph wrongly said otherwise. The
`Ansi` code constants did collapse into the `Style` constants, so there is one source of ANSI codes. But
`HelpStyle.paint` still exists (`internal/render/Help.kt:232`) and still applies styles as
`bold.render(text, color)`, passing the enabled flag by hand rather than "through an internal palette"
as `2026-07-28-color-palette-design.md` §4 specifies. And §4 also lists `ErrorRendering.kt` as chrome,
which applies no colour at all. See `2026-08-02-chrome-palette-design.md`.

The palette went undemonstrated until 2026-08-02, when the example gained colorized output through a
`ColorScope` extension — see `2026-08-01-completion-value-scope-design.md` §6.
