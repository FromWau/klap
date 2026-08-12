# Dash-led operands, by opt-in

**Status:** implemented on `feat/dash-led-operands`.

**Goal:** let one declared operand accept a single-dash token such as `-1m`, without changing what any
existing CLI does and without letting an operand shadow anything the author declared.

## The problem

A dash-led token is an option token. `seek -1m` reports `unknown option '-1' (in '-1m')`, because short
clustering splits `-1m` into `-1` and `-m` and neither is declared. That is deliberate and tested: the
rationale above `String.isFlagLike` explains that exempting `-100` so it could reach a numeric positional
would make `ls -5` bind a file named `-5`, a silent mis-binding.

The stance is right as a default. It is wrong as the only option, because a real grammar shape falls
outside it: a number carrying a unit, used as an operand. `echoctl seek -1m`, `seek +500ms`, `seek -100`.
The klap consumer that hit this recorded it across two entries of its own friction log, checked klap's
tests, agreed the stance was deliberate, and worked around it by respelling the operand as an option.

Neither existing escape fits well:

- `seek -- -1m` works, but the error a user meets on the way to learning it names a token (`-1`) that is
  not in what they typed.
- `seek --offset -1m` works, because an option's value slot takes the next token whatever it looks like.
  It changes the tool's grammar to suit the parser.

## Verified before designing

Every row below was run against master, not reasoned about.

| Configuration | argv | Result today |
|---|---|---|
| plain | `seek -1m` | `unknown option '-1' (in '-1m')` |
| plain | `seek 0 -1m` | `unknown option '-1' (in '-1m')` |
| `optionsEndAtFirstOperand` | `seek 0 -1m` | binds `off=0 to=-1m` |
| `optionsEndAtFirstOperand` | `seek -1m` | `unknown option '-1' (in '-1m')` |
| plain, global option declared | `seek -1m` | `unknown option '-1' (in '-1m')` |
| plain | `-1m seek` | `unknown option '-1'` |
| plain | `seek -- -1m` | binds `off=-1m` |
| plain | `--amount -1`, `--amount=-1`, `-a -1` | all bind `-1` |
| plain | `seek +500ms` | binds, `+` is not dash-led |

Two facts from that table shape the whole design.

**klap already turns dash-led tokens into operands.** `optionsEndAtFirstOperand` does it. What no switch
can currently do is start in that mode: the trigger is a preceding non-dash operand, so the *first*
operand can never be dash-led. That is the entire gap.

**An option's value slot already accepts anything.** No part of this design touches that path.

## Surface

One modifier on an argument:

```kotlin
command("seek") {
    val position = argument("position", "1-9, or +/-N with a unit (ms|s|m|h)").dashLed()
    action { Ok(seekTo(position())) }
}
```

That is the consumer's real declaration, one operand and no converter, because the command parses the
string itself. The two-operand shape appears only in the semantics table below, where a second unmarked
slot is what makes `seek 0 -1m` an error.

`dashLed()` sets display and parse metadata rather than transforming a value, so it is order-free like
`placeholder()`, `file()` and `hidden()`: `argument("x").dashLed().int()` and `argument("x").int().dashLed()`
mean the same thing.

It is declared per argument and not per command, so the declaration names the slot that accepts dash-led
tokens. A command with three operands says which one it is.

## Semantics

Three rules, in precedence order.

1. **Declared wins, and resolution is all-or-nothing.** A token that resolves *in full* parses as what it
   resolves to: a flag, a short cluster, a long option, an abbreviation, a `numericAlias`, or a built-in
   such as `-h`. An operand can never shadow a declaration. This is what keeps the feature safe, and it is
   why `--` remains necessary for an operand that genuinely collides.

   **A short cluster resolves only if every character in it resolves.** One declared character does not
   rescue a cluster that fails elsewhere, and a declared character sitting *behind* an undeclared one is
   never reached. This is measured behaviour on master, not a new rule:

   ```
   -h     ShowHelp                                     resolves in full
   -1h    UnknownOption(token=-1, cluster=-1h)         -1 resolves to nothing, and neither would h
   -24h   UnknownOption(token=-2, cluster=-24h)        same, first failure wins
   -hh    UnknownOption(token=-h, cluster=-hh)         `h` resolves to nothing in a cluster; same reason -1h fails
   ```

   So all three of `-1h`, `-24h` and `-hh` are candidates for a marked slot, and `-h` alone is not. The
   alternative reading, "any declared character anywhere in the cluster wins", is explicitly **rejected**:
   it would cost every negative hour offset (`-1h`, `-24h`) for no safety gain, since nothing in those
   clusters resolves anyway.
2. **Single dash only.** A token starting with exactly one `-` may reach a `dashLed()` slot. A `--`-led
   token is always an option. See *Rejected alternatives*.
3. **The slot must be marked.** A dash-led token landing in a slot without `dashLed()` is an unknown
   option, exactly as today, with today's message.

Worked through, for a `seek` declaring `flag("--verbose", "-v")` and `argument("offset").dashLed()`:

```
seek -1m           offset = "-1m"          matches nothing, slot is marked
seek -100          offset = "-100"
seek +500ms        offset = "+500ms"       already works, + is not dash-led
seek -v            verbose = true          declared, rule 1
seek -h            help                    built-in, rule 1
seek -1h           offset = "-1h"          neither -1 nor -h resolves inside a cluster
seek -hh           offset = "-hh"          the built-in -h matches a whole token only, never a cluster char
seek -- -v         offset = "-v"           escape still works
seek --verbsoe     unknown option, did-you-mean   rule 2 keeps the suggestion
seek 0 -1m         unknown option '-1' (in '-1m') second slot is not marked, rule 3
```

`--` is unchanged, and remains the portable spelling that needs no declaration.

## Where it lands in the parser

Rule 1 makes the trigger condition identical to "klap would have reported `UnknownOption`". That collapses
the change from the twelve `isFlagLike()` call sites to two phases that already exist.

**Sift** (`Parser.kt`, the short-cluster branch that today raises `UnknownOption`). When the command
declares a `dashLed()` argument, collect the token into `positionals` instead of failing. The loop already
accumulates `positionals` as it walks, so no new state is needed.

**Bind** (`bindPositionals`). It already assigns collected positionals to `ArgumentSpec`s and already
honours `absentWhen`, `requiredUnless` and `multiple`. It enforces rule 3: a dash-led positional landing in
an unmarked spec produces the `UnknownOption(token, cluster)` the sift would have produced.

Completion needs no separate work. It runs the same sift and the same binder under `BindPolicy.Lenient`,
so it follows the parser by construction. A test pins that rather than assuming it.

### The one question the plan must settle

Sift decides before bind knows which slot a token lands in, and "the next unfilled slot" is not
`arguments[positionals.size]` once `absentWhen()` removes a slot or a variadic absorbs several.

Two candidate answers, to be probed rather than assumed:

- **(a) Permissive sift, precise bind.** Sift admits a dash-led token whenever *any* argument on the
  command declares `dashLed()`; bind rejects it if the slot it actually lands in is unmarked. Simple, no
  new index arithmetic, and the error still carries the right token and cluster.
- **(b) Shared slot resolution.** Extract the index-to-spec mapping that bind performs and call it from
  sift too. Precise in one place, at the cost of running slot resolution twice per parse.

(a) is the recommendation. (b) only becomes necessary if a case exists where admitting a token into
`positionals` changes an outcome that bind cannot then undo, which the plan must look for explicitly.

The motivating consumer cannot settle this. Its `seek` declares a single operand, so (a) and (b) are
indistinguishable there. The probe has to be built from a command with a marked slot plus `absentWhen()`
or a variadic, which no consumer has yet.

## Errors

No new `CliError` case. A dash-led token that does not reach a marked slot stays
`UnknownOption(token, cluster)`, which already names both the character and the word it came from. From
the user's side that token really is an unknown option, and inventing a second error for it would ask them
to care about a distinction only the author can act on.

**What marking a slot gives up.** On a command with a marked slot, a single-dash token that resolves to
nothing no longer produces `UnknownOption`; it binds. So a genuine single-dash option typo becomes a
positional, and the author's own value error replaces klap's:

```
seek -vv     without dashLed():  unknown option '-v' (in '-vv')
             with dashLed():     binds as the operand, then fails the command's own value check
```

This is a real cost and it is the price of the feature, not an oversight. Rule 2 contains it: `--`-led
tokens are always options, so `--jsn` keeps its did-you-mean, and only commands that opt in are affected.
A short never had one to lose: `clusterCharError` leaves `suggestion` null, because a single letter is
too noisy to edit-distance against.
Whether the trade is good is the author's call and depends on their error text. A command whose value
error names the whole accepted grammar is usually clearer than `unknown option '-v'`; a command with a
terse value error is worse off. The guide must say this where `dashLed()` is documented, so the choice is
made knowingly.

## Help and docs output

A `dashLed()` operand renders as it does today. The modifier changes what parses, not how the operand is
described, and a placeholder already covers the case where the author wants `<OFFSET>` rather than
`<offset>`.

## Testing

- **The default is untouched.** `PosixConformanceTest` keeps asserting that `ls -5` and `sleep -1` reject,
  unchanged and unedited. If any existing parser test needs editing, the opt-in leaked into the default.
- **A third documented exit from POSIX.** klap advertises conformance, and `README.md:125` currently
  describes the account as "the one option-level opt-out and the one switch that trades an extension
  back". `dashLed()` is neither: it is argument-level. So `PosixConformanceTest` gains a case, the guide's
  POSIX section gains an entry, and that README sentence must stop counting, because a count in prose is a
  claim nothing verifies and this is the second time it would need touching.
- **Rule 1 is the safety property**, so it gets the most cases: a declared short, a declared long, an
  abbreviation, a `numericAlias`, and the `-h` built-in all still win against an open marked slot.
- **All-or-nothing cluster resolution gets its own cases**, because the outcome currently follows from
  left-to-right evaluation order rather than from anything named: `-1h`, `-24h` and `-hh` all reach a
  marked slot even with the `-h` built-in present, since it matches a whole token only. Without these, a
  future change to cluster evaluation could take negative hour offsets away silently.
- **The error trade is pinned**: on a marked command a single-dash typo binds, and on the same tree
  without the mark it still reports `UnknownOption`.
- **The consumer's real shapes**: `-1m`, `-100`, `+500ms`, `-- -v`, and `dashLed()` combined with
  `multiple()` and with `optional()`.
- **Completion agrees with the parser** for a dash-led current word against a marked slot.

## Rejected alternatives

**Changing the default.** Every CLI would lose the ability to distinguish a mistyped option from an
operand, and `ls -5` would bind a file named `-5`. The rationale comment above `isFlagLike` already argues
this and the argument holds.

**A per-command switch.** Closer to `optionsEndAtFirstOperand`, and simpler to implement, but it does not
say which operand accepts dash-led tokens. A command with three operands would accept them into any of
them, which is broader than any known need.

**Double-dash operands.** A `--`-led token that matches no declaration would become an operand. Rejected
for now, not forever: it would swallow long-option typos that today produce a did-you-mean suggestion, and
abbreviation makes "matches no declaration" much subtler to define. No consumer has asked for it. If one
does, it is a follow-up spec, and this design does not preclude it.

**A new error case for a dash-led token in an unmarked slot.** See *Errors*.

## Out of scope

`optionsEndAtFirstOperand` is untouched. The option value path is untouched. The default parse is
untouched.

## Feedback

From the echo / echoctl consumer, 2026-08-12. This is the CLI the problem statement refers to, so the
evaluation below is against `seek`'s real grammar, read out of `parseSeek` / `relativeSeek` /
`decileSeek` / `SEEK_UNITS` rather than out of its help string.

### Does it solve the problem? Yes, completely.

echoctl's `seek` accepts exactly four shapes. Measured against the published build, as bare positionals:

| shape | examples | today |
|---|---|---|
| decile, bare `1`-`9` | `seek 5` | binds |
| relative, unsigned | `seek 2s` `seek 500ms` `seek 1m` `seek 1h` | binds |
| relative, explicit `+` | `seek +500ms` `seek +1h` | binds |
| relative, negative | `seek -1m` `seek -500ms` `seek -2s` `seek -1h` | `unknown option` |

Only the fourth row is affected, and `dashLed()` covers all of it in one declaration:

```kotlin
argument("position", "1-9, or +/-N with a unit (ms|s|m|h)").dashLed()
```

No converter, because echoctl parses the string itself. The other three rows are untouched.

**Rule 1 costs echoctl nothing.** Worth stating because it is the rule carrying the safety argument: no
valid `seek` input can resolve as a declared cluster. The units are `ms`, `s`, `m`, `h` and the
magnitudes are digits, so a token like `-2s` would need both `-2` and `-s` declared to be shadowed, and
echoctl declares neither. The precedence rule never fires against a legitimate value here.

### Is it elegant for `seek`? Yes, and `seek` is close to the ideal case for it.

- **One operand**, so per-argument marking is unambiguous. The rejected per-command switch would have
  been indistinguishable here, but the per-argument form costs nothing and reads better.
- **Zero user-visible change.** This is the decisive property. The consumer had already taken a decision
  between the two existing escapes, and both were user-facing regressions: `--offset -1m` adds a second
  spelling for one of four shapes, and `seek -- -1m` leaves the natural typing failing. `dashLed()`
  removes the decision rather than resolving it.
- It keeps the grammar the tool's rather than the parser's, which is the spec's own framing of why
  neither escape fits, and that framing holds up from this side.

### Gap 1: a PARTIALLY matching cluster is unspecified, and echoctl depends on the answer

Rule 1 is written over "a token that resolves to anything the tree declares". Every worked example is a
cluster that fully matches (`-v`, `-h`) or fully misses (`-1m`). echoctl has a third case:

```
seek -1h      cluster is -1 + -h:  neither resolves, though -h alone IS the help built-in
```

**Every negative hour offset has this shape**, and `-1h` is a documented `seek` form.

Measured, so the answer is known rather than assumed:

```
-1h    -> UnknownOption(token=-1)
-24h   -> UnknownOption(token=-2)
```

The sift fails on the first undeclared short and never reaches the `-h`. So under this design's trigger
condition, which the spec defines as "identical to klap would have reported `UnknownOption`", `-1h`
qualifies and reaches the marked slot. **The outcome is correct.**

The concern is that it is correct *incidentally*, as a consequence of left-to-right cluster evaluation,
and nothing pins it. The Testing section lists "a declared short, a declared long, an abbreviation, a
`numericAlias`, and the `-h` built-in all still win against an open marked slot", which tests `-h`
alone. It does not test a cluster where a declared short sits *behind* an undeclared one. Suggest adding
to the worked examples and to the rule 1 cases:

```
seek -1h     offset = "-1h"     -1 is undeclared, so the cluster never resolves; -h is not reached
```

If the intended rule is instead "any declared character anywhere in the cluster wins", then `-1h` breaks
and echoctl loses hour offsets, so the two readings are worth separating explicitly in the text.

### Gap 2: a marked slot converts single-dash option typos into value errors

Follows from rules 1 and 3 rather than from a measurement, and the Errors section does not name it.

On a command with a marked slot, a token that matches no declaration no longer produces
`UnknownOption`; it binds. So a genuine single-dash option typo becomes a positional:

```
seek -vv       today: unknown option '-v' (in '-vv')
               with dashLed(): binds as position, then fails with the COMMAND's value error
```

Rule 2 contains the blast radius usefully: `--` led tokens are always options, so `--jsn` keeps its
did-you-mean. Only single-dash typos are affected, and only on commands that opt in.

For echoctl this is an acceptable trade and arguably an improvement, since `seek`'s own error
(`must be 1-9 ... or a relative offset with a unit: +500ms, 2s, -1m, +1h`) names the actual input and
the whole grammar, where `unknown option '-v'` names neither. But it IS the cost of marking a slot, an
author choosing `dashLed()` should know they are trading that report away on that command, and the
Errors section is where that belongs. The current text argues the converse case (why an unmarked slot
keeps `UnknownOption`) and reads as though nothing is given up.

### Smaller notes

- **The worked example's `seek` is not this `seek`.** It declares two operands (`offset` and `to`).
  echoctl's has one (`position`). Since the example borrows the consumer's command name, matching the
  real shape would make it concrete, and the two-operand case is already carried by the `seek 0 -1m`
  line in the semantics table.
- **(a) vs (b) cannot be settled from here.** With a single marked operand the two are
  indistinguishable, so the motivating consumer provides no evidence either way. (a) still looks right;
  just do not read the consumer's case as support for it.
- **The `README.md:125` catch is correct and worth keeping.** A count in prose that nothing verifies has
  now needed touching twice, which is the argument for removing the count rather than incrementing it.
- **Verification note on the table.** The published artifact this consumer builds against renders
  `unknown option '-1'` without the cluster; master carries `UnknownOption(token, suggestion, cluster)`
  and renders `(in '-1m')`. The table is accurate against master, as it says. Flagged only so the
  difference is not mistaken for a discrepancy by anyone re-running these rows against a release.

### Verdict

Worth building, and echoctl adopts it the moment it lands. It is the only one of the three options that
leaves the tool's grammar unchanged, and the one-operand `seek` case exercises the core of the design
without exercising its harder corners. Gap 1 is the one to close before implementation, because it is
the difference between echoctl keeping hour offsets and losing them, and it currently rests on an
evaluation-order detail rather than on a stated rule.

## Feedback, round 2: after shipping, from the completed migration

2026-08-12. `dashLed()` is implemented and echoctl's port to klap is finished and green. This round is
written from the far side of that: what the implementation did to the real consumer, not what the
design promised.

### Gap 1 is closed, and closed correctly

The revised rule 1 ("declared wins, and resolution is all-or-nothing", with the explicit note that a
cluster resolves only if every character resolves) is exactly right, and the worked examples now cover
the case that mattered. Measured against the published build, on a tree declaring echoctl's real
globals (`-i` repeatable, `-A`) rather than a toy:

```
seek -1h    -> pos=-1h     -1 undeclared, so -h is never reached
seek -24h   -> pos=-24h
seek -hh    -> pos=-hh     -h IS declared; the cluster still fails as a whole
seek -1A    -> pos=-1A     -A is a declared global flag
seek -1i    -> pos=-1i     -i is a declared global option
seek -h     -> ShowHelp    resolves in full, rule 1 wins
```

Rejecting the "any declared character anywhere wins" reading in the spec text, rather than leaving it to
evaluation order, is what makes this safe to depend on. Every negative hour offset works.

And on the shipped native binary, which is the line the whole exercise was for:

```
$ echoctl seek -1m
error: cannot reach daemon at 127.0.0.1:6680 (connection refused)     # not "unknown option '-1'"
```

**One declaration, zero user-visible change.** The consumer had already taken a decision between the two
existing escapes, and both were regressions; this removed the decision instead of resolving it.

### What the migration exercised beyond `seek`

Reported because it is evidence about the library as a whole, from a real port rather than a probe.
echoctl is now ~2,700 lines lighter, having deleted its parser, help renderer, completion generator and
arity checks. Things that worked with no friction: `globalOption(...).multiple()` for a repeatable
`-i`, `.int().range(1..65535)`, `example(...)`, the `CommandBuilder` extension pattern for factoring a
20-command tree into per-group builders, `parse` + `runAction` for parser-only tests, and
`actionSuspending` + `runSuspending` end to end on **linuxX64 native**, not only JVM.

`CliError.Domain` deserves specific mention: carrying echoctl's own 15-case error hierarchy through with
its `exitCode` and rendered detail intact is what made the port a grammar swap rather than an error-model
rewrite. Exit codes and the `--json` stderr envelope came out byte-identical to the hand-rolled CLI.

### The one seam that cost real time

**A success value cannot carry a non-zero exit**, so a command that must print output *and* exit
non-zero has no direct expression. echoctl's `--all` fan-out is exactly that: print a per-instance block,
exit 1 if any instance failed.

The guide's "Actions that print their own output" section is where a reader looks, and it answers the
printing half (`ActionScope.json` to hold back the human half) while stopping short of the exit half.
Following it produced a real bug that reached a live-daemon run: printing the block and returning an
empty sentinel `Ok` emitted **two JSON documents on stdout** under `--json`, the array plus
`{"output":""}`, because klap serializes whatever the action returns. Invisible under the human renderer,
where the empty string prints nothing.

The fix is fine and arguably better structured: on success the block *is* the returned value, so klap
renders it and stdout carries one document; only the failure path prints and returns `Err`. But it is
not the shape the guide leads you toward, and the failure mode is silent for text output and broken for
machine output.

Two suggestions, in order of value:

1. **Extend that guide section to name the exit-code half**, with the "print on failure, return the value
   on success" shape spelled out. A sentence would have prevented this.
2. Consider whether a success value can declare an exit code. Any CLI fanning out across N targets has
   "partial success" as a first-class outcome, and today it must be modelled as an error.

### Smaller notes

- `dashLed()` composed with everything it was tried against (a required operand, alongside repeatable
  and value-taking globals, in both argument orders). No interaction surprises.
- The `-h`-inside-a-cluster case is now the example most worth keeping in the docs; it is the one a
  reader will not predict, and it is the one a unit-suffixed grammar hits constantly.

### Resolution

The feedback is folded into the sections above rather than answered here. What changed:

- **Gap 1 is closed by sharpening rule 1**, not by adding a rule. Resolution is now stated as
  all-or-nothing over the whole token, with the measured `-h` / `-1h` / `-24h` / `-hh` table inline and
  the "any declared character wins" reading explicitly rejected. `-hh` was found while re-measuring and
  settles the question better than `-1h` does: `-h` *is* declared there, and the cluster still fails, so
  the rule cannot be about whether a declared character is present. Both `-1h` and `-hh` are now worked
  examples and both are named in Testing, so the behaviour stops resting on evaluation order.
- **Gap 2 is now in Errors**, as a stated cost with the `-vv` case, plus a requirement that the guide say
  it where `dashLed()` is documented. The section previously argued only the converse and read as though
  nothing was given up. The judgement of whether the trade is good is left to the author, because it
  depends on their value error, which is exactly the consumer's own reasoning.
- **The Surface example is now the consumer's real one-operand declaration.** The two-operand shape stays
  in the semantics table, where the second unmarked slot is what makes `seek 0 -1m` an error.
- **(a) vs (b) records that this consumer cannot settle it**, and names what a probe would need.

Not changed: the `README.md:125` catch stands, and the note about the published artifact rendering
`unknown option '-1'` without the cluster is accurate. The table is stated as measured against master and
stays that way.
