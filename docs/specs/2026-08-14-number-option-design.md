# Number option

**Status:** implemented. This file has been rewritten as the design moved; earlier proposals are recorded
under Rejected alternatives rather than deleted, because each was rejected for a reason that will otherwise
be re-proposed.

**Goal:** make `-<NUM>` an input of its own, recognised as a run of digits wherever nothing else has
claimed it, replacing `numericAlias` entirely.

## The problem

`numericAlias(option)` has two faults, one in what it recognises and one in what it is.

**It claims `-<NUM>` only when the entire token is digits**, which is stricter than the tools the form
comes from, and where it declines, the error names a token nobody typed.

| argv | GNU head 9.11 | klap today |
|---|---|---|
| `head -5v f` | 5 lines, verbose | `UnknownOption(-5, cluster=-5v)` |
| `head -12v f` | 12 lines, verbose | `UnknownOption(-1, cluster=-12v)` |
| `head -v12 f` | `invalid trailing option` | `UnknownOption(-1, cluster=-v12)` |
| `head -5 f` | 5 lines | 5 lines |

**It has no existence apart from the option it aliases.** The digits become an occurrence of a named
option, so the form cannot be declared without one and cannot carry validation of its own. A tool whose
only spelling is `-<NUM>` is not expressible.

A help row is the one thing it does not lack. `BuilderImpl.kt` ~120 appends `"or -NUM"` to the aliased
option's `valueHint`, and `head --help` renders it today:

```
-n, --lines <[-]NUM>   print the first NUM lines instead of the first 10 (or -NUM; last of -n, -c wins)
```

The replacement inherits the opposite problem, since it has no spelling to render at all; see Errors.

## Verified before designing

Rows measured, not reasoned about. Against installed binaries: coreutils and git on 2026-08-13, the
cluster forms on 2026-08-14. Against klap itself, through throwaway trees run on 2026-08-14: the
digit-short collision, the `lastWins` default, and the rendered help row above.

**GNU coreutils 9.11, `head`.** `-NUM` is accepted only as the first argument, and only at the head of its
own token.

| argv | result |
|---|---|
| `head -2 f` | prints two lines |
| `head -2 -q f` | prints two lines |
| `head -2 -c 5 f` | accepted, prints five bytes |
| `head -2 -- f` | prints two lines |
| `head -5v f` | five lines, verbose |
| `head -12v f` | twelve lines, verbose |
| `head -v5 f` | `invalid trailing option -- 5` |
| `head f -2` | `invalid trailing option -- 2` |
| `head -q -2 f` | `invalid trailing option -- 2` |
| `head -c 5 -2 f` | `invalid trailing option -- 2` |
| `head -2 -3 f` | `invalid trailing option -- 3` |

**GNU coreutils 9.11, `tail`.** Same rule, different wording, so this is a family convention rather than
one tool's quirk.

| argv | result |
|---|---|
| `tail -2 f` | prints two lines |
| `tail -q -2 f` | `option used in invalid context -- 2` |
| `tail f -2` | `option used in invalid context -- 2` |

**git 2.55.0, `git log`.** The opposite rule: anywhere, and the last one wins.

| argv | result |
|---|---|
| `git log -2 --oneline` | two commits |
| `git log --oneline -2` | two commits |
| `git log -2 -1` | one commit |

**Digit shorts are real.** `ping -4` selects IPv4, and ssh and curl carry `-4`/`-6` too. A tool wanting
both a digit short and `-<NUM>` is a plausible declaration, not an author mistake, which is what rules out
refusing the combination or resolving it by declaration order.

**A digit short that TAKES A VALUE is the hard case**, and klap already answers it. Measured on
2026-08-14 against `option("--two", "-2").int()` beside `numericAlias(lines)`:

| argv | binds |
|---|---|
| `-2 5` | `two=5` — the run `2` is fully covered, so the cluster reading holds and `-2` takes the next token |
| `-25` | `lines=25` — the run `25` is not fully covered, so it is a number, not `-2`'s value |
| `-235` | `lines=235` |
| `-2c5` | `BadValue(--two, c5, "not an integer")` — the run `2` is covered, `-2` takes the rest of the cluster, and no run is left |
| `-2` | `MissingOptionValue(--two)` |

The run is decided before the per-character walk reaches inside it. That is the precedence Semantics has
to state, because the two rules there read as disagreeing about `-25`.

**A `lastWins` loser binds its DEFAULT, not null.** Measured the same day, on
`option("-a").int().default(10)` and `option("-b").int().default(20)` in one `lastWins` set:

| argv | binds | `a() ?: b()` |
|---|---|---|
| `-a 1 -b 2` | `a=10`, `b=2` | `10` — the loser |
| `-b 2 -a 1` | `a=1`, `b=20` | `1` |

`resolveLastWins` writes `absentValue()` (`internal/parse/Parser.kt` ~251), which is the `.default()` when
there is one, not null. A `?:` fold over two members has to survive that, and does not; Surface takes it up.

**A third tool in the corpus has the shape.** `example/task-manager`'s `list` declares
`option("--limit", "-n").int().range(1..100)` and `flag("--reverse", "-r")` (`Main.kt` ~120), so
`taskmanager list -5r` is `head -5v` under another name. It declared no `numericAlias`, so it was a
prospective consumer rather than a migration, but it made the shape three tools wide rather than two. It
adopted the number input with this work.

**klap today**, measured through a `head`-shaped declaration.

| argv | binds |
|---|---|
| `head -5 -1` | `lines=1`, `files=[]` |
| `head -q -2 f` | `lines=2`, `files=[f]` |
| `head -5 -- -1` | `lines=5`, `files=[-1]` |
| `head -n5 ./-1` | `lines=5`, `files=[./-1]` |
| `head -5v f` | `UnknownOption(-5, cluster=-5v)` |

## Surface

```kotlin
command("head") {
    val named = option("--lines", "-n").int()
    val direct = numberOption().int()
    val bytes = option("--bytes", "-c").int()
    val lines = lastOneWins(named, direct)
    lastWins(lines, bytes)
    action { Ok("lines=${lines()} bytes=${bytes()}") }
}
```

`numberOption()` declares an input rather than aliasing one. It returns a handle like `option()` does, takes
the same converters, and is read back the same way. A tool whose only spelling is `-<NUM>` declares it
alone and reads it directly.

**It must be a real `Input`**, not a read-back closure, because its whole value is that it composes with
the constraints klap already has. `head -c 5 -3 f` answers `lines=3, bytes=null` only if the number takes
part in the last-wins resolution that also holds `--bytes` — as a member, not as a value read beside one.
That is the requirement implementation must meet. A fourth `HolderSpec` variant looked like the likely cost
and was not: it is an `OptionSpec` carrying no spellings and an `isNumber` marker, so it reuses the whole
existing bind pipeline — converters, positions, `lastWins` membership, `BadValue` naming — where a new
sealed subtype would have made every exhaustive `when` over `HolderSpec` a compile error and bought nothing.
`lastOneWins`'s handle is the same shape, marked by the `folds` list naming its members.

**No range parameter.** The handle takes `.range()` and `.validate()` directly, like any input. Measured on
today's alias, where digits reach the aliased option's chain: `-50` and `-n 50` both answer
`BadValue(--lines, 50, "must be in 1..10")`.

**A returning constraint is a dependency, not an ergonomic extra.** An earlier draft required a
`lastOneWins(a, b)` yielding the winner's value; a later one dropped it on the premise that
`resolveLastWins` nulls every loser, so `named() ?: direct()` would already be exact last-wins. It does
not null them. `resolveLastWins` writes `absentValue()`, which is the member's `.default()` when it has
one, and the measurement above shows the fold then answering with the LOSER: `-a 1 -b 2` binds `a=10`,
`b=2`, and `a() ?: b()` is 10.

So the fold is correct only while no member of the set carries a `.default()` — a property of the
declaration, not of the parser. `example/head` happens to satisfy it today, which is exactly what makes
the trap worth naming: adding `.default(10)` to `--lines` later, a change that looks local and harmless,
would silently change what `head -c 5 -3 f` answers, with no error anywhere and no test that would have
to be updated to hide it.

`lastOneWins` therefore has to do two things at once, and the second is the load-bearing half:

- yield a handle reading whichever member the user wrote last, or the absent reading when none was written;
- **be usable as a member of an enclosing `lastWins`**, so `head` can hold one set over all three inputs
  and still tell a line count from a byte count. `lastOneWins(named, direct)` folds the two spellings of
  one quantity; `lastWins(lines, bytes)` is the override rule real `head` has between quantities. Reading
  a bare `lastOneWins(named, direct, bytes)` would collapse both into one value and lose the unit, which
  is the thing `head`'s action branches on.

The shape it takes is implementation's to choose; the composition is the requirement. This is a
prerequisite, so it lands with this work rather than after it.

**`numericAlias` is deleted.** Two rules claiming `-<NUM>` would have to be implemented, and kept in step,
in each of the seven places that read a short cluster (see "Where it lands in the parser"), and
`numberOption` is a superset of it.

## Semantics

Two rules. The first says what a number is, the second resolves the only case where something else could
want the same characters.

**A maximal run of digits binds to the number input.** Maximal, so `-12v` is twelve then `v`, never one
then two. Anywhere in the token, so `-5v`, `-v12` and `-s12v` all bind. Whatever the ordinary cluster rules
have already claimed is not available: a value-taking short still takes the rest of its cluster, so `-c5`
is `-c`'s value and no run remains.

**A run every character of which is a declared short is that cluster, not a number.** This is POSIX
guideline 14 — a token identifiable as a group of options is one — scoped to the run rather than the whole
token, which is the only change from today's rule.

**Where the two disagree, the run is decided first.** A digit that is itself a value-taking short
(`option("--two", "-2")`) makes the rules answer `-25` two ways: rule 1 says `-2` takes the rest of its
cluster, so `-25` is `two=5`; rule 2 says the run `25` is not fully covered by declared shorts, so it is
the number 25. **Rule 2 wins.** Where the walk arrives at a digit as a fresh cluster character, the maximal
run is cut out first and rule 2 decides what it is; rule 1's value rule claims only a remainder the walk
was already handed, which is why `-c5` and `-2c5` still give `-c`/`-2` their values.

Two reasons, and the second is the stronger. The measured behaviour above is already this — `-25` binds 25
today — so this is the reading that changes nothing. And the alternative costs a tool an unbounded family
of numbers for one declaration: a `option("--two", "-2")` beside a number option would take `-25`, `-2000`
and every other number beginning with `2` as its own value, silently, with nothing in help to say so.
Guideline 14 already accepts that a declaration changes what a token *means*; letting it change where the
token *splits* is a further step, and it is the one that stops the rule being stateable in a sentence.

| declared | token | result |
|---|---|---|
| `flag("-4")` | `-4` | the flag; the run is fully covered |
| `flag("-4")` | `-45` | number 45; `5` names nothing |
| `flag("-4")`, `flag("-5")` | `-45` | flags `4` and `5` |
| nothing | `-12` | number 12 |
| `flag("-4")` | `-v45` | number 45, after `-v` |
| `option("--two", "-2")` | `-25` | number 25; the run is not fully covered, so `-2` never claims it |
| `option("--two", "-2")` | `-2 5` | `two=5`; the run `2` IS fully covered, so the cluster reading holds |
| `flag("--four", "-4").negatable("-3")` | `-3` | the negation; an explicit negative short is declared too |

"Declared" includes a global's shorts, as it does today: a cluster mixing the two reaches the command's
sift whole. It also includes the shorts a `.negatable(vararg)` gives a flag's negative half, which are
declared spellings like any other — without them `flag("--four", "-4").negatable("-3")` would leave `-3`
binding the number and the negation unreachable, with nothing reported.

Worked, for a `head` declaring `-n` and `-c` (value-taking) and `-q` and `-v` (flags):

```
head -5 f          number = 5
head -5v f         number = 5, verbose
head -v5 f         number = 5, verbose      klap is looser than head here, deliberately
head -v12 f        number = 12, verbose     the run is maximal
head -c5 f         bytes = 5                the value rule ran first; no run is left
head -q -2 f       number = 2               placement is not constrained
head -- -2         operand "-2"             post-`--` tokens are operands, unchanged
```

**Cases this rule settles, stated because a one-sentence rule invites the question:**

- `-0`, `-007`: digits are digits. The run is passed to the handle's converters, so `.int()` makes `-007`
  seven and `-0` zero. A leading zero is not special to the parser.
- A run too large for its converter: the converter's error, exactly as for the long form.
- `-1a2`: two runs in one token, *if* `a` is a flag. Each run is an occurrence of the same input, so the
  last wins, or all are collected under `.multiple()`. Where `a` is a value-taking short there is only one
  run, since `a` takes the `2` as its value by rule 1; where `a` is undeclared the cluster is reported at
  `a`, as it is today. `.multiple()` and a `lastWins` set are mutually exclusive
  (`validateLastWinsMembers`, `BuilderValidation.kt` ~110), so a `head`-shaped tree gets the last-wins
  reading and only a standalone handle can collect both runs.
- `-3n7`: the run `3` binds, then `-n` takes the remaining `7` as its value. Positions come from
  `clusterPosition`, so the enclosing set can order the two — here `-n` sits later in the cluster and wins.
- A negative-number-looking operand is unaffected: it reaches a `dashLed()` slot only when no number option
  is declared, since otherwise the run resolves.

## Where it lands in the parser

Recognition is one function, consulted from every walk that reads a short cluster character by character.
There are **nine**: seven must learn the rule, and two are deliberately exempt. A rule that six of the
seven learn and the seventh does not is the defect class this parser has produced repeatedly, which is why
they are counted out here rather than discovered one bug at a time. The count read 2, then 4, then 8, then
9 across four readings of the same code, which is the argument for enumerating them at all.

1. **`Command.firstUnresolvedShort`** (`internal/parse/Parser.kt` ~818). The pre-walk predicate: the
   leftmost character naming nothing, without recording a hit. **A run that resolves must resolve here**,
   and recognition must live *inside* this function rather than merely run before its callers — the
   dash-led admission consults it *before* the binding walk starts (a walk that records hits as it goes
   cannot be unwound), so there is no "before" left to put recognition in.

   `shortClusterResolvesInFull` (~799) is a one-line wrapper over it, not a separate rule, and it has four
   callers, each of which this one change fixes:
   - the dash-led admission at the top of the cluster arm (~669) — the guard is
     `a numeric alias wins against a marked slot` in `DashLedOperandTest`;
   - the post-operand `MixedClusterAfterOperands` gate (~548);
   - `Command.namesHelpShort` (~847);
   - **`Command.bind`'s group arm (~127)**, which calls `firstUnresolvedShort` directly, not through the
     wrapper, to name the offending character in an unknown-option error at a group.
2. **`Command.sift`'s cluster arm** (`internal/parse/Parser.kt` ~679). The binding walk.
3. **`Command.namesHelpShort`'s own loop** (`internal/parse/Parser.kt` ~848), past the predicate call
   above. Returns false at any character resolving to nothing, so `-5h` is not a help request today. Once
   a run resolves, it is, and its KDoc's promise that the help ladder and the bind cannot disagree about
   one cluster depends on it learning the same rule.
4. **`ArgvScan.clusterClaim`** (`internal/parse/ArgvScan.kt` ~230). The arity walk, which decides whether
   a token claims the next one. It consulted `numericAliasValue` as a whole-token predicate (~232);
   it must instead walk *past* a digit run, or `-5n` claims its value in the sift and claims nothing here.
5. **Completion's short-cluster continuation gate** (`internal/render/Completion.kt` ~160). Offers each
   remaining short as a continuation only when every typed character is a flag, so `-5<TAB>` offers
   nothing once a number option is declared.
6. **`Command.trailingValueOption`** (`internal/render/Completion.kt` ~365). Peels leading flag chars to
   find the option that would consume the NEXT word; a digit stops the peel, so `-5n <TAB>` completes no
   values for `-n`.
7. **`Command.attachedValueOption`** (`internal/render/Completion.kt` ~413). The same peel for a glued
   value, so `-5cpar<TAB>` never reaches `-c`.
8. **`siftGlobals`'s cluster arm** (`internal/parse/Parser.kt` ~1086) — **deliberately exempt**, recorded
   here so its absence is a decision rather than an oversight. It is all-or-nothing against GLOBAL specs
   alone, and `numberOption()` is declared on a command; it must stay ignorant of the rule, because teaching
   it would let the pre-strip claim a token before the command owning the number input is even known.

   **The exemption is only safe because a global option may not hold a digit short.** Its FLAG arm is
   genuinely all-or-nothing, so a digit sets `fullyGlobal = false` and the whole token reaches the
   command's own sift. Its OPTION arm is not: `optionSpecs.findOption(null, ch)` matches the run's first
   digit, takes the rest of the token as that option's value and leaves `fullyGlobal = true`, so the token
   is consumed before any sift sees it. Measured on `globalOption("--two", "-2")` beside a command's
   `numberOption()`: `app go -25` bound `two=5` where every one of the seven informed walks read 25. That
   combination is refused at build time instead (see Errors), which is what makes this entry true.
9. **`Cli.routesTransparently`** (`klap/Parser.kt` ~191) — **deliberately exempt for the same reason**. It
   is the subcommand walk's step-over predicate, and it reads a cluster against the globals and `-h` alone
   (`token.drop(1).all { it.toString() in globalFlagShorts || (builtins.helpShort && it == 'h') }`), so a
   digit already stops the walk. Teaching it the rule would step the walk over a token belonging to a
   command it has not reached, before that command is known.

An earlier draft numbered the dash-led ordering and `shortClusterResolvesInFull` as two constraints. They
are one: the ordering requirement *is* the demand that the predicate know the rule, since the predicate is
what the dash-led admission asks. Both are constraint 1 here. That also sinks "one test per numbered
constraint" as a coverage target — one behavioural test can only ever exercise the predicate through one
of its four callers. Testing states what is actually covered and by what.

## Errors

- **The input has no spelling.** `requireValidSpelling` refuses any one-dash spelling longer than two
  characters (`spelling.startsWith("--") || spelling.length == 2`, `HolderSpec.kt` ~256), so `-<NUM>`
  cannot name it. It is a display label only — help rows, and the `name` field of a `BadValue`, which
  renders as `invalid value '50' for -<NUM>: must be in 1..10`.
- **A nameless input renders a blank help row, and giving it one is part of this work.** `NamedSpec.words()`
  (`internal/render/Help.kt` ~114) builds its signature by joining `shorts + longs`; with neither, the
  signature is the four-space long-option indent and a bare ` <value>`, and the reader sees a described
  row that names nothing. The display label has to reach `words()`. This is the one place the replacement
  is *worse* off than `numericAlias`, which got its `"or -NUM"` onto the aliased option's row for free.
- A run the converters reject: the converter's own error, unchanged — an overflowing run is the same
  `BadValue(-<NUM>, ..., "not an integer")` a non-numeric long value gets, since `.int()` is
  `toIntOrNull()` either way.
- A run with no number option declared: unchanged, `unknown option`.
- `-12x` where `x` is undeclared: reports `x`. Fixing the misleading blame is a consequence of recognising
  the run rather than declining the token.

Three declarations are refused at build time, each because the alternative is a silent wrong answer rather
than an error a user could act on:

- **A second `numberOption()` on one command.** `-<NUM>` can only mean one input. The spec left this open and
  implementation settled it; refusing is the only reading that keeps a digit token meaning one thing, and it
  is what `numericAlias` already did.
- **A global value-taking option with a digit short, anywhere in a tree that declares a number input.** The
  pre-strip resolves globals before any command is known, so it cannot ask the run rule, and its option arm
  would silently take the run as that option's value. See entry 8 under "Where it lands in the parser". A
  global *flag* with a digit short is unaffected and stays legal.
- **`.required()` or `.multiple()` on a `lastOneWins` handle.** The fold has no occurrences of its own, so a
  required one fails on every line, naming a label nobody can type, and a repeatable one writes the absent
  reading — null — into an accessor whose type is a non-null `List`.

## Interaction with `dashLed()`

Independent, but only because constraint 1 holds. A `dashLed()` slot takes a single-dash token resolving to
nothing; once a run resolves, a number never reaches the slot. `echoctl`'s `seek -1h` is unaffected: it
declares no number option, so `1` still resolves to nothing there.

## Migration

Written as the work to do and left in that voice; all of it was carried out. Line citations predate it.

**`example/head`** is a four-part change, not the one-line swap an earlier draft implied. `Head.kt` drops
`numericAlias(lines)` (~50) for a `numberOption()` handle; the constraint widens from `lastWins(lines, bytes)`
(~59) to `lastOneWins` over the two line spellings inside `lastWins(..., bytes)`; and **both** readers of
`lines()` move to the folded handle — the action's `unit` expression (~76) and the `projection` that builds
`HeadInputs` (~87). Missing either read leaves the fixture compiling while `-5` reads back as absent, which
is the shape of failure the whole design exists to remove.

Its two `bindsLoosely` entries stay: klap remains looser than head on placement, deliberately.

Its cluster divergences do **not** close, because it has none to close. The fixture's only digit-cluster
case is `parity.rejects("-5x", "f")` (`HeadParityTest.kt` ~59), which stays a rejection either way — only
the blamed character moves from `-5` to `-x`, which `parity.rejects` does not assert. `-5v` and `-12v`
appeared nowhere in it. They are the forms real head accepts and klap refused, so they were cases to
**add**: new `parity.binds` entries, and the note at ~58 ("the numeric alias claims an all-digit token
only") became false and went.

**`example/git`** needs the same two-handle fold, not a bare `numberOption()` read directly. `Git.kt` ~242
declares `option("--max-count", "-n")` and reads `maxCount()` in the action's filter list (~278) and in the
`GitInputs.Log` projection (~300), and `NOTHING_LOG` pins the field (~561). So `git log` gets a
`numberOption()` beside `--max-count`, its own `lastOneWins(maxCount, direct)`, and both reads moved onto the
fold. `git log -5` (`GitParityTest.kt` ~226) is the case that must keep binding 5; `git log -n 3 -5` is
what the constraint is for, and `git log -2 -1` answers 1 from ordinary last-occurrence-wins on one input.

**Every `numericAlias` caller breaks, tests included.** Beyond the two fixtures, the call appears in
`AbbreviationModeTest` (~94), `AuthorTest` (~212, ~213, ~226), `DashLedOperandTest` (~204),
`OpenFindingsTest` (~89), `ParseOptionsTest` (~1791, ~1808, ~1822 — the whole `NumericAliasTest` class),
and `PosixConformanceTest` (~364, ~378). An earlier draft called the `DashLedOperandTest` guard
"unchanged"; it is not — that test calls `numericAlias(lines)` and has to be ported like the rest. Most
are a one-line swap, but three need thought. `AuthorTest`'s two construction-rule tests
(`asecond numeric alias on one command fails to build`, `a numeric alias naming another commands option
fails to build`) both assert rules that exist only because the alias names another input; the second has
no counterpart at all once the form declares its own, and the first turned into a new question — whether a
second `numberOption()` on one command should be refused — that this spec did not answer and implementation
settled by refusing it (see Errors). And
`PosixConformanceTest`'s `extension numeric alias claims only what no declared short does` is the
guideline-14 case; it should become the collision table's test rather than being deleted.

Removing `numericAlias` is an accepted breaking change; no mitigation is proposed and none is needed. The
library is at `klapVersion = "0.2.0"` (`gradle/libs.versions.toml`) and master already carries three
unreleased ABI breaks from earlier work — `Invocation.ShowVersion` gained a `json` parameter, and
`CliError` gained the `MixedClusterAfterOperands` and `UnroutedSubcommand` leaves — so this joins a break
already pending rather than causing the first one, and costs close to nothing on top. Versioning is its own
piece of work and is not a precondition for this design.

## Testing

**Written first, as the RED half of this cycle** — `NumberOptionTest`, which did not compile and was not meant
to: `numberOption` and `lastOneWins` were the only unresolved references in it, so every compile error was
attributable to something this spec had yet to build.

- The measured `head` cluster forms: `-5`, `-5v`, `-v5`, `-12v`, `-v12`.
- A value-taking short still swallows its cluster: `-c5`.
- The collision table, every row: `-4`, `-45` with one digit short declared and with two, `-v45`, and both
  `option("--two", "-2")` rows.
- Placement is unconstrained: `-q -2 f` and `f -2`. `--` is unaffected, both sides.
- Position and ordering: `-2 -c 5 f`, `-c 5 -3 f`, `-2 -1`.
- The handle takes ordinary converters and reports under its label: a `.range()` rejection, and a run too
  large for `.int()`.
- Leading zeros are not special: `-0` is zero and `-007` is seven.
- Two runs in one token: `-1a2` where `a` is a flag, under last-wins and under `.multiple()`, plus the
  one-run reading where `a` takes a value.
- A run followed by a value-taking short in the same token: `-3n7`, and the separate form `-5n 7`.
- The blame moves to the right character: `-12x` reports `-x`.
- **The help row is not blank.** Asserted against rendered help, since it is the one part of this no
  parsing test can reach and the one part the replacement regresses.
- **The structure**: the handle readable with no option beside it, and composing as a `lastOneWins` member
  folded into an enclosing `lastWins` — the case `example/head`'s parity rests on.
- Constraint 1 through its dash-led caller (`a number outranks a marked slot`), constraints 1 and 3
  through the help ladder (`-5h`), constraints 2 and 4 through the parsing cases and `-5n 7`.

**Written after**, and listed separately because none of it is reachable from the cases above:

- **Completion**, constraints 5 through 7: `-5<TAB>` still offers the remaining shorts, `-5n <TAB>`
  completes `-n`'s values, `-5c<partial><TAB>` completes `-c`'s. A parse-only implementation passes
  everything above and fails all three.
- **Constraint 1's other two callers**: a digit cluster carrying a global written after an operand
  (`MixedClusterAfterOperands`), and a number written at a *group*, which must still be an unknown option
  there — recognition is per-command and must not leak up.
- **Constraints 8 and 9**: a mixed local+global digit cluster still reaching the command's sift whole, and a
  number never routing past the command that declares it, so both exempt walks are pinned as decisions
  rather than as accidentally correct.
- **`example/head` parity**: `-5v` and `-12v` as new `parity.binds` entries, since real head accepts a run
  opening a cluster at any length; `-v12` as a third `bindsLoosely`, since it does not; its two existing
  `bindsLoosely` entries unchanged, and `-5x` still rejected.
- **`example/git` parity**: `log -5` unchanged, plus `log -n 3 -5` and `log -2 -1`.
- **`example/task-manager`**, which adopted it with this work: `list -5r` binding limit and reverse together, and
  `list -101` rejected by the existing `.range(1..100)` under the number spelling.
- **`DashLedOperandTest`**'s ordering guard, ported off `numericAlias`.

**Written last**, once a whole-branch review found what the per-walk tests above could not, because each of
these needs a shape no single walk's test declares:

- **The global-option hole**: a global option with a digit short beside a command's number input, refused at
  build. The defect it replaces bound `two=5` for `app go -25` while all seven informed walks read 25.
- **An explicit negative short is a declared short**: `flag("--four", "-4").negatable("-3")` beside a number
  input binds `-3` as the negation.
- **The fold's cardinality**: `.required()` and `.multiple()` on a `lastOneWins` handle both refused at build.
- **Folds nest**: a fold whose member is itself a fold stands where that member's own winner stands, at any
  depth. The flat reading answered correctly only when the inner fold happened to lose.
- **Both members carry the set's note**, asserted row by row rather than as one substring over the whole
  help text, which either member alone satisfies.

## Rejected alternatives

**Leading-only placement.** An earlier draft proposed a mode accepting `-<NUM>` only as a command's first
token, matching head and tail, so `head -5 -1` would stop silently reading stdin when `-1` was meant as a
filename. Declined: a dash-led token *is* an option under guideline 14, so klap's reading is the conformant
one and head's is the deviation, kept for a pre-getopt obsolescent form. Both escapes already work (`--`
and `./`), and a CLI declaring `-<NUM>` has told its users numbers are options.

The counterweight, recorded because it is the strongest argument for the other side: the failure is
silent — `head -5 -1` reads standard input and hangs on an empty terminal, which reads as the tool being
broken rather than as a typo. Judged not to outweigh the above.

**head's cluster-leading rule** (digits must open the token, so `-v12` errors). Declined: it exists because
a value-taking short takes the rest of its cluster, which makes `-s12v` ambiguous *to a reader*. klap
resolves it from the declarations.

**Resolving a digit/short collision by declaration order.** `-45` would mean flag-then-number or
number-alone depending on which line came first. Deterministic, but it makes a cosmetic reorder change what
argv means, with nothing in the help output to reveal it. The guideline-14 run rule needs no such
dependence.

**Refusing the combination at construction.** Tempting while the collision looks pathological, but `ping
-4` is real, so a tool wanting both a digit short and `-<NUM>` is a legitimate declaration.

That rejection stands for a LOCAL short, which is the case it was argued about: `flag("-4")` beside a
number input builds, and the run rule resolves it. One narrow case is refused, and only because the pre-strip
cannot resolve it: a GLOBAL value-taking option with a digit short, on a tree where any command declares a
number input. See entry 8 under "Where it lands in the parser" for the mechanism, and Errors for the rule.

**A `range` parameter on the declaration.** Dropped after measuring that the existing machinery covers it;
see Surface. A returning `lastOneWins` was dropped alongside it on the same reasoning, and that half is
now withdrawn: the measurement it rested on was wrong, and it is a dependency. See Surface.

**Letting the value rule split a run** — reading `-25` as `-2`'s value where `-2` is a value-taking digit
short. Declined; see Semantics for the two reasons and the measurement.

## Out of scope

- Placement. Settled: klap does not constrain it.
- Any change to `--` or `./` handling, both already correct.
- A returning form of `requireExactlyOne`. `lastWins`'s returning form is a dependency of this work, not
  out of scope; the other constraints' are neither needed here nor blocked by anything here.

## Open questions

1. **Name.** `numberOption()` reads well but the thing it declares takes a value, so it is not a flag in
   klap's sense. `numberOption()` is accurate and duller.

   *Resolved: `numberOption()`.* It shipped as `numberFlag()` first, on the weak ground that the parked
   tests pinned that spelling, and was renamed before release. `flag` and `option` are a load-bearing
   distinction in this DSL — `flag()` takes no value and rejects the `Opt` converter chain — so a
   value-taking input named `numberFlag` contradicted the vocabulary at every call site that wrote
   `numberFlag().int()`. The rename cost nothing because `v0.2.0` ships `numericAlias`, so no release ever
   carried the wrong name.
2. **Does anything else in the corpus want it?** Answered. `example/git` and `example/head` were the
   only `numericAlias` users, but `example/task-manager`'s `list` has the shape without the declaration:
   `--limit/-n` with `.int().range(1..100)` beside `--reverse/-r`, so `taskmanager list -5r` is `head -5v`
   under another name (`Main.kt` ~120). That makes three tools, one of which is not a coreutils clone —
   which is the half of the question that mattered. Whether it should actually adopt it is the tool's call.

   *Answered:* it adopted it. `example/task-manager`'s `list` folds `--limit/-n` with a `numberOption()` of
   its own, so all three corpus tools declare one.

## Feedback

From the echo / echoctl consumer, 2026-08-13, written against the leading-placement draft. Kept because
the reasoning is what produced the current shape; resolution notes added.

**One correction, found in review:** the section below says `head -q -2 f` "silently reads standard input
instead of the named file". It does not — klap binds `files=[f]` there, as the "klap today" table shows.
The silent case is `head -5 -1`. The argument stands on that line instead, and is weaker than written.

### Direct answers to open questions 2 and 3: echo provides no demand

echoctl declares **no `numericAlias` anywhere**. Its only dash-led construct is `seek`'s operand, which
is a `dashLed()` positional carrying a unit suffix (`-1m`, `-500ms`, `-1h`), not a bare `-<NUM>`
shorthand for an option. So the one production consumer that has been through a full port adds nothing
to the case, and open question 2's "no klap consumer has asked for it" should be read as still true
after checking rather than merely unchecked.

Taken at face value that argues for **not building this yet**. The counterweight is the failure mode,
and it is the right one to weigh: `head -q -2 f` silently reads standard input instead of the named
file. Every genuinely dangerous gap this consumer has hit in klap has been a silent one, including both
bugs it shipped during the port, so "the failure is invisible" carries more weight than "someone asked".
That reasoning is sound; it is the demand that is absent, not the danger.

*Resolved:* the silent failure was weighed and accepted as the user's own to avoid, so it no longer
carries the proposal. What survives is the recognition and coupling work, which has measured parity
evidence behind it and does not rest on demand.

### One gap: `Leading` combined with `dashLed()` is unspecified

Reasoned from the described implementation, not measured, since nothing is built yet.

"Interaction with `dashLed()`: None" is too strong once `Leading` exists. The ordering the spec fixes is
long option → alias arm → short cluster, with the dash-led admission inside that last arm. Under
today's default the alias claims `-<NUM>` first, so a marked slot never sees it and "None" holds. Under
`Leading` the alias arm *declines* a non-leading `-2`, which then falls through to exactly that dash-led
admission.

So on a command declaring both, `cmd -q -2 f` would bind `-2` as the **operand**, not report an unknown
option.

*Resolved by rejection:* nothing declines a qualifying run any more, so no token falls through. The
ordering concern it raised is not resolved — it is now constraint 1 under "Where it lands in the parser".
It also turned out sharper than written here: the dash-led admission is not something recognition can run
*ahead of*, because it is a pre-walk predicate rather than a step in the walk. Recognition has to live
inside that predicate.

### Open question 1: the enum

`numericAlias(lines, NumericAliasPlacement.Leading)`. A boolean reads as `numericAlias(lines, true)` at
the call site, which says nothing, and a second function name buries the choice in autocomplete. klap
already spends a public type on exactly this kind of switch with `Abbreviation`, so the cost is
precedented rather than new.

*Resolved by rejection:* there is no placement setting to name. The argument still applies to any future
switch on this declaration.
