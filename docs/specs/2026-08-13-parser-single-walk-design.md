# Parser: one walk

**Status:** rejected. Three adversarial review rounds each found critical defects: three in the first, five in
the second, four in the third, three of which were inside the replacements written for round two. Round
three's verdict on the spec's own open question 4: do not proceed as scoped. The reason is structural: klap's
current answers come from the interaction of its passes, and that interaction is exactly what "one resolution
rule instead of four" removes. Reviewers kept finding new divergences by running argv rather than by reading
the design, and the supply was not close to exhausted. The do-nothing alternative in Rejected alternatives was
taken instead: the `--version` JSON shape, the completion routing fix, and the `Bind.kt` extraction are on
`fix/parser-cheap-path`. Ledger 1 remains unfixed and is the one cost that genuinely requires the rewrite.

**Goal:** replace the four separate resolution rules klap applies to argv with one, so a token is read the
same way by everything that reads it.

**Re-measured 2026-08-14 against `9afe7c1` + working tree.** Everything below was read off `25329b9`
(2026-08-13). Since then `7ede8f6` closed **ledger 4, 5 and 6** (5 by inverting it) and removed cluster
deferral from the parser outright: a short cluster the command in scope cannot resolve now stops the
subcommand walk instead of being deferred past it. That makes C1's whole `today` column and C3's first
measured row historical; both are marked in place. **Ledger 1, 2, 3 and 7 and section C2 were re-checked
and still hold**, so the remaining case for the rewrite is intact. Line citations have been repointed to
symbol names, which do not rot, and the `Parser.kt:320-321` quotation below no longer exists in the
source. The verdict and the argument are left exactly as written.

## What this buys, and what the first draft wrongly claimed it buys

The first draft was sold on deleting the `positions` family. It cannot. That claim is retracted here rather
than quietly dropped, because it is the reason the project looked cheaper than it is.

**Buys:**

- **One resolution rule instead of four.** `ArityWalk`, `siftGlobals`, `parseTokens`' routing loop and `sift`
  each resolve longs and short clusters today, and each must reach the same answer. The comments saying so
  (`ArityWalk.clusterClaim`, `offeredBuiltinLongs`) stop being load-bearing because there is one implementation.
- **`walkTo` and `completeCandidates`' preamble delete.** Completion currently re-runs `parseTokens`' first
  eighty lines and adds a fifth, subtly different walk of its own.
- **Precedence becomes an explicit ordered list** instead of a property of which pass happens to run first.
- **`ArgvScan` deletes**, along with `optionValueSlots` and `positionIndependentLongs`, because no scan runs
  before the command is known.

**Does not buy:**

- **The `positions` family does not delete.** Deferral is a reordering, and ordering a reordered token needs
  its original index. See C1.
- **Built-ins do not join one event stream.** The same token has two readings, so the walk carries two. See
  C2.
- **The bind half is not untouched.** Three consumers never read `Sifted` at all, and no single list of
  leftover tokens can serve them. See C3.
- **One error field is not enough.** The precedence table reads three, in an order that is not argv order.
  See C4.

## The problem

`Cli.parseTokens` makes roughly nine traversals of argv before anything binds: the arity walk, `--json`,
`--color`, `--version`, `--completion`/`--docs`, `siftGlobals`, the subcommand routing walk, a second
`ArgvScan` for `--help`, and finally `sift`. Four of those are genuine walks. `completeCandidates` runs the
same preamble a second time and adds a fifth walk, `walkTo`.

Every one of those passes has to reach the same conclusion about the same token, and the source said so.
`Parser.kt:320-321` at `25329b9`: *"Shares siftGlobals's own resolution rule, reused rather than restated, so
this walk and ArityWalk cannot drift apart."*

> **Removed since (2026-08-14).** That comment is gone from `klap/src`: `7ede8f6` made the routing loop stop
> at a mixed cluster rather than share `siftGlobals`' rule to step over it. The nearest surviving statement
> is `offeredBuiltinLongs`' KDoc, *"Shared by the pool that resolves a token and by the sift that binds one,
> so the two cannot disagree about whether a name is klap's own."*

The root cause is ordering. `siftGlobals` runs before routing, because a global option must eat its value
before the walk can tell which token is the subcommand name (`app --file x list`, where `x` is a value and not
a subcommand). But `siftGlobals` holds no local specs, so it cannot tell a flag char from a glued value inside
a mixed cluster like `-fv`, and defers those whole. The reached command's `sift` resolves them one pass later.

The cost compounds. `optionValueSlots` exists to answer "is this token a value" before the command is known.
`positionIndependentLongs` exists to resolve a long spelling before the command is known, and its own KDoc
names the price: *"one command's long can decline an abbreviation on behalf of its siblings, so
`app sub --ver` is refused even where `sub` alone declares no `--verbose`."*

## Verified before designing

Read off the source at `25329b9` on 2026-08-13, not recalled.

**klap already contains most of this walk.** `ArityWalk`'s KDoc (`internal/parse/ArgvScan.kt`):

> "The left-to-right walk behind [optionValueSlots]. It tracks the command the tokens belong to as it goes, so
> every option is resolved against the surface actually in scope at its position rather than against the
> root's alone."

It descends into subcommands, resolves longs against the reached command's pool, walks clusters char by char
against local-then-global specs, knows `numericAlias`, knows `optionsEndAtFirstOperand`, and knows which
built-ins take a value. Then it discards all of it and returns `Set<Int>`: the argv indices holding a value.

Citations below name symbols rather than lines, repointed 2026-08-14; every row was re-checked then and
still holds.

| Fact | Where |
|---|---|
| An option takes the rest of its cluster as its value | `sift`'s cluster branch; whether it reaches the NEXT token is `OptionSpec?.valueFrom` and `ArityWalk.takesValue` |
| An error names the cluster a char came from | `clusterCharError` |
| An `.optionalValue()` option never takes the next token | `ArityWalk.takesValue` |
| A repeated single-value option binds its last occurrence | `bindOptions` (`internal/parse/Bind.kt`) |
| `rest` is every token of `preStrip.cleaned` not consumed as a subcommand name, not a suffix | `parseTokens`' `rest`/`restPositions` (`Parser.kt`) |
| `--color` is validated before `--version` so a bad value still reports | `parseTokens`' `withoutColor` |
| `optionsEndAtFirstOperand` deliberately does not reach the built-ins | `ArityWalk.slots` |
| Occurrence order across the two passes is restored from an index | `Polarity.position`, `Occurrence.position` |
| Globals are declared on `CliBuilder` only, so their scope is the whole tree | `CliBuilder.globalOption`, `CliBuilder.globalFlag` |
| The module's only `expect` is `platformExit`, which is not in the parse path | `internal/platform/Exit.kt` |

Sizes at `25329b9`: `Parser.kt` 483 lines, `internal/parse/Parser.kt` 1627, `internal/parse/ArgvScan.kt` 289,
`internal/render/Completion.kt` 426. (2026-08-14: 507 / 1267 / 272 / 429, after `Bind.kt` and `Sifted.kt`
split out.) Tests: 1030 `@Test` in `klap/src/commonTest` (2026-08-14: 1044); 123 across `example/`, of
which **73 are parity tests** in the 15 files using `ParitySuite` (`chmod`, `cp`, `curl`, `dd`, `find`, `git`,
`head`, `ls`, `mkdir`, `mv`, `pacman`, `rm`, `rsync`, `ssh`, `tar`). The other 50 belong to `task-manager` and
`pulse`, which are demo apps, not oracles. Both example counts are unchanged.

## Measured divergences that shape the design

Each was produced by running the input against the current parser, not reasoned about. They are the reason
this revision exists, and each one forces a design change.

### C1. Deferral is a reordering, so a deferred token needs its original index

A naive drain that appends the deferred token loses its place relative to globals consumed between it and the
subcommand name. Each row names the declarations it needs: rows three and four differ only in whether `-f` is
an option or a flag, and they give opposite answers.

| declarations | argv | today | naive drain |
|---|---|---|---|
| global `--port`/`-p`; `list` has `--long`/`-l` | `app -lp8080 --port 9090 list` | `port=9090` | `port=8080` |
| global `--xray`/`-x`, negatable `-X`; `list` has `--long`/`-l` | `app -lX --xray list` | `xray=true` | `xray=false` |
| global `--verbose`/`-v`; `list` has **option** `--file`/`-f` | `app -vf list report.txt` | `file=report.txt` | `-f` starves and the operand takes it |
| global `--verbose`/`-v`; `list` has **flag** `--file`/`-f` | `app -vf list -- a` | `file=true files=[a]` | `files=[-vf, a]`, `file=false` |

> **Historical as of 2026-08-14: every `today` cell above.** `7ede8f6` removed cluster deferral. A short
> cluster the command in scope cannot resolve now stops the subcommand walk, so all four lines report
> `UnknownOption` naming the out-of-scope char with the cluster it came from: `-lp8080`→`-l`, `-lX`→`-l`,
> `-vf`→`-f` (pinned by *mixed cluster is refused before the subcommand and binds after it*,
> `ParseOptionsTest.kt`). With no drain there is no naive drain either. The rows are kept because the
> design consequence below is the answer to them.

The last is a silent mis-bind, the failure class klap has shipped before. Its cause is separate from the
others: routing ends at `--`, which also ends options, so a token drained *after* that point is re-read under
a state that did not hold at its position.

The flag reading is load-bearing in that row. With `-f` declared as an **option**, the same argv is
`MissingOptionValue("--file")` today, and deferral has nothing to do with it: `list -f -- a`, with no cluster
and no deferral anywhere, errors identically. An option never takes `--` as its value in any position
(`sift`, in both its long and its cluster branch), which is its own rule the walk has to carry and its own
corpus shape. (2026-08-14: the clustered form now reports `UnknownOption("-f", cluster = "-vf")` like the
rows above; the unclustered `list -f -- a` still reports `MissingOptionValue("--file")`, so the rule the row
is here for is unchanged.)

**Design consequence.** A deferred token carries its original ordinal, and the drain re-inserts by ordinal
rather than appending. This is `Polarity.position` and `Occurrence.position` doing their existing job in one
place instead of two, not their deletion.

What the deferred token does **not** need to carry is the option-parsing state that held when it was seen. The
drain runs while the routing-stop token is still queued, so a deferred token is always re-read with options
still open; the cursor below states that as an invariant. `clusterPosition`'s `tokenIndex * 1000 + charIndex`
stride is genuinely replaceable by per-char events, and `dashLedAdmitted` genuinely rides on `Operand`; those
two deletions stand.

### C2. Built-ins need a second reading of argv, and it is not "ignore `optionsEnded`"

`optionsEndAtFirstOperand` deliberately does not reach the built-ins (`ArityWalk.slots`), but `--` does. A
walk has one `optionsEnded` flag and both set it (`sift`), so a rule phrased as "ignore `optionsEnded`"
ignores `--` as well and loosens five inputs at once:

| argv | today |
|---|---|
| `app file.txt --version` | `ShowVersion` |
| `app file.txt --help` | `ShowHelp` |
| `app file.txt --json` | `Execute`, json on, `file=file.txt` |
| `app -- --version` | `Execute`, `file=--version` |
| `app -- --json` | `Execute`, json **off** |

**The visibility rule.** A built-in spelling is sighted when it sits **before `--`** and is **not consumed as
an option's value**. That is `ArgvScan.isOpen` exactly, and `optionsEndAtFirstOperand` is invisible to it.

**Three classes, not one.** The first draft said a sighted token "also becomes an `Operand`". That holds for
the built-ins that short-circuit and fails for the two that do not:

| class | which | what it emits into the event stream |
|---|---|---|
| short-circuiting | `--version`, `--help`, `--help-all`, `--completion`, `--docs` | its ordinary reading, which matters only when the sighting does not fire |
| **consuming** | `--json`; `--color` with its value | **nothing**, removed exactly as `ArgvScan.strip`/`stripValued` remove them today |
| surviving | a built-in spelling the tree declined | its ordinary reading, and no sighting |

Duplicating a consuming built-in is not a subtle error. On a POSIX-mode root, `app f.txt -e x --json` binds
`files=[f.txt, -e, x]` today: `--json` is gone from the operand list while `-e` and `x` stay, because removal
is a property of the built-in token and not of `optionsEnded`. Emitting an `Operand` for it as well turns a
working line into `TooManyArguments`.

### C3. Three consumers take raw tokens, and one `leftover` list cannot serve them

1. **`Command.bind`'s group branch** (its `isGroup` branch) never calls `sift`. It reads the raw segment for
   `ddIndex`, `firstToken` and `positionals`, and blames `firstToken[1]` *without resolving it*.
   (2026-08-14: it resolves the cluster through `firstUnresolvedShort` now — ledger 6, closed. Everything
   else in this item still holds: the branch still reads the raw segment and still never calls `sift`.)
2. **`routeBuiltin(kind, rest)`** enforces arity on raw tokens: `app completion bash -x` reports
   `TooManyArguments` today, on a dispatcher root where the built-in node exists.
3. **`unknownSubcommandBeforeHelp(cmd, rest)`** needs the raw first token and its flag-likeness.

The first draft gave `Walked` a `leftover: List<String>` for all three. It cannot work, for two independent
reasons.

**A token list cannot express partial consumption.** `rest` is a raw slice, so a token is wholly present or
wholly gone. A walk resolves clusters character by character. Measured on a group root with global `-v`:

| argv | today | why |
|---|---|---|
| `app -vq zzz` | `UnknownOption("-v")`, `cluster=null` | the whole `-vq` survived into `rest` |
| `app -v zzz` | `UnknownSubcommand("zzz")` | `siftGlobals` consumed `-v` |

> **Historical as of 2026-08-14: row one.** `7ede8f6` closed ledger 6, so `app -vq zzz` already reports
> `UnknownOption("-q", cluster = "-vq")` — the very reading the design consequence below proposes. Row two
> is unchanged.

After a walk, `v` has bound and `q` has not. No `List<String>` can say that.

**The three consumers want incompatible contents.** `routeBuiltin` needs `--` present as a token and `--json`
absent; the group branch needs `--` present for its `ddIndex`; and `--` is definitionally consumed by the
walk, being the token that ends options.

**Design consequence, in three parts.**

- **A built-in node halts the walk.** It binds nothing, so the walk stops on entering it and exposes
  `remaining: List<String>`, the raw tokens from that point. Well defined because the halt falls between
  tokens, never inside a cluster. `--json` is already gone (consuming, C2), while `--` and a surplus `-x` are
  both still there, which is what the three measured `completion` lines each need.
- **The group branch reads `events`, not raw tokens.** `app -vq zzz` becomes
  `UnknownOption("-q", cluster = "-vq")` instead of `UnknownOption("-v")`. A change, and a correction: today
  the message names a valid global and drops the cluster context `UnknownOption.cluster` exists to carry.
  Ledger 6. (2026-08-14: shipped in `7ede8f6` without the walk, by resolving the cluster in place.)
- **`unknownSubcommandBeforeHelp`'s guard is restated.** Its "unless the leading token is flag-like" test
  exists to stop a line carrying options from being diagnosed as a bad subcommand. Against the walk that
  becomes "unless the walk recorded an error before the first operand", which preserves the measured
  `app -vq zzz --help` returning `ShowHelp`. (2026-08-14: that measurement is historical. `7ede8f6` put an
  `UnknownOption` from the segment sift ahead of help, so the line now reports
  `UnknownOption("-q", cluster = "-vq")` — see *unknown option with help errors identically to without
  help*, `BuiltinsTest.kt`. The guard itself is unchanged.)

Separately, `Walked` needs a **global sink**: `sift` records a global found in a mixed cluster into
`GlobalAccumulator`, not into `Sifted`, and `bindGlobals` reads `GlobalSift`.

### C4. One error field where the precedence table reads three

Measured on a tree with `globalOption("--gopt")` and a subcommand `list`:

```
app list --unknown --gopt          today: MissingOptionValue("--gopt")
```

The unknown option sits at argv index 1 and the dangling global value at index 2, yet the global wins.
`siftGlobals` walks the whole head regardless of routing, so step 9 returns its error before `cmd.bind` ever
sifts the segment. A single first-error-wins field in walk order records `UnknownOption("--unknown")`, leaves
step 9 nothing to read, and flips the answer.

**Design consequence.** `Walked` carries three error channels, each first-wins within itself, matching the
three the precedence table already distinguishes: `routingError` (step 5), `globalError` (step 9), and
`error`, the segment's own (step 11).

## Architecture

```
argv
 └─ walk()  ──>  Walked { path, events, builtins, remaining, globals, pendingValue, 3 error channels }
                    │
                    ├─ precedence read over `builtins` and the three error channels
                    │     (each step short-circuits to its own Invocation)
                    │
                    ├─ a built-in node reads `remaining` raw; a group reads `events`
                    │
                    └─ toSifted() ──> bind() ──> Invocation.Execute
```

**walk** decides what each token means. **bind** converts values and enforces arity and constraints. **route**
picks the `Invocation`.

`--json` and `--color` stop being pre-strips: they are recorded wherever they appear rather than removed
first. That is what lets `ArgvScan` be deleted rather than moved.

## The cursor

```kotlin
/** A token still to parse, with the argv index it was written at. */
private class Pending(val text: String, val at: Int)

private class Cursor(argv: List<String>) {
    private val queue = ArrayDeque(argv.mapIndexed { i, t -> Pending(t, i) })
    private val deferred = mutableListOf<Pending>()

    fun peek(): Pending? = queue.firstOrNull()
    fun shift(): Pending = queue.removeFirst()

    /** A cluster holding a char the current command does not declare; retried when the drain runs. */
    fun defer(token: Pending) { deferred += token }

    /** Front-insertion by argv index, never appending, and never after the routing-stop token: see below. */
    fun drainDeferred() {
        queue.addAll(0, deferred.sortedBy { it.at })
        deferred.clear()
    }
}
```

`peek` is required, not convenience: whether an option reaches for the next token depends on what that token
is, and an `.optionalValue()` option never reaches at all.

**Consumption is what dissolves the ordering problem for everything except deferral.** Once an option takes
the next token as its value, that token is gone from the args still to parse, so it can never later be read as
a subcommand name or an operand. `app --file x list` needs no pre-strip.

**Deferral handles the one case consumption cannot**, and pays for it with an index. A cluster written before
the subcommand may only be resolvable after it: in `app -fv list`, where `f` is `list`'s flag and `v` is a
global, the root cannot resolve `f` either. The walk defers the whole token, recording `at`, and retries it
against the command it reached.

Three properties of the drain are load-bearing, and none of them is visible from the code sketch alone.

**It runs when routing ends, or when argv runs out, whichever comes first.** Routing ends at the first token
the walk can neither descend into nor defer (`parseTokens`' routing loop, `ArityWalk.slots`; 2026-08-14: as
of `7ede8f6` neither of those defers anything, so routing simply ends at the first token it cannot descend
into). Exhaustion is not a
corner case: `app -fv list`, this section's own motivating example, has no routing-stop token at all, so a
drain fired only by the first trigger drops the cluster silently.

**It runs before the routing-stop token is consumed.** This is what makes C1's fourth row come out right, and
it is the property an implementation reviewer would otherwise have no way to check. In `app -vf list -- a` the
drain fires with `--` still queued, so the deferred cluster is read while options are still open. Draining
after `--` is precisely what produces the naive drain's mis-bind.

**Front-insertion restores argv order on its own.** Every deferred token has a lower argv index than
everything still queued, because during routing the walk either consumes or defers every token it sees, so
nothing survives at an index between two deferred ones. `sortedBy { it.at }` therefore only orders the
deferred list against itself, and no queued token can be overtaken.

## What the walk emits

```kotlin
/** One resolved token, in argv order. */
internal sealed interface Resolved {
    data class Flag(val spec: FlagSpec, val on: Boolean, val at: Int) : Resolved
    data class Option(val spec: OptionSpec, val raw: String, val at: Int) : Resolved
    data class Operand(val text: String, val dashLed: Boolean) : Resolved
}

internal class Walked(
    val path: List<Command>,
    val events: List<Resolved>,
    /** Sighted before `--` and outside a value slot; `optionsEndAtFirstOperand` is ignored. See C2. */
    val builtins: List<BuiltinSighting>,
    /** Raw tokens from where the walk halted at a built-in node, which binds nothing. See C3. */
    val remaining: List<String>,
    /** Globals found by this walk, including ones buried in a mixed cluster. */
    val globals: GlobalSift,
    /** The option or meta-option still waiting for a value when the args ran out. */
    val pendingValue: PendingValue?,
    /** Three channels, because the precedence table reads them at three different steps. See C4. */
    val routingError: CliError?,
    val globalError: CliError?,
    val error: CliError?,
)

internal fun Walked.toSifted(): Sifted
```

`at` survives on `Flag` and `Option` because C1 needs it and because `bindPositionals` reads
`dashLedAdmitted` as indices into `positionals` (`internal/parse/Bind.kt`); that one keeps its
current shape, produced by `toSifted()`.

The list is eager, not a `Sequence`: `path`, `pendingValue` and `error` are terminal facts, so a lazy stream
would oblige every caller to fully drain before reading them with nothing in the type saying so.

`toSifted()` produces today's `Sifted` shape, so `bindFlags`, `bindOptions`, `bindPositionals` and
`checkConstraints` keep their current code.

## Built-in value rules differ from option value rules

A meta-option keeps a strict reading a command's options dropped. `ArgvScan.value`'s KDoc: `--color --json`
means a *missing* `--color` value, not the literal value `--json`. `ArityWalk.builtinClaim` mirrors it with
`!next.isFlagLike()`, while `OptionSpec.valueFrom` takes whatever is next.

The walk carries both rules explicitly. Without that, `app --color --version` turns from
`MissingOptionValue("--color")` into `InvalidChoice("--color", "--version")`.

## Errors and precedence

**The walk never throws and never stops early.** It records the first error and keeps going, including past a
failed routing decision, where it turns routing off and keeps collecting built-in sightings. This is today's
`record { }` contract (`sift`'s KDoc and its `record`) plus `ArityWalk`'s `routing = false`.

**Precedence becomes data.** Transcribed from `Parser.kt` line by line, with the guard on each step and the
token view it reads, because reproducing all three exactly is most of the parity risk:

| # | Step | Guard | Reads | Where in `parseTokens` |
|---|---|---|---|---|
| 1 | built-in inline-value error | none | raw `scan` | `builtinInlineValueError` |
| 2 | `--color` value validation | `builtins.color` | `withoutJson` | the `withoutColor` block |
| 3 | `--version` | `version != null` | raw `scan` | `scan.names("version")` |
| 4 | `--completion` / `--docs` | `metaOptions && !hasHelpRequest(scan)`, then `builtins.completion` / `builtins.docs` | `withoutColor`, tree-wide pool | the `metaOptions` block |
| 5 | ambiguous subcommand | none | routing loop | `SubcommandMatch.Ambiguous` |
| 6 | unknown subcommand | `hasHelpRequest(segment)`, and `cmd.isGroup` with a non-flag leading token | `segment`, command pool | `unknownSubcommandBeforeHelp` |
| 7 | `--help-all` | none | `segment`, command pool | `segment.names("help-all")` |
| 8 | `--help` / `-h` | `builtins.helpShort` for the short form | `segment`, command pool | `helpRequested` |
| 9 | hard global error | none | `globalSift` | `globalSift.error` |
| 10 | built-in node routing | `cmd.builtinKind != null` | raw `rest` | `routeBuiltin` |
| 11 | bind | none | `Sifted` | `cmd.bind` |

2026-08-14: `7ede8f6` inserted one step between 6 and 7 — under the same help guard, an `UnknownOption` from
the segment sift outranks help. Steps 1-11 themselves are unchanged.

Four things in that table are easy to lose and each has cost a design assumption already:

- **`--color` is validated before `--version`** (the comment on `withoutColor` says so), so a malformed value
  still reports when both are present.
- **Step 4 is gated on `metaOptions`**, the single-command-root switch, so on a dispatcher tree it never fires
  at all.
- **`--help` is resolved twice against two different pools.** Step 4's gate uses the tree-wide pool; step 8
  uses the reached command's. `hasHelpRequest`'s KDoc names the price it accepts for that. One walk collapses
  them to one answer, which is a behaviour change (ledger 3).
- **Step 11's "first act is raising the walk's error" is true only for a leaf.** `Command.bind` runs its
  `isGroup` branch first, which returns without ever calling `sift`.

`builtinInlineValueError` also orders offenders by built-in name rather than argv order, which an
event-order read would invert.

## The completion contract

`walk(head)` replaces `completeCandidates`' preamble: `builtinScan`, `valueSlots`, the `--json` strip, the
`--color` strip, `siftGlobals` and `walkTo`. Not `accumulator`: several completion branches need a
`GlobalAccumulator` rather than a `GlobalSift`, so it is rebuilt from `Walked.globals` rather than replaced.

`pendingValue` replaces the current reconstruction of `prev` in `completeCandidates`
(`internal/render/Completion.kt`):

```kotlin
val prev = segment.lastOrNull()?.takeUnless { preStrip.positions.last() in scan.valueSlots }
```

together with the `cmd.trailingValueOption(...)` lookup behind `valueOption`. The
trailing-option-char-of-a-cluster case (`mygrep -vp <cur>`) falls out for free, because the walk was going to
consume the next token and found none.

Three things `pendingValue` does not cover on its own, all of which the first draft missed:

- **`--color` has no `OptionSpec`.** `colorValueCandidates` answers `--color <cur>` precisely because it is a
  meta-option. `PendingValue` is therefore a spec-or-built-in union, not `OptionSpec?`.
- **`END_OF_OPTIONS !in segment` gates the whole flag-name branch.** With no segment list, `Walked` needs a
  terminal `optionsEnded` fact. It must reflect `--` only, not `optionsEndAtFirstOperand`, or POSIX-mode
  commands stop offering flag names after their first operand.
- **`walkTo` is not the routing walk.** It breaks on the first non-subcommand token, so a mixed cluster before
  the subcommand stops it, where `parseTokens`' routing loop skips over it. `app -lp list <TAB>` completes
  against the *root* today. Unifying them is an improvement and a behaviour change (ledger 4).
  **Closed 2026-08-14** — see ledger 4.

**User converters stay guarded by the right lazy.** `sift` contains no user code and is safe to force
anywhere (the comment on `completeCandidates`' `sifted` lazy); the converter guard is the separate `values`
lazy beside it. Preserving
`toSifted()`'s laziness is not what preserves that guarantee, and the guarantee must be preserved on its own
terms.

## Components

| File | Change |
|---|---|
| `internal/parse/Walk.kt` | New. The walk: descent, long and cluster resolution against the command in scope, globals, the two built-in readings, `numericAlias`, dash-led admission, `--`, `optionsEndAtFirstOperand`, deferral with ordinals. |
| `internal/parse/Walked.kt` | New. `Resolved`, `BuiltinSighting`, `PendingValue`, `Walked`, `toSifted()`. |
| `internal/parse/Bind.kt` | Extracted. `bindFlags`/`bindOptions`/`bindPositionals`/`checkConstraints` move out of the then-1627-line file, code unchanged. (Done at `86462fc`; `Sifted.kt` split out too.) |
| `internal/parse/ArgvScan.kt` | Deleted. |
| `Parser.kt` | Shrinks to walk, precedence, bind, assemble. |
| `internal/render/Completion.kt` | Loses its preamble and `walkTo`. |

## Behaviour contract

**Strict parity, adjudicated per failure.** A red test is investigated, not reverted on sight, but the burden
of proof sits on the change.

- **The 73 parity tests are non-negotiable.** They were measured against real `coreutils`, `git`, `curl`,
  `findutils`, `tar`, `ssh`, `rsync` and `pacman` binaries. If one flips, the rewrite is wrong.
- **The 1030 klap tests and the 50 demo-app tests are adjudicated one at a time**, and every verdict is
  appended to the ledger with its reason.
- **Loosening needs a higher bar than tightening.** A test moving from "error" to "accepted" is the dangerous
  direction, because every genuinely bad gap klap has shipped was a silent mis-bind. C1's `app -vf list -- a`
  and C3's `app completion bash -x` are both in that class, and both come from this design rather than from
  the old one.

**The baseline must be green and named by SHA before the corpus is captured.**
`klap/src/commonTest/kotlin/com/fromwau/klap/NumericAliasPlacementTest.kt` is untracked and does not compile,
so `:klap:jvmTest` fails at HEAD. It moves out of the tree first. (2026-08-14: it is no longer in the tree.)

## Golden corpus

Captured on a green `master` **before any code changes** and committed.

- **Lives in `klap/src/jvmTest/kotlin`.** The KMP plugin already creates that source set and `:klap:jvmTest`
  runs today, so this is a directory rather than a build change. JVM-only is sound because the parse path is
  entirely common Kotlin and the module's single `expect` is not in it.
- **Trees:** the 15 parity fixtures plus one synthetic kitchen sink covering globals, `dashLed`,
  `numericAlias`, `lastWins`, `requireExactlyOne`, `optionalValue`, negatable and counted flags, aliases,
  depth-2 subcommands, `optionsEndAtFirstOperand`, group nodes, built-in nodes, and each `Abbreviation` mode.
- **Projection:** for an error, its type and rendered message; for a success, the `Invocation` kind, the
  resolved command path, and the bound sink in declaration order.
- **Shapes the generator covers explicitly.** The first six were in the first draft; the last five are the
  ones that actually diverged under measurement, and none of the first six would have caught them.
  - `app -f list run`, a value-taking option deferred across routing
  - mixed clusters both before and after the subcommand
  - `--` in every position
  - `-NUM` beside a `dashLed()` slot
  - an option value that looks like a subcommand name
  - an option value that is literally `--`
  - **a global occurrence between a deferred cluster and the subcommand name** (C1a)
  - **a deferred cluster followed by `--`** (C1c)
  - **a built-in after the operand that ends options**, on an `optionsEndAtFirstOperand` root (C2)
  - **a surplus token at a built-in node**, `app completion bash -x` (C3.2)
  - **`--color` followed by a built-in spelling**, `app --color --version` (the strict/greedy split)
  - **a consuming built-in after the operand that ends options**, `app f.txt -e x --json` (C2)
  - **a built-in spelling after `--`**, both `app -- --version` and `app -- --json` (C2)
  - **an unknown option before a dangling global value**, `app list --unknown --gopt` (C4)
  - **a mixed cluster before an unknown subcommand**, with and without a trailing `--help` (C3)
  - **`--` and a surplus token at a built-in node**, `app completion -- bash` and `app completion --json bash`
- Regeneration sits behind a system property. A diff is a review artefact to adjudicate, never an
  auto-accept.

## Ledger

1. **`app sub --ver` ambiguity.** `positionIndependentLongs` resolves a long against a tree-wide pool, so a
   sibling's `--verbose` can decline an abbreviation on `sub`'s behalf. A single walk knows the command.
   **Blast radius is wider than unknown-option wording:** narrowing the pool also moves value slots, so a
   spelling that is ambiguous today claims nothing and lets a following built-in through, while a
   command-scoped pool resolves it and eats the built-in as its value. Measured:
   `app list --li --version` shows the version today. `longMatchPool`'s KDoc states the superset invariant
   this breaks.
2. **`--version` resolves against a tree-wide pool, `--help` against the command's.** Under one walk both
   resolve against the command in scope.
3. **`--help` is resolved twice today, against two pools** (step 4's gate versus step 8). One walk gives one
   answer. `hasHelpRequest`'s KDoc describes the case that changes: a hybrid root given `--h` alongside
   `--completion <shell>`.
4. **`app -lp list <TAB>` completes against the root today**, because `walkTo` stops at the mixed cluster
   while `parseTokens`' routing loop skips it. Unifying them completes against `list`. An improvement, and a
   change.
   **CLOSED 2026-08-14 by `7ede8f6`**, from the other end: the routing loop now stops at a mixed cluster too,
   so the two walks agree by both stopping. `app -lp list <TAB>` still completes against the root, and
   `app -lp list` is now a parse error (`MixedClusterRoutingCompletionTest`, `CompletionTest.kt`).
5. **`-vh` is not a help request today.** `hasHelpRequest` matches `-h` by whole-token equality. A walk that
   resolved cluster chars against built-ins would change that; it must not.
   **INVERTED 2026-08-14 by `7ede8f6`**: `-vh` *is* a help request. `hasHelpRequest` reads the short through
   `Command.namesHelpShort`, which resolves it inside a cluster exactly as a declared short does
   (`docs/guide.md`, "the built-in `-h` clusters with them"; *the help short clusters with a declared short
   in either order*, `BuiltinsTest.kt`). The prohibition this entry states is void.
6. **A group's unknown-option error names a different token.** `app -vq zzz` reports `UnknownOption("-v")`
   today, with `cluster = null`, because the group branch blames `firstToken[1]` without resolving it.
   Reading `events` gives `UnknownOption("-q", cluster = "-vq")`. A correction rather than a loosening: the
   current message names a declared global and hides the cluster the user actually typed.
   **CLOSED 2026-08-14 by `7ede8f6`**, without the walk: the group branch resolves the cluster with
   `firstUnresolvedShort` and reports `UnknownOption("-q", cluster = "-vq")` (*group cluster error names
   first offending char like a leaf*, `ParseOptionsTest.kt`).
7. **A surplus token at a meta-option is already ignored.** On a single-command root, `app --completion bash
   -x` returns `ShowCompletion(BASH)` and drops `-x` silently, where the dispatcher's built-in *node* reports
   `TooManyArguments`. Pre-existing, not caused by this design, and worth deciding rather than inheriting.

## Rejected alternatives

**Deleting the `positions` family** (the first draft's central claim). C1 measures four inputs where it
breaks. Deferral survives the rewrite, deferral is a reordering, and ordering a reordered token needs its
index. What deletes is the *duplication* of that index across passes, not the index.

**Decluster in a pre-step.** Splitting a cluster is spec-dependent and the specs are the command's: `-p8080`
splits to `-p 8080` only if `p` is an option, and `-vS` depends on whether `S` has an `.optionalValue()`.
Errors also name the cluster a char came from, so an expansion would have to carry provenance back.

**A `Sequence` for the args still to parse.** The walk needs `peek`, which an iterator cannot do without a
hand-rolled lookahead buffer, and push-back for the deferral, which a single-pass sequence cannot do at all.

**Keep the built-in pre-scans, unify only the walk.** The pre-scans still need value slots, so `ArityWalk`
survives and klap still has two walks that must agree.

**Two parsers side by side behind a switch.** Strongest differential signal, but it keeps `GlobalPreStrip` and
`positions` alive for the whole project and doubles the surface under review.

**Bind inline during the walk.** Error precedence would become walk-order dependent and completion's
`Lenient` policy would thread through every branch.

**Do nothing, and fix the two named costs individually.** This is the serious alternative and it deserves
stating. Ledger 2 is fixable on its own by moving the `--version` check below the routing walk and resolving
it against `cmd.resolvedLongPool(globalAcc)`, exactly as `--help` already does: small, bounded, reviewable.
Ledger 1 is fixable the same way. If the four resolution rules were not the actual problem, that is the
cheaper path, and after C1, C2 and C3 the remaining win is narrower than the first draft claimed. It is still
a real win, but this comparison is now the decision rather than a formality.

## Out of scope

- Converters, arity, cross-input constraints and `bindPositionals` keep their current behaviour and, apart
  from moving file, their current code. Their *inputs* are produced by `toSifted()` rather than `sift`.
- The public API. No signature in `CommandBuilder`, `CliBuilder` or `Cli` changes.
- `NumericAliasPlacement` (`2026-08-13-numeric-alias-placement-design.md`).
- Error message wording. Messages are parity-checked, not improved.

## Open questions

Two of the first draft's three were answered by measurement rather than argument, which is recorded here
because it is the reason to trust the rest less.

1. ~~Does `Builtin` belong in the event stream?~~ **Answered: no.** C2 measures a token that is simultaneously
   an operand and a built-in sighting.
2. ~~Does anything still need an argv index?~~ **Answered: yes.** C1 measures four inputs that need one.
3. **Corpus size.** Brute combinatorics finds what nobody thought to test, but a corpus in the tens of
   thousands is easy to rubber-stamp. A cap needs picking, and the caps the generator applies need logging so
   a truncated run cannot read as full coverage.
4. **Is the remaining win worth the risk, now that it is only "one resolution rule instead of four"?** This is
   the question the "do nothing" alternative above poses, and the first draft did not pose it honestly.
5. ~~Does the group branch stay raw?~~ **Answered: it cannot.** C3 shows a token list cannot express a
   partially consumed cluster, so "raw" stops being available once a walk has resolved one. The branch reads
   `events`, and the resulting message change is ledger 6.
