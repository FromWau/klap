# A command's exit code, declared beside its rendering

**Status:** draft, not approved. The ABI break it requires is accepted, so it targets the 0.3.0 gate
alongside the other unreleased signature change. No implementation work has started.

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

## Semantics

**Success path only.** `exitCode` is consulted when the action returns `Ok`. A returned `Err` still exits
through `CliError.exitCode`, which stays the single answer for failures. Two mechanisms that could
disagree about the same run would be worse than the gap this closes.

**Clamped to 0..255**, where an error is clamped to 1..255. The asymmetry is deliberate and is the whole
safety property: a success may report any code including a non-zero one, and an error may never report 0.
So `exit 0` no longer implies `Ok`, but a non-zero exit still never means the command silently succeeded.

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

1. **Does the projection need the `ActionScope` receiver?** `human` has it. Nothing in the three worked
   examples reads it, so it may be ceremony copied from a neighbour rather than a requirement.
2. **Should `exitCode` see an `Err` too**, as `(Result<T, CliError>) -> Int`, making it the single answer
   for the whole run? It would unify the two mechanisms at the cost of letting a command override
   `CliError.exitCode` from a second place.
3. **Is one consumer enough?** echo rated this "not a strong ask" and its workaround holds. The case rests
   on `diff` and `grep`, which are conventions no klap CLI has yet tried to reproduce. `example/` has no
   fixture for either, so nothing in the corpus currently fails without this.
