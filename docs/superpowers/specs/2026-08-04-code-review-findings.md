# Code review findings — adversarially verified

**Status:** PARTIAL. Generated 2026-08-04 from a multi-agent review of `master` @ `0c39c57`.

23 findings, re-rated by hand after the automated severities proved unreliable (see below).
**1 fixed** (the sole HIGH), 22 open.

## Fixed since the review

**The built-in token-stealing bug is fixed**, along with two cases it turned out to be one instance of.
The whole family came from the same root: several scans read a token's spelling without asking whether a
preceding option had already claimed it as its argument.

The fix adds `Cli.optionValueSlots(argv)` — one left-to-right pre-pass that descends the subcommand path
and returns the argv indices already claimed as option arguments — and wraps argv in an `ArgvScan` view
that every scan shares (`internal/parse/ArgvScan.kt`). That replaced four independent free functions
(`namesBuiltin`, `metaOptionValue`, `stripToken`, `stripMetaOptionWithValue`), and the same scan is
threaded through `run()` and `completeCandidates()` so completion cannot disagree with parse. It is
deliberately fail-open: an unknown option, ambiguous abbreviation or malformed cluster claims no slot, so
a line klap was going to reject is scanned exactly as before and the fix can introduce no new rejections.

Three rounds, each validated red-first:

| Case | Before | Now |
|---|---|---|
| built-in in a local option's slot (`-e --json f`) | `e="f"`, files `[]` | `e="--json"`, files `[f]` |
| global in a local option's slot (`sub -e --tag f`) | `MissingOptionValue(--regexp)` | `e="--tag"`, files `[f]` |
| fully-global cluster in a slot (`-e -vq f`) | claimed whole, `e="f"` | `e="-vq"`, files `[f]` |
| negation spelling in a slot (`-e --no-verbose`) | negation claimed | `e="--no-verbose"`, default kept |
| completion at a shielded slot (`-e --tag <TAB>`) | offered `--tag`'s values | offers the operand's |

`--help`/`-h` are no exception, by decision: `mygrep -e --help f.txt` binds `--help` and does not print
help, which is what GNU `grep` does and what klap's conformance claim requires.

**Coverage:** 891 klap tests, 1872 across klap plus all 15 parity suites, 0 failures. No parity suite
needed editing, which is the load-bearing regression evidence — those suites exercise built-ins heavily
and were not written by the agent that made the change. New tests live in `PosixConformanceTest`
(rewritten as a guideline-10 conformance claim, no longer a divergence), `BuiltinsTest`,
`ParseOptionsTest`, and a new `CompletionValueSlotTest`.

**Two residuals, both deliberate:**

- `optionsEndAtFirstOperand` past its first operand is unchanged — globals keep their documented
  unconditional reach into the tail there, and narrowing it would contradict the switch's own docs. Now
  pinned by a control test.
- `mygrep -e --color=<TAB>` still offers colour choices. The shielded token is the cursor's *own* word
  rather than part of `head`, so the slot set cannot see it — and widening the set to cover the cursor
  word would break `mygrep -e <TAB>`, where the cursor word is also `-e`'s value and must complete it.
  Those need different questions asked, so it is a separate change rather than a wider slot set.

**Docs corrected alongside:** `guide.md`'s "Dash-led values" now states built-ins and globals are no
exception; the "Global / persistent options" section said a global is "recognized anywhere on the line"
and now names the one place it is not; the `optionsEndAtFirstOperand` notes in `guide.md` and
`CommandBuilder.kt` claimed built-ins are claimed "wherever they sit in argv, because that scan runs
before the tree knows which command it will reach" — both halves of which are now false.

## Severity was re-rated by hand — read this before trusting a label

The per-agent severities weighted *how vividly a finding was reproduced* over *how much it matters*.
A build-config finding arrived with stack traces and class-file hex dumps and was rated `high`; a
parser bug that silently binds the wrong argument arrived as a plain list of values and was rated
`medium`. That is backwards.

The rule applied here instead:

> A defect that fails **loudly at integration time** is less severe than one that **succeeds with
> wrong output at runtime.** `UnsupportedClassVersionError` tells you exactly what is wrong before you
> ship. A silently rebound argument does not.

Two entries changed. The built-in token-stealing bug moved `medium` → `high`: losing an argument is
the worst thing an argument parser can do. The `jvmToolchain(25)` entry moved `high` → `medium` and
was rewritten — its proposed fix (lower the toolchain) does not compile, because `Console.isTerminal()`
requires Java 22+, and a duplicate of it raised by a second dimension was merged away.

## Coverage gap

The run was interrupted by spend limits. Findings whose verifier died were dropped entirely, so
absence here is not evidence of absence. `parse-core`, `public-api`, `validation` and `render` were
reviewed but their findings were **lost unverified** — that is the parser core and the public surface.
The one parser bug below surfaced from `posix`, which happened to finish. Assume it has siblings.



## HIGH

### ~~Built-in scans steal a token sitting in an option-argument slot, rebinding the option to the next operand~~ — FIXED

> **Resolved.** See [Fixed since the review](#fixed-since-the-review) above. The entry is kept intact
> because the reproduction below is what the regression tests now assert the inverse of, and because the
> two further cases the fix uncovered (globals, and completion at a shielded slot) are only legible
> beside the original claim.

`klap/src/commonMain/kotlin/com/fromwau/klap/Parser.kt:172` — *posix* / parser-misbind

**Claim.** The position-independent built-in scans (`--json`, `--color`, `--help`/`-h`, `--completion`, `--docs`) run over raw argv with no notion of option arity, so a built-in spelling that occupies a value-taking option's argument slot is consumed by the built-in and the option silently binds the FOLLOWING operand instead — breaking guideline 6/10 and the guide's own "an option takes the next token, whatever it looks like" contract.

**How it fails.** Tree `cli("mygrep") { option("--regexp","-e"); argument("file").multiple(min=0) }`. Actual, from running the parser: `-e --json f.txt` -> `e=f.txt files=[]` (getopt gives `e=--json`, operand `f.txt`); `-e --js f.txt` -> same, via prefix resolution; `-e --color never f.txt` -> `e=f.txt files=[]` (TWO tokens vanish); `-e --help f.txt` -> ShowHelp; `-e --completion bash` -> ShowCompletion; `-e --docs man` -> ShowDocs. `mygrep -e --json f.txt` therefore searches for the literal string "f.txt" and reads no files. guide.md:186 promises "An option that takes a value takes the next token, whatever it looks like" and guide.md:195-196 enumerates the ONLY two exceptions (no next token; `--`); the built-ins are an undocumented third. guide.md:1301 claims "klap never changes the meaning of a command line the guidelines define" — this line's meaning is defined by guidelines 6 and 10.

<details><summary>Evidence</summary>

`parseTokens` line 172 `val withoutJson = if (builtins.json) stripToken(argv, "json", builtinPool) else argv`; `stripToken` (Parser.kt:413) `head.filterNot { '=' !in it && matchedLong(it, pool) == name }`; `stripMetaOptionWithValue` (Parser.kt:429) which also drops the token AFTER `--color`; `metaOptionValue` (Parser.kt:390) `head.indexOfLast { matchedLong(it, pool) == name }`; `namesBuiltin` (Parser.kt:87) `tokens.takeWhile { it != END_OF_OPTIONS }.any { ... }`. None of the four consults the preceding token's arity. Contrast internal/parse/Parser.kt:794/877, which DO bind a dash-led next token as the value — proved by `-e -x f.txt` binding `e=-x`.

</details>


## MEDIUM

### Abbreviated long option: completion offers operand values the parser then rejects

`klap/src/commonMain/kotlin/com/fromwau/klap/internal/render/Completion.kt:343` — *completion* / parser-completion-disagreement

**Claim.** trailingValueOption/attachedValueOption resolve the option under the cursor with exact-spelling byName(), while the parser's sift resolves the same token through resolveLong() prefix abbreviation, so for any abbreviated long option completion silently falls through to the NEXT positional slot and offers operand candidates that the parse then rejects as bad option values.

**How it fails.** cli("tool") { option("--sort").choice("name","size"); argument("a").choice("A1","A2") }. Verified by running completeCandidates: `tool --sort <TAB>` -> [name, size] (correct); `tool --sor <TAB>` -> [A1, A2] (the POSITIONAL's choices). `tool --sor=<TAB>` -> [] instead of [name, size]. The user accepts the offered A1 and `parse(listOf("--sor","A1"))` returns `Error(InvalidChoice(name=--sort, value=A1, choices=[name, size]))` — completion offered a token that makes the very next parse fail.

<details><summary>Evidence</summary>

render/Completion.kt:343 `private fun <S : NamedSpec> List<S>.byName(token: String): S? = when { token.startsWith("--") -> firstOrNull { token.removePrefix("--") in it.longs } ... }` — exact membership only. It is the sole lookup behind `matchingValueOption` (line 270-271), which backs both `trailingValueOption` (line 279) and `attachedValueOption` (line 313). The parser instead does `val resolved = resolveLong(typed, longPool)` (internal/parse/Parser.kt:747) and binds `LongMatch.Prefix` the same as `Exact`.

</details>

### lastWins members are hidden from name completion though the parser accepts them

`klap/src/commonMain/kotlin/com/fromwau/klap/internal/render/Completion.kt:201` — *completion* / parser-completion-disagreement

**Claim.** membersRuledOutBy drops every other member of ANY constraint once one member is supplied, without checking arity, but ConstraintArity.LastWins is an override rule the parse explicitly never rejects — so after typing one member, completion refuses to offer the very options lastWins exists to let you write together.

**How it fails.** The shipped example/find fixture declares `lastWins(physical, logical, followArgs)` (example/find/.../Find.kt:103). Verified with an equivalent tree: after `-L`, `-<TAB>` returns [--logical, -L, -h, --help, --json, --color, --completion, --docs] — `-P`, `--physical`, `-H`, `--follow-args` are all gone. Yet `parse(listOf("-L","-P","x"))` returns Success. Same for the `rm -i -f` / `head -c -n` / `cp -t -T` shapes the API doc advertises.

<details><summary>Evidence</summary>

render/Completion.kt:201-203 `private fun Command.membersRuledOutBy(sifted: Sifted): Set<HolderSpec> = constraints.filter { constraint -> constraint.members.any { supplied(it, sifted) } }.flatMapTo(mutableSetOf()) { constraint -> constraint.members.filterNot { supplied(it, sifted) } }` — iterates `constraints` with no `arity` test (its KDoc says "under either arity", but ConstraintArity has THREE). The parser does gate: internal/parse/Parser.kt:241 `if (constraint.arity == ConstraintArity.LastWins) continue`, and CommandBuilder.kt:153 states "**This is an override rule, not an exclusivity rule**". Consumed at render/Completion.kt:120 `.filterNot { it.hidden || it in ruledOut }`.

</details>

### Mid-list variadic operand: completion returns nothing past the slot count (kills file completion for the cp/mv/rsync shape)

`klap/src/commonMain/kotlin/com/fromwau/klap/internal/render/Completion.kt:167` — *completion* / correctness

**Claim.** The positional slot picker falls back to the last slot only when that slot is Cardinality.Multiple, so for the supported `SRC... DEST` shape (variadic followed by a required fixed slot) every operand from index slots.size onward resolves to no slot at all and completion yields zero candidates — including no native file completion — even though bindPositionals accepts unlimited operands there.

**How it fails.** example/cp declares `argument("source").file().multiple(min = 1)` then `argument("dest").file()` (example/cp/.../Cp.kt:177-184). Verified with the same shape: `copier a b <TAB>` -> [] and `copier a b c <TAB>` -> []; the `.file()` variant `copier2 a b <TAB>` -> [] (not even the COMPLETE_FILES directive), while `parse(listOf("a","b","c"))` returns Success. So `cp f1 f2 <TAB>` offers no filenames at all.

<details><summary>Evidence</summary>

render/Completion.kt:163-169: `val positionalIndex = sifted.positionals.size` ... `val positional = (slots.getOrNull(positionalIndex) ?: slots.lastOrNull()?.takeIf { it.cardinality is Cardinality.Multiple })?.takeUnless { it.hidden }`. With slots = [source(Multiple), dest(Optional/Required)], getOrNull(2) is null and slots.last() is not Multiple, so positional is null. The parse side handles the shape explicitly: internal/parse/Parser.kt:457 `val fixedAfter = args.size - index - 1; val take = (values.size - i - fixedAfter).coerceAtLeast(0)`.

</details>

### Arg.default(v) before a type converter stores the pre-conversion value; accessor reads the wrong type and the CLI dies with an unhandled ClassCastException

`klap/src/commonMain/kotlin/com/fromwau/klap/Converters.kt:207` — *converters* / type-confusion

**Claim.** `Arg<T : Any>.default(value: T)` snapshots the value into `Cardinality.Default` at the point of the call, but a later converter (`.int()`, `.long()`, `.enum<E>()`, ...) advances the handle's static type without touching the stored default, so an absent operand binds a `String` into an `Arg<Int>` accessor and the process crashes.

**How it fails.** `cli("app") { command("c") { val n = argument("n").default("0").int(); action { Ok("n+1=${n() + 1}") } } }` builds without complaint and `--help` renders `usage: app c [n=0]` / `<n>  count (default: 0)`. Running `app c 41` prints `n+1=42`. Running `app c` (the default path) throws out of `run()`: `java.lang.ClassCastException: class java.lang.String cannot be cast to class java.lang.Number`. Verified by execution. The Opt mirror is blocked by narrowing (`Opt<T?>.default` returns `Opt<T>`, which `.int()` no longer accepts), so this is Arg-specific — and Arg is exactly where the KDoc says the guards exist "at build time" because "the type system cannot express" them.

<details><summary>Evidence</summary>

Converters.kt:207-214 `public fun <T : Any> Arg<T>.default(value: T): Arg<T> { require(spec.cardinality !is Cardinality.Multiple) {...}; spec.cardinality = Cardinality.Default(value); return Arg(spec) }` — the only guard is against `Multiple`; nothing records or re-checks the value's type. `Arg<String>.int(): Arg<Int>` (Converters.kt:146) mutates only `spec.convert`. Parser.kt:498 `is Cardinality.Default -> sink[spec] = c.value` writes the raw stored value with no `convertOne`. ValueScope.kt:37 `return if (bound.containsKey(spec)) bound[spec] as T else unbound(spec)` is an unchecked cast.

</details>

### .absentWhen(t).default(v) narrows the accessor to non-null but binds null when the trigger fires

`klap/src/commonMain/kotlin/com/fromwau/klap/Converters.kt:257` — *converters* / correctness

**Claim.** `absentWhen()` widens to `Arg<T?>` precisely because the slot binds nothing when the trigger fires, but the `Arg<T?>.default(value: T): Arg<T>` narrowing overload immediately un-does that widening, and the parser still writes `null` for a removed slot — so a non-null accessor hands the action a null.

**How it fails.** `command("c") { val ref = option("--reference"); val mode = argument("mode").absentWhen(ref).default("755"); val files = argument("file").multiple(min = 1); action { Ok("modeLen=${mode().length} files=${files()}") } }` builds fine. `app c 644 a` -> `modeLen=3 files=[a]`. `app c --reference=r a` throws `java.lang.NullPointerException: Cannot invoke "String.length()" because the return value of "com.fromwau.klap.ActionScope.invoke(com.fromwau.klap.Arg)" is null`. Verified by execution. (The reverse order `.default("755").absentWhen(ref)` is safe — it returns `Arg<String?>` — so the outcome depends purely on chain order.)

<details><summary>Evidence</summary>

Converters.kt:257-260 `public fun <T> Arg<T>.absentWhen(input: Input): Arg<T?> { spec.absentWhen = input.holderSpec(); return Arg(spec) }`. Converters.kt:223-231 `@JvmName("defaultOptionalNarrowing") public fun <T : Any> Arg<T?>.default(value: T): Arg<T>` — guards only `Cardinality.Multiple`. Parser.kt:444 `arguments.filterNot { it in args }.forEach { sink[it] = null }` (comment: "binding it to null here rather than in the loop keeps the accessor total") writes null regardless of `Cardinality.Default`. BuilderValidation.kt:135-144 `validateConditionalOperandTriggers` rejects only `.absentWhen()` + `.multiple()` and a foreign trigger — never `.absentWhen()` + `.default()`.

</details>

### .requiredIf() accepts a flag that is not one of the command's own inputs; the rule then silently never fires while --help advertises it

`klap/src/commonMain/kotlin/com/fromwau/klap/Converters.kt:391` — *converters* / api-contract

**Claim.** `Opt<T>.requiredIf(flag)` performs no check that `flag` belongs to the same command, but `checkConditionalRequirements` looks the trigger up in the leaf's own `Sifted.flags`, which never contains a global flag (globals accumulate in `GlobalAccumulator`) or another command's flag — so the requirement is a silent no-op while the help row still prints `(required when --verbose)`.

**How it fails.** `cli("app") { val verbose = globalFlag("--verbose"); command("c") { val token = option("--token").requiredIf(verbose); action { Ok("token=${token()}") } } }` builds. `app c --help` prints `--token <value>  auth token (required when --verbose; optional)`. `app c --verbose` exits 0 printing `token=null` — the advertised rule never fires. Same silent no-op when the trigger is another command's flag (`app c` -> `token=null`, no error possible). Verified by execution.

<details><summary>Evidence</summary>

Converters.kt:391-398 `public fun <T> Opt<T>.requiredIf(flag: Flag): Opt<T> { require(spec.cardinality !is Cardinality.Required) {...}; spec.requiredWhen = flag.spec; spec.valueHint = listOfNotNull(spec.valueHint, "required when ${flag.spec.token()}").joinToString("; "); return this }` — the only `require` is about cardinality. Parser.kt:158-165 `checkConditionalRequirements`: `val triggered = (sifted.flags[condition] ?: 0) > 0`. Globals never reach that map — Parser.kt:395-413 `bindGlobals` reads `globalSift.flags` instead. Contrast BuilderValidation.kt:140-143 / 152-155, which DO enforce "a trigger must be one of this command's own inputs" for `.absentWhen()`/`.requiredUnless()`; `requiredWhen` is absent from `validateConditionalOperandTriggers` entirely.

</details>

### Guide says `group(title) { }` returns `Unit`; it returns the block's value

`docs/guide.md:979` — *docs* / api-contract

**Claim.** The guide states `group(title) { }` returns `Unit` and prescribes a `lateinit var` hoist as the only way to capture a grouped handle, but `CommandBuilder.group` is generic (`fun <R> group(title: String, block: CommandBuilder.() -> R): R`) with a `callsInPlace(EXACTLY_ONCE)` contract added specifically to make a plain `val` work.

**How it fails.** A reader following the guide writes `lateinit var host: Opt<String>` above every `group { }` and hand-writes each converted type, giving up definite-initialisation and inference — the exact boilerplate the API was changed to remove. Worse, the guide asserts the simpler form is a compile error, so a reader who guessed right is told to revert it.

<details><summary>Evidence</summary>

docs/guide.md:979 "`group(title) { }` returns `Unit`, so you cannot capture a holder from its return value. Declare a `lateinit var` above the block and assign it inside" and docs/guide.md:158-160 "A holder declared inside a `group { }` block needs a different pattern, since a plain `val` there does not compile when read from the enclosing `action`". Code: klap/src/commonMain/kotlin/com/fromwau/klap/CommandBuilder.kt:110 `public fun <R> group(title: String, block: CommandBuilder.() -> R): R { contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) } ... return try { block() } finally { currentSection = previous } }`, whose KDoc (line 100-107) says "Generic and `callsInPlace`, not `Unit`-returning, so a handle declared inside can be captured by a plain `val` outside". klap/src/commonTest/kotlin/com/fromwau/klap/ErgonomicsTest.kt:12-42 pins both shapes (`aHandleDeclaredInsideGroupBindsToAPlainVal`, `groupReturnsItsBlocksValue`), and example/ls/src/main/kotlin/com/fromwau/klap/fixture/ls/Ls.kt:60 uses `val outputFormat = group("Output format") { ... }`.

</details>

### `lastWins` members disappear from tab completion once one is typed

`klap/src/commonMain/kotlin/com/fromwau/klap/internal/render/Completion.kt:201` — *docs* / correctness

**Claim.** The guide's completion rule ("stops offering what the parse would reject") is implemented over ALL constraint arities, including `LastWins`, where a second member is explicitly legal — so `rm -i -<TAB>` stops offering `-f`/`--force` even though `rm -i -f` is the documented behaviour of that very rule.

**How it fails.** A CLI declaring `lastWins(interactive, force)`: user types `rm -i -<TAB>`; `supplied(interactive)` is true, so `force` lands in `ruledOut` and `-f`/`--force` is filtered out of the candidate list, hiding a flag the parser accepts. Same for the `find` (-P/-L/-H), `ls` (sort shorts), `head` (-c/-n) and `cp` fixtures, all of which use `lastWins`.

<details><summary>Evidence</summary>

`private fun Command.membersRuledOutBy(sifted: Sifted): Set<HolderSpec> = constraints.filter { constraint -> constraint.members.any { supplied(it, sifted) } }.flatMapTo(mutableSetOf()) { constraint -> constraint.members.filterNot { supplied(it, sifted) } }` — no `arity` filter, unlike `Command.checkConstraints` in internal/parse/Parser.kt:241 which does `if (constraint.arity == ConstraintArity.LastWins) continue`. Its own KDoc claims "under either arity, since a second member is a usage error in both", but `ConstraintArity` (internal/spec/Constraint.kt:4-17) has three arities and `LastWins` "can never fail". The set is applied at Completion.kt:119-120 `val ruledOut = cmd.membersRuledOutBy(sifted); (cmd.options + cmd.flags + globalSpecs).filterNot { it.hidden || it in ruledOut }`. Guide claim at docs/guide.md:543 and the `lastWins` contract at docs/guide.md:499-511 ("`rm -i -f` forces and `rm -f -i` prompts ... neither is an error").

</details>

### jvmToolchain(25) ships Java-25 bytecode in the published jvm jar and android AAR

`klap/build.gradle.kts:14` — *multiplatform* / packaging

**Claim.** klap's JVM source calls `java.io.Console.isTerminal()` (added in Java 22), so the toolchain cannot go below 22 and the published artifacts are Java 25 bytecode, unloadable on JDK 17/21. Verified on the local JDKs: isTerminal is absent on 17 and 21, present on 25 and 26. NOTE: the original finding proposed lowering the toolchain, which does not compile. The real question is whether that one call is worth the consumer floor it imposes; a reflective call with a `System.console() != null` fallback would compile at 17 and stay correct on 22+.

**How it fails.** A consumer on the current JDK 21 LTS adds `com.fromwau.klap:klap:0.1.0`; Gradle resolves the jvm variant without complaint and the program dies at first touch of any klap class with `UnsupportedClassVersionError: com/fromwau/klap/RunnerKt has been compiled by a more recent version of the Java Runtime (class file version 69.0), this version of the Java Runtime only recognizes class file versions up to 65.0`. An Android app consuming the AAR fails at dexing with "Unsupported class file major version 69". minSdk is 24, so the module advertises very broad Android reach that its own bytecode denies.

<details><summary>Evidence</summary>

klap/build.gradle.kts:14 `jvmToolchain(25)` inside `kotlin { }`, with no `compilerOptions { jvmTarget = ... }` override anywhere in the repo. Verified against the produced artifacts: `od -An -tu1 -N8` on `com/fromwau/klap/RunnerKt.class` extracted from `klap/build/libs/klap-jvm-0.1.0.jar` reports major version `69`, and the same class extracted from `klap/build/intermediates/aar_main_jar/androidMain/syncAndroidMainLibJars/classes.jar` (the AAR's `classes.jar`) is also `69`. `jq '.variants[] | .attributes' klap/build/publications/jvm/module.json` shows no `org.gradle.jvm.version` key.

</details>

### No test binds one global option through both parser passes; the merge is order-blind

`klap/src/commonTest/kotlin/com/fromwau/klap/ParseOptionsTest.kt:604` — *test-quality* / coverage-gap

**Claim.** The suite has a whole class (NegatableGlobalPolarityTest, 10 tests) pinning argv-order resolution for a global FLAG resolved across the pre-strip and the segment sift, but not a single test binds a global value OPTION through both passes — and that path merges occurrences by append order, not argv order, so last-wins and multiple()-order are both wrong.

**How it fails.** Verified by running a probe through `Cli.run`: tree `globalOption("--retries","-r").int().default(0)` + `command("build"){ flag("--force","-f") }`. `build -fr 5 --retries 9` binds r=5, though `--retries 9` is the last occurrence in argv (`build --retries 9 -fr 5` also binds 5, so one of the two is necessarily wrong). With `globalOption("--tag","-t").multiple()`, `build -ft a --tag b` binds tags=[b, a] — the mixed-cluster occurrence is always sorted last regardless of where it sat, silently reordering ordered repeatables (include paths, filter rules).

<details><summary>Evidence</summary>

ParseOptionsTest.kt:604 `mixedClusterLocalFlagThenGlobalOptionConsumesValue` is the only test that binds a global option via a mixed cluster, and it supplies the option exactly once. The merge point is `GlobalAccumulator.addOptionValue(spec: OptionSpec, value: String)` (internal/parse/Parser.kt:1307) — no `position` parameter, unlike its sibling `fun hitFlag(spec: FlagSpec, position: Int? = null, on: Boolean = true)` (Parser.kt:1302), which exists solely because `Polarity` (Parser.kt:1253-1265) documents that the two passes resolve out of argv order. `bindFlagsAndOptions` then takes `val raw = raws.lastOrNull()` (Parser.kt:358).

</details>

### RequiredIfTest never uses a negatable condition flag, so `--no-x` firing the requirement is unpinned

`klap/src/commonTest/kotlin/com/fromwau/klap/ConstraintTest.kt:851` — *test-quality* / coverage-gap

**Claim.** All six RequiredIfTest cases use a plain flag as the `.requiredIf()` condition; none uses a negatable one, so nothing catches that the negative spelling `--no-remote` — an explicit opt-OUT — triggers the requirement exactly as `--remote` does.

**How it fails.** Verified by probe: `cli("app"){ val remote = flag("--remote","-r").negatable(); val token = option("--token").requiredIf(remote); action{...} }`. `app --no-remote` returns `Error(MissingRequiredOption(--token))` — the user explicitly turned the remote off and is told to supply a credential for it. `app` bare succeeds, so the negation is strictly worse than absence.

<details><summary>Evidence</summary>

`checkConditionalRequirements` reads the raw hit count: `val triggered = (sifted.flags[condition] ?: 0) > 0` (internal/parse/Parser.kt:161). It does not call `Command.supplied()`, whose doc at Parser.kt:268-271 states the rule it is bypassing: "A negatable flag counts only in its positive form: `--no-create` asks to turn create OFF, so reading it as 'create was selected' would make `--no-create --extract` a conflict" — and `hit(flag, polarity, at)` (Parser.kt:709-713) increments `flagCounts` for both polarities.

</details>


## LOW

### Published POM carries no name, description, URL, license, developer, or SCM metadata

`klap/build.gradle.kts:59` — *build* / publishing-metadata

**Claim.** The `publishing { }` block configures only a repository and never touches `pom { }`, so every published POM ships with nothing but coordinates and dependencies — in particular no `<licenses>`, even though the project is GPL-3.0-or-later.

**How it fails.** A consumer resolves `com.fromwau.klap:klap:0.1.0` and runs any license-scanning tool (Gradle's `licenseReport`, FOSSA, Dependency-Track, an SBOM generator). The POM declares no license, so klap is reported as "unknown license" and silently passes a policy gate that would have flagged GPL-3.0 copyleft in a proprietary product — the strongest legal constraint in this repo does not travel with the artifact. Separately, publishing to Maven Central is impossible without name/description/url/licenses/developers/scm, a signature, and a javadoc jar, so the current config only ever works for the private VPS repo.

<details><summary>Evidence</summary>

`klap/build.gradle.kts:59-71` contains only `repositories { maven { name = "vps"; url = ...; credentials { ... } } }` — no `publications { withType<MavenPublication> { pom { ... } } }`. The generated output proves the result: `klap/build/publications/jvm/pom-default.xml` and `klap/build/publications/kotlinMultiplatform/pom-default.xml` contain only `<groupId>`, `<artifactId>`, `<version>` and `<dependencies>` — no `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`, `<scm>`. `LICENSE` at the repo root is `GNU GENERAL PUBLIC LICENSE Version 3` and `README.md:100` states "GPL-3.0-or-later". `klap/build/libs/` also shows sources jars but no javadoc jar, and the `signing` plugin is never applied (`klap/build.gradle.kts:1-6` is `kotlinMultiplatform`, `androidKotlinMultiplatformLibrary`, `kotlinSerialization`, `maven-publish`).

</details>

### A second .choice()/.enum() stacks onto the first instead of replacing it, producing a spec that can bind no input while help and the error text advertise the second set

`klap/src/commonMain/kotlin/com/fromwau/klap/Converters.kt:77` — *converters* / correctness

**Claim.** `applyChoice`/`applyEnum` OVERWRITE `spec.choices` (what help and `InvalidChoice` show) but COMPOSE the matcher via `andThenConvert`, so a second choice-set declaration leaves an unsatisfiable AND-of-two-disjoint-sets converter that no value can pass, with no build-time rejection.

**How it fails.** `argument("m", help = "mode").choice("a", "b").enum<Shade>()` (enum Shade { DARK, LIGHT }) builds without error. `app c --help` prints `<m>  mode (one of: dark, light; required)`. Every possible input fails: `app c dark` -> `error: invalid value 'dark' for m (choose from dark, light)` — the error names exactly the value the user just typed as a legal choice. Same for `.choice("a","b").choice("c")`: `app c c` -> `error: invalid value 'c' for m (choose from c)`. Verified by execution.

<details><summary>Evidence</summary>

Converters.kt:77-87 `applyChoice`: `this.choices = choices` (overwrite) followed by `andThenConvert { raw -> choices.firstOrNull { it.equals(raw, ignoreCase = true) } ... }` (compose). Converters.kt:117-129 `applyEnum` does the same: `choices = displayNames` then `andThenConvert { ... }`. `Arg<String>.choice(...)` returns `Arg<String>` (Converters.kt:172-175), which is still a legal receiver for `.choice()` and `.enum<E>()`, so both stack in a plain non-aliased chain. Parser.kt:543-551 then reports any converter failure as `InvalidChoice(name, raw, choices!!, ...)` using the OVERWRITTEN list.

</details>

### Guide's rendered `invalid value` errors drop the option's dashes

`docs/guide.md:282` — *docs* / documented-output

**Claim.** Both worked examples of a value error print the option without its dashes (`for port`, `for level`), but `BadValue`/`InvalidChoice` are constructed with `ValueSpec.name`, which for an option is the primary spelling including dashes, so the real output is `for --port` / `for --level`.

**How it fails.** A user greps their CLI's stderr for the string the guide shows (`for port:`) or writes a test asserting it; the real line is `error: invalid value '70000' for --port: must be 1..65535` and the match fails. The guide itself states the opposite rule 450 lines later (docs/guide.md:737 "`token.name` there is the handle's own primary spelling (`--token`, or `file` for a positional)").

<details><summary>Evidence</summary>

docs/guide.md:281-282 `$ app --port 70000` → `error: invalid value '70000' for port: must be 1..65535`, and docs/guide.md:289-290 → `error: invalid value 'bogus' for level (choose from ...)`. Code: internal/spec/HolderSpec.kt:148 `override val name: String = names.first()` (OptionSpec) with the comment at line 63-64 "[HolderSpec.name] stays the primary spelling — dashes included — and remains the sink key and the identity used in error text"; internal/parse/Parser.kt:553 `CliError.BadValue(name, raw, reason)` and :548-551 `CliError.InvalidChoice(name, raw, choices!!, ...)`; renderer internal/render/ErrorRendering.kt:59 `"invalid value '$value' for $name: $reason"`. Pinned the other way by klap/src/commonTest/.../ParseOptionsTest.kt:318 `assertEquals(CliError.BadValue("--port", "70000", "must be in 1..65535"), err)`.

</details>

### Converter table says `.multiple()` is limited to one per command; that holds only for positionals

`docs/guide.md:250` — *docs* / api-contract

**Claim.** The `.multiple(min = 0)` row is marked as applying to "argument, option" and asserts "at most one per command", but the one-variadic rule is enforced only over `ArgumentSpec`; a command may declare any number of repeatable options.

**How it fails.** An author modelling `curl -H a -H b -d x -d y` reads the table, believes only one option may be `.multiple()`, and hand-rolls a second collector (or splits the command) to work around a restriction that does not exist. The guide's correct statement of the real rule is elsewhere (docs/guide.md:311 "A command may declare **one** variadic positional").

<details><summary>Evidence</summary>

docs/guide.md:250 `| .multiple(min = 0) | argument, option | collects every occurrence into a List; at most one per command, and min is enforced |`. Code: internal/builder/BuilderValidation.kt:54-58 `val positionals = specs.filterIsInstance<ArgumentSpec>(); require(positionals.count { it.cardinality is Cardinality.Multiple } <= 1)` — no equivalent check exists for `OptionSpec`, and `ConverterScope.Opt<T?>.multiple` (Converters.kt:449-460) only rejects combining with `.default()`/`.required()`/`.optionalValue()`. example/curl/src/main/kotlin/com/fromwau/klap/fixture/curl/Curl.kt declares five repeatable options (lines 46, 50, 56, 66) plus a variadic operand (line 110) and builds fine.

</details>

### example/README says `requireAtMostOne` has no fixture, two rows after pointing at the fixture that uses it

`example/README.md:43` — *docs* / stale-doc

**Claim.** The page claims `requireAtMostOne` and `requiredIf` both have no example, but `requireAtMostOne` is used by the `tar` fixture and is listed as such in the lookup table on the same page; only `requiredIf` is genuinely unexemplified.

**How it fails.** A reader looking for a worked `requireAtMostOne` is told none exists and sent to the guide, skipping example/tar/…/Tar.kt:45 and its parity test (TarParityTest.kt:70), which is exactly the example they wanted.

<details><summary>Evidence</summary>

example/README.md:42-45 "Two constructs klap offers have **no example here yet** ... [`requireAtMostOne`] (at most one of a set, none required) and [`requiredIf`]", contradicting example/README.md:20 "| Two options that conflict but are both optional (`tar -z` vs `-j`) | [`tar`](tar/) | `requireAtMostOne` |". Code: example/tar/src/main/kotlin/com/fromwau/klap/fixture/tar/Tar.kt:45 `requireAtMostOne(gzip, bzip2)`. A repo-wide grep for `requiredIf` over example/ returns no hits, so that half of the sentence is correct.

</details>

### Generated-docs section claims raw HTML in help text renders; `<`/`>` are entity-escaped

`docs/guide.md:1132` — *docs* / documented-behavior

**Claim.** The markdown-escaping list omits `<` and `>`, and the guide states raw HTML in a help string "renders as markdown"; both `mdText` and `mdCell` convert `<`/`>` to `&lt;`/`&gt;`, so raw HTML is neutralized.

**How it fails.** An author who deliberately wants `<br>` or an HTML anchor in a description reads the guide, ships it, and gets the literal text `&lt;br&gt;` in the published page; conversely a security reviewer trusting the guide over-scopes the threat model for `docs markdown` output.

<details><summary>Evidence</summary>

docs/guide.md:1129-1134 "klap escapes a backslash and a backtick (and, in a table cell, a pipe `|` and newline) ... but it does not neutralize other markdown, so a `#`, `[link](...)`, `*emphasis*`, or raw HTML in your help text renders as markdown". Code: internal/render/Docs.kt:92-96 `internal fun mdText(text: String): String = text.replace("\\", "\\\\").replace("`", "\\`").replace("<", "&lt;").replace(">", "&gt;")` and :193-199 `mdCell` doing the same, whose KDoc states "no `<`/`>` can be read as an (unclosed) HTML tag".

</details>

### find fixture's raw-expression split silently drops the first expression token when no starting point is given

`example/find/src/main/kotlin/com/fromwau/klap/fixture/find/Find.kt:244` — *fixtures* / correctness

**Claim.** `.ifEmpty { listOf(".") }` is applied BEFORE `startingPoints.size` is used as the drop count, so when the operand list is non-empty but its first token is dash-led, the synthetic `.` default makes the code drop one real token off the front of the raw expression tail.

**How it fails.** `find -- -name '*.kt' -o -print` binds `operand = ["-name", "*.kt", "-o", "-print"]`. `takeWhile` yields `[]`, `ifEmpty` turns it into `["."]` (size 1), so `rawExpression = operand().drop(1) = ["*.kt", "-o", "-print"]` — the `-name` predicate is gone. The action then reports "3 unparsed expression tokens" for a 4-token expression, and any reader copying this split idiom (which the README presents as runnable example code) loses the first token of every expression not preceded by a path. No test exercises the action, so nothing catches it.

<details><summary>Evidence</summary>

Find.kt:241-244:
```
val startingPoints = operand()
    .takeWhile { !it.startsWith("-") && it != "(" && it != "!" }
    .ifEmpty { listOf(".") }
val rawExpression = operand().drop(startingPoints.size)
```
This is the fixture's demonstration of the escape hatch its own KDoc sells: `epilogue = "... pass a real find expression verbatim after `--` and parse it yourself."` (Find.kt:59-61), and the file declares the no-starting-point case as supported: `example("find --type f", "no starting point: the operand list binds empty and the action applies `.`")` (Find.kt:83).

</details>

### example/README.md contradicts itself on requireAtMostOne in the sentence claiming every row was checked

`example/README.md:42` — *fixtures* / documentation

**Claim.** The README lists `requireAtMostOne` twice as demonstrated by the tar fixture, then asserts two paragraphs later that it has no example in the corpus — inside the sentence that claims every table row was verified against a real call.

**How it fails.** A reader looking for how to express "at most one of a set, none required" follows README.md:43 away from the corpus to the guide, when the corpus already contains the exact call one directory over. The same sentence's blanket assurance ("Every row above was checked against a real call") is undermined by contradicting its own table.

<details><summary>Evidence</summary>

example/README.md:20 `| Two options that conflict but are both optional (\`tar -z\` vs \`-j\`) | [\`tar\`](tar/) | \`requireAtMostOne\` |`; README.md:137 `| [\`tar\`](tar/) | ... both exclusivity shapes side by side: \`requireExactlyOne\` for \`-c\`/\`-x\`/\`-t\`, \`requireAtMostOne\` for \`-z\`/\`-j\` |`; README.md:42-45 "Every row above was checked against a real call, not a mention in a comment. Two constructs klap offers have **no example here yet**, so read the guide for them: [\`requireAtMostOne\`](../docs/guide.md#cross-input-constraints) (at most one of a set, none required) and [\`requiredIf\`]...". The call exists: Tar.kt:45 `requireAtMostOne(gzip, bzip2)`, exercised by TarParityTest.kt:78 `parity.rejects("-czjf", "a.tar", because = "real tar: Conflicting compression options")`. Grep confirms `requiredIf` genuinely has zero call sites in `example/`, so only the `requireAtMostOne` half of the claim is false.

</details>

### mingwX64 enables ANSI from isatty() alone, never calling SetConsoleMode(ENABLE_VIRTUAL_TERMINAL_PROCESSING)

`klap/src/nativeMain/kotlin/com/fromwau/klap/internal/platform/Platform.native.kt:19` — *multiplatform* / correctness

**Claim.** The shared native `defaultTerminal()` — which mingwX64 compiles as-is, since mingwMain contributes only `terminalWidth()` — sets `ansi = ansiEnabled(isTty, env)` from `isatty(fileno(stdout))`, but nothing in the repository ever calls `SetConsoleMode` with `ENABLE_VIRTUAL_TERMINAL_PROCESSING`, which the Windows console host requires before it will interpret escape sequences.

**How it fails.** Run the mingwX64 binary (`klapExample.exe --help`) in cmd.exe or a conhost window without VT opted in: `isatty` reports a character device, `ansiEnabled` returns true, and the user sees `←[1mUsage:←[0m ...` — literal escape bytes interleaved through every heading, option name, and `error:` line. Windows Terminal and PowerShell 7 happen to start with VT enabled and hide the bug; the classic console does not.

<details><summary>Evidence</summary>

Platform.native.kt:19 `val isTty = isatty(fileno(stdout)) != 0` and :24 `override val ansi: Boolean = ansiEnabled(isTty, env)`; Style.kt:27 emits raw `ESC[...m` bytes when enabled. `grep -rn 'SetConsoleMode|VIRTUAL_TERMINAL|GetConsoleMode' --include='*.kt' .` over the whole repository returns nothing — mingwMain/Platform.mingw.kt only uses `GetStdHandle`/`GetConsoleScreenBufferInfo` for width. Microsoft's console documentation states ENABLE_VIRTUAL_TERMINAL_PROCESSING is off by default and must be set via SetConsoleMode on the screen-buffer handle.

</details>

### numericAlias claims a `-<digits>` token that is a complete option group when the digit shorts are split between global and local specs

`klap/src/commonMain/kotlin/com/fromwau/klap/internal/parse/Parser.kt:67` — *posix* / posix-conformance

**Claim.** Guideline 14 requires a token identifiable as a group of options to be treated as one, and `numericAliasValue` implements that by checking whether every digit names a declared short — but it checks only the command's own shorts, and its justifying comment ("a global's token is pre-stripped before this ever runs") is false for a cluster that mixes a global digit with a local one, which `siftGlobals` deliberately leaves whole.

**How it fails.** `cli("app") { globalFlag("--two","-2"); command("head") { flag("--zero","-0"); val lines = option("--lines","-n").int(); numericAlias(lines) } }`. Actual runs: `head -2 -0` -> `zero=true lines=null` (both characters are declared option chars, so `-20` IS a valid group), yet `head -20` -> `zero=false lines=20`. The same tree with both shorts declared locally correctly yields the cluster reading (pinned by PosixConformanceTest.kt:324-333), so the guideline-14 rule silently depends on where the shorts were declared.

<details><summary>Evidence</summary>

internal/parse/Parser.kt:67 `val shorts = shortsOf(namedInputs)` guarded by the comment at :62-66 "This command's own shorts are the set to check — a global's token is pre-stripped before this ever runs." The pre-strip's mixed-cluster branch at internal/parse/Parser.kt:1180-1184 does the opposite: `// Mixed cluster: hand the whole token to the command sift; record none of it here.` / `keep(token, i)`. So a `-20` whose `2` is global and `0` is local reaches `numericAliasValue` intact and `"2" in shorts` is false.

</details>

### BuiltinsTest asserts the undashed MissingOptionValue("color"/"completion") value and never its rendered text

`klap/src/commonTest/kotlin/com/fromwau/klap/BuiltinsTest.kt:220` — *test-quality* / test-asserts-the-bug

**Claim.** The meta-option error tests assert only the CliError data class, which encodes the option name without dashes, and never call `.message()`; the user-visible string is therefore "option color requires a value" while every author-declared option renders "option --host requires a value".

**How it fails.** Verified by probe: `tree.run(arrayOf("--color"), term)` writes to stderr `error: option color requires a value`, and `--completion` writes `error: option completion requires a value`. A user copying the name out of the error types `color`, not `--color`.

<details><summary>Evidence</summary>

BuiltinsTest.kt:220 `assertEquals(CliError.MissingOptionValue("color"), ...)` and BuiltinsTest.kt:106/131 `assertEquals(CliError.MissingOptionValue("completion"), ...)`; the produced value comes from `Result.Error(CliError.MissingOptionValue(name))` in Parser.kt:404 where `name` is the bare pool key. `ErrorRendering.kt:55` is `is CliError.MissingOptionValue -> "option $option requires a value"`. Two other tests pin the dashed form for author options — ParseOptionsTest.kt:1204 `assertEquals("option --host requires a value", err.message())` and ErrorRenderingTest.kt:117 — so the inconsistency is invisible only because no meta-option test renders. Same shape for `InvalidChoice("color", ...)` at BuiltinsTest.kt:209/235 (renders "invalid value 'bogus' for color").

</details>
