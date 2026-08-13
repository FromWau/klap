# Dash-led operands Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** let an argument declared with `dashLed()` accept a single-dash token such as `-1m`, without changing what any existing CLI does.

**Architecture:** A single-dash token reaches a marked operand slot only when it fails to resolve as an option *in full*. A side-effect-free pre-check answers that before the cluster walk mutates anything, so the sift either resolves the token as options exactly as today or hands the whole word to `positionals`. `bindPositionals` then enforces that the slot it lands in is actually marked.

**Tech Stack:** Kotlin Multiplatform 2.4.10, no new dependencies.

**Spec:** `docs/specs/2026-08-12-dash-led-operands-design.md`

## Global Constraints

- **No existing test under `klap/src/commonTest` may be edited.** The opt-in must not change the default parse. If a parser test there needs editing, the feature leaked; stop and report rather than editing it. This does **not** apply to `example/*/src/test`: Task 8 deliberately rewrites a chmod parity test whose whole subject is the divergence this feature closes.
- `PosixConformanceTest` keeps asserting that `ls -5` and `sleep -1` reject.
- Single dash only. A `--`-led token is always an option, never an operand.
- Declared wins: a token that resolves in full parses as what it resolves to. A short cluster resolves only if **every** character resolves, with one exception the walk itself defines: an option takes the rest of the cluster as its value, so `-p8080` resolves and `8080` is never asked to.
- `--` is unchanged and remains the escape needing no declaration.
- No new `CliError` case.
- klap targets jvm, android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64. `./gradlew build` compiles all seven and runs jvm + linuxX64 tests.
- Test names use backticked sentence style, **no commas inside the backticks** (Kotlin/Native rejects them, and it only surfaces on the native compile).
- Verification: `./gradlew build` from the repo root. Single JVM test: `./gradlew :klap:jvmTest --tests '*ClassName*' --rerun`.

---

## File Structure

| File | Responsibility |
|---|---|
| `klap/src/commonMain/kotlin/com/fromwau/klap/internal/spec/HolderSpec.kt` | `ArgumentSpec` gains the `dashLed` flag. |
| `klap/src/commonMain/kotlin/com/fromwau/klap/Converters.kt` | The public `Arg<T>.dashLed()` modifier. |
| `klap/src/commonMain/kotlin/com/fromwau/klap/internal/parse/Parser.kt` | The resolvability pre-check, the sift admission, the bind enforcement. |
| `klap/src/commonTest/kotlin/com/fromwau/klap/DashLedOperandTest.kt` | **new.** The feature, end to end. |
| `klap/src/commonTest/kotlin/com/fromwau/klap/PosixConformanceTest.kt` | One added case: the opt-in as a documented exit. |
| `docs/guide.md`, `README.md` | Documentation. |

---

## Task 1: The `dashLed()` surface, changing nothing

Declaring `dashLed()` must compile, be order-free, and leave every parse exactly as it is. Nothing reads the flag yet.

**Files:**
- Modify: `klap/src/commonMain/kotlin/com/fromwau/klap/internal/spec/HolderSpec.kt:104-118`
- Modify: `klap/src/commonMain/kotlin/com/fromwau/klap/Converters.kt` (the `Arg<T>` section, beside `hidden()`)
- Test: `klap/src/commonTest/kotlin/com/fromwau/klap/DashLedOperandTest.kt` (new)

**Interfaces:**
- Consumes: nothing.
- Produces: `internal var ArgumentSpec.dashLed: Boolean` and `public fun <T> Arg<T>.dashLed(): Arg<T>`. Tasks 2, 3 and 4 read the flag.

- [x] **Step 1: Write the failing test**

Create `klap/src/commonTest/kotlin/com/fromwau/klap/DashLedOperandTest.kt`:

```kotlin
package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DashLedOperandTest {

    private fun seekTree(mark: Boolean) = cli("echoctl") {
        command("seek") {
            val position = if (mark) argument("position").dashLed() else argument("position")
            action { Ok("pos=${position()}") }
        }
    }

    @Test
    fun `declaring dashLed leaves an ordinary parse untouched`() {
        val t = RecordingTerminal()
        assertEquals(0, seekTree(mark = true).run(listOf("seek", "5"), t))
        assertEquals("pos=5\n", t.out.toString())
    }

    @Test
    fun `dashLed is order free against a converter`() {
        val tree = cli("app") {
            command("go") {
                val n = argument("n").dashLed().int()
                action { Ok("n=${n()}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "7"), t))
        assertEquals("n=7\n", t.out.toString())
    }

    @Test
    fun `an unmarked command still rejects a dash led operand`() {
        val error = assertIs<Result.Error<CliError>>(seekTree(mark = false).parse(listOf("seek", "-1m"))).error
        assertEquals(CliError.UnknownOption("-1", cluster = "-1m"), error)
    }
}
```

- [x] **Step 2: Run it to verify it fails**

Run: `./gradlew :klap:compileTestKotlinJvm`
Expected: FAIL with `Unresolved reference 'dashLed'`.

- [x] **Step 3: Add the flag to the spec**

In `HolderSpec.kt`, inside `internal class ArgumentSpec`, beside the other `var`s (`hidden`, `isPath`):

```kotlin
    // Only arguments carry this: an option's value slot already takes the next token whatever it looks
    // like, so there is nothing for a flag to opt into there.
    var dashLed: Boolean = false
```

Declare it on `ArgumentSpec` itself, **not** on `ValueSpec`, so it cannot be set on an option.

- [x] **Step 4: Add the public modifier**

In `Converters.kt`, in the `Arg<T>` section directly after `hidden()`:

```kotlin
    /**
     * Lets this operand accept a single-dash token such as `-1m`, which klap otherwise reads as an option.
     *
     * ```kotlin
     * command("seek") {
     *     val position = argument("position", "1-9, or +/-N with a unit").dashLed()
     *     action { Ok(seekTo(position())) }
     * }
     * ```
     *
     * Anything the tree declares still wins: a flag, a short cluster, a long option, an abbreviation, a
     * `numericAlias`, or a built-in like `-h`. Only a token that resolves to none of those reaches this
     * slot, and `--` remains the escape for a value that genuinely collides.
     *
     * In exchange, a single-dash **typo** on this command binds here instead of being reported as an
     * unknown option. Long options are unaffected and keep their did-you-mean. Prefer this on a command
     * whose own value error names the grammar it accepts, since that error is what a mistyped short now
     * produces.
     */
    public fun <T> Arg<T>.dashLed(): Arg<T> {
        spec.dashLed = true
        return Arg(spec)
    }
```

- [x] **Step 5: Run the new tests**

Run: `./gradlew :klap:jvmTest --tests '*DashLedOperandTest*' --rerun`
Expected: PASS, 3 tests.

- [x] **Step 6: Run the whole suite unedited**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, 0 failures, and `git status --short klap/src/commonTest` shows only the new file.

- [x] **Step 7: Commit**

```bash
git add klap/src/commonMain/kotlin/com/fromwau/klap/internal/spec/HolderSpec.kt \
        klap/src/commonMain/kotlin/com/fromwau/klap/Converters.kt \
        klap/src/commonTest/kotlin/com/fromwau/klap/DashLedOperandTest.kt
git commit -m "feat: dashLed() on the argument surface"
```

---

## Task 2: The resolvability pre-check

The heart of the feature, and the reason it is safe. `Parser.kt:1000` reports an unknown short **mid-walk**, after earlier characters have already been recorded as flag hits: for `-v1m` with `-v` declared, `-v` is recorded and then `1` fails. Deciding at that point that the token is an operand would need the recorded hit unwound.

A side-effect-free predicate run *before* the walk avoids that entirely, and it turns "resolution is all-or-nothing" from an emergent property of left-to-right evaluation into a stated rule with its own tests.

**Files:**
- Modify: `klap/src/commonMain/kotlin/com/fromwau/klap/internal/parse/Parser.kt` (beside `clusterCharError`, around line 1090)
- Test: `klap/src/commonTest/kotlin/com/fromwau/klap/DashLedOperandTest.kt`

**Interfaces:**
- Consumes: `ArgumentSpec.dashLed` from Task 1.
- Produces: `internal fun Command.shortClusterResolvesInFull(chars: String, globalAcc: GlobalAccumulator?): Boolean`. Task 3 calls it.

- [x] **Step 1: Write the failing test**

Append to `DashLedOperandTest.kt`, inside the class:

```kotlin
    @Test
    fun `a cluster resolves in full only when every character does`() {
        val tree = cli("app") {
            command("go") {
                val verbose = flag("--verbose", "-v")
                val n = argument("n").dashLed()
                action { Ok("v=${verbose()} n=${n()}") }
            }
        }
        // -v resolves, so it stays a flag and never reaches the marked slot.
        val resolved = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-v", "5"), resolved))
        assertEquals("v=true n=5\n", resolved.out.toString())
        // -v1m does not resolve in full, so the whole word is the operand and -v is NOT counted.
        val admitted = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-v1m"), admitted))
        assertEquals("v=false n=-v1m\n", admitted.out.toString())
    }
```

The flag is read in the action on purpose. `n` is required, so `go -v` alone would fail on the missing
operand and prove nothing; `go -v 5` reaching `v=true n=5` is what pins that `-v` was not swallowed by the
marked slot, and `v=false` in the second half is what pins that a failed cluster records no hit.

- [x] **Step 2: Run it to verify it fails**

Run: `./gradlew :klap:jvmTest --tests '*DashLedOperandTest*' --rerun`
Expected: the new test FAILS reporting `unknown option '-1' (in '-v1m')`, because nothing admits the token
yet. The first half already passes.

- [x] **Step 3: Write the predicate**

In `Parser.kt`, directly above the private `clusterCharError` (around line 1090):

```kotlin
/**
 * Whether a short cluster resolves in full against this command and [globalAcc]'s globals: every character
 * is a flag, a negated short, or an option, an option taking whatever follows it in the cluster as its
 * value. Answers without recording a hit, which is the point: [sift]'s cluster walk mutates as it goes, so
 * a token that turns out to be an operand has to be recognised before the walk starts rather than unwound
 * afterwards.
 *
 * The loop mirrors that walk, early exit included. Sharing only its lookups would not be enough: `-p8080`
 * resolves there, where requiring every character to resolve would demand the same of `8080` and hand a
 * declared option's own token to an operand slot.
 *
 * This is what makes "declared wins" all-or-nothing. A declared character behind an undeclared one is
 * never reached (`-1h` with the `-h` built-in present), and one declared character does not rescue a
 * cluster that fails elsewhere.
 */
internal fun Command.shortClusterResolvesInFull(
    chars: String,
    globalAcc: GlobalAccumulator?,
): Boolean {
    for (c in chars) {
        val ch = c.toString()
        if (globalAcc.clusterHit(findFlag("-$ch")) { flagSpecs.findFlag("-$ch") } != null) continue
        if (globalAcc.clusterHit(findNegatedShort(ch)) { flagSpecs.findNegatedShort(ch) } != null) continue
        // An option takes the rest of the cluster as its value, so nothing after it has to resolve.
        return globalAcc.clusterHit(findOption(null, ch)) { optionSpecs.findOption(null, ch) } != null
    }
    return true
}
```

If `flagSpecs` / `optionSpecs` are not in scope at that position, move the function next to the sift that owns them and say so in your report.

The lookups must be the same three the walk performs, in the same order, **and the control flow around them must match too**. The walk resolves a flag or a negated short and moves to the next character; on an option it takes `chars.substring(j + 1)` as the value and sets `j = chars.length` (`Parser.kt:1004` and `1020`), so the characters after an option are its value and never resolve on their own. A predicate that demands they resolve would report `-vp8080` as unresolvable and let a marked operand swallow a token the parser binds today.

The opposite direction is deliberate and needs no guard: when an option matches with no value available the walk records `MissingOptionValue` while this returns true. The token is still an option and still errors as one, which is what "declared wins" means.

- [x] **Step 4: Run it to confirm the predicate compiles**

Run: `./gradlew :klap:compileKotlinJvm`
Expected: BUILD SUCCESSFUL. The test still fails; nothing calls the predicate yet. That is Task 3.

- [x] **Step 5: Commit**

```bash
git add klap/src/commonMain/kotlin/com/fromwau/klap/internal/parse/Parser.kt \
        klap/src/commonTest/kotlin/com/fromwau/klap/DashLedOperandTest.kt
git commit -m "feat: side-effect-free short-cluster resolvability check"
```

---

## Task 3: The sift admits an unresolvable token on a marked command

**Files:**
- Modify: `klap/src/commonMain/kotlin/com/fromwau/klap/internal/parse/Parser.kt` (the sift's short-cluster branch, around lines 970-1005)
- Test: `klap/src/commonTest/kotlin/com/fromwau/klap/DashLedOperandTest.kt`

**Interfaces:**
- Consumes: `shortClusterResolvesInFull` from Task 2, `ArgumentSpec.dashLed` from Task 1.
- Produces: the sift places an unresolvable single-dash token into `Sifted.positionals` and records its index in `Sifted.dashLedAdmitted: Set<Int>`. Task 4 enforces which slot it may land in.

- [x] **Step 1: Write the failing tests**

Append to `DashLedOperandTest.kt`, inside the class:

```kotlin
    @Test
    fun `a marked slot takes a negative offset`() {
        val t = RecordingTerminal()
        assertEquals(0, seekTree(mark = true).run(listOf("seek", "-1m"), t))
        assertEquals("pos=-1m\n", t.out.toString())
    }

    @Test
    fun `a marked slot takes a negative hour offset even though -h is a builtin`() {
        // -1 is undeclared, so the cluster fails before -h is ever reached. Pinning this stops a change to
        // cluster evaluation order from silently taking hour offsets away.
        val t = RecordingTerminal()
        assertEquals(0, seekTree(mark = true).run(listOf("seek", "-1h"), t))
        assertEquals("pos=-1h\n", t.out.toString())
    }

    @Test
    fun `a declared builtin still wins against a marked slot`() {
        val invocation = assertIs<Result.Success<Invocation>>(
            seekTree(mark = true).parse(listOf("seek", "-h")),
        ).value
        assertIs<Invocation.ShowHelp>(invocation)
    }

    @Test
    fun `a double dash token is never an operand`() {
        val error = assertIs<Result.Error<CliError>>(
            seekTree(mark = true).parse(listOf("seek", "--verbsoe")),
        ).error
        assertIs<CliError.UnknownOption>(error)
    }

    @Test
    fun `a dash led operand ends options when the command says the first operand does`() {
        val tree = cli("app") {
            command("seek") {
                optionsEndAtFirstOperand = true
                val verbose = flag("--verbose", "-v")
                val position = argument("position").dashLed()
                val rest = argument("rest").multiple()
                action { Ok("v=${verbose()} pos=${position()} rest=${rest().joinToString(",")}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("seek", "-1m", "-v"), t))
        assertEquals("v=false pos=-1m rest=-v\n", t.out.toString())
    }

    @Test
    fun `a cluster whose tail is an option value still resolves`() {
        // The characters after a short option are its value, not more shorts to resolve, so `-vp8080`
        // binds as it always has. Without this the marked slot would swallow a token the parser owns.
        val tree = cli("app") {
            command("go") {
                val verbose = flag("--verbose", "-v")
                val port = option("--port", "-p").int()
                val n = argument("n").dashLed().optional()
                action { Ok("v=${verbose()} port=${port()} n=${n()}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-vp8080"), t))
        assertEquals("v=true port=8080 n=null\n", t.out.toString())
    }
```

- [x] **Step 2: Run to verify they fail**

Run: `./gradlew :klap:jvmTest --tests '*DashLedOperandTest*' --rerun`
Expected: `a marked slot takes a negative offset`, `...negative hour offset...` and Task 2's cluster test FAIL with `unknown option`. The builtin, double-dash and option-value-tail tests already PASS, which is the point: they assert behaviour that must not change. The last of the three is the one that catches an admission which is too eager.

- [x] **Step 3: Admit the token in the sift**

In `Parser.kt`, in the short-cluster branch, **before** the `var j = 0` walk begins (around line 976), insert:

```kotlin
                // Decided before the walk, because the walk records flag hits as it goes and a token that
                // turns out to be an operand would have to unwind them. Single dash only: a `--` token is
                // always an option, so long-option typos keep their did-you-mean.
                if (hasDashLedSlot && !shortClusterResolvesInFull(chars, globalAcc)) {
                    dashLedAdmitted += positionals.size
                    positionals += token
                    i += 1
                    // It is an operand, so it ends options under the POSIX reading exactly as the
                    // not-flag-like route above does; "the first operand" cannot depend on its spelling.
                    if (optionsEndAtFirstOperand) optionsEnded = true
                    continue
                }
```

Three things about that shape are deliberate.

`hasDashLedSlot` is hoisted above the token loop, beside `longPool`, as
`val hasDashLedSlot = specs.any { it is ArgumentSpec && it.dashLed }`. It is loop-invariant, and going
through `Command.arguments` would allocate a filtered list per short-cluster token on every command,
marked or not.

`i += 1`, not `i += advance`: `advance` is only mutated inside the walk, which this branch skips, so
reading it would imply an admitted token can consume the next one. The sibling operand routes use the
literal too.

No `!token.startsWith("--")` guard. Reaching this branch already requires `isFlagLike()` (which excludes
`--`) and requires the sibling `startsWith("--")` arm not to have claimed the token, so such a guard can
never be false. The comment above carries the constraint instead.

- [x] **Step 3b: Carry the admitted indices on `Sifted`**

Beside `val positionals = mutableListOf<String>()` (around line 842), declare:

```kotlin
    val dashLedAdmitted = mutableSetOf<Int>()
```

Add it to the `Sifted` construction at the foot of the sift (around line 1026) as the last argument, and to the class itself (around line 1375) as a defaulted parameter after `optionPositions`:

```kotlin
    // Indices into [positionals] that arrived through a `dashLed()` slot: a single-dash token that resolved
    // to no option. Only these are refusable at bind time, so a `--`-escaped operand keeps binding in any
    // slot exactly as it does today.
    val dashLedAdmitted: Set<Int> = emptySet(),
```

This set is what makes Task 4's rule 3 check safe. Every other route into `positionals` — a plain operand, a
post-`--` token, an operand after `optionsEndAtFirstOperand` fired — leaves the set empty and is untouched.

- [x] **Step 4: Run the tests**

Run: `./gradlew :klap:jvmTest --tests '*DashLedOperandTest*' --rerun`
Expected: PASS, 10 tests.

- [x] **Step 5: Run the whole suite unedited**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, 0 failures. **No existing test may need editing.** If one does, the admission leaked into the default: stop and report which test and why, rather than editing it.

- [x] **Step 6: Commit**

```bash
git add klap/src/commonMain/kotlin/com/fromwau/klap/internal/parse/Parser.kt \
        klap/src/commonTest/kotlin/com/fromwau/klap/DashLedOperandTest.kt
git commit -m "feat: sift hands an unresolvable single-dash token to a marked operand"
```

---

## Task 4: Bind enforces that the landing slot is marked

Task 3 admits a token when *any* argument on the command is marked. This is candidate (a) from the spec: permissive sift, precise bind. `bindPositionals` already resolves which spec each positional lands in, honouring `absentWhen`, `requiredUnless` and `multiple`, so it is where rule 3 belongs.

**Files:**
- Modify: `klap/src/commonMain/kotlin/com/fromwau/klap/internal/parse/Parser.kt:460-500` (`bindPositionals`)
- Test: `klap/src/commonTest/kotlin/com/fromwau/klap/DashLedOperandTest.kt`

**Interfaces:**
- Consumes: `ArgumentSpec.dashLed` from Task 1; the admission and `Sifted.dashLedAdmitted` from Task 3.
- Produces: nothing further.

- [x] **Step 1: Write the failing test**

Append to `DashLedOperandTest.kt`, inside the class:

```kotlin
    @Test
    fun `an unmarked second slot rejects a dash led token`() {
        val tree = cli("app") {
            command("seek") {
                val from = argument("from").dashLed()
                val to = argument("to").optional()
                action { Ok("from=${from()} to=${to()}") }
            }
        }
        // The first slot is marked, so the sift admits both words; only bind can tell that `to` is not.
        val error = assertIs<Result.Error<CliError>>(tree.parse(listOf("seek", "-1m", "-2m"))).error
        assertIs<CliError.UnknownOption>(error)
    }

    @Test
    fun `an unmarked variadic rejects a dash led token`() {
        val tree = cli("app") {
            command("seek") {
                val from = argument("from").dashLed()
                val rest = argument("rest").multiple()
                action { Ok("from=${from()} rest=${rest().joinToString(",")}") }
            }
        }
        val error = assertIs<Result.Error<CliError>>(tree.parse(listOf("seek", "-1m", "-2m", "f"))).error
        assertIs<CliError.UnknownOption>(error)
    }

    @Test
    fun `the escape still reaches an unmarked slot on a marked command`() {
        // The rule 3 check must see only what the sift admitted through the dash-led path. A `--`-escaped
        // operand has always bound in an unmarked slot and still must.
        val tree = cli("app") {
            command("seek") {
                val from = argument("from").dashLed()
                val to = argument("to").optional()
                action { Ok("from=${from()} to=${to()}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("seek", "-1m", "--", "-2m"), t))
        assertEquals("from=-1m to=-2m\n", t.out.toString())
    }
```

- [x] **Step 2: Run to verify they fail**

Run: `./gradlew :klap:jvmTest --tests '*DashLedOperandTest*' --rerun`
Expected: the two rejection tests FAIL. The parse currently succeeds, binding `to = "-2m"`, because nothing
checks the landing slot. `the escape still reaches an unmarked slot on a marked command` already PASSES,
which is the point: it pins behaviour Step 3 must not break.

- [x] **Step 3: Enforce it in the bind loop**

`bindPositionals` walks `args` with `var i = 0` indexing into `values`, which is always `sifted.positionals`,
so `i` is directly comparable with `sifted.dashLedAdmitted`. Both branches of the `when (val c =
spec.cardinality)` need the check, because either kind of slot can be the one an admitted token lands in.

The `Cardinality.Multiple` branch comes first in the function, so the rationale goes there. Place it after
`slice` is computed and **above** the `if (slice.size < min)` check:

```kotlin
                // Rule 3: the sift is permissive (it admits when ANY argument on the command is marked),
                // so the slot a value actually lands in is only known here. Keyed on what the sift
                // admitted rather than on the token's shape, so a `--`-escaped operand still binds in an
                // unmarked slot. Reported as the unknown option it is from the user's side; naming the
                // whole word beats naming a cluster character they did not type. Checked before the min
                // count below, so a rejected token is never also blamed for the slice coming up short.
                if (policy != BindPolicy.Lenient && !spec.dashLed) {
                    (i until i + take).firstOrNull { it in sifted.dashLedAdmitted }?.let {
                        return Result.Error(CliError.UnknownOption(values[it]))
                    }
                }
```

In the `else ->` (single-value) branch, after `val raw = values.getOrNull(i)` is known non-null and **before**
`spec.convertOne(raw, inferValues)`:

```kotlin
                    // Rule 3, same as the Multiple branch above.
                    if (policy != BindPolicy.Lenient && i in sifted.dashLedAdmitted && !spec.dashLed) {
                        return Result.Error(CliError.UnknownOption(raw))
                    }
```

Use whatever the loop already calls the current spec and the current value; the names above are the ones the
function uses today, but match the file rather than this snippet if they differ.

`!= BindPolicy.Lenient` rather than `== BindPolicy.Strict`, matching every other guard in the function.
Lenient is the completion path and must never fail a parse, which is the whole requirement; `DeferRequired`
behaves exactly like `Strict` here, and the function's KDoc says so, so singling `Strict` out would make
that sentence false.

- [x] **Step 4: Run the tests**

Run: `./gradlew :klap:jvmTest --tests '*DashLedOperandTest*' --rerun`
Expected: PASS, 13 tests.

- [x] **Step 5: Run the whole suite unedited**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, 0 failures, no existing test edited.

- [x] **Step 6: Commit**

```bash
git add klap/src/commonMain/kotlin/com/fromwau/klap/internal/parse/Parser.kt \
        klap/src/commonTest/kotlin/com/fromwau/klap/DashLedOperandTest.kt
git commit -m "feat: reject a dash-led operand landing in an unmarked slot"
```

---

## Task 5: The safety properties, pinned

Rule 1 is what makes the feature safe, and rule 2 is what bounds its cost. These tests should pass with no production change. If any fails, that is a bug to fix before continuing, not a test to weaken.

**Files:**
- Test: `klap/src/commonTest/kotlin/com/fromwau/klap/DashLedOperandTest.kt`
- Test: `klap/src/commonTest/kotlin/com/fromwau/klap/PosixConformanceTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1 to 4.
- Produces: nothing.

- [x] **Step 1: Write the safety tests**

Append to `DashLedOperandTest.kt`, inside the class:

```kotlin
    @Test
    fun `a declared short a long and an abbreviation all win against a marked slot`() {
        val tree = cli("app") {
            abbreviation = Abbreviation.Options
            command("go") {
                flag("--verbose", "-v")
                option("--mode")
                val n = argument("n").dashLed()
                action { Ok("n=${n()} ") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-v", "--mod", "x", "5"), t))
        assertEquals("n=5 \n", t.out.toString())
    }

    @Test
    fun `a numeric alias wins against a marked slot`() {
        val tree = cli("app") {
            command("go") {
                val lines = option("--lines", "-n").int()
                numericAlias(lines)
                val rest = argument("rest").dashLed().optional()
                action { Ok("lines=${lines()} rest=${rest()}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-5"), t))
        assertEquals("lines=5 rest=null\n", t.out.toString())
    }

    @Test
    fun `the escape still works on a marked command`() {
        val t = RecordingTerminal()
        assertEquals(0, seekTree(mark = true).run(listOf("seek", "--", "-h"), t))
        assertEquals("pos=-h\n", t.out.toString())
    }

    @Test
    fun `a marked slot pairs with multiple`() {
        val tree = cli("app") {
            command("go") {
                val ns = argument("n").dashLed().multiple(min = 1)
                action { Ok("ns=${ns().joinToString(",")}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-1m", "-2m"), t))
        assertEquals("ns=-1m,-2m\n", t.out.toString())
    }
```

- [x] **Step 2: Run them**

Run: `./gradlew :klap:jvmTest --tests '*DashLedOperandTest*' --rerun`
Expected: PASS, 17 tests. If `a numeric alias wins against a marked slot` fails, the pre-check is missing the alias: the alias is resolved outside the cluster walk, so the sift admission must not run ahead of it. Fix the production code, not the test.

- [x] **Step 3: Add the POSIX conformance case**

In `PosixConformanceTest.kt`, in the **guideline 14** group, directly after
`guideline 14 a dash led number is an option too`:

```kotlin
    @Test
    fun `guideline 14 dashLed is the opt in that steps outside it`() {
        // `dashLed()` takes THAT operand outside guideline 14, knowingly: a token identifiable as an
        // option binds as its value instead. It is per-argument and the author asks for it, so the
        // conforming reading above is what a CLI gets unless it says otherwise.
        val conforming = cli("app") { command("go") { argument("n"); action { Ok("") } } }
        assertIs<Result.Error<CliError>>(conforming.parse(listOf("go", "-5")))

        val optedIn = cli("app") { command("go") { argument("n").dashLed(); action { Ok("") } } }
        assertIs<Result.Success<Invocation>>(optedIn.parse(listOf("go", "-5")))
    }
```

Guideline 14, not guideline 10. Guideline 10 is the `--` delimiter, which this feature does not touch at
all; 14 is "if an argument can be identified as an option then it should be treated as such", which is
exactly what a marked operand steps outside. The name follows the file's own idiom for this shape, set by
`guideline 7 optional value is the opt in that steps outside it`.

Add whatever imports that file needs for `assertIs`, `Result`, `Invocation` and `Ok` if they are not already present.

- [x] **Step 4: Run the whole suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, 0 failures. `ls -5` and `sleep -1` conformance cases still pass unedited.

- [x] **Step 5: Commit**

```bash
git add klap/src/commonTest/kotlin/com/fromwau/klap/DashLedOperandTest.kt \
        klap/src/commonTest/kotlin/com/fromwau/klap/PosixConformanceTest.kt
git commit -m "test: pin the dash-led safety properties and the POSIX opt-in"
```

---

## Task 6: Completion agrees with the parser

Completion runs the same sift and the same binder under `BindPolicy.Lenient`, so it should follow by construction. This task proves that rather than assuming it.

**Files:**
- Test: `klap/src/commonTest/kotlin/com/fromwau/klap/DashLedOperandTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1 to 4.
- Produces: nothing.

- [x] **Step 1: Write the test**

Append to `DashLedOperandTest.kt`, inside the class. This drives the same planner `__complete` answers from, which is what `CompletionTest.kt` does; its own `completionsFor` helper is private to that file, so the call is written out here:

```kotlin
    @Test
    fun `completion after a dash led operand still offers the next slot`() {
        val tree = cli("app") {
            command("go") {
                val from = argument("from").dashLed()
                val to = argument("to").optional().completeWith { candidate("after ${from()}") }
                action { Ok("${from()}${to()}") }
            }
        }
        assertEquals(
            listOf("after -1m"),
            tree.completeCandidates(listOf("go", "-1m", "")).map { it.value },
        )
    }
```

Add `import com.fromwau.klap.internal.render.completeCandidates` if the file does not already resolve it.

The provider reads `from()` on purpose. A `completeWith { }` block that touches no accessor never forces
`CompletionScope`'s lazily-bound values, so `bindPositionals` is never called under `Lenient` at all and the
test proves only that the sift and the planner's own slot arithmetic agree. Reading the accessor is what
puts the shared binder in the path, and asserting the bound value is what shows the token reached `from`.

- [x] **Step 2: Run it**

Run: `./gradlew :klap:jvmTest --tests '*DashLedOperandTest*' --rerun`
Expected: PASS, 18 tests. A failure here means the lenient bind path rejects or mis-assigns the dash-led token, which is a production bug: fix `bindPositionals`, not the test.

- [x] **Step 3: Run the whole suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, 0 failures.

- [x] **Step 4: Commit**

```bash
git add klap/src/commonTest/kotlin/com/fromwau/klap/DashLedOperandTest.kt
git commit -m "test: completion follows the parser for a dash-led operand"
```

---

## Task 7: Documentation

**Files:**
- Modify: `docs/guide.md` (the "Numbers on the command line" section, and the POSIX conformance section)
- Modify: `README.md:125`

**Interfaces:**
- Consumes: the public surface from Task 1.
- Produces: nothing.

- [x] **Step 1: Replace the guide's unit-suffix paragraph**

`docs/guide.md`'s "Numbers on the command line" section currently ends with a paragraph beginning "A number carrying a **unit** (`-1m`, `-500ms`) fits none of the three". Replace that paragraph with:

````markdown
A number carrying a **unit** (`-1m`, `-500ms`) fits none of the three: `numericAlias` claims all-digit
tokens only, so `-1m` splits as the cluster `-1` `-m` and reports the first char it cannot place. Mark the
operand instead:

```kotlin
command("seek") {
    val position = argument("position", "1-9, or +/-N with a unit (ms|s|m|h)").dashLed()
    action { Ok(seekTo(position())) }
}
```

`dashLed()` lets that one slot take a single-dash token that resolves to nothing. Anything declared still
wins, and a cluster counts as resolved only when every character in it does, so `-1h` reaches the operand
even with the `-h` built-in present: `-1` fails first and `-h` is never reached.

The trade is on that command only. A mistyped short option now binds as the operand instead of being
reported as an unknown option, so reach for this where the command's own value error names the grammar it
accepts. Long options are unaffected and keep their did-you-mean, and `seek -- -1m` still works and needs
no declaration.
````

- [x] **Step 2: Add the POSIX section entry**

In `docs/guide.md`'s POSIX conformance section, add `dashLed()` alongside the existing opt-outs, in the same voice the section uses for `optionsEndAtFirstOperand`: it is per-argument, the author opts in, and the default reading is unchanged for every CLI that does not.

- [x] **Step 3: Stop the README counting**

`README.md:125` reads "including the one option-level opt-out and the one switch that trades an extension back". A count in prose is a claim nothing verifies, and this is the second time it needs touching. Reword so it names no number, for example "including each opt-out and the switch that trades an extension back".

- [x] **Step 4: Verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. `GuideSnippetsTest` and `ReadmeSnippetsTest` are hand-transcribed copies rather than extractors, so they neither compile these snippets nor break because of them. Check the `dashLed()` snippet against Task 1's signature by eye.

- [x] **Step 5: Commit**

```bash
git add docs/guide.md README.md
git commit -m "docs: dash-led operands"
```

---

## Task 8: Close the `chmod` fixture's gap

`example/chmod` documents this exact limitation in a `KLAP-GAP` comment and concludes it is permanent.
`dashLed()` is what makes that conclusion false, so the fixture and its reasoning both have to change.

This task doubles as the real-world validation the spec asked for. chmod's `mode` slot is
`.absentWhen(reference)` and its `file` slot is `.multiple(min = 1)`, so marking `mode` is the first case
combining a marked slot with both an optional-slot rule and a variadic. If candidate (a) from the spec
were wrong, it would break here.

**Files:**
- Modify: `example/chmod/src/main/kotlin/com/fromwau/klap/fixture/chmod/Chmod.kt:117-127`
- Modify: `example/chmod/src/test/kotlin/com/fromwau/klap/fixture/chmod/ChmodParityTest.kt`
- Modify: `example/README.md` (only if it names this gap; check before editing)

**Interfaces:**
- Consumes: `Arg<T>.dashLed()` from Task 1.
- Produces: nothing.

- [x] **Step 1: Read the fixture and its parity suite first**

Read `Chmod.kt` around the `mode` declaration and all of `ChmodParityTest.kt`. The parity suite is the
contract: `binds` asserts a whole projected record, `rejects` asserts only that a line does not parse.
That distinction matters in Step 4.

- [x] **Step 2: Write the failing parity cases**

In `ChmodParityTest.kt`, alongside the existing `--` escape case, add the three lines GNU chmod accepts
that klap could not. Match the file's existing `NOTHING_BOUND.copy(...)` style exactly:

```kotlin
    @Test
    fun `a leading dash mode binds without the escape`() {
        parity.binds(
            "-w", "notes.txt",
            expected = NOTHING_BOUND.copy(mode = ChmodMode.Symbolic(listOf("-w")), files = listOf("notes.txt")),
        )
        parity.binds(
            "-rwx", "notes.txt",
            expected = NOTHING_BOUND.copy(mode = ChmodMode.Symbolic(listOf("-rwx")), files = listOf("notes.txt")),
        )
        parity.binds(
            "-R", "-w", "dir",
            expected = NOTHING_BOUND.copy(
                recursive = true,
                mode = ChmodMode.Symbolic(listOf("-w")),
                files = listOf("dir"),
            ),
        )
    }
```

The third line is the one that proves rule 1: `-R` is a declared flag and still parses as one, while `-w`
is not and reaches the marked slot. If `NOTHING_BOUND`'s field for `--recursive` is spelled differently,
use the fixture's spelling.

- [x] **Step 3: Run to verify they fail**

Run: `./gradlew :example:chmod:test --rerun`
Expected: FAIL. `-w` is currently an unknown option.

- [x] **Step 4: Mark the operand and rewrite the comment**

In `Chmod.kt`, add `.dashLed()` to the `mode` declaration:

```kotlin
    val mode = argument("mode", "the new mode: octal (755) or symbolic (u+x,a-w)")
        .dashLed()
        .convert(::parseChmodMode)
        .completeWith { candidates(listOf("644", "755", "600", "700", "u+x", "a-w", "go-rwx")) }
        .absentWhen(reference)
```

Then replace the `KLAP-GAP` paragraph. Its claim that "no general rule serves both chmod and mkdir" and
that this is "a permanent divergence rather than a gap to close" is what `dashLed()` disproves, so the
comment must not survive in weakened form. Write what is true now:

```kotlin
    // `dashLed()` is why `chmod -w f`, `chmod -rwx f` and `chmod -R -w d` bind here as GNU chmod binds
    // them. The rule that serves both this and mkdir (where `-w f` really is an error) is per-argument
    // opt-in: chmod marks its mode slot and mkdir does not. `-R` still parses as the flag it is, because
    // only a token resolving to nothing reaches the slot, and `chmod -- -w f` still works.
```

- [x] **Step 5: Replace the `known divergence` test, which is now false**

`ChmodParityTest.kt:84-94` holds a test named `known divergence from real chmod` whose four cases each
carry `because = "permanent klap non-goal: dash-led operand; ..."`. All four flip from reject to bind,
which is the point of this task, so the test is rewritten rather than deleted. Verified against
`parseChmodMode`: it accepts `-755` (it does `raw.trimStart('-', '+', '=')` before folding the octal
digits) and accepts `-w` and `-rwx` as symbolic clauses, so every one of the four now converts.

Replace that whole test with:

```kotlin
    @Test
    fun `a leading dash mode binds the way real chmod binds it`() {
        // GNU chmod reads a dash-led MODE as the operand it is. This file used to call that a permanent
        // divergence, reasoning that no general rule serves both chmod and mkdir. A per-argument opt-in is
        // that rule: chmod marks its mode slot and mkdir does not.
        parity.binds(
            "-w", "notes.txt",
            expected = NOTHING_BOUND.copy(mode = ChmodMode.Symbolic(listOf("-w")), files = listOf("notes.txt")),
        )
        parity.binds(
            "-rwx", "notes.txt",
            expected = NOTHING_BOUND.copy(mode = ChmodMode.Symbolic(listOf("-rwx")), files = listOf("notes.txt")),
        )
        parity.binds(
            "-R", "-w", "d",
            expected = NOTHING_BOUND.copy(
                recursive = true,
                mode = ChmodMode.Symbolic(listOf("-w")),
                files = listOf("d"),
            ),
        )
        parity.binds("-755", "f", expected = NOTHING_BOUND.copy(mode = octal("755"), files = listOf("f")))
    }
```

`octal(...)` is the file's own helper at the bottom of the class. If `NOTHING_BOUND`'s field for
`--recursive` is spelled differently, use the fixture's spelling. This makes Step 2's separate test
redundant, so fold Step 2's cases into this one rather than keeping both.

- [x] **Step 6: Run the fixture's whole parity suite**

Run: `./gradlew :example:chmod:test --rerun`
Expected: PASS.

`rejects("-Q", "700", "d", because = "real chmod: invalid option -- 'Q'")` must **still reject**, and this
is the case to check by hand. `-Q` now reaches the marked slot rather than being reported as an unknown
option, and `parseChmodMode` rejects it because `Q` is not a valid permission character. `parity.rejects`
asserts only that the line does not parse, so it stays green and stays honest, since real chmod rejects it
too. If it starts binding, `parseChmodMode` is looser than it should be: report that rather than editing
the case.

- [x] **Step 7: Check the example README**

Run: `grep -n "chmod" example/README.md`
If any row or sentence describes chmod's dash-led mode as unsupported, update it. If none does, change
nothing and say so in your report.

- [x] **Step 8: Run the whole suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, 0 failures.

- [x] **Step 9: Commit**

```bash
git add example/chmod example/README.md
git commit -m "example: chmod binds a leading-dash mode, closing its KLAP-GAP"
```

---

## Task 9: Confirm the marker audit still holds

The audit is **already done**, per marker, by reading every declaration site rather than trusting comment
wording. Its result is recorded below so this task is a confirmation, not a survey. Do not re-litigate it;
do check that nothing drifted while the feature was built.

`grep -rn "KLAP-GAP" example/` returns **42** hits. Three are not gap claims: `find/Find.kt:17` is the
file's statement of the marking convention, `find/Find.kt:160` is a forward pointer to `Find.kt:232`, and
`example/README.md:124` is prose explaining what the label means. That leaves **39 gap claims, of which
exactly one is closed by this feature.**

| module | markers | verdict | why not closed |
|---|---|---|---|
| chmod | `Chmod.kt:117` | **CLOSED** | the only dash-led *operand* gap in the repo |
| find | 67, 93, 118, 169, 198, 209, 232 | not closed | single-dash multi-char option *names*, case-sensitive `.choice()` values, flag ordering, a terminator-delimited variadic, and the boolean expression grammar. All option-side or structural. |
| git | 51, 84, 171, 196, 262, 268, 310 + test 39, 181 | not closed | short-name reservation and collision, builtin aliasing, `negatable` + `optionalValue` not composing, `--` splitting operand *groups*, and subcommand-walk ordering |
| curl | 43, 57, 67, 102, 115 | not closed | option value grammar, cross-input arity, per-URL option pairing, `--next` restarting the option set, reserved builtin names |
| rsync | 64, 87, 132 | not closed | `count()` + `negatable()` not composing, two-list vs one interleaved list, and `SRC... [DEST]` operand **arity** |
| pacman | 86, 140, 167 + test 74, 120 | not closed | terminal `-V` routing, per-operation modifier meanings, per-operation operand arity |
| dd | 63, 66 | not closed | builtins reachable as operands, and `name=value` operands, which carry no dash at all |
| cp / mv | `Cp.kt:144, 178`, `Mv.kt:97` | not closed | a flag/option name collision, and `-T`'s exactly-two-operand cap (arity) |
| rm | `Rm.kt:65, 77` + test 89 | not closed | a file literally named `__complete` (no dash), and `negatable` + `optionalValue` not composing |
| ssh | `Ssh.kt:112` | not closed | `--version` is a reserved builtin long name |

The counts above already include two markers that the audit's grep could not see: `Git.kt:262`
(`--decorate`) and `Rm.kt:65` (`--preserve-root`) each described a gap in untagged prose, and each was the
target of a parity test's "see the KLAP-GAP note beside its declaration". Both now carry the tag. Neither
is closed by this feature: both are the `negatable` + `optionalValue` composition gap.

`example/README.md:124` stays true whatever is closed, since it describes the convention and names no
marker, count, or condition.

- [x] **Step 1: Confirm the count and the single closure**

Run: `grep -rn "KLAP-GAP" example/ | wc -l`
Expected: `41`, one fewer than before, because Task 8 removed `Chmod.kt:117`.

- [x] **Step 2: Confirm no other marker was touched**

Run: `git diff --stat master -- example/`
Expected: `example/chmod/**`, plus `example/README.md` if Step 7 of Task 8 found something to change
there, plus the tagging-only edits to `Git.kt` and `Rm.kt` that this branch already carries (each adds
`KLAP-GAP:` to prose that was already there; neither changes a declaration). If any other fixture appears,
a marker was edited that this feature does not close: report it rather than keeping the edit.

---

## Self-Review

**Spec coverage.** Surface to Task 1; rule 1 and all-or-nothing cluster resolution to Tasks 2, 3 and 5; rule 2 to Task 3's double-dash test; rule 3 to Task 4; the Errors section's stated trade to Task 1's KDoc and Task 7's guide text; Testing to Tasks 3, 5 and 6; the POSIX exit and the README count to Tasks 5 and 7. The spec's `Verified before designing` table needs no task, being a record of probes already run.

**The spec's open question is settled by construction, not by assumption.** The spec asked the plan to probe (a) versus (b). Task 2 answers it: the sift cannot cheaply know the landing slot, but it also cannot decide mid-walk without unwinding recorded flag hits, so the pre-check plus a permissive admission plus a precise bind is the only shape that avoids both problems. (b) is not needed, and the plan says why rather than deferring it.

**One thing deliberately not asserted.** Task 4 produces `UnknownOption(value)` naming the whole word, where a fully unmarked command produces `UnknownOption(char, cluster = word)`. The same input therefore reports differently depending on whether some other slot on that command is marked. This is a deliberate choice, not an oversight: at bind time the cluster character that failed is no longer known, and re-deriving it to reproduce the sift's exact payload would duplicate the walk. Naming the whole word is also the better message. Tasks 4 and 5 assert the error **type** rather than its payload for this reason.

**Test counts are per-target.** A `commonTest` test runs once per test target, and klap's aggregate sums across `jvmTest` and `linuxX64Test`, so a repo-wide total counts each new test more than once. Every whole-suite step therefore asserts **0 failures** rather than a total. The per-class counts under `--tests '*DashLedOperandTest*'` are single-target and are exact.

**The fixtures are covered.** Task 8 closes `example/chmod`'s `KLAP-GAP`, which is the only one of the repo's 39 gap claims this feature reaches, and Task 9 verifies that rather than assuming it. `head` was checked and needs nothing: it already binds `-1`, `-20` and `-n -5` through `numericAlias`, and carries no marker.

**Type consistency.** `ArgumentSpec.dashLed` is defined in Task 1 Step 3 and read in Task 3 Step 3 and Task 4 Step 3. `Arg<T>.dashLed()` is defined in Task 1 Step 4 and called in every later test. `shortClusterResolvesInFull(chars, globalAcc)` is defined in Task 2 Step 3 and called in Task 3 Step 3 with that exact argument order.
