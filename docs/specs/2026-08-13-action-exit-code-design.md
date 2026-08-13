# A command's exit code, declared beside its rendering

**Status:** implemented. The ABI break it requires is accepted, so it ships at the 0.3.0 gate alongside the
other unreleased signature change.

**Goal:** let a command exit non-zero while still rendering its value on stdout, the way `diff` and `grep`
do, without letting a failure claim success.

## The problem

klap decides three things from one choice. Returning `Ok` writes the value to stdout, renders it through
`human` or the serializer, and exits 0. Returning `Err` writes to stderr, renders `error: <detail>`,
discards any value, and exits with the error's code.

That is right for an error. It has no answer for the UNIX convention where **a non-zero exit is the answer
rather than a failure**. `diff` exits 1 to say the files differ; the diff itself is on stdout and stderr is
empty. `grep` exits 1 to say nothing matched. Neither is an error, and neither prints one.

A klap-built `diff` has to pick a wrong option. Return `Ok` and `if diff a b; then` silently takes the
branch for "identical". Return `Err` and the diff is gone from stdout unless the action printed it itself,
and an `error:` line appears on stderr that real `diff` never emits.

The same shape reaches a consumer. echo's `echoctl --all pause` renders a row per instance, which is the
answer and belongs on stdout, and exits 1 when any instance failed. Both are true at once. Its workaround
(print the block from the action on the failure path, return `Err`) works, but it renders the same block
through two code paths, klap's on success and its own on failure, and that asymmetry is what produced a
second JSON document on stdout that shipped past 292 green tests.

## Verified before designing

Run on 2026-08-13, not reasoned about.

**klap today**, a one-command tree, measured through `RecordingTerminal`:

| action returns | exit | stdout | stderr |
|---|---|---|---|
| `Ok("the diff")` | 0 | `the diff` | empty |
| `Err(CliError.Failure("boom", 1))` | 1 | empty | `error: boom` |
| `Err(CliError.Failure("", 1))` | 1 | empty | `error: ` |
| `Ok("")` | 0 | empty | empty |

There is no fourth combination. A non-zero exit costs stdout and adds an `error:` line, and the empty
detail escape still emits `error: `.

**GNU diffutils 3.12 and GNU grep 3.12**, the convention klap cannot express:

| command | exit | stdout | stderr |
|---|---|---|---|
| `diff x y` (differ) | 1 | the diff | empty |
| `diff x z` (identical) | 0 | empty | empty |
| `grep b x` (match) | 0 | the line | empty |
| `grep zzz x` (no match) | 1 | empty | empty |

**In the source.** `Runner.kt:141-144`'s `onSuccess` returns `0` unconditionally and skips the write when
the rendered text is empty. `ErrorRendering.kt:116` clamps an error's code with `coerceIn(1, 255)`, so an
error can never claim success. `CommandBuilder.kt:174` declares `action`'s `human` parameter as
`ActionScope.(T) -> String`, a declared projection of the returned value.

## Surface

One optional parameter, beside the one that already turns the value into output:

```kotlin
public inline fun <reified T> action(
    noinline human: (ActionScope.(T) -> String)? = null,
    noinline exitCode: (ActionScope.(T) -> Int)? = null,
    noinline block: ActionScope.() -> Result<T, CliError>,
)
```

`actionSuspending` takes the same parameter, since the two differ only in how the block is driven.

```kotlin
// diff: the diff is the value; a non-empty one means "they differ"
action(exitCode = { if (it.hunks.isEmpty()) 0 else 1 }) { Ok(diff(a, b)) }

// grep
action(exitCode = { if (it.matches.isEmpty()) 1 else 0 }) { Ok(search()) }

// a fan-out whose rows are the answer and whose exit reports whether all of them worked
action(
    human = { it.block() },
    exitCode = { if (it.rows.any { row -> !row.ok }) 1 else 0 },
) { Ok(fanOut()) }
```

Omitting it keeps today's behaviour exactly: a success exits 0.

### What adopting it costs

The projection sees only `T`, and the three examples above are all commands whose value already holds the
answer (`hunks`, `matches`, `rows`). A CLI whose commands share one uniform return type usually does not,
so adopting this is a change to that type rather than to a call site:

```kotlin
// before: one type carries every command's output, and nothing in it decides an exit
data class CommandOutput(val human: String, val json: JsonElement)

// after: the outcome becomes a stated property of the result, and one projection wires every command
data class CommandOutput(val human: String, val json: JsonElement, val exit: Int = 0)

action(human = { it.human }, exitCode = { it.exit }) { Ok(fanOut()) }
```

That is the design's own argument turned on the consumer: the exit code stops being control flow and
becomes something the result states. It is still worth naming here, because "one optional parameter" reads
like a change nobody has to prepare for, and for this shape of CLI it is a change to the return type.

## Semantics

**Success path only.** `exitCode` is consulted when the action returns `Ok`. A returned `Err` still exits
through `CliError.exitCode`, which stays the single answer for failures. Two mechanisms that could
disagree about the same run would be worse than the gap this closes.

**Clamped to 0..255**, where an error is clamped to 1..255. The asymmetry is deliberate and is the whole
safety property: a success may report any code including a non-zero one, and an error may never report 0.
So a non-zero exit no longer implies a failure, but `exit 0` still implies success.

**Rendering is untouched.** The value goes to stdout through `human` or the serializer exactly as now, and
`--json` serializes the same value it does today. Only the process's exit code changes. This is the point:
the two axes stop being welded.

**The lambda runs after the value exists and before the render.** It is a pure projection of the value and
must not print; anything it writes lands between klap's own output in an order the action cannot rely on.

## Where it lands in the runner

`Runner.kt:141-144`'s `onSuccess` arm is the whole change: it writes the rendered text as it does now, then
returns the declared code rather than the literal `0`. The spec's value reaches it through the same
`renderOutput` path that already carries `human`.

`registerAction` is `@PublishedApi internal` and `action` is `public inline`, so adding a parameter changes
the ABI: a consumer compiled against 0.2.0 would get `NoSuchMethodError`. That is accepted and is why this
targets the same release as the existing unreleased break rather than shipping additively.

## Errors

No new `CliError` case. Nothing about the error path changes.

## Testing

- The four klap rows in the table above, unchanged, proving the default did not move.
- A command declaring `exitCode` and returning `Ok`: value on stdout, nothing on stderr, the declared code.
- `diff`'s two outcomes end to end, since the empty case must still exit 0 and print nothing.
- `exitCode` returning a value outside 0..255, clamped, and returning 0 from a command that also declares
  `human`, proving the two are independent.
- A command declaring both `exitCode` and returning `Err`: the error's code wins and the projection is
  never called.
- `--json` with a declared non-zero code: exactly one document on stdout, the code on the process.
- `actionSuspending` carrying the same behaviour, since a second entry point is where this would rot.

## Rejected alternatives

**The action returns the exit code**, as a second return channel or a mandatory part of the return. The
exit code is a function of the value in every case examined (is the diff empty, were there matches, did a
row fail), so a separate channel implies independence that does not exist. Mandatory would also put
ceremony on every action that wants 0 and break every existing one, and the return type is kern's
`Result<T, CliError>`, which klap cannot extend without wrapping every value.

**A `var exitCode` on `ActionScope`.** No signature change and no ABI break, which is its appeal. Rejected
because it makes the action's result depend on mutable state set somewhere in its body, so the value and
the code can be set in two places and a reader of the return site cannot see the exit code at all. The
projection keeps both output decisions declared in the same place.

**A marker interface on `T`**, klap reading `exitCode` when the returned type implements it. Non-breaking
and zero ceremony, but a type deciding a process's exit code by implementing an interface is action at a
distance, and it forces the concern into the consumer's own domain types.

**An exit code on `Ok` itself.** `Ok` is kern's, shared beyond klap, and a result type is the wrong place
for a process-level concern.

## Out of scope

- Any change to how errors exit. `CliError.exitCode` already works and is not in question.
- Letting an error exit 0.
- The guide's "Actions that print their own output" section, which needs the print-plus-exit shape named
  whether or not this lands. That is a docs fix that stands on its own.

## Open questions

All three are answered by the consumer feedback below.

1. **Does the projection need the `ActionScope` receiver?** Keep it. Nothing in the worked examples reads
   it, but adding it later is as breaking as removing it, and a consumer wanting a different code under
   `--json` has no other route to that fact.
2. **Should `exitCode` see an `Err` too?** No. A second place able to override `CliError.exitCode` is
   exactly the "two mechanisms disagreeing" the Semantics section rejects, and a consumer of this shape
   already owns a total classifier for the error side. Success-only is what keeps the feature reviewable.
3. **Is one consumer enough?** The case is stronger than the ergonomic reading it was first given. It
   rests on bug class: the workaround has two render paths and this design has one, and the second path is
   where a `--json` bug came from that survived 292 green tests and was made three times in one codebase.
   `diff` and `grep` remain the convention argument, and `example/` still has no fixture for either.

## Feedback

From the echo / echoctl consumer, 2026-08-13. This is the gripe's author validating whether the design
actually closes it, checked against echoctl's real fan-out and batch code rather than against the memory
of the complaint.

### Yes, it fixes it — and the fix is bigger than the ergonomics

The value is not that the workaround is ugly. It is that **the workaround has two render paths and this
design has one**.

Today echoctl renders the same block twice, in two places:

```kotlin
if (fanOutExitCode(results) == 0) return Ok(block)      // klap renders it
printBlock(if (json) block.json.toString() else block.human)   // echoctl renders it
return Err(CliError.Operation("--all", "$failed of ${results.size} instances failed"))
```

That asymmetry is not a smell, it is the exact mechanism of a bug that shipped past 292 green tests and
was caught only by driving a live daemon: the success arm returned an empty sentinel `Ok` so that klap
would print nothing, and under `--json` klap serialized the sentinel anyway, putting **two JSON documents
on stdout**. The same mistake was made a third time in the empty-stdin branch and found later still, by
grep rather than by test.

With `exitCode`, both arms return `Ok(block)` and klap renders once. The failure mode is not fixed, it is
**unreachable**, because there is no second renderer to disagree with the first.

So I would upgrade my earlier "not a strong ask", which open question 3 quotes. It was an honest read of
the ergonomics and a wrong read of the risk.

### The one thing to know before building it: the projection is over `T`

`exitCode: ActionScope.(T) -> Int` can only see the value. echoctl's every command returns one uniform
type so a single helper can wire all twenty:

```kotlin
class CommandOutput(val human: String, val json: JsonElement)
```

which does not carry the per-instance outcomes the exit decision needs. So adopting this is not a pure
call-site change for a consumer with a uniform `T`; the decision has to be threaded into the value type
(here, one `exit: Int = 0` field, after which `exitCode = { it.exit }` covers every command at once).

That is the right trade and not a criticism: it forces the exit code to be a stated property of the
result rather than control flow, which is the design's own argument. Worth naming in the spec, because
"one optional parameter" reads like a change no consumer has to prepare for, and for this shape of CLI it
is a change to the return type.

It also means the three worked examples are all commands whose `T` happens to already hold the answer
(`hunks`, `matches`, `rows`). A fourth example where the author has to *add* the field would show the
real adoption cost.

### One factual error, in the paragraph that states the safety property

> So `exit 0` no longer implies `Ok`, but a non-zero exit still never means the command silently
> succeeded.

This is inverted. Under the specified clamps (success `0..255`, error `1..255`), an error can never
produce 0, so **`exit 0` still implies `Ok`**. What changes is the other direction: a non-zero exit no
longer implies a failure, because a success may now declare one. The sentence should read:

> A non-zero exit no longer implies a failure, but `exit 0` still implies success.

Worth fixing precisely because this is the sentence a reader will quote when reasoning about whether the
feature is safe, and the property it asserts is the good one stated backwards.

### The open questions

1. **`ActionScope` receiver:** not needed by echoctl, whose projection would be `{ it.exit }`. Keep it
   anyway for symmetry with `human`: adding it later is as breaking as removing it, and a consumer wanting
   a different code under `--json` has no other route to that fact.
2. **Should `exitCode` see an `Err` too?** No. echoctl already owns a total `exitCode(CliError)`
   classifier, and a second place able to override it is exactly the "two mechanisms disagreeing" the
   Semantics section rejects. Keeping it success-only is what makes the feature reviewable.
3. **Is one consumer enough?** Answered above: the consumer is stronger than it looked, on bug-class
   grounds rather than ergonomic ones.

### Out of scope is right, with one nudge

Listing the guide's "Actions that print their own output" fix as standing on its own is correct, and it
should land **regardless of whether this spec does**. That section is where I looked, it answers the
printing half, and stopping one step short of the exit half is what sent me to the sentinel workaround in
the first place. It is the cheapest fix in either document.

### Resolution

Folded into the sections above rather than answered here. What changed:

- **The safety property was stated backwards** and is corrected in Semantics. The clamps make `exit 0`
  still imply success; what the feature changes is that a non-zero exit no longer implies a failure. The
  correction matters because that sentence is the one a reader quotes when deciding whether this is safe.
- **Surface gained "What adopting it costs"**, with a fourth example where the value type has to gain the
  field rather than already carrying it.
- **The three open questions are answered** above, in place, with the reasoning that settled them.
- **Out of scope is unchanged.** The guide fix stands on its own and should land first either way.
