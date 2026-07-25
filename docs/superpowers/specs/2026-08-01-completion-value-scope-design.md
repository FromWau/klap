# Completion value scope

**Status:** Implemented on `feat/completion-value-scope` (commits `ae1bc0f..7c7a01d`, 2026-08-01/02). The
design below is the design of record and has been amended in place wherever the implementation corrected
it — see "Where the design was wrong" for the four places that happened. The Out-of-scope list at the end
is the remaining open work, not implemented.
**Date:** 2026-08-01

## Goal

Give a `.completeWith { }` provider the same typed access to parsed inputs that `action { }` already has,
so a completion provider can read the CLI's own declared options instead of re-parsing raw words itself.

The motivating case is in `example/task-manager/src/commonMain/kotlin/com/fromwau/example/Main.kt`. The example declares
a global `--file` option pointing at the task store, and every action reads it through the accessor:

```kotlin
val storeFile = globalOption("file", "f", "path to the task store").default(DEFAULT_STORE_FILE).file()
fun ActionScope.taskStore() = TaskStore(Path(storeFile()))
```

A completion provider cannot do that, because `CompletionScope` carries only raw strings. So the example
today hand-rolls a 27-line `storeFileFromWords(words)` that re-implements global-option parsing for the four
token shapes (`--file V`, `--file=V`, `-f V`, `-fV`), with its own copy of the default — carrying the
comment *"This is very hack and defeats the whole purpose of klap."* It is also wrong in the cases it
does not cover (a `--file` inside a mixed short cluster like `-fv`, and `--` handling).

The machinery to fix this already exists one call away. `completeCandidates()`
(`internal/render/Completion.kt:30`) already resolves the target command and pre-strips globals via
`globalSpecs.siftGlobals(words.dropLast(1))` — then discards the sift result (`val (strippedHead, _)`) and
constructs `CompletionScope(current, words)` with strings only. Meanwhile `parse()` (`Parser.kt:184`) builds
a `Map<HolderSpec, Any?>` sink and freezes it into an `ActionScope`. This design runs that same binding on
the completion path.

Pulling the two paths together also fixes a routing bug the divergence had been hiding (§3).

## Locked decisions

1. **Read scope: globals + the resolved command's own typed inputs.** Not globals alone. `CompletionScope`
   resolves exactly what `ActionScope` resolves for the command under the cursor, so the two scopes never
   disagree about what an accessor means. This also enables sibling-input completion (see §6).
2. **Unresolved read throws, degrading to no candidates.** Accessors return `T`, identical to `ActionScope`.
   During completion the line is half-typed, so a required input may not be typed yet and a typed value may
   fail conversion; reading such an input throws, and `candidatesFor`'s existing `runCatching` turns that
   into zero candidates. No nullable-accessor variant, no `orNull()` escape hatch — they can be added later
   without breaking anything if a real need appears.
3. **Shared `ValueScope` supertype.** The accessors move to a common sealed base that both `ActionScope` and
   `CompletionScope` extend, so a helper like `taskStore()` is written once as a `ValueScope` extension and
   works in both. This is the decision that makes the feature worth having: without it every shared helper
   is written twice.
4. **`sift` is rewritten to accumulate-and-record**, the shape `siftGlobals` already uses, instead of
   returning at the first error. A half-typed line hits `sift`'s error paths constantly, and the alternative
   — spot-fixing the two shapes that occur most — leaves a second, subtly different token walk in the
   completion renderer forever. See §2.
5. **Completion strips the position-independent modifiers `parse()` strips** (`--json`, `--color <value>`)
   before its subcommand walk. It does *not* strip the terminal short-circuits (`--help`, `--help-all`,
   `--version`): those lines never run a command, so there is nothing downstream worth completing. See §3.

## Design

### 1. The scope hierarchy

New sealed base (new file `ValueScope.kt`) holding the resolved values and the four accessors, lifted
verbatim from today's `ActionScope`:

```kotlin
@KlapDsl
// No `internal constructor()` (the guard ColorScope used): a sealed class rejects it — "constructor must be
// private or protected in sealed class" — and does not need it, since sealed already confines subclassing
// to this module.
public sealed class ValueScope {
    internal abstract val values: Map<HolderSpec, Any?>

    // The subclass owns the WHOLE failure, not just its wording: an unbound read is a genuine error in an
    // action and expected control flow during completion, so the base must not fix the exception kind.
    internal abstract fun unbound(spec: HolderSpec): Nothing

    public operator fun <T> Arg<T>.invoke(): T = read(spec)
    public operator fun <T> Opt<T>.invoke(): T = read(spec)
    public operator fun Flag.invoke(): Boolean = read(spec)
    public operator fun CountFlag.invoke(): Int = read(spec)

    /** The one unchecked cast behind every accessor: the heterogeneous value map erases each holder's T. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> read(spec: HolderSpec): T =
        // containsKey, not a null fallback: a holder legitimately bound to null must read back null, not fail.
        if (values.containsKey(spec)) values[spec] as T else error(unboundMessage(spec))
}
```

`ActionScope` keeps today's message ("an accessor is only readable inside the `action { }` of the command
that declares it"). `CompletionScope` supplies its own, naming the completion reason: the input is not typed
yet, or its value failed to convert, so the provider yields no candidates.

`ActionScope` needs both `ValueScope` and the `Style` operators, and Kotlin allows one superclass. The
`internal abstract val values` forces `ValueScope` to be a class — verified against the compiler:

```
error: modifier 'internal' is not applicable inside 'interface'.
```

So `ColorScope` becomes the interface. It becomes a pure capability interface — two operator declarations,
no state — which keeps `colorEnabled` internal rather than widening it to public API:

**`sealed` interface, not a plain one.** `ColorScope` was an `abstract class` with an `internal
constructor()` — unimplementable outside the module by construction, the same guard `CommandBuilder`,
`ActionScope`, and `CompletionScope` all carry. A plain `public interface` would silently drop it, letting
a consumer implement a contract they cannot satisfy (`Style.render` is `internal`) and inheriting klap's
`@KlapDsl` marker into their own DSLs. Caught in review of this section, not in its first draft.

```kotlin
@KlapDsl
public sealed interface ColorScope {
    public operator fun Style.invoke(block: () -> String): String
    public operator fun Style.invoke(text: String): String
}

public class ActionScope internal constructor(
    override val values: Map<HolderSpec, Any?>,
    internal val colorEnabled: Boolean = false,
) : ValueScope(), ColorScope {
    override fun Style.invoke(block: () -> String): String = render(block(), colorEnabled)
    override fun Style.invoke(text: String): String = render(text, colorEnabled)
    internal fun withColorEnabled(enabled: Boolean): ActionScope = ActionScope(values, enabled)
}
```

`ColorScope` has exactly one production subclass and no README or spec mentions, so the blast radius is
`Style.kt`, `ActionScope.kt`, and `StyleTest.kt` (whose `scope(on)` helper becomes an `ActionScope`).

`CompletionScope` keeps `current`, `words`, `candidate()`, and `candidates()` unchanged, and gains the base:

```kotlin
@KlapDsl
public class CompletionScope internal constructor(
    public val current: String,
    public val words: List<String>,
    // Lazy: a Tab press whose slot has no provider must never run a user converter. NONE, not the default
    // SYNCHRONIZED — one completion resolves on one thread, so the lock would only cost.
    private val resolved: Lazy<Map<HolderSpec, Any?>>,
) : ValueScope() {
    override val values: Map<HolderSpec, Any?> get() = resolved.value
    internal val collected: MutableList<Candidate> = mutableListOf()
    // candidate() / candidates() unchanged
}
```

### 2. `sift` becomes accumulate-and-record

`Command.sift` (`internal/parse/Parser.kt:331`) returns `Result<Sifted, CliError>` and abandons the whole
walk at its first error. That is fine for a complete command line and wrong for one still being typed:
`list --status <TAB>` leaves `--status` dangling with no value, which is `MissingOptionValue` — and it is
the single most common completion shape.

`siftGlobals` already solved this for the pre-strip pass: it walks to the end, records the **first** error in
its result, and lets the caller decide whether that error matters. `sift` adopts the same shape:

```kotlin
internal class Sifted(
    val flags: Map<FlagSpec, Int>,
    val negations: Map<FlagSpec, Boolean>,
    val options: Map<OptionSpec, List<String>>,
    val positionals: List<String>,
    // The first hard syntax error hit while walking, or null. bind() raises it before binding anything;
    // completion ignores it and uses whatever the walk did manage to collect.
    val error: CliError? = null,
)

internal fun Command.sift(segment: List<String>, globalAcc: GlobalAccumulator? = null): Sifted
```

Its sole caller (`bind`, `Parser.kt:78`) keeps identical behavior — same error, still raised before any
binding:

```kotlin
val sifted = sift(segment, globalAcc)
sifted.error?.let { return Result.Error(it) }
```

Each of `sift`'s six error returns becomes a record-and-continue, mirroring `siftGlobals` token for token.
The first error wins (`if (error == null) error = ...`), so the reported error and its `suggest(...)`
computation are unchanged from today:

| Site | Today | After |
|---|---|---|
| `--flag=value` on a boolean flag | return `FlagTakesNoValue` | record; still `hit(flag, true)`; `i += 1` |
| `--no-flag=value` | return `FlagTakesNoValue` | record; still `hit(negated, false)`; `i += 1` |
| unknown `--long` | return `UnknownOption` | record; skip the token (**not** a positional); `i += 1` |
| `--opt` with no value | return `MissingOptionValue` | record; skip the token, bind nothing; `i += 1` |
| bad char in a short cluster | return `UnknownOption` | record; abandon the rest of the cluster; `advance = 1` |
| cluster option with no value | return `MissingOptionValue` | record; abandon the rest of the cluster; `advance = 1` |

The two `advance = 1` claims are the load-bearing ones, and they hold structurally rather than by
inspection: `advance` is written in exactly one place, on the successful option-value path, which
immediately sets `j = chars.length` and exits the loop — so no `break` can ever observe `advance == 2`.

Verified during implementation: these six advances are byte-identical to what `consumedPositionals`
already computes via `longOptionAdvance`/`shortClusterAdvance`, which is why deleting that copy (below)
is mechanical rather than a re-derivation.

An unknown option is skipped rather than demoted to a positional: demoting it would shift every later
positional into the wrong slot.

**This deletes the completion renderer's duplicate token walk.** `consumedPositionals`
(`internal/render/Completion.kt:158`) exists only because `sift` could not be run on a half-typed segment, so
it re-implements the same walk purely to count filled positional slots — its own doc comment says it
"mirrors `sift`'s token walk exactly". Once `sift` always returns, that count is just
`sifted.positionals.size`. That removes six private functions from the completion renderer
(`consumedPositionals`, `optionLookup`, `negatedBy`, `isUsableValue`, `longOptionAdvance`,
`shortClusterAdvance`, ~75 lines) and, more importantly, removes a standing drift risk: two walks that must
agree on short clusters, attached values, dash-led values, and `--`, kept in sync by hand. `flagLookup`
stays — `trailingValueOption` and `attachedValueOption` still use it.

The two walks are equivalent on every shape they can see, including the ones where sift now records an
error: an unknown long option advances one token and counts as no positional in both; an unrecognized short
cluster likewise; `--` ends options in both; a dash-led value (`-1m`) is a positional in both. The
implementation validates this against the existing completion suite before the old walk is deleted.

Cost: on a malformed command line the runtime parse now walks to the end of the segment instead of bailing
early. That is a pure token walk over argv, with no user code, so it is not measurable.

### 3. Completion's token view (routing bug fix)

`parse()` removes the position-independent modifiers before its subcommand walk — `--json` at `Parser.kt:76`,
then `--color` with its value at `Parser.kt:99` — so `mytool --color=never build` still resolves `build`.
`completeCandidates` never did, and only recognizes `--color` when it is the token under the cursor. So the
walk hits `--json` first, fails to match a subcommand, breaks at token 0, and completes against the **root**
instead of the typed subcommand. Measured against `completeCandidates` directly, on a tree whose `list`
command declares `--status`:

```
["list", "--st"]                  -> ["--status"]   correct
["--json", "list", "--st"]        -> []             wrong: root's options, none match
["--color", "never", "list", "--st"] -> []          wrong: same
```

The fix is to give completion the same token view, in the same order (`--json` first, so a `--json` sitting
between a space-form `--color` and its value is never mistaken for that value — the reason `parse()` and
`colorMode()` both order it that way):

```kotlin
val current = words.lastOrNull().orEmpty()
val head = words.dropLast(1)

// --color's own value is completed from the RAW head, BEFORE the strip below removes the flag and its
// value: afterwards no token would name it any more.
if (head.lastOrNull() == "--color") return colorModeCandidates(current)
if (current.startsWith("--color=")) return colorModeCandidates(current.removePrefix("--color="))

val (strippedHead, globalSift) =
    globalSpecs.siftGlobals(stripMetaOptionWithValue(stripToken(head, "--json"), "color"))
```

Two consequences worth naming:

- The first `--color` branch changes from `prev == "--color"` (derived from the post-`siftGlobals`, post-walk
  segment) to `head.lastOrNull()`, read straight off the raw words. It must move above the strip, or it can
  never fire. Reading the raw line is also the more direct statement of what that branch means.
- `stripToken` and `stripMetaOptionWithValue` are `private` in `Parser.kt` and become `internal`. Both stay
  internal; no public surface changes.

The terminal short-circuits are deliberately left alone. `--help`, `--help-all`, and `--version` make the
line print and exit, so there is no "rest of the command" whose completion could matter; `parse()` does not
strip them either (it returns on them), and inventing a completion-only rule would be a second divergence of
exactly the kind this section removes.

Stripping also keeps `--json`/`--color` out of the segment `sift` walks, so neither shows up as a spurious
`UnknownOption` in the recorded error, and neither perturbs the positional count.

### 4. Where the values come from

New internal entry point in `internal/parse/Parser.kt`, beside `bind` and `bindGlobals`:

```kotlin
internal fun Cli.completionValues(
    cmd: Command,
    sifted: Sifted,
    globalAcc: GlobalAccumulator,
): Map<HolderSpec, Any?>
```

It mirrors `parse()`'s bind phase, so completion and runtime resolve identically:

1. Bind the command's own flags/options and positionals from `sifted` under `BindPolicy.Lenient`.
2. `bindGlobals(globalSpecs, globalAcc.toGlobalSift(), sink, BindPolicy.Lenient)`.

`sifted.error` is ignored — that is the whole point of §2. Whatever the walk collected before and after the
malformed token is still bound, so `done --bogus 3 <TAB>` resolves `id = 3` **and** every global, where a
first-error-wins `sift` would have yielded nothing but globals.

The caller, `completeCandidates`, threads two lazies through:

```kotlin
val globalAcc = globalSift.accumulator(globalSpecs, version)
// The token walk: cheap and pure, but only the positional/value branches need it, so it stays lazy.
val sifted by lazy(LazyThreadSafetyMode.NONE) { cmd.sift(segment, globalAcc) }
// The bind: runs user converters and validators, so it must not happen on a Tab press with no provider.
val values = lazy(LazyThreadSafetyMode.NONE) { completionValues(cmd, sifted, globalAcc) }
```

`positionalIndex` becomes `sifted.positionals.size`, and `values` is handed to every `CompletionScope` the
planner builds. Passing `globalAcc` (not the raw `GlobalSift`) is what lets a global buried in a mixed short
cluster (`-fv`) resolve in completion exactly as it does at runtime: `sift` tops the accumulator up, and
`bindGlobals` reads it afterwards, the same order `parse()` uses.

No `dropLast` workaround is needed anywhere: a dangling option under the cursor is now simply a recorded
error that completion ignores.

### 5. Lenient binding

The existing `deferRequired: Boolean` parameter on `bindFlagsAndOptions` is the same axis as leniency, so it
generalizes into a three-value policy rather than gaining a second boolean beside it:

```kotlin
/**
 * How a bind reacts to an input it cannot satisfy. [Strict] fails the parse (a leaf's own inputs).
 * [DeferRequired] returns the error for the caller to judge later (globals, whose absence may not matter).
 * [Lenient] leaves the input unbound and carries on (completion, where the line is still being typed).
 */
internal enum class BindPolicy { Strict, DeferRequired, Lenient }
```

`bindFlagsAndOptions` takes it in place of `deferRequired`; `bindPositionals` takes it as a new parameter
(today it is always strict). `Strict` and `DeferRequired` behavior is unchanged, so `parse()` is unaffected.

Under `Lenient`, no input's failure may abandon the bind — a bad `--priority zzz` must not blank out
`--file`:

| Situation | Lenient outcome |
|---|---|
| Required **scalar** option/argument absent | left unbound (reading it throws) |
| Converter fails or `validate` rejects | that spec left unbound, walk continues |
| `multiple(min = n)`, fewer than `n` typed — **including none** | binds what is typed, possibly an empty list |
| Surplus positionals (`TooManyArguments`) | ignored |
| Option/argument with a default, absent | binds the default, as always |
| Flag | binds normally; flags never fail |

**A variadic binds the same way whichever kind it is.** The first draft of this table said only "binds
however many are typed so far", and the two binders implemented that differently: `bindFlagsAndOptions`
bound `[]` for an absent `multiple()` option, while `bindPositionals` skipped the bind entirely for an empty
slice, leaving an absent `multiple()` argument unbound. Same declaration shape, opposite outcomes, and
nothing a provider author could use to tell them apart. The rule is **bind what is typed, including the
empty list, for both**: "nothing typed yet" truthfully *is* an empty list, it keeps a provider that reads
the input alive rather than aborting it, and it matches the non-null `List<T>` the accessor promises. The
cost is that inside a provider a `multiple(min = n)` can be shorter than `n`, which an action can never
see — so the scope's KDoc tells provider authors not to assume the arity.

The defaults line matters most: it is why `storeFile()` reads `DEFAULT_STORE_FILE` in a provider when
`--file` was not typed, with no fallback constant duplicated in consumer code.

### 6. What the example demonstrates

`storeFileFromWords` and its TODO are deleted, and the two `taskStore()` helpers collapse into one:

```kotlin
fun ValueScope.taskStore() = TaskStore(Path(storeFile()))
```

Because local inputs resolve too, `tag rm` gains completion that reads its own sibling argument:

```kotlin
val id = argument("id", "task id").int().completeWith { taskIdCandidates() }
// Only a tag the task actually carries can be removed; an id not yet typed leaves id() unbound, so the
// provider throws and yields no candidates — which is the right answer for `tag rm <TAB>`.
val tag = argument("tag", "tag to remove").completeWith {
    val tasks = taskStore().load().getOrElse { return@completeWith }
    tasks.find { it.id == id() }?.tags?.let { candidates(it) }
}
```

`klapExample tag rm 3 <TAB>` then offers only task 3's tags, and
`klapExample --file other.json tag rm 3 <TAB>` reads them from the right store — the thing that is
impossible today.

**Shipped alongside, on the same branch.** Reviewing the `ColorScope` question surfaced that the example
never used colour at all, despite the switch being fully resolved (`--color`/`NO_COLOR`/TTY) and handed to
every action. A survey found it also demonstrated no `flag()` whatsoever — plain, `.count()`, or
`.negatable()` — nor `globalFlag`, `.range()`, or `hidden`. So the example additionally gained: task
rendering as a `ColorScope` extension, a counting global `--verbose` driving a detail ladder, a plain
`--done` flag on `add`, `--limit` with `.range(1..100)`, and a hidden `where` command. `.negatable()` was
deliberately left undemonstrated — a task manager has no flag that reads honestly as `--no-x`, and
contriving one would turn the example into a feature catalogue.

The most valuable thing that fell out of it is unrelated to colour: `--limit` lives in the action while
`--verbose` lives in `human`, so `--json list -n 1` truncates the payload to one object while
`--json list -vv` is byte-identical to `--json list`. That is the presentation-versus-data line the README
previously described only in the abstract, and putting a display concern on the wrong side of it silently
corrupts `--json` for every script consuming it.

### 7. Components (isolation)

- `ValueScope` — public sealed base: owns the value map and the four accessors. One responsibility: typed
  reads of bound inputs. Knows nothing about actions, completion, or color.
- `ColorScope` — public capability interface: "styles resolve here". No state.
- `ActionScope` — `ValueScope` + `ColorScope`, plus the color switch. Unchanged from the consumer's view.
- `CompletionScope` — `ValueScope` + the candidate collector, plus `current`/`words`. Takes its values as a
  `Lazy` so an unread map is never built.
- `Sifted` / `sift` — one token walk, total: never fails, records the first error for the caller to judge.
  Now the single source of truth for how a segment splits, for both parsing and completion.
- `BindPolicy` — internal enum naming the three failure reactions the binders already needed.
- `Cli.completionValues(cmd, sifted, globalAcc)` — internal: the lenient mirror of `parse()`'s bind phase.
  Pure, no I/O, testable directly.
- `completeCandidates` — same routing and slot rules; loses its duplicate token walk, gains the shared token
  view and two lazies.

## Public API changes (surface)

- **New:** `ValueScope` (public sealed class) with the four `invoke()` accessors.
- **Changed:** `ActionScope` extends `ValueScope` and implements `ColorScope`; its accessors move to the
  base. No call-site change — `action { }` bodies compile untouched.
- **Changed:** `CompletionScope` extends `ValueScope`, so the four accessors are now available inside
  `completeWith { }`. `current`, `words`, `candidate()`, `candidates()` unchanged.
- **Changed:** `ColorScope` becomes an interface declaring the two `Style.invoke` operators; `colorEnabled`
  is no longer one of its members (it stays internal on `ActionScope`).
- No change to `argument`/`option`/`flag`/`globalOption`/`globalFlag`/`completeWith` signatures.
- `sift`, `Sifted`, `BindPolicy`, `completionValues`, `stripToken`, and `stripMetaOptionWithValue` are all
  internal: §2, §3, and §4 change no public surface.

Pre-release, so these are in-place replacements: no parallel API, no deprecation window.

## Edge cases & safety

- **Provider throws** — unchanged contract: `candidatesFor`'s `runCatching` catches any `Throwable` and
  yields no candidates. An unresolved read rides that same path.
- **Cost of a Tab press** — the bind is `lazy(NONE)`, so a completion whose slot has no provider never binds
  anything and never runs a user converter. The token walk is no more work than today's `consumedPositionals`.
- **Parse errors are unchanged** — `bind` still raises `sifted.error` before binding anything, and the first
  error recorded is the same one the old early return produced, so every error-message test holds.
- **`--color <TAB>` still completes its modes** — the two `--color` branches move above the strip and read
  the raw head (§3); losing that ordering silently breaks them, so it is called out in the tests below.
- **`--` (end of options)** — handled by `sift`/`siftGlobals` as at runtime; no completion-specific rule.
- **Repeated option under the cursor** — `--tag work --tag <TAB>` reads `tags() == ["work"]`: what was typed
  so far, which is what a provider wants.
- **Reading another command's accessor** — still unbound, still throws, same as in `action { }`.
- **Group/root nodes** — `completionValues` binds whatever specs the resolved node declares; an unrouted
  subcommand token lands as a surplus positional and is ignored under `Lenient`.
- **Concurrency** — unchanged: the sink is per-call, the command tree stays immutable, no shared state.

## Testing

- **`sift` rewrite (regression first, before anything is deleted):** the full existing suite passes
  untouched — `ParseOptionsTest`, `ParsePositionalsTest`, `ParseResolutionTest`, `ErrorRenderingTest`,
  `EndToEndTest`. Each of the five error sites keeps reporting the same `CliError` with the same suggestion.
- **`sift` accumulation:** a segment with an error still yields the flags/options/positionals from before
  *and* after the offending token; the first of two errors is the one recorded.
- **Positional count parity:** `sifted.positionals.size` matches the deleted `consumedPositionals` on short
  clusters, attached values (`-p8080`, `--opt=v`), dash-led values (`-1m`), `--`, and unknown options.
- **Routing past the modifiers (§3):** `["--json", "list", "--st"]` and `["--color", "never", "list", "--st"]`
  both yield `["--status"]`, matching the plain `["list", "--st"]` baseline — all three currently measured as
  `["--status"]`, `[]`, `[]`. Also `--json`/`--color=never` between the subcommand and the cursor, and both
  present at once.
- **`--color` completion survives the strip:** `["--color", ""]` still offers `auto|always|never`, and
  `--color=al` still offers `always` — the regression the reordering in §3 risks.
- **Globals readable in a provider**, in every token shape: `--file V`, `--file=V`, `-f V`, `-fV`, and `-fv`
  (mixed cluster, the shape the deleted example hack got wrong); absent reads the declared default.
- **Local inputs readable**: an option, a flag, a count flag, and an earlier positional, from a provider on a
  later slot.
- **Sibling-argument completion**: `tag rm 3 <TAB>` offers only task 3's tags; `tag rm <TAB>` (id not typed)
  offers none and does not crash.
- **Cursor on an option value**: `list --status <TAB>` — a provider there can still read every other input.
- **Partial failure isolation**: a bad value for one option leaves the others bound; an unknown option in the
  segment leaves both the globals and the surrounding local inputs readable.
- **Lenient cardinality**: `multiple(min = 2)` with one typed binds a one-element list; surplus positionals
  do not error.
- **`ActionScope` unchanged**: existing action/accessor tests pass without edits.
- **`StyleTest`**: rewritten against `ActionScope` now that `ColorScope` carries no state.

## Where the design was wrong

Four defects in this document were caught during implementation, not during design. All four are corrected
in place above; they are listed here so the pattern is visible rather than buried.

1. **`ColorScope` silently lost a guard (§1).** It was `abstract class ColorScope internal constructor()` —
   unimplementable outside the module by construction. The first draft turned it into a plain
   `public interface`, which drops that guard: a consumer could implement a contract they cannot satisfy
   (`Style.render` is `internal`) and would inherit klap's `@KlapDsl` marker into their own DSLs. The design
   reasoned about blast radius (three files) and concluded it was small — true for *internal* callers, but it
   never asked what the change did to the *outward* surface. Fixed by one word: `sealed interface`.

2. **The lenient variadic rule was underspecified (§5).** The table said only "binds however many are typed
   so far", and the two binders implemented that differently: an absent `multiple()` option bound `[]`, an
   absent `multiple()` argument stayed unbound. Same declaration shape, opposite outcomes, nothing a provider
   author could use to tell them apart. Fixed by naming the rule explicitly and making both follow it.

3. **The base owned the exception kind (§1).** `unboundMessage(spec): String` forced every subclass into the
   base's `error(...)`. But an unbound read is a real error in an action and *expected control flow* during
   completion — where the message is swallowed by `runCatching` and shown to nobody. Fixed by handing the
   subclass the whole failure: `unbound(spec): Nothing`.

4. **`read` read the abstract `values` twice (§1).** Free while it was a field on the only subclass; not free
   once `CompletionScope` computes it through a `Lazy`, and a non-idempotent override could have `containsKey`
   and `get` disagree. Fixed by snapshotting the map once.

Two further errors were in prose rather than design, and are worth recording because both sat on
user-facing surfaces: the `tag rm` provider comment described an abort that cannot occur (that cursor
position belongs to the id provider, so it is reachable only via a *malformed* id), and the README sample
would not have compiled (`TaskStore.load()` returns a `Result`).

**One bug was fixed without being noticed.** §3's strip was written to fix subcommand *routing*. It also
fixed positional *slot counting*: before it, a space-form `--color <value>` consumed a slot, because the old
walk treated `--color` as an unknown long option and then counted its value as a positional. Found by the
final whole-branch review, and now pinned by a test.

## Out of scope / future

- `orNull()` accessors or any nullable-read variant (locked decision 2 defers this until a need appears).
- Completing sensibly after `--help`/`--help-all`/`--version` (locked decision 5: the line never runs a
  command).
- Reading an *ancestor* command's non-global options in either scope: klap does not inherit them at runtime
  either, and completion must not invent a rule the parser does not have.
- Async or suspending providers.
- **Three follow-ups raised by the Task 2 code review, deliberately deferred** (recorded so they are not
  re-raised as oversights, and so the analysis is to hand when they land):
  - **Extract per-branch advance helpers inside `sift`.** After the rewrite `sift` is ~145 body lines with
    36 columns of indentation at its deepest point — over the line for a function one person holds in their
    head. `internal/render/Completion.kt` already prototypes the fix it needs (`longOptionAdvance`,
    `shortClusterAdvance`, each returning the advance); local funs of the same shape, closing over the
    tallies and `record`, would leave a ~30-line dispatcher. Deferred because it is a *second* structural
    rewrite of the same function inside one branch, and the readability win can land at any time whereas
    the regression risk compounds here.
  - **Hoist `record` and use it in `siftGlobals` too.** The rewrite converged the two functions' *contract*
    but introduced a new divergence in *idiom*: `sift` uses `record { … }`, `siftGlobals` still spells the
    same rule as a bare `if (error == null) error = …` at four sites. One file, two spellings of "keep the
    first error".
  - **Move `SiftAccumulationTest` next to the parse suites.** It currently lives in `CompletionTest.kt`
    because completion is *why* it exists, but it tests `internal.parse.sift`, and a maintainer changing
    `sift` looks in `ParseOptionsTest.kt`, where the parse-level analogues of these same errors already live.
- **A fourth follow-up, from the Task 3 review: the subcommand walk is itself duplicated.** Task 3 extracted
  `Cli.walkTo` in `internal/render/Completion.kt` to bind the resolved command to a `val`, but `parse()`
  (`Parser.kt:137-145`) still runs the identical loop, differing only in that it also accumulates the
  qualified-name `path`. That is the same hand-synced-copy shape this design set out to remove from the
  positional count, one size smaller. Folding them together means giving `walkTo` an optional path
  accumulator, or having `parse()` derive the path from the walk result — neither is hard, both are outside
  this feature.
- **No binary-compatibility check exists, and this feature is the argument for adding one.** With
  `explicitApi()` already on and a first release ahead, "did this widen the public surface?" is currently a
  manual review question on every change. It was missed once in this very feature (`ColorScope` losing its
  `internal constructor()` guard, §1) and caught only by a reviewer reading the diff. A
  `binary-compatibility-validator` `.api` dump would turn that class of mistake into a visible diff.
- **No public seam for testing a provider.** `CompletionScope`'s constructor and `completeCandidates` are
  both internal, so a downstream user cannot unit-test a `.completeWith { }` block — the only route is
  running the CLI and capturing stdout. That mattered little when providers were closures over constants;
  it matters more now that they contain input-dependent logic. Raised by the Task 5 review.
- **`lazy(NONE)` does not cache a thrown initializer.** `convertOne` catches `Exception`, so an `Error`
  (e.g. `TODO()`) escapes the bind; if a provider swallows the first throw, the next accessor read re-runs
  the whole bind. Duplicated work, not incorrectness, and `NONE` remains right. Recorded, not fixed.
- **`ColorScope` may not earn its keep — CLOSED, 2026-08-02.** It now has a second implementor: the chrome's
  internal `Palette`, so klap's own help output and a consumer's action output resolve a `Style` through one
  mechanism instead of two. That was already specified in `2026-07-28-color-palette-design.md` §4 and had
  not shipped. See `2026-08-02-chrome-palette-design.md`. The original analysis is kept below because the
  reasoning about the asymmetric window still applies to any future public type in this position.

- **(Original entry) `ColorScope` may not earn its keep at all — still open.** After this feature it holds no code and has one
  implementor. The example now contains `private fun ColorScope.render(task, verbosity)`, which is the shape
  the type exists for, but that does **not** settle the question: `private fun ActionScope.render(...)` would
  compile and behave identically, so the example discriminates between the two worlds not at all. What would
  settle it is a **second implementor** — a help or error renderer, or `CompletionScope` — letting one helper
  serve both. Until then the choice stands: keep it sealed as a named seam for `fun ColorScope.warn(...)`
  helpers, or delete it and put the two operators directly on `ActionScope`, where `fun ActionScope.warn(...)`
  serves the same users with one fewer public type.

  **The window is asymmetric and closes at v1.** Adding a supertype later is source- and binary-compatible;
  removing one is not. So shipping it costs a permanent commitment for a payoff that does not exist yet,
  while not shipping it costs nothing, because it can be reintroduced non-breakingly the day a second
  color-bearing scope appears. Decide before the first release, not after.
- Two smaller walks are deliberately **not** consolidation candidates: `trailingValueOption` and
  `attachedValueOption` each peel a short cluster with their own logic and are KDoc'd as mirroring `sift`,
  but they answer "what would the *next* token bind to", which `Sifted` does not expose. Recorded here so a
  future cleanup pass does not mistake them for copies of the walk and try to delete them.
- The two deferred QA-pass-6 findings in `2026-07-28-completion-descriptions-design.md` were untouched by
  this work. The first (negatable global polarity in a mixed cluster) lives in this same code, and the
  shared shape this work gave `sift` and `siftGlobals` is what made threading argv position through both
  passes cheap; it was fixed that way on 2026-08-02. The second is still open.

## Files touched (as shipped)

Twelve files, +882 / −295.

- `ValueScope.kt` (new, +39) — sealed base, the four accessors, the one unchecked read.
- `ActionScope.kt` — extends `ValueScope`, implements `ColorScope`, keeps `withColorEnabled`.
- `Style.kt` — `ColorScope` becomes a stateless **sealed** interface.
- `Completion.kt` (public) — `CompletionScope` extends `ValueScope`, takes the lazy map, supplies its own
  `unbound`.
- `Parser.kt` (public root file) — `stripToken` and `stripMetaOptionWithValue` widened to `internal`.
- `internal/parse/Parser.kt` (+291) — `sift` returns `Sifted` with an `error` field, record-and-continue at
  its **six** error sites; `bind` raises `sifted.error`; `BindPolicy`; lenient branches in
  `bindFlagsAndOptions` and `bindPositionals`; `bindGlobals` takes the policy; new `Cli.completionValues`.
- `internal/render/Completion.kt` (−76 net) — strip `--json`/`--color` with the `--color` branches moved
  above it; `walkTo` extracted; `consumedPositionals` and five private helpers deleted;
  `sifted.positionals.size` for the slot count; two lazies; the value map threaded through `candidatesFor`.
- `example/.../Main.kt` — `storeFileFromWords` deleted; one `ValueScope.taskStore()`; providers on `tag rm`
  (sibling `id()`) and `tag add` (aggregate); plus the colour and surface work described in §6.
- `example/.../Task.kt` — a `@Suppress("unused")` on `Priority` removed, obsolete once `Priority.style`
  referenced every constant.
- `README.md` — the `completeWith` capability, and the example inventory updated to name the new surface.
- Tests: `CompletionTest.kt` (+335, five new classes), `StyleTest.kt` (fixture rebased onto `ActionScope`);
  the parse suites acted as the regression gate throughout and needed no edits.

Final state: 1062 tests green on jvm and linuxX64, `./gradlew build` green across all targets, no warnings.
