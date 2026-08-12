# Numeric alias placement

**Status:** draft, not approved. Written from a measured divergence in `example/head`; no implementation
work has started.

**Goal:** let a CLI say that its `-<NUM>` shorthand is accepted only as the first token of a command, the
way coreutils accepts the obsolescent form, without changing what `numericAlias` does for every CLI that
already uses it.

## The problem

`numericAlias(option)` makes `-<NUM>` shorthand for an option's value. klap claims that token **wherever
it appears** in a command's segment. Two real tools that both use the form disagree about that, and klap
matches one of them:

- `git log --oneline -2` is accepted by real git, and by klap.
- `head -q -2 f` is rejected by real head, and accepted by klap.

The second is not merely permissive. On a tool whose operands are filenames, a file genuinely named `-1`
is unreachable in real head without `--` or `./`, which is exactly why real head raises an error. klap
instead binds the token as a count and leaves the operand list **empty**, so the line silently reads
standard input rather than the file the user named. The failure is invisible: no error, no missing
argument, just the wrong input.

`example/head` records this today with `bindsLoosely`, which is the fixture suite's honest way of saying
klap is looser than the tool it models. This spec asks whether to close it instead.

## Verified before designing

Every row was run against the installed binary on 2026-08-13, not reasoned about.

**GNU coreutils 9.11, `head`.** The rule is that `-NUM` is accepted only as the first argument.

| argv | result |
|---|---|
| `head -2 f` | prints two lines |
| `head -2 -q f` | prints two lines |
| `head -2 -c 5 f` | accepted |
| `head -2 -- f` | prints two lines |
| `head f -2` | `invalid trailing option -- 2` |
| `head -q -2 f` | `invalid trailing option -- 2` |
| `head -c 5 -2 f` | `invalid trailing option -- 2` |
| `head -n 2 -3 f` | `invalid trailing option -- 3` |
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
| `git log --format=%h -2` | two commits |
| `git log -2 -1` | one commit |

**klap today**, measured through `example/head`'s own declaration:

| argv | binds |
|---|---|
| `head -5 -1` | `lines=1`, `files=[]` |
| `head -n5 -1` | `lines=1`, `files=[]` |
| `head -n 5 -1` | `lines=1`, `files=[]` |
| `head -5 -- -1` | `lines=5`, `files=[-1]` |
| `head -n5 -- -1` | `lines=5`, `files=[-1]` |
| `head -n5 ./-1` | `lines=5`, `files=[./-1]` |

Three facts from those tables shape the design.

**The two tools want opposite rules**, so a global change to `numericAlias` cannot serve both. Making the
alias leading-only by default would break `example/git`, whose parity currently holds.

**Both escapes already work.** `--` and `./` reach the file in klap exactly as they do in the real tool,
so this is about closing a hole rather than opening a path.

**The fixture cannot fix this by itself.** klap's public constraints are `requireExactlyOne`,
`requireAtMostOne`, `lastWins` and `numericAlias`. None is position-aware. Token positions *are* tracked
internally (`Sifted.flagPositions`, `Sifted.optionPositions`, `clusterPosition`, all of which `lastWins`
already reads), so the information exists; nothing exposes it to a declaration.

## Surface

One opt-in on the existing call. The default stays what it is today, since changing it would be a
breaking change for every current consumer and would cost `example/git` its parity.

```kotlin
command("head") {
    val lines = option("--lines", "-n")
    numericAlias(lines, NumericAliasPlacement.Leading)
}
```

The API shape is an open question, see below. Whatever it is, `numericAlias(lines)` must keep meaning what
it means now.

## Semantics

One rule, and everything the tables above show falls out of it:

**Under `Leading`, the alias may claim only the first token of the command's own segment.** A token that
looks like `-<NUM>` anywhere else is not the alias, so it reaches the short-cluster walk and is reported as
the unknown option it is.

Worked against the measured lines, for a `head` declaring `-n`, `-c`, `-q`:

```
head -2 f          lines = 2          first token of the segment
head -2 -q f       lines = 2          still first
head -2 -c 5 f     lines = 2          still first, and -c still overrides per lastWins
head -2 -- f       lines = 2          first, and the escape is unaffected
head f -2          unknown option     not first
head -q -2 f       unknown option     not first
head -c 5 -2 f     unknown option     not first
head -2 -3 f       unknown option     -2 claimed; -3 is not first
head -- -2         operand "-2"       post-`--` tokens are operands, unchanged
```

"At most one" needs no separate rule: a second `-<NUM>` cannot also be first.

**Segment, not argv.** The first token *after* the subcommand path, so a tree that opts in behaves the
same at any depth. For `head` there are no subcommands and the two readings coincide; naming the segment
is what makes the rule stateable for a tool that has them.

**Globals do not get a pass.** `head --verbose -2 f` has `--verbose` first, so `-2` is not the alias. That
matches the measured `head -q -2 f`.

## Where it lands in the parser

`numericAliasValue` is consulted from a `when` arm in `Command.sift`'s token loop, ahead of the
long-option and short-cluster arms. The arm already knows the token's index `i`. Under `Leading` it
additionally requires that no token has been consumed yet.

The check belongs in that arm and nowhere else. It must stay **ahead** of the dash-led admission, which is
already load-bearing and pinned: `a numeric alias wins against a marked slot` in `DashLedOperandTest`
asserts that `head -5` binds the alias rather than reaching a `dashLed()` operand.

## Errors

klap says `unknown option '-2'`; real head says `invalid trailing option -- 2` and real tail says `option
used in invalid context -- 2`. All three reject, and the fixture suite's `rejects` asserts only that a
line does not parse, so the cases become honest parity rather than a documented divergence. The wording
difference is not worth a new `CliError` case: it is the same class of mistake klap already names.

## Interaction with `dashLed()`

None, and this is worth stating because the two features look adjacent. Marking `head`'s `file` operand
`dashLed()` would not fix any line in this spec, because the alias resolves ahead of the dash-led
admission. It would also be wrong on its own terms: real head rejects `head -x f`, so the operand must
stay unmarked. `example/head` carries a note at the declaration saying so.

## Testing

- The nine measured `head` lines above, as parity cases, replacing the two `bindsLoosely` entries the
  fixture currently carries.
- `example/git` unchanged and still green, which is what proves the default did not move.
- A klap-level test that `numericAlias(option)` with no placement argument still claims `-NUM` anywhere,
  so the existing behaviour is pinned rather than assumed.
- A klap-level test for `Leading` on a tree **with** subcommands, since the fixtures cannot cover the
  segment-versus-argv distinction.

## Rejected alternatives

**Make leading-only the default.** Breaks `example/git`, whose parity is measured and currently holds, and
is a silent behaviour change for any consumer already relying on the current rule.

**Infer the rule from the declaration**, for example "leading-only when the command has a `.multiple()`
file operand". Too clever, and wrong for a tool that wants git's rule alongside operands.

**Reject only the second occurrence**, leaving placement alone. Closes `head -2 -3 f` but not
`head -q -2 f` or `head f -2`, which are the cases that actually cost the filename.

## Out of scope

- The wording of the rejection. klap's existing `unknown option` is enough.
- `example/git`'s rule, which already matches.
- Any change to `--` or `./` handling, both already correct.

## Open questions

1. **API shape.** `numericAlias(lines, NumericAliasPlacement.Leading)` with a two-case enum; a second
   function such as `leadingNumericAlias(lines)`; or a boolean parameter. The enum reads best at the call
   site and leaves room for a third rule, at the cost of a new public type for one setting.
2. **Is this worth a public API at all?** No klap consumer has asked for it. The case rests on the failure
   being silent rather than on demand, and on the coreutils family sharing the rule (`head` and `tail`
   measured; `uniq`, `split` and `fold` not checked).
3. **Does anything else in the corpus want it?** Only `head` uses `numericAlias` alongside filename
   operands today. `git` wants the current default. Checking the remaining fixtures would firm up whether
   this is one tool's parity or a shape worth naming.
