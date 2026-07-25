# klap ergonomics study: where the code you write stops resembling the code you wanted

**Status:** No longer observational. Seven of the frictions recorded here have been closed; each is marked
in place below. **Date:** 2026-08-02 (original observations), updated the same day as the fixes landed.

> ### Read this before citing anything below
>
> **Every file path and line number in this document is historical, the `ergo/` ones included.** Neither
> corpus it cites is where it says:
>
> - the ten per-tool stubs became a live fixture suite under `example/<tool>/`, one Gradle module per tool;
> - the ten `ergo/` stubs were **deleted on 2026-08-03**, together with the construction check that was
>   their only consumer. Eight of the ten carried no annotation at all and existed only to be constructed.
>   The two that did, `FirstCli.kt` (4 STUMBLE notes) and `TestingACli.kt` (14 `KLAP-GAP` markers),
>   recorded gaps this document already states in prose and in more detail, F17 and P7 and §8 among them.
>   What was lost is a second copy of the record rather than the record itself. The showcase they sat
>   beside is now `example/task-manager/`.
>
> The `klap/` line numbers moved when the spelling model changed in `2119bed`.
>
> **The 34 `README.md:<line>` citations below need one more hop.** On 2026-08-03 the README was split:
> it is now a 73-line overview, and the reference material every one of these citations points into moved
> verbatim to [`docs/guide.md`](../../guide.md). So read `README.md:631` as "the guide, in the section that
> paragraph names". Section titles survived the move unchanged, which is what makes them findable.
>
> **The holder pattern this document repeatedly measures no longer exists.** Late on 2026-08-03 klap
> gained a second entry point, `cliOf(name) { ... }`, whose block ends in `projection { }` describing how
> to read one parse into a type of your own; `dispatch(...)` combines one such projection per command for a
> tree. All fifteen fixtures were rebuilt on it the same day. So the `<Tool>Inputs` classes of
> `public lateinit var` handles that F1's §219-226 census counts (`Curl.kt:25-40` (14), and the rest) are
> gone, replaced by `data class`es of resolved values, and every fixture's assertions became whole-record
> comparisons. That closes the *tree-level* version of F1: a handle no longer needs hoisting to escape any
> block, `command { }` included. The per-finding analysis below is unaffected, since it is about what
> writing a klap CLI felt like, not about how the fixtures happen to be spelled today.
>
> The analysis stands; the citations are deliberately left unrewritten as a record of the tree the
> observations were made against.
>
> **Eleven of the twenty-four findings are closed**, each struck in the table at §2 and marked at its own
> section. The rest stand.
>
> | closed | was | by |
> |---|---|---|
> | F1 | `group { }` returns `Unit`, forcing a `lateinit` tax | `60ab662` |
> | F2 | no value placeholder | `60ab662` |
> | F3 | no opt-out from injected built-ins | `b0d437a` |
> | F4 | help ignores declaration order | `f4987c2` |
> | F5 | no cross-input constraint vocabulary | `faf5b91`, `65b34d8`, `44bdb48`, `f418607` |
> | F7 | `Failure` exits 1 while parse errors exit 2 | `ca9574e` |
> | F8 | domain errors cannot be `CliError`s | `f5fc85f` |
> | F10 | handles are opaque, no public `name` | `f5fc85f` |
> | F11 | parsed values unreadable without running the action | `3d54fb5` |
> | F13 | `Failure.detail` skips sanitization | `ca9574e` |
> | F16 | the did-you-mean machinery is internal | `f5fc85f` |
>
> F14 was verified as deliberate rather than a defect (`BuilderImpl.kt` states positionals never take a
> section), so it is a wart with no diagnostic, not a bug.
>
> **Checked again on 2026-08-03**, after the coverage study's gap-closure branch. That branch was aimed at
> declarability rather than ergonomics, so it closed nothing here on its own: **F9** (`--version` has no
> short) gained an abbreviation and nothing else, and F5 was already closed and got wider.
>
> **F18 was then fixed directly, and is the one finding this date closed.** It had been re-verified defect
> by defect against the rewritten README first, which corrected its own citations and found four of its
> five defects still holding. All five are now closed, and the two that were snippet defects are pinned by
> `klap/src/commonTest/kotlin/com/fromwau/klap/ReadmeSnippetsTest.kt` rather than by prose: the README's
> load-bearing snippets are transcribed there and executed, so one that stops compiling fails the build.
> Both the re-verification and the resolution are recorded at F18's own section.
**Subject tree:** `master` @ `46de18f`, plus the untracked study sources under
`example/src/commonMain/kotlin/com/fromwau/example/study/ergo/`.
**Toolchain:** Kotlin 2.4.10 (`gradle/libs.versions.toml:6`), no pinned `languageVersion`, no
`freeCompilerArgs` anywhere in the build.
**Compile gate:** all ten ergonomics sources compile unmodified on every target
(`./gradlew build` green, `:example:compileKotlinLinuxX64 --rerun-tasks` green).

## 1. What this is

The [CLI surface coverage study](2026-08-02-cli-surface-coverage-study.md) asked what klap **cannot
express**; this one asks where klap **feels hacky to use** even when it can. Ten agents each took one
ergonomics task (declare a group and read it, express an exclusive flag set, share an option set across
subcommands, write a reusable converter, test a CLI as a consumer, control help layout, opt out of a
built-in, produce a domain error with a real exit code, express a cross-input rule, build a first CLI
README-only) and recorded what they wanted to write, what they had to write, and why. The unit of
measurement here is not "is it possible" but "how far is the shipped spelling from the intended one, and
what does the gap cost in ceremony, in lost compile-time guarantees, and in what `--help` then tells the
user".

## 2. Method limitations

Read the numbers with these in mind.

- **Ten tasks, chosen by the coordinator.** They were picked to span the DSL, not sampled from real usage.
  A friction absent from this list is not a friction absent from klap.
- **One attempt each, no adversarial verification pass.** Each agent had one shot and no reviewer. Section
  6 lists everything that did not survive my own re-checking, including one claim whose central premise was
  simply wrong.
- **The corpus was written by agents, not humans.** The ten stubs under `study/` (`mkdir`, `rm`, `cp`,
  `chmod`, `tar`, `dd`, `find`, `ssh`, `curl`, `git`) were produced by the earlier coverage study, by
  agents that had already read klap's source. Corpus hit counts therefore measure "how often this shape
  came up when reproducing ten real tools", not "how often a human trips on it".
- **The stubs are deliberately inert.** Every corpus action is a "would do X, here are your inputs" string
  dump *by specification of the coverage study*. Any friction whose evidence is "10/10 stubs ship the
  workaround shape" is weakened by that: see §6.8.
- **Corpus counts here are mine, not the agents'.** Every count in §3 and §4 was re-derived by grepping
  `example/src/commonMain/kotlin/com/fromwau/example/study/`; several agent counts were off, and the
  corrections are in §6.
- **Every `file:line` here was opened.** Where an agent's citation drifted, §6.5 records the correction and
  the body uses the verified line.

One methodological positive: all ten corpus files carry klap `file:line` citations in their comments (155
across the ten, from 5 in `Tar.kt` to 31 in `Find.kt`). Ten of ten agents read the source. That is itself a
documentation finding, recorded as F18.

## 3. Ranked friction table

Ordered by (corpus hits x severity). "Hits" counts how many of the ten study stubs demonstrably exhibit the
friction or its workaround, verified by grep, not by agent assertion.

| # | Friction | Kind | Hits | Severity | One-line cost |
|---|---|---|---|---|---|
| ~~F1~~ | ~~`group(title) { }` returns `Unit`, so a grouped handle needs a hoisted `lateinit var`~~ | hoisting | ~~6/10~~ **0** | **RESOLVED** | `group` is generic and `callsInPlace`, so a grouped handle is captured as an ordinary `val` -- either as the block's result or assigned inside it |
| ~~F2~~ | ~~No value placeholder / metavar on `option`/`argument`~~ | lying-output | ~~8/10~~ **0** | **RESOLVED** | `.metavar(name)` on both `Arg` and `Opt` sets the placeholder shown in help, usage and docs |
| ~~F3~~ | ~~No opt-out for any injected built-in (`--json`, `--color`, `-h`, `--help-all`, `--completion`, `--docs`)~~ | escape-hatch | ~~10/10~~ **0** | **RESOLVED** | a root-only `builtins { }` block declines them and frees each name tree-wide; `--help`/`--help-all`/`--version` stay unconditional |
| ~~F4~~ | ~~Help section and row order is `options + flags`, never declaration order~~ | hidden-knowledge | ~~7/10~~ **0** | **RESOLVED** | help renders from `namedInputs`, which keeps options and flags interleaved in declaration order |
| ~~F5~~ | ~~No cross-holder constraint vocabulary (exactly-one-of, conflicts-with, required-if)~~ | boilerplate | ~~6/10~~ **0** | **RESOLVED** | `requireExactlyOne`/`requireAtMostOne`, then `lastWins` for the override rule and `.requiredIf(flag)` for the conditional one; all four reach `--help`, the usage line and completion |
| F6 | One option's choice list is a page-wide layout parameter | hidden-knowledge | 4/10 | have-to-fight-it | 4 corpus files silently flip their whole help page to the stacked narrow layout at `COLUMNS=80` |
| ~~F7~~ | ~~`CliError.Failure` exits 1 while every parse error exits 2; `USAGE_ERROR_EXIT` is undocumented~~ | hidden-knowledge | ~~4/10~~ **0** | **RESOLVED** | `CliError.Usage(detail)` fixes the code at 2, and the README now documents it plus the any-variant escape hatch |
| ~~F8~~ | ~~`CliError` is sealed, so a consumer's domain errors cannot be `CliError`s~~ | boilerplate | ~~4/10~~ **0** | **RESOLVED** | `CliError.Domain(error, detail, exitCode)` carries the value itself, so a `parse()` caller matches on the payload instead of re-parsing prose |
| F9 | `--version` has no short and cannot get one | hidden-knowledge | 3/10 | have-to-fight-it | `Ssh.kt`'s `-V` fallback is dead code: a required positional fails first. **Still open, re-verified 2026-08-03**; four fixtures now pin it |
| ~~F10~~ | ~~`Opt`/`Arg`/`Flag` are opaque; no public `name`~~ | repetition | ~~3/10~~ **0** | **RESOLVED** | `Input.name`, one extension over the sealed supertype, returns the primary spelling klap's own errors use |
| ~~F11~~ | ~~Parsed values are unreadable without executing the action~~ | hidden-knowledge | n/a | **RESOLVED** | `Invocation.Execute.inputs` exposes the bound snapshot as a `ValueScope`; the values were always there, one keyword away |
| F12 | No way to share an option set across some-but-not-all subcommands | lying-output | 1/10 | significant | globals are tree-wide and reserve their short everywhere; `Git.kt` redeclares 5 names across 11 sites |
| ~~F13~~ | ~~`Failure.detail` skips `stripTerminalEscapes` on the human path~~ | escape-hatch | ~~1/10~~ **0** | **RESOLVED** | the exemption is gone; a detail is sanitized on both paths, with a newline/tab carve-out that also fixes the `--json` mangling |
| F14 | `argument()` inside `group { }` is a silent no-op | lying-output | 1/10 | feels-hacky | the source says grouped, `--help` disagrees, nothing warns |
| F15 | No short-circuit for klap's own `Result` inside an action | repetition | n/a | minor | 14 `getOrElse { return@action Err(it) }` sites in the 255-line showcase |
| ~~F16~~ | ~~The did-you-mean machinery is internal though the `suggestion` slot is public~~ | escape-hatch | ~~0/10~~ **0** | **RESOLVED** | `suggest(token, candidates)` re-exports the parser's own, threshold included, so a consumer's phrasing matches rather than approximates |
| F17 | No public introspection of the built tree | escape-hatch | n/a | significant | 12 of klap's own 18 test files import `com.fromwau.klap.internal.*` |
| ~~F18~~ | ~~The README never names the builder receiver; its `ValueScope` snippet does not compile; the `.file()` row is wrong; five of six `Result` combinators are undocumented~~ | discoverability | 10/10 | significant | **RESOLVED 2026-08-03.** All five defects closed. The two snippet defects are now pinned by `ReadmeSnippetsTest.kt`, which transcribes the README's load-bearing snippets and executes them, so prose cannot silently rot back; see F18 |
| F19 | Three spec mutators overwrite while two compose, all spelled identically | hidden-knowledge | 2/10 | feels-hacky | a second `.completeWith` deletes a bundled converter's candidates instead of extending them |
| F20 | No way to write to stdout and exit non-zero | lying-output | 0/10 | feels-hacky | the `diff`/`cmp`/`grep` contract is inexpressible |
| F21 | `globalOption`/`globalFlag` inside `group { }` silently discards the heading | lying-output | 0/10 | feels-hacky | the DSL accepts a nesting it entirely ignores |
| F22 | A `group { }` block's receiver is the enclosing command | hidden-knowledge | 0/10 | feels-hacky | `group("Advanced") { hidden = true }` hides the whole command |
| F23 | Built-in subcommand reservation is shape-dependent; README:366 states the opposite | hidden-knowledge | 0/10 | feels-hacky | adding a root `action { }` silently flips whether `command("docs")` is legal |
| F24 | `.validate` before a type converter compiles and fails at parse | hidden-knowledge | 0/10 | feels-hacky | a Kotlin cast error reported as "invalid value '4M' for keep-under" |

Severity scale, as the agents used it: `minor-friction` < `feels-hacky` < `significant-friction` <
`have-to-fight-it`.

## 4. The frictions in detail

### F1. `group(title) { }` returns `Unit`, so every grouped handle costs a `lateinit var` — **RESOLVED**

> **RESOLVED in `60ab662`, 2026-08-02.** `group` is `fun <R> group(title: String, block: CommandBuilder.() -> R): R`
> with a `callsInPlace(EXACTLY_ONCE)` contract, so a grouped handle is captured as an ordinary `val` —
> either as the block's own result, or by assigning it inside the block, which the contract makes legal.
> The `lateinit var` tax and its runtime-crash risk are gone.
> The analysis below is the pre-fix record.

**Wanted.**

```kotlin
group("Details") {
    val profile  = option("profile", "p", "target environment").enum<Profile>().default(Profile.DEV)
    val region   = option("region", "r", "cloud region").required()
    val replicas = option("replicas", "n", "how many instances").int().range(1..50).default(1)
}
action<String>(human = { it }) { Ok("profile=${profile()} region=${region()}") }
```

**Had to write.**

```kotlin
lateinit var profile: Opt<Profile>
lateinit var region: Opt<String>
lateinit var replicas: Opt<Int>

group("Details") {
    profile = option("profile", "p", "target environment").enum<Profile>().default(Profile.DEV)
    region = option("region", "r", "cloud region").required()
    replicas = option("replicas", "n", "how many instances").int().range(1..50).default(1)
}

// the ungrouped comparison, in the same command, needing neither:
val dryRun = flag("dry-run", help = "print the plan and exit")
```

**Mechanism.** `CommandBuilder.kt:46` declares `public abstract fun group(title: String, block:
CommandBuilder.() -> Unit)`. Two things follow from that one signature. `Unit` means a handle declared
inside cannot leave the block. `abstract` means the block cannot carry a `callsInPlace` contract, so the
compiler cannot know the block runs exactly once, even though `BuilderImpl.kt:86-91` runs it exactly once,
synchronously, on the same receiver.

Both repairs a Kotlin user reaches for first are rejected. Verified against a replica of klap's builder
shape with `kotlinc 2.4.10`:

```
A_abstract.kt:16:9: error: captured values cannot be initialized because of possible reassignments.
A_abstract.kt:18:24: error: variable 'profile' must be initialized.
```

and, for the capture-the-block's-value attempt:

```
G_capture.kt:10:24: error: unresolved reference. None of the following candidates is applicable
because of a receiver type mismatch: fun <T> Opt<T>.invoke(): T
```

Neither diagnostic mentions `group`. The first blames "possible reassignments" for a block that in fact
runs once; the second lands on the use site, ten lines away, and never says the block returned `Unit`.

The fix exists at `README.md:631-646`, three paragraphs after the `group` introduction at
`README.md:598-615`, past the `.hidden()`, `author`, colour-resolution and `--help-all` prose. A reader who
copied the introduction's example (which discards all its handles, `README.md:605-608`) has already failed
before reaching it.

**Three costs beyond the line count.**

1. *You must hand-write the converted type before writing the chain.* `.enum<E>()` yields `Opt<E?>`
   (`Converters.kt:279`) and only `.default()`/`.required()` flatten it (`:311`, `:298`); `.count()` yields
   `CountFlag`, a different class from `Flag` (`:369`). Get one wrong and the diagnostic is a type mismatch
   inside the lambda, contradicting a declaration ten lines up:
   `error: assignment type mismatch: actual type is 'Opt<Profile?>', but 'Opt<Profile>' was expected`
   (verified with kotlinc). Every later edit to a chain now means editing two places.
2. *Two compile-time guarantees are traded away for nothing.* The compiler no longer proves the handle is
   bound (a forgotten assignment is an `UninitializedPropertyAccessException` at build time, not a compile
   error, `ActionScope.kt:24-28`), and the handle is now mutable, so a second `group("Other") { profile =
   ... }` silently rebinds the name while the first spec stays declared, parsed, and permanently unreadable.
   Neither hazard exists for the ungrouped `val` in the same command.
3. *Inside a reusable helper the tax doubles*: three inputs need three `lateinit` declarations, three
   assignments, three constructor parameters and three constructor arguments to escape one function.

**Corpus.** 6 of the 10 stubs call `group` (`Chmod.kt:103`, `Cp.kt:143`, `Find.kt:196`, `Curl.kt:51/89/115`,
`Git.kt:163`, `Ssh.kt:82`) and **all 6 hoist**. Verified `lateinit var` declarations: `Curl.kt:25-40` (14),
`Git.kt:157-161` (5), `Cp.kt:139-141` (3), `Chmod.kt:56-58` (3), `Ssh.kt:79-81` (3), `Find.kt:194` (1) = 29,
plus `Main.kt:61-64` (4) in the library's own showcase = 33. The four stubs that never call `group` (`Rm`,
`Mkdir`, `Dd`, `Tar`) contain **zero** `lateinit`. Four of the six wrote their own comment blaming `group`
by name and file:line (`Curl.kt:19-22`, `Git.kt:155-156`, `Ssh.kt:78`, `Chmod.kt:55`), which is what people
do when the API did not explain itself. `Main.kt` performs the dance for four handles at `:61-66`, then
declares its ungrouped options 30 lines later as plain `val`s (`:97-98`).

Five of the ten ergonomics agents reported this friction independently, from five unrelated tasks.

### F2. An option's value placeholder is computed, never declared — **RESOLVED**

> **RESOLVED in `60ab662`, 2026-08-02** (renamed `.metavar` -> `.placeholder` later the same day).
> `.placeholder(name)` exists on both `Arg` and `Opt` and sets the
> placeholder shown in the help row, the usage line and the generated docs. It also outranks a choice list
> in the signature column, so a long choice set no longer widens every other row.
> The analysis below is the pre-fix record.

**Wanted.** `option("output", "o", "write the archive to FILE", placeholder = "FILE")`, rendering
`-o, --output FILE`.

**Had to write.** The metavar goes inside the help string and the signature column keeps lying:

```kotlin
option("output", "o", "write the archive to FILE").file().required()
// renders:  -o, --output <value>   write the archive to FILE (required)
```

**Mechanism.** `Help.kt:61` is the whole story:

```kotlin
internal fun ValueSpec.valuePlaceholder(): String = choices?.joinToString("|") ?: "value"
```

There is no `placeholder` parameter on `CommandBuilder.option` (`CommandBuilder.kt:32`) and no field for one
on `OptionSpec` (`HolderSpec.kt:76-96`). `.file()` sets `isPath`, which affects completion only
(`Converters.kt:354-357`); `.range()` writes `valueHint`, which renders in the trailing parenthetical, not
the placeholder (`Converters.kt:343`, `Help.kt:96`). Because `words()` (`Help.kt:76-80`) feeds
`helpSections` (`:153`) and `helpSections` is the single source for `--help`, `--help-all`, the man page and
the markdown table (`Docs.kt:39`, `:128`), the wrong placeholder appears in four renderers at once.

The one lever that *does* change the placeholder is `.choice()`, and it is a parse constraint:
`applyChoice` (`Converters.kt:60-69`) sets `choices` **and** installs a matching converter. So
`.choice("FILE")` renders exactly the wanted `-o, --output <FILE>` and then rejects every real path.

**Corpus.** 79 option declarations across the ten stubs (`option(` + `globalOption(`); 9 carry `.choice()`
(`Cp.kt:108/148/175/180`, `Git.kt:124/185/327`, `Find.kt:217`, `Curl.kt:59`) and none carries `.enum<>()`.
**70 of 79 render `<value>`**, in 8 of 10 files: `Ssh` 11/11, `Find` 22/23, `Git` 21/24, `Curl` 6/7,
`Cp` 5/9, `Mkdir` 2/2, `Tar` 2/2, `Chmod` 1/1. (`Dd` and `Rm` declare no options.) The workaround is
already in-tree: `Find.kt:226` and `Find.kt:269` and `Chmod.kt:98` all spell the metavar inside the help
string. `Find.kt` pays the extreme version: 11 help strings carry value grammar as prose, including
`[+-]N*24h ago` repeated across mtime/ctime/atime (`Find.kt:257-260`). `Dd.kt:83-105` copies its entire
operand grammar into `epilogue`, which `Dd.kt:44-45` itself flags as prose "which klap never validates
against the parser".

**Second-order.** Because there is no metavar, a reusable converter cannot teach `--help` its own input
language either, so the grammar is re-typed at every declaration site: exactly the repetition a named
converter was supposed to remove.

### F3. No opt-out for any injected built-in — **RESOLVED**

> **RESOLVED in `b0d437a`, 2026-08-02.** A root-only `builtins { }` block declines `--json`, `--color`,
> `-h`, `--completion` and `--docs`, and a declined built-in frees its name for the whole tree, stops being
> parsed, and stops being advertised in help, docs and completion. `--help`, `--help-all` and `--version`
> remain unconditional, so they stay reserved. `example/ls` needs the block twice over and `example/pacman`
> once, which is what pins it.
> The analysis below is the pre-fix record.

**Wanted.**

```kotlin
cli("curl") {
    builtins { json = off; color = off; version = on(short = "V") }
    option("json", help = "Post this JSON body")
}
```

**Had to write.** argv surgery before `cli.run()` ever sees the line, reimplementing three functions klap
already has:

```kotlin
fun rewriteReservedCollisions(raw: List<String>): List<String> {
    val end = raw.indexOf("--")                    // re-derived from source: every builtin scan is takeWhile
    ...
    token.startsWith("--json=") -> out += "--json-body=" + token.removePrefix("--json=")
    token == "--json" -> { ... }                    // stripToken compares with `!=`, so `=` form survives
    token == "-V" -> out += "--version"
    // `-sV` is NOT handled: doing it right means reimplementing internal/parse/Parser.kt's cluster walk
}
val jsonBody = option("json-body", help = "Post this JSON body (real curl spells this --json)")
epilogue = "Note: --json is klap's output switch, not curl's request-body option."
```

**Mechanism.** The complete root-only builder surface is `version`, `author`, `globalOption`, `globalFlag`
(`CliBuilder.kt:8-23`); the complete per-command surface is `description`, `aliases`, `epilogue`, `hidden`
plus the declaration verbs (`CommandBuilder.kt:21-70`). Nothing disables anything. The injections happen at
four unconditional sites in `parse`: `--json`/`--color` stripped for every tree (`Parser.kt:70-99`),
`--version` (`:101`), `--completion`/`--docs` on any root with an action (`:110-132`), `-h`/`--help`/
`--help-all` on every node (`:150-163`). `parse()` is not an escape hatch either: it does the stripping
itself.

`--help`, generated docs and completion advertise them unconditionally too. `Help.kt:191-201` appends the
built-in rows with no filter; `Completion.kt:105-117` does the same; `Docs.kt:39`/`:128` reuse
`helpSections`. `BuiltinOptionHelp` is an `internal object` (`Help.kt:37-44`). The only lever is `epilogue`
prose.

Three specific consequences:

- **A real tool's own `--json` is both undeclarable and silently hijacked.** `option("json", ...)` throws at
  construction (`BuilderValidation.kt:12` + `:291-293`), and because klap's `--json` is
  position-independent and stripped before binding (`Parser.kt:71`, `:77`), `curl --json '{"a":1}'
  https://x` switches output to JSON *and* leaves the request body to bind as a URL. No error, no warning.
- **`-h` is reserved tree-wide** (`BuilderValidation.kt:15`, enforced at `:295-297`), so `chmod -h` (the
  short of `--no-dereference`) is unreachable three separate ways, and `curl --help all` is inexpressible
  because klap's `--help` is a bare boolean.
- **The workaround cannot be written correctly.** `metaOptionValue` is `private` (`Parser.kt:289`),
  `stripToken` and `stripMetaOptionWithValue` are `internal` (`:302`, `:320`). The hand-written rewriter is
  ~35 lines, must special-case `--json=` (because `stripToken` compares with `!=`, so the `=` form survives
  the strip and then errors as `flag '--json' does not take a value`, `Parser.kt:59`), and still cannot
  handle `-sV` without reimplementing the short-cluster walk.

**Corpus.** Structurally 10/10. Four stubs document it explicitly: `Dd.kt:77-82` ("Real dd answers
`unrecognized operand '--json'` to all five. Nothing in the builder opts out of them."), `Rm.kt:89-95`,
`Ssh.kt:153-159`, `Curl.kt:152-162`. Two more hit the `-h`/`-V` half: `Chmod.kt:84-90`, `Git.kt:88-90`.

### F4. Help section and row order is `options + flags`, never declaration order — **RESOLVED**

> **RESOLVED in `f4987c2`, 2026-08-02.** Help renders from `Command.namedInputs`, which keeps options and
> flags interleaved in the order they were declared, rather than from `options + flags`, which grouped every
> option ahead of every flag. A related `--verbose`/`--quiet` pair written together now renders together.
> The analysis below is the pre-fix record.

**Mechanism.** `helpSections` walks `(options + flags)` at `Help.kt:161`, `:170`, `:180`; `Command.options`
and `Command.flags` are two separately filtered views of the one ordered `specs` list (`Cli.kt:24-27`). So
**all options are emitted before any flag**, in every section and in the section-title ordering.

Two consequences, neither documented anywhere:

1. A `group { }` whose first-declared member is a flag sorts after every group containing an option,
   regardless of where it is written. A `Behavior:` block of pure flags can never be placed above an
   `Output:` block of options.
2. Inside one section, options jump above flags declared before them.

**Corpus.** 7 of 10 stubs declare a flag before an option (`Chmod`, `Cp`, `Curl`, `Find`, `Git`, `Mkdir`,
`Tar`); `Dd` and `Rm` declare no options, `Ssh` declares all its options first. Four verified same-section
inversions:

- `Tar.kt` declares `-c/-x/-t` at `:44-46` (the mode selectors a tar user needs first) and renders
  `-f, --file` (`:59`) and `--exclude` (`:65`) above them, pushing the modes to rows 3 through 8.
- `Mkdir.kt:58` declares `--context` last of six and it renders second.
- `Chmod.kt:98` declares `--reference` after seven flags and it renders above all of them.
- `Curl.kt`'s `Connection` group declares `-L, --location` (`:116`) before `--max-time` (`:120`) and renders
  `--max-time` first.

### F5. No cross-holder constraint vocabulary — **RESOLVED**

> **RESOLVED on 2026-08-02**, in four commits and closer to the wanted shape than the first two got.
> `requireExactlyOne`/`requireAtMostOne` (`faf5b91`, `65b34d8`) cover the mode set; `lastWins`
> (`44bdb48`) covers the override rule this section's `conflictsWith` sketch was reaching for — the
> corpus turned out to want last-wins, not exclusivity, so `conflictsWith` would have rejected lines the
> real tools accept; and `.requiredIf(flag)` (`f418607`) covers the conditional one. It takes a handle
> rather than the sketch's `"--format=json"` string plus lambda, which is what lets the help row say
> `(required when --remote)` instead of leaving the rule to be discovered.
>
> Not covered: a condition on another input's VALUE (`requiredIf { format() == "json" }`), which the
> sketch below also wanted. A flag handle is inspectable and a value predicate is not, so the rendered
> hint that makes the rule discoverable would be lost — the reason the lambda form was declined here.
>
> **Widened on 2026-08-03** (`bb34c38`, `a4ca460`, `9feefcd`, `c6a7ca4`). `lastWins` takes any `Input`, so
> a set may hold options and mix them with flags (`head -c 5 -n 3`, `ls -S --sort=time`); a positional and
> a `.required()`/`.multiple()` option are rejected at construction, since a loser must bind what absence
> would have bound and neither has such a value. And the vocabulary grew a second axis this section never
> asked for, because the coverage study did: `.absentWhen(input)` removes an operand slot and
> `.requiredUnless(input)` relaxes its minimum, both reaching `--help`, the usage line and completion the
> same way. The **cross-input rule this study's own `ergo/CrossInputRules.kt` stub was written to
> demonstrate is largely expressible now**, and that stub's prose is corrected in place.
> The analysis below is the pre-fix record.

**Wanted.**

```kotlin
group("Mode", arity = EXACTLY_ONE) {
    flag("create", "c", "create a new archive")
    flag("extract", "x", "extract files from an archive")
    flag("list", "t", "list the contents of an archive")
}
val bzip2 = flag("bzip2", "j", "filter through bzip2").conflictsWith(gzip)

val output = option("output", "o").file().requiredIf("--format=json") { format() == "json" }
```

**Had to write.** ~27 lines inside `action { }`, plus a shadow registry because `Flag` is opaque:

```kotlin
data class Mode(val label: String, val flag: Flag)
...
action<String>(human = { it }) {
    val modes = given(listOf(Mode("-c", create), Mode("-x", extract), Mode("-t", listContents)))
    if (modes.isEmpty())  return@action Err(CliError.Failure("You must specify one of the '-ctx' options", USAGE_ERROR_EXIT))
    if (modes.size > 1)   return@action Err(CliError.Failure("You may not specify more than one of ${modes.joinToString(" ")}", USAGE_ERROR_EXIT))
    if (gzip() && bzip2()) return@action Err(CliError.Failure("Conflicting compression options: -z -j", USAGE_ERROR_EXIT))
    // only now may the command do its actual work
}
epilogue = "The mode flags -c, -x and -t are mutually exclusive; exactly one must be given."
```

**Mechanism.** Nothing in klap relates two holders. `group(title) { }` is a help heading only: `BuilderImpl`
sets `currentSection` around the block (`BuilderImpl.kt:86-91`) and the resulting `section` string has
exactly one consumer, `Help.kt:169-183`. `Cardinality` has four cases and all four describe one holder's own
occurrences (`Cardinality.kt:4-9`). `.validate(message) { }` receives only its own converted value with no
receiver and no sibling access (`Converters.kt:84-90`, `:335`), and it never runs when the input is absent
(`internal/parse/Parser.kt:356`), which is exactly the case a conditional requirement is about.
`.validate` does not exist for `Flag` at all: the whole `Flag` surface is `.count()`/`.negatable()`/
`.hidden()` (`Converters.kt:369-390`). Grepping `/exclusive|conflict|requireOne|atMostOne|oneOf/` over
`klap/src/commonMain` returns zero hits.

**Four downstream costs, all structural.**

- *`--help` and `--docs` actively misdescribe the command.* `usageTail()` (`Help.kt:130-137`) renders
  `usage: tar [file...] [options]`, asserting that giving no mode is legal, where a faithful line reads
  `tar (-c|-x|-t) [-z|-j] -f ARCHIVE [FILE...]`. `FlagSpec.metaHint()` (`Help.kt:83-89`) emits a hint only
  for `negatable` and `isCount`, so the three mode rows are indistinguishable from three independent
  toggles while the `--file` option one row below is annotated `(required)` for free. Man and markdown
  repeat it verbatim.
- *Completion recommends the invocations the action is about to reject.* `Completion.kt:95-118` emits
  `(cmd.options + cmd.flags + globalSpecs).filterNot { it.hidden }` unconditionally, so `tar -c -<TAB>`
  still offers `-x` and `-t`. The planner already computed the parsed prefix (`val sifted by lazy` at
  `Completion.kt:70`) and simply does not consult it.
- *`action { }` is not the recommended home, it is the only reachable one*, so the rule runs strictly after
  every parse-time check. `Cli.parse(argv)` is public (`Parser.kt:69`) and looks like the seam, but
  `Invocation.Execute.scope` is `internal` (`Invocation.kt:17`) and the only public operation is
  `runAction()` (`Runner.kt:95`). Concretely: `tar -c -x` in the stub reports `missing required option
  --file`, because `.required()` is checked during bind, where GNU tar reports the mode conflict.
- *The guard must be the first statement of every action by convention only.* Putting it below the first
  side effect in a real tool is a silent correctness bug nothing can catch.

**Corpus.** 6 of 10 hit some form of cross-input rule: `Tar.kt:38-43`/`:52` (both arities, hand-written at
`:76-101`), `Cp.kt:230-232` + `:249-255` (conditional operand arity, and the `-t`/`-T` conflict),
`Rm.kt:100-105` (required-unless-`-f`), `Find.kt:166-169` + `:400-401`, `Chmod.kt:109-114`,
`Curl.kt:74-86` + `:166`. Four of those are specifically exclusive-flag sets (`tar`, `find`, `chmod`, `cp`).
`Find.kt` is the tell: it wrote the gap comment, then never enforced the rule at all: `:401` computes
`symlinkModes` and `:427` prints the count instead of erroring. `Chmod.kt:109-114` declines to model it and
says so.

Note the shape klap already has and cannot lend: subcommand dispatch is exactly-one arity with parse-time
enforcement, free help rendering and a structured error (`Parser.kt:140-145`), but `requireValidName`
rejects a name starting with `-` (`HolderSpec.kt:130-132`) and `BuilderValidation.kt:128-130` runs every
alias through the same check, so `command("create") { aliases = listOf("-c") }` throws at build time.

### F6. One option's choice list is a page-wide layout parameter

**Mechanism.** `helpText` sizes **one** shared signature column from the longest row across **all** sections
(`Help.kt:314`), and every row's description budget is `columns - (2 + width + 2)` (`Help.kt:265-266`). Past
`budget < WRAP_FLOOR` (20, `Help.kt:221`), the entire page abandons the aligned two-column layout and
switches to a stacked one (`Help.kt:279-286`). Solving for `columns = 80`, any signature wider than 56
characters restyles the whole help page, including `-h, --help`, whose own signature is 10 characters.
Nothing caps, truncates, or opts a row out.

**Measured against the corpus** (widths computed from `Help.kt:76-80`'s `words()`; budget at `COLUMNS=80`):

| Site | Signature | Width | Budget@80 | Layout |
|---|---|---|---|---|
| `Find.kt:216` | `    --regextype <findutils-default\|gnu-awk\|posix-awk\|posix-basic\|posix-egrep\|posix-extended>` | **92** | -16 | stacked, and the row itself overflows 80 columns |
| `Curl.kt:58` | `-X, --request <GET\|HEAD\|POST\|PUT\|PATCH\|DELETE\|OPTIONS\|TRACE>` | 60 | 16 | stacked |
| `Cp.kt:147` | `    --backup <none\|off\|numbered\|t\|existing\|nil\|simple\|never>` | 60 | 16 | stacked |
| `Git.kt:184` | `    --cleanup <strip\|whitespace\|verbatim\|scissors\|default>` | 58 | 18 | stacked |
| built-in | `    --completion <bash\|zsh\|fish\|powershell>` | 43 | 33 | aligned |

**4 of 10 stubs silently ship a restyled help page.** The built-in row sets a floor the author cannot lower:
any command with a root action gets the 43-character `--completion` row (`Cli.kt:89`, `Help.kt:199-201`), so
a hello-world with one argument and one option burns 43 of 80 columns on a signature column whose widest
user row is 22.

**The available workaround is a bad trade.** Dropping `.choice()` for a hand-rolled
`.validate` + `.completeWith` loses the typed `CliError.InvalidChoice` (whose renderer appends
`(choose from ...)` plus a did-you-mean, `ErrorRendering.kt:44-45`) in favour of a generic `BadValue`
(`Converters.kt:84-90`), and kills tab completion outright, because `candidatesFor` keys on
`choices != null` (`Completion.kt:192`). The algorithm list then gets read four times: validator predicate,
validator message, help string, completion provider. No corpus author took that trade; all five
choice-using declarations kept `.choice()` and ate the layout cost, which is the honest ordering of the two
evils and means the corpus ships restyled help pages nobody chose.

### F7. `Failure` exits 1, parse errors exit 2, and `USAGE_ERROR_EXIT` is undocumented — **RESOLVED**

> **RESOLVED on 2026-08-02**, as P4 proposed. `CliError.Usage(detail)` is a `Failure` twin with no
> `exitCode` parameter at all, so it always exits `USAGE_ERROR_EXIT`. The README's typed-errors section
> gained a *Usage errors you detect yourself* subsection covering the exit-code split, `Usage`, and the
> study's best single finding: that an action may return **any** `CliError` variant, shown with a
> `MissingRequiredOption` returned from `action { }`. The analysis below is the pre-fix record.


**Mechanism.** `CliError.kt:8` gives every variant a default `exitCode` of `USAGE_ERROR_EXIT` (= 2,
`CliError.kt:4`). `CliError.kt:43` overrides it for the one variant a consumer can construct with a custom
message: `Failure(val detail: String, override val exitCode: Int = 1)`. So the discoverable path for a
usage error you detect yourself has the wrong exit code, at every site.

`grep -rn USAGE_ERROR_EXIT klap/src/commonMain` returns exactly two hits, both in `CliError.kt`.
`grep -n USAGE_ERROR_EXIT README.md` returns **zero**. The README's typed-errors section presents
`CliError.Failure(detail, exitCode = 1)` as "the catch-all" (`README.md:399`) and says cross-field rules
belong in `action { }` with a typed `Err` (`README.md:295`) without mentioning the exit code in the same
breath.

**Corpus.** Four stubs construct a usage-shaped error and spell it three different ways in one repository:
`Tar.kt:86/:94/:100` pass `USAGE_ERROR_EXIT` (the only stub that found it, importing it at `Tar.kt:7`);
`Rm.kt:104` deliberately does not and explains why at `:100-102`; `Cp.kt:250` and `:254` do not and say
nothing about it. `Curl.kt:166` reaches exit 2 only by re-constructing a klap variant
(`CliError.MissingArgument("curl", "url")`) whose default happens to be 2. Same repo, same rule class, no
shared answer.

**Related, and the best single answer in the study:** an action may return **any** `CliError` variant, not
just `Failure`, and a returned `MissingRequiredOption` renders byte-identically to the parse-time original,
exit 2 included (`CommandBuilder.kt:64-69` types the block as `Result<T, CliError>`; `Runner.kt:31` and
`:75` funnel both paths into `ErrorRendering.kt:80`). The README frames the non-`Failure` variants as things
"klap raises and renders for you" (`README.md:420-422`), so a consumer would not think to construct one.
1 of 10 discovered it, and only by reading `Runner.kt` and `ErrorRendering.kt` and writing a six-line
comment citing them (`Curl.kt:83-85`).

### F8. `CliError` is sealed, so domain errors cannot be `CliError`s — **RESOLVED**

> **RESOLVED in `f5fc85f`, 2026-08-02.** `CliError.Domain(error, detail, exitCode)` carries the
> consumer's own value through klap's error path with the payload intact; klap renders `detail` and exits
> as it does for `Failure`, and a `parse()` caller recovers `error` with a cast and matches on it. The
> field is `Any` rather than a klap-owned supertype, which is what keeps a hierarchy that already has its
> own root from having to adopt a second one.
> The analysis below is the pre-fix record.

**Mechanism.** `CliError.kt:7` is a `public sealed interface`. The only variant carrying a consumer-chosen
exit code is `Failure(detail: String, exitCode: Int)`, whose payload is one flat `String`. Every other
variant's `exitCode` is the non-overridden interface default of 2. So a tool with documented exit codes
(2 usage, 3+ domain) needs a parallel hierarchy flattened at the boundary:

```kotlin
private sealed interface ReleaseError { data class NoSuchEnvironment(val name: String) : ReleaseError; ... }
private fun ColorScope.toCliError(error: ReleaseError): CliError = when (error) {
    is ReleaseError.NoSuchEnvironment -> CliError.Failure(
        detail = red("unknown environment '${sanitized(error.name)}'") + nearest(error.name, KNOWN).didYouMean(),
        exitCode = EXIT_NO_SUCH_ENVIRONMENT,
    )
}
```

`runAction()` then hands an embedder back a `Failure` whose only payload is prose (`Runner.kt:95`), so
recovering "was this a lock or a drift?" means re-parsing the message. The choice is forced, not stylistic:
riding the typed error through the success channel (`Ok(Result.Error(...))`) preserves the type, but the
`Ok` path hard-codes exit 0 (`Runner.kt:74-79`). Typed errors for the embedder **or** correct exit codes for
the shell, not both.

There is also no `MutuallyExclusive` variant, which is the most common consumer-level usage error
(`CliError.kt:10-40`). One idea, three phrasings in one repo: `Tar.kt:93` "You may not specify more than one
of -c -x", `Cp.kt:250` "cannot combine --target-directory (-t) and --no-target-directory (-T)",
`ErrorsAndExit.kt:176` "--dry-run and --force cannot be combined".

**Corpus.** 4 of 10 construct a `CliError` in an action (`Rm.kt:104`, `Tar.kt:84/92/100`, `Cp.kt:250/254`,
`Curl.kt:166`); 100% of those are `Failure(String)` or a reused klap variant. None can name its own error
type.

### F9. `--version` has no short and cannot get one

> **STILL OPEN, re-verified 2026-08-03.** The gap-closure branch of that date touched the built-in matcher
> heavily (`--version` is resolved through `LongMatch` now, so `--vers` reaches it), but it gave it no
> short and no way to declare one, and `version` is still reserved. Three fixtures pin the cost as a
> `rejects` line whose `because` names klap rather than the tool: `example/curl` (`-V`), `example/ssh`
> (`-V`), `example/git` (`-v`), plus `example/pacman`, which is a fourth instance the original corpus of
> ten did not contain. Nothing here changed except that the failure is now reachable through an
> abbreviation as well.

**Mechanism.** `Parser.kt:101` matches the whole literal token `"--version"` on the root only, with no short
and no alias hook. `version` is in `RESERVED_LONG` (`BuilderValidation.kt:12`), so you cannot redeclare it.

The obvious fallback, a plain `flag("show-version", "V")` the action reads, **is dead code on any tool with
a required operand**: positionals bind strictly before the action runs
(`internal/parse/Parser.kt:299-301`).

**Corpus.** 3 of 10, and one ships the dead code. `Ssh.kt:123` declares `flag("show-version", "V", ...)`;
`Ssh.kt:138` declares `argument("destination", ...)` with no `.optional()`, hence `Cardinality.Required`
(`HolderSpec.kt:61`). `ssh -V` therefore fails with `missing required argument <destination> for 'ssh'` and
the flag at `:123` is never reachable. `Curl.kt:161-162` and `Git.kt:88-90` record the same gap without
attempting a fallback.

### F10. Handles are opaque — **RESOLVED**

> **RESOLVED in `f5fc85f`, 2026-08-02.** `public val Input.name` reads the primary spelling off any of the
> four handles — one extension over the sealed supertype rather than four members. It returns exactly what
> klap's own errors name the input by, so a hand-written message cannot drift from the declaration.
> The analysis below is the pre-fix record.

`Arg.kt:8/11/14/17` declare `Arg<T>`, `Opt<T>`, `Flag` and `CountFlag` with `@PublishedApi internal` or
`internal` `spec` and nothing else. From a consumer module a `Flag` has exactly one capability,
`invoke(): Boolean` inside a `ValueScope` (`ValueScope.kt:25-28`). It cannot be asked its long name, its
short, or its help.

So any hand-rolled constraint or helper re-types each input's spelling as a string literal the compiler will
never check. `Tar.kt:77-80` builds `"-c".takeIf { create() }` and friends; `Git.kt:210-216` does it for a
whole option summary; `Curl.kt:166` hardcodes its own command name inside `cli("curl") { }`. Rename a short
and the message keeps saying `-c` forever, silently. A generic `requiredWhen(opt, ...)` helper is impossible
without also passing the name as a `String`.

There is no generic form either: `Arg<T>` and `Opt<T>` have no common supertype and `ValueSpec` is internal
(`HolderSpec.kt:25`), so a helper must be written twice. klap pays the same tax in the open: roughly 240 of
`Converters.kt`'s 391 lines are the mirrored Arg/Opt surface (`:125-249` vs `:253-364`), about 16 mirrored
pairs with only `.optional()`/`.required()` asymmetric.

### F11. Parsed values are unreadable without executing the action

**Wanted.**

```kotlin
val exec = cli.parse(listOf("add", "milk", "-n", "3")).let { /* ... */ }
with(exec.scope) { assertEquals(3, limit()) }        // does not compile: scope is internal
```

**Had to write.** A second, test-only copy of the CLI whose action returns its inputs as strings, then a
cast through `Any?`:

```kotlin
val bound = ((exec?.runAction() as? Result.Success<Any?>)?.value as? List<*>).orEmpty()
assertEquals("3", bound[3])    // the Int I declared, now a String, after running the action
```

**Mechanism.** `Invocation.kt:17` makes `scope` `internal`. The accessors are public members of
`ValueScope` (`ValueScope.kt:25-28`) but the only public thing that ever *is* a `ValueScope` is the receiver
of `action { }`. `runAction()` (`Runner.kt:95-98`) is the single public door and it executes the action, so
a pure parsing question costs the action's side effects, and it erases the value to `Any?`, so every typed
input comes back untyped.

The friction compounds with F17: `CliError.message()` is `internal` (`ErrorRendering.kt:31`), so asserting
the wording a user sees means running the whole program against a fake terminal and string-matching stderr
including the `error: ` prefix and trailing newline; `Command.specs`/`arguments`/`options`/`flags` are
internal (`Cli.kt:17`, `:25-27`) and `helpText` is internal, so "does `add` declare `--limit` bounded
1..100?" degrades to a substring match on rendered prose; and `cli.subcommands` mixes klap's injected
builtins into the user's own (`CliBuilder.kt:33-35`, `:42`) with `Command.hidden` internal (`Cli.kt:36`), so
the only way to drop them is to filter by hardcoded name.

The library author hit the same wall: klap's own parse tests build a CLI whose action is
`Ok("port=${port()} verbose=${verbose()} ...")` and assert on stdout through a hand-written adapter
(`ParseOptionsTest.kt:31-35`). **12 of klap's 18 test files import `com.fromwau.klap.internal.*`**, that is,
the library's own suite is not writable from outside it. `RecordingTerminal` exists verbatim at
`TestTerminal.kt:4` and lives in `commonTest`, which is not in the artifact, so every consumer retypes it
from `README.md:777-782`, a snippet that also omits `columns`. There is no `run(List<String>, Terminal)`
overload (`Runner.kt:18` takes `Array` only) though `parse` has both (`Parser.kt:69`, `:221`). And nothing
in the repo demonstrates testing a klap CLI from outside: `example/build.gradle.kts` declares only
`commonMain.dependencies` with no test source set, and the README's testing guidance is four lines under
`## Escape hatch` (`README.md:754`), with no `## Testing` heading anywhere.

### F12. No way to share an option set across some-but-not-all subcommands

**Wanted.** Declare `--verbose`/`--config`/`--dry-run` once, attach them to the five subcommands that read
them, leave the other three clean.

**Had to write.** A holder class, an extension on `CommandBuilder` (not a function taking one), the lateinit
dance, and per-call-site rebinding five times, so each of the three inputs is named **four** times before it
is used once:

```kotlin
class Common(val verbose: Flag, val config: Opt<String>, val dryRun: Flag)

fun CommandBuilder.commonOptions(): Common {
    lateinit var verbose: Flag
    lateinit var config: Opt<String>
    lateinit var dryRun: Flag
    group("Common options") {
        verbose = flag("verbose", "v", "print each step as it runs")
        config = option("config", "c", "path to the depot config").file().default("depot.toml")
        dryRun = flag("dry-run", "n", "show what would happen; change nothing")
    }
    return Common(verbose, config, dryRun)
}
```

**Mechanism.** The documented answer, and the one baked into klap's own error message, is
`globalOption`/`globalFlag`. It is structurally wrong whenever the set is shared but not universal:

- Globals are root-only (`CliBuilder.kt:19`, `:22`) and tree-wide. `Help.kt:187-203` appends every global to
  every node's `Global options` block with no per-command filter, so `depot logs --help` advertises
  `--dry-run`. Worse, `depot logs --dry-run` parses and binds silently, because globals are stripped from
  argv before the subcommand walk (`Parser.kt:135`) and nothing checks that the resolved command reads them.
  The same wrong text lands in `docs markdown`/`docs man` (`Docs.kt:39`) and in completion
  (`Completion.kt:97`).
- Globals reserve their long name **and** short across the whole tree (`BuilderValidation.kt:247-263`), so
  one global steals a letter from every subcommand forever. A global `-n` for `--dry-run` turns
  `logs --lines/-n` into a construction-time throw.
- You cannot hoist the set onto an action-less parent: `validateActionlessLocalOptions`
  (`BuilderValidation.kt:158-171`) rejects it, and its message explicitly redirects you to
  `globalOption/globalFlag`.

The route that works needs three facts, none in the README or any signature: the helper must be an
**extension on `CommandBuilder`**, not a function taking one (every converter is a member-extension of
`ConverterScope`, `Converters.kt:121`, so `b.option("config","c").default("x")` fails with
`unresolved reference: default` pointing nowhere near the cause); the handles cannot be one shared `val`
(every call constructs a fresh spec, `BuilderImpl.kt:49-72`, and `bind` reads only the resolved command's
own specs, `internal/parse/Parser.kt:42`); and putting the set under its own heading costs the F1 dance.

**A trap that compiles and crashes.** Declaring the set on a *hybrid* parent (own action plus subcommands,
which klap allows) and closing over the handles in the children's actions compiles, builds, and throws
`IllegalStateException` the first time a child reads one (`ActionScope.kt:24-28`, keyed on spec identity at
`ValueScope.kt:32-37`). `Git.kt:305-312` is a shipped hybrid one line of code away from it.

**Corpus.** Only `Git.kt` is a multi-subcommand CLI, so 1/10 directly. Its in-file evidence is the cost:
`dry-run` declared twice (`:109`, `:181`), `verbose` twice (`:110`, `:314`), `author` twice (`:166`,
`:244`), `patch` twice (`:112`, `:248`), `all` three times (`:114`, `:176`, `:252`), each an independent
spec with independently written help text. `Git.kt:50-58` and `:169-172` document losing `-C, --reuse-message`
on `commit` to the global `-C`. `Git.kt:305-312` shows the workaround shape: `remote` is a hybrid with its
own action purely so it can hold `-v`.

### F13. `Failure.detail` skips sanitization exactly where a consumer is most likely to be careless — **RESOLVED**

> **RESOLVED on 2026-08-02**, going further than P4. Rather than sanitizing only the new `Usage` variant,
> the trust-boundary exemption was removed outright: `renderError` now runs *every* variant through
> `stripTerminalEscapes` on both paths, so `Cp.kt:254`'s shape is safe without the author knowing anything.
> The line moved from "who is the audience" to "who wrote the sentence": an authored detail (`Usage`,
> `Failure`) keeps its own newlines and tabs via `allowWhitespace = true`, which fixes the second
> inconsistency below in the same stroke — a multi-line detail now survives `--json` as a real `\n` escape
> instead of the literal `\x0A`. An argv-echoed token keeps neither, so a caller cannot forge a second
> `error:` line. The cost, accepted deliberately: an action can no longer color its own error detail,
> because a detail that interpolates argv makes the author's color and an injected one indistinguishable.
> The analysis below is the pre-fix record.


`ErrorRendering.kt:95-98` is a deliberate, commented trust-boundary decision:

```kotlin
val rendered = when (error) {
    is CliError.Failure -> error.detail          // developer-authored, like Ok's output
    else -> stripTerminalEscapes(error.message())
}
```

The rationale (`:91-94`) is sound. The consequence is not: `Failure` is also the only variant that lets you
write a custom sentence, so it is exactly what a hand-written usage rule reaches for, and a usage message
almost always wants to name the offending token, which comes from argv. `stripTerminalEscapes` is `private`
(`:18`), so compensating means copying it, and the README never mentions the exemption.

**This is live today, not hypothetical.** `Cp.kt:254` interpolates a raw argv operand:
`CliError.Failure("missing destination file operand after '${ops.first()}'")`. So `cp $'\e[31mred'` writes
that escape unmodified to stderr, while `cp --e$'\e[31m'` (a parse error over the same bytes) is stripped.
The showcase does the same shape at `Main.kt:252` and is safe only by luck (the interpolated value is an
`Int` from `.int()`).

**A second inconsistency in the same field.** A multi-line detail (a `hint:` continuation line, as
cargo/rustc/git all emit) means two different things on the two paths: the human path passes the detail
through verbatim so the newline renders (`:96`), while the `--json` path runs the same string through
`stripTerminalEscapes` (`:89`), which maps `0x0A` to the literal four characters `\x0A`. A JSON consumer
reads `"...\\x0Ahint: ..."` instead of a properly escaped newline.

### F14. `argument()` inside `group { }` is a silent no-op

`BuilderImpl.kt:49-54` constructs `ArgumentSpec` without `currentSection`, with the comment "Positionals
never take a section", while `:56-72` stamps `currentSection` on options and flags. `ArgumentSpec`
(`HolderSpec.kt:57-74`) has no `section` field at all, and `Help.kt:160` renders every argument in the
default block unconditionally. So `group("Arguments") { argument("input") }` compiles, parses identically,
and renders as if the group were not there, with no warning at build time. `Curl.kt:144-145` already
recorded this: "Positionals are never sectioned: `BuilderImpl.argument()` ignores `currentSection`".

Related and unfixed the same way: `globalOption`/`globalFlag` inside a `group { }` also silently discards
the heading (`BuilderImpl.kt:74-84` never reads `currentSection`; `Help.kt:187-203` renders globals under
`Global options` regardless of `spec.section`). That is F21.

### F15. No short-circuit for klap's own `Result` inside an action

Every fallible call carries the same eight-token tail, and `Result.kt:15-39` exposes no `bind`, no raise
scope, no short-circuiting helper:

```kotlin
val tasks = store.load().getOrElse { return@action Err(it) }
```

The showcase is the evidence: **14 `getOrElse { return... }` sites in 255 lines** (`Main.kt:54, 81, 90, 106,
127, 133, 144, 146, 160, 171, 175, 190, 202, 206`), roughly one every eighteen lines. This one is a genuine
minor-friction: the shape reads fine, it just does not compose.

### F16. The did-you-mean machinery is internal though the slot is public — **RESOLVED**

> **RESOLVED in `f5fc85f`, 2026-08-02.** `suggest(token, candidates, ignoreCase)` is a public re-export of
> the parser's own helper, not a reimplementation, so the threshold cannot drift: the ~25 copied lines
> this section measured are now one call.
> The analysis below is the pre-fix record.

Four variants expose `suggestion: String?` as a public constructor parameter a consumer can fill:
`UnknownSubcommand` (`CliError.kt:13`), `UnknownOption` (`:16`), `InvalidChoice` (`:31`),
`TooManyArguments` (`:37`). The search that produces the value is `internal fun suggest`
(`Suggest.kt:35`) over `internal fun levenshtein` (`Suggest.kt:4`), and the phrasing helper
`String?.didYouMean()` is `private` (`ErrorRendering.kt:8`). Rendering `. Did you mean staging?` on a
domain error therefore costs ~25 lines copied verbatim, and any divergence is a visible inconsistency
inside one tool's output. Nobody in the corpus attempted it, which is the signal: the moment your error is
domain-level, the hint silently disappears from your tool.

### F20. No way to write to stdout and exit non-zero

`Ok(text)` writes stdout with a hard-coded exit 0 (`Runner.kt:74-79`); `Err(e)` writes stderr behind a
mandatory `error: ` prefix (`ErrorRendering.kt:99`). That excludes the `diff(1)` / `cmp(1)` /
`git diff --exit-code` / `grep(1)` contract: report on stdout, non-zero exit meaning "differences found",
not "failure". There is no `Terminal` on `ActionScope` either (`ActionScope.kt:12-29`), so writing stdout by
hand also bypasses the I/O seam and the broken-pipe check (`Runner.kt:44`). No corpus tool needs it, and
that is an honest caveat, but `grep`/`diff`/`cmp`/`test` are a real class klap cannot express.

### F19, F22, F23, F24 (short)

- **F19, overwrite vs compose.** `spec.complete = provider` is a plain assignment (`Converters.kt:246`,
  `:361`), as are `spec.choices` (`:63`, `:103`) and `spec.valueHint` (`:234`, `:343`), while
  `andThenConvert` (`:17-25`) and `applyValidate` (`:84-90`) compose. All five are spelled as
  identical-looking chain steps. So `.choice("a","b").choice("b","c")` accepts only `b` while `--help` and
  completion advertise `b|c`, and a call site that adds its own `.completeWith { }` to a converter that
  already carries candidates deletes them. `Chmod.kt:140-141` and `Dd.kt:65-75` both attach `.completeWith`
  directly after a `.convert`, which is exactly the bundle a reuse-site `.completeWith` would erase.
- **F22, `group`'s receiver is the enclosing command.** `BuilderImpl.kt:89` invokes `block()` on `this`, the
  same builder, so `@KlapDsl` cannot bite and every member of the enclosing command stays reachable and
  mutable inside the block. `group("Advanced") { hidden = true }` hides the entire command from its parent's
  help, not the group, and there is no way to hide a group as a unit. Same for `description`, `epilogue`,
  `aliases` and `action { }`.
- **F23, shape-dependent reservation.** `cli()` injects `[__complete]` when the root has an action and
  `[__complete, completion, docs]` when it does not (`CliBuilder.kt:33-35`), and `Cli`'s init derives its
  reserved-subcommand set purely from the injected nodes (`Cli.kt:68-75`). So on a hybrid root
  `command("docs")` builds fine; on a dispatcher the same line throws at startup. Adding or removing an
  unrelated root `action { }` silently flips it. `README.md:366` explicitly promises the opposite:
  "`completion` / `docs` / `__complete` as a root subcommand name, **regardless of your command's shape**".
  klap's own tests confirm the real behaviour (`BuiltinsTest.kt:157-172`).
- **F24, `.validate` before a converter.** The predicate's type is fixed at the call site, but `convertOne`
  invokes the stored validator on the **final** converted value after the whole chain has run
  (`internal/parse/Parser.kt:356-365`), and `applyValidate` stores `predicate(value as T)` with an
  unchecked cast (`Converters.kt:88`). A helper that pre-checks the raw string and then converts hands
  `it.isNotBlank()` a `ByteSize`; the never-throw guard (`:360-363`) turns the cast failure into
  `invalid value '4M' for keep-under: <cast message>`, a Kotlin error attributed to the user's input. The
  rule is "`.validate` must come after every converter" and nothing states it: the only ordering note in the
  file is "call before `.multiple()`" (`Converters.kt:225`) and the README's `.validate` table row
  (`README.md:246`) says nothing.

### F18. The README, as a first-run document

> **RESOLVED on 2026-08-03.** All five defects are closed, and the two that were snippet defects are now
> pinned by an executing test rather than by prose: `klap/src/commonTest/kotlin/com/fromwau/klap/ReadmeSnippetsTest.kt`
> transcribes the README's load-bearing snippets and runs them, so a snippet that stops compiling fails the
> build. That is the durable half of this fix. The prose half:
>
> 1. **Closed.** `README.md:234-243` names all three receivers in a table (`CommandBuilder`, `CliBuilder`,
>    `ConverterScope`), states the inheritance chain, and says klap owns the implementations so a reader
>    writes extensions against them. `README.md:245-270` then works the case the finding named: a
>    `private fun CommandBuilder.tagOption()` shared by two subcommands, with the reason the extension
>    returns the handle rather than hoisting one shared `val`. Executed by
>    `aCommandBuilderExtensionSharesOneDeclarationBetweenTwoSubcommands`.
> 2. **Closed.** The `ValueScope` snippet (`README.md:1226-1245`) is wrapped in its `cli("tasks") { }`, and
>    `:1247-1252` states why the helper must be a LOCAL extension function: `globalOption` is a `CliBuilder`
>    member, so both the declaration and the helper that closes over its handle have to sit inside the
>    block. Executed by `aLocalValueScopeExtensionInsideCliClosesOverAGlobalHandle` and
>    `theSharedHelperResolvesTheGlobalTheSameWayFromEitherScope`.
> 3. **Closed earlier the same day.** The converter table's "On" column for `.file()` now reads
>    `argument, option`.
> 4. **Closed.** `README.md:811-821` states the asymmetry outright, with a `when` showing `is Result.Success`
>    beside the comment `// not \`is Ok\``. `:823-832` documents all six combinators with their signatures,
>    and `:834-843` works `mapError` at a layer boundary, which is the one a reader needs first. Executed by
>    `aParseResultIsMatchedOnTheSubtypesRatherThanTheBuilders`, `mapErrorTurnsADomainErrorIntoACliErrorWithoutUnwrapping`
>    and `mapErrorLeavesASuccessAloneAndMapLeavesAnErrorAlone`.
> 5. **Closed.** The false sentence is replaced by `README.md:751-758`, which states the real rule and why it
>    is shape-dependent: klap reserves exactly the nodes it injects, `__complete` is injected always, and
>    `completion`/`docs` only when the root has no action of its own. Verified against `CliBuilder.kt:50-56`
>    and `Cli.kt:93-97`, not against F23's restatement of it.
>
> The aggregate evidence stands as a historical measurement and is not re-run: the ten corpus files no
> longer exist as such, and the fourteen fixtures that replaced them carry `file:line` citations for the
> same reason. Ten of ten agents having read the source is what produced this finding; whether a fresh
> corpus would still need to is untested.
>
> The analysis below is the pre-fix record. It was re-verified on 2026-08-03 before being acted on, and
> that re-verification is preserved here because it corrected the finding's own citations:

> **RE-VERIFIED against the current `README.md` on 2026-08-03, rather than assumed.** The README has been
> rewritten twice since this was written (`b0d437a`'s built-ins section and `3d54fb5`'s testing section,
> then the 2026-08-03 gap-closure pass), so each of the five defects was re-checked by grep and by reading
> the passage. **Four of the five still hold verbatim. One is now half stale.** The finding stays open.
>
> 1. **Still true.** `grep -n "CommandBuilder\|CliBuilder\|ConverterScope" README.md` still returns **zero
>    hits**, and the sentence that frames the receiver as unnameable is still there, now at `README.md:232`:
>    "it is a member resolved through the builder receiver", followed by the member list. What *did* change
>    is the neighbourhood: `ValueScope`, `ActionScope`, `CompletionScope` and `ColorScope` are all named and
>    explained now (`:906`, `:943`, `:956`, `:1127`, `:1179`), which makes the omission narrower and
>    stranger: every scope a reader *reads* values through is named, and the one they *write* declarations
>    through is not. A `private fun CommandBuilder.tagOption(...)` extension is still the only way to factor
>    a repeated declaration out of two subcommands, and it still appears nowhere.
> 2. **Still true.** The `ValueScope` snippet (now `:1155-1169`) still opens the fenced block with
>    `val store = globalOption("--file", "-f").default("tasks.json")` at what reads as file top level,
>    followed by `fun ValueScope.taskStore() = ...` and a bare `command("tag") { }`. `globalOption` is a
>    `CliBuilder` member, so as printed it does not compile. The block gained an explanatory comment and the
>    prose around it now names `ValueScope` properly; the enclosing `cli { }` it needs is still missing, and
>    the working shape (a local `val` plus a local extension inside the `cli { }` lambda) still appears only
>    at `example/.../Main.kt:32` and `:48`.
> 3. **Was true until this pass; FIXED on 2026-08-03.** The converter table's "On" column for `.file()` said
>    `argument`, while `Converters.kt` declares both `Arg<T>.file()` (`:237`) and `Opt<T>.file()` (`:466`).
>    The row now reads `argument, option`.
> 4. **Half stale.** The first half stands: of `Result`'s six combinators (`map`, `mapError`, `getOrElse`,
>    `fold`, `onSuccess`, `onError`), `grep -n "mapError\|\.fold(\|onSuccess\|onError" README.md` still
>    returns **zero hits**, and `getOrElse` still appears only as an import name and inside one snippet,
>    never with a signature. The second half is no longer true: `3d54fb5`'s "Testing your CLI" section uses
>    `Result.Success` and `Result.Error` in two worked snippets (`:1281`, `:1300`), so a reader who reaches
>    that section does meet the names they must match on. Nothing still states the `Ok`/`Err`-versus-subtype
>    asymmetry outright, so the trap is softened rather than closed.
> 5. **Still true.** The sentence has moved from `:366` to `:719` and is otherwise unchanged: "`completion`
>    / `docs` / `__complete` as a root subcommand name, **regardless of your command's shape**". Verified
>    against the source, not just against F23: `CliBuilder.kt:50-56` injects `completion` and `docs` only
>    when `base.action == null`, and `Cli.kt:94-97` derives the reserved set purely from the injected nodes,
>    so a hybrid root accepts `command("docs")` and a dispatcher rejects it. Only `__complete` is
>    unconditional, which is the half of the sentence that is true.
>
> The aggregate evidence below is also stale in its own way, and in klap's favour: the ten corpus files no
> longer exist as such, and the fourteen fixtures that replaced them carry `file:line` citations for the
> same reason. Ten of ten agents having read the source remains the finding.

Five concrete defects, all verified:

1. **The builder receiver is never named.** `README.md:158-163` says everything inside a block "is a member
   resolved through the builder receiver" and then lists the members, actively framing the receiver as
   unnameable. `grep -n "CommandBuilder\|CliBuilder\|ConverterScope" README.md` returns **zero hits**. The
   receivers are `public abstract class CommandBuilder` (`CommandBuilder.kt:21`) and
   `public abstract class CliBuilder : CommandBuilder()` (`CliBuilder.kt:8`), and a
   `private fun CommandBuilder.tagOption(...)` extension is the only mechanism for factoring a repeated
   declaration out of two subcommands. Zero of the ten corpus files and the showcase use it; the showcase
   instead declares `argument("id", "task id").int().completeWith { taskIdCandidates() }` byte-identically
   at `Main.kt:139`, `:153` and `:181`.
2. **The one `ValueScope` helper snippet does not compile as printed.** `README.md:684-698` shows
   `val store = globalOption("file", "f").default("tasks.json")` followed by
   `fun ValueScope.taskStore() = TaskStore(Path(store()))` with no enclosing block, reading as file top
   level. `globalOption` is a `CliBuilder` member (`CliBuilder.kt:19`), so the `val` cannot live there, and
   the `fun` must close over the handle. The working shape is a local `val` plus a **local extension
   function inside the `cli { }` lambda**, which appears only in `Main.kt:48` and `:53` and nowhere in the
   README.
3. **The converter table's "On" column is wrong for `.file()`.** `README.md:255` says `argument`;
   `Converters.kt:354` declares `public fun <T> Opt<T>.file(): Opt<T>`, and the showcase applies it to a
   `globalOption` at `Main.kt:32-34`. The README contradicts the library's own example.
4. **Five of six `Result` combinators are undocumented, and the type names you build with are not the ones
   you match on.** `grep -n "mapError\|\.fold(\|onSuccess\|onError\|Result.Success\|Result.Error" README.md`
   returns **zero hits**; `getOrElse` appears twice, never with a signature. `Ok`/`Err` are constructor
   functions (`Result.kt:33`, `:36`) while the subtypes are `Result.Success`/`Result.Error` (`Result.kt:5-6`),
   so `when (r) { is Ok -> ... }` does not compile and nothing hints at the real names.
5. **`README.md:366` states a rule that is false** (see F23).

The aggregate evidence: **155 klap `file:line` citations in the ten corpus files' comments** (`Find` 31,
`Git` 28, `Curl` 19, `Cp` 18, `Ssh` 15, `Chmod` 12, `Rm` 10, `Dd` 9, `Mkdir` 8, `Tar` 5). Every agent read
the source.

## 5. What klap does well

Only entries an agent volunteered, each re-verified.

- **Error rendering is unified, and it is the best thing in the study.** `run()` funnels parse errors and
  action errors into one function (`Runner.kt:31` and `:75` into `ErrorRendering.kt:80`), so an
  action-returned error gets stderr, the `error: ` prefix, the width/colour treatment and the
  `{"error":...,"code":n}` envelope under `--json` with zero extra work. `error.exitCode.coerceIn(1, 255)`
  at `ErrorRendering.kt:84`, with the JSON `code` field using the **same clamped value**, means a
  hand-written rule can never accidentally report success or wrap on the OS, and the two channels can never
  disagree. Returning `Err(CliError.MissingArgument("copy", "dest"))` from an action produces the same
  sentence and the same exit 2 as if the binder had raised it. Action-level failures are second-class in
  *declaration*, never in *rendering*.
- **Single-holder arity is excellent.** `.required()` (`Converters.kt:298`) is parse-time enforced, renders
  `(required)` for free, and fails with a structured `MissingRequiredOption` at exit 2 with zero code from
  the author. `.multiple(min)` and `.count()` are the same quality. The gap is specifically that this
  treatment stops at the boundary of one holder.
- **`CliError` is a public sealed hierarchy of data classes with a public `exitCode`** (`CliError.kt:7-44`),
  so `assertEquals(CliError.BadValue("limit", "abc", "not an integer"), err)` is exactly the assertion a
  test wants: no string matching, no rendering. This is the best-designed part of the surface for testing.
- **`parse()` is a genuinely good seam**: pure, no output, no exit, no action executed (`Parser.kt:69`), and
  it does run converters and validators, so every negative test is a clean structural assertion with zero
  fixtures. `--help` short-circuits before any binding (`Parser.kt:150-163`), so a guard in an action never
  breaks `tool --help`.
- **Collisions fail loudly at construction, never silently at parse.** A duplicate short inside one command
  (`BuilderValidation.kt:85-95`), a global colliding with any descendant (`:247-263`), a group heading
  clashing with a built-in section (`:309-314`, with a message that names the group and tells you to rename
  it), a reserved built-in name (`:291-293`), a case-colliding choice or enum constant
  (`Converters.kt:32-56`). One agent predicted the group-heading case would be a lying-output finding and
  was wrong. This is the right failure mode, and in the built-in case it is the right failure mode for the
  wrong policy.
- **One row model, four renderers.** `helpSections` (`Help.kt:153`) is the single source for `--help`,
  `--help-all`, the man page and the markdown table (`Docs.kt:39`, `:128`), and `BuiltinOptionHelp`
  (`Help.kt:37-44`) pins the built-in wording so `--help` and tab completion cannot disagree
  (`Completion.kt:103-116`). Nothing can drift.
- **The layout engine itself is careful.** The `WRAP_FLOOR` fallback degrades to a readable stacked layout
  instead of overflowing (`Help.kt:279-286`); the bold path pads by visible width rather than byte length so
  ANSI codes cannot break alignment (`Help.kt:246-252`); `collapseWhitespace` (`:260`) lets a multi-line
  Kotlin help string render as one aligned row; `wrapParagraph` (`:289-297`) respects intentional newlines.
  The problem is a missing input knob, not the renderer.
- **`--` shields every built-in consistently**, because every scan really is `takeWhile { it != "--" }`
  (`Parser.kt:70`, `:110`, `:148`, `:290`; `internal/parse/Parser.kt:39-40`). One escape hatch, uniformly
  applied, beats six special cases. `Ssh.kt:153-159` independently verified it.
- **Groups are re-entrant and idempotent by title.** `group` saves and restores the previous section
  (`BuilderImpl.kt:87-90`), and the help walk collects titles into a `LinkedHashSet` and re-filters every
  spec by title (`Help.kt:169-183`), so two separate `group("Details")` blocks merge into one heading in
  declaration order rather than emitting a duplicate. This is a genuinely good property and it is documented
  nowhere.
- **One concept covers inputs and subcommands.** `group("Danger") { command("destroy") { } }` threads the
  section into the child's build (`BuilderImpl.kt:97-104`) and renders under the same heading machinery. No
  second API to learn.
- **The read side is completely symmetric.** Inside `action { }`, `profile()` (grouped) and `dryRun()`
  (ungrouped) are indistinguishable (`ValueScope.kt:25-28`); the entire cost of grouping is paid at
  declaration time and nothing leaks into the action.
- **`ValueScope` as a shared sealed base is a real win.** `fun ValueScope.store() = Store(path())` reads
  identically inside `action { }` and inside `.completeWith { }` (`ValueScope.kt:19-28`), so a completion
  provider can offer ids out of whatever store `--file` resolved to, in one line, with no re-parsing.
- **`@KlapDsl` as a `DslMarker` does exactly the right thing.** Calling a helper inside `command("build") { }`
  resolves against the innermost `CommandBuilder`, so a helper written once cannot accidentally declare on
  the root; reading an input during construction, or declaring one inside `action { }`, is a compile error
  rather than a runtime surprise (`CommandBuilder.kt:9-10`, `ValueScope.kt:35`).
- **Converters compose in the order you read them, and the plain parser reuses perfectly.** A top-level
  `(String) -> Result<T, String>` has zero klap types in its signature and type-checks identically as `::fn`
  on `Arg<String>.convert` and `Opt<String?>.convert`. The corpus found this unaided: `Chmod.kt:140`,
  `Ssh.kt:69`, `Find.kt:255-267`. Cardinality composes with a custom converter with no special-casing.
  `.map`'s exception-to-`BadValue` wrapping (`Converters.kt:71-82`) makes a hand-written mini-parser a
  one-liner.
- **The "klap appends its own hint" rule** (`README.md:297-302`) removes a whole category of help-text
  drift: write the plain description, get `(required)`, `(default: v)`, `(repeatable, min N)` and the range
  for free.
- **`README.md:415-418` pre-warns about the reified-`Nothing` trap** for error-only actions. One agent said
  it would have hit it and did not, because it was documented before they got there.
- **`ActionScope.unbound`'s message names the input and states the rule in one sentence**
  (`ActionScope.kt:24-28`). When the parent-handle trap fires, the message tells you what you did wrong.
- **`Result.mapError` is `inline`** (`Result.kt:20`), so the `ActionScope` stays the implicit receiver
  inside the lambda and a boundary mapper can still resolve colours. Nothing about the conversion is
  awkward.
- **The `Failure.detail`-is-not-sanitized decision is reasoned about in-source** with an explicit
  trust-boundary comment (`ErrorRendering.kt:91-94`) rather than being an accident, even though F13 argues
  the consequence is wrong.

## 6. Corrected claims

This section is what makes the rest trustworthy. Everything below was asserted by an agent and did not
survive re-checking.

### 6.1 REFUTED: "a named postfix converter cannot be written"

The converter-reuse agent's central claim was that `fun Opt<String?>.byteSize()` "has no dispatch receiver
and cannot get one", because `.convert` is a member-extension of `ConverterScope` (`Converters.kt:284-296`),
`ConverterScope` has an `internal constructor` (`:121`), "and the example module enables no
`-Xcontext-parameters`, so a context receiver is unavailable too".

**The last clause is false.** klap is on Kotlin 2.4.10 (`gradle/libs.versions.toml:6`) with no pinned
`languageVersion` anywhere in the build, and **context parameters are on by default at language version
2.4**. Verified with `kotlinc 2.4.10`:

```
$ kotlinc -Xcontext-parameters I_ctx.kt
warning: the argument '-Xcontext-parameters' is redundant for the current language version 2.4.
```

and the file compiles clean with **no flag at all**:

```kotlin
context(scope: ConverterScope)
fun Opt<String?>.byteSize(): Opt<ByteSize?> = with(scope) { convert(::parseBytes) }

// call site, inside any builder block, reading exactly as wanted:
option("block-size", "b").byteSize().default(4.mib)
```

So the postfix named converter **is** expressible today, in the exact spelling the agent said was
impossible. This is a pure discoverability failure, and it is still a finding: it appears nowhere in the
README (which never names `ConverterScope` at all), nowhere in the corpus, and nowhere in the showcase, and
three independent studies in this repo rediscovered the inferior workaround from source instead. But the
friction is "undiscoverable", not "inexpressible", and the ranking in §3 reflects that (the converter-reuse
task contributes to F2, F10 and F18, not to a distinct top-tier entry).

### 6.2 MISSED: the prefix `ConverterScope` extension also works

The same agent asserted only two shapes work (an extension on `CommandBuilder` that declares and converts in
one call, or a holder extension taking the scope as an explicit parameter). A third, better one was not
tried and compiles cleanly (verified):

```kotlin
fun ConverterScope.byteSize(o: Opt<String?>): Opt<ByteSize?> = o.convert(::parseBytes)
fun ConverterScope.byteSize(a: Arg<String>): Arg<ByteSize>   = a.convert(::parseBytes)

// inside a builder block:
val blockSize = byteSize(option("block-size", "b")).default(4.mib)
val keepUnder = byteSize(argument("keep-under"))
```

It keeps the declaration site in control of naming, help and further chaining, which the
`CommandBuilder`-extension shape does not. It still needs one overload per holder kind, so the Arg/Opt
mirror complaint stands.

### 6.3 PARTLY WRONG: the `.multiple()` hoisted-type diagnostic

The group agent claimed that hoisting `.multiple()` with the wrong nullability yields
`assignment type mismatch: actual type is 'Opt<List<String>>', but 'Opt<List<String?>>' was expected`.

**No such error occurs.** klap's signature is `public fun <T> Opt<T?>.multiple(min: Int = 0): Opt<List<T>>`
(`Converters.kt:322`), and with an expected type of `Opt<List<String?>>` the compiler simply infers
`T = String?`, since `T? == String?` is satisfied by both `T = String` and `T = String?`. Verified: a file
declaring `lateinit var tags: Opt<List<String?>>` and assigning `option("tag").multiple()` compiles clean.

The friction (you must hand-write the converted type) is real, and the `.enum<E>()` case does produce the
claimed shape of error, verified verbatim:
`error: assignment type mismatch: actual type is 'Opt<Profile?>', but 'Opt<Profile>' was expected`.
But `.multiple()` is *more* forgiving than claimed, which is arguably worse: a wrong-but-accepted
`Opt<List<String?>>` silently hands the action a `List<String?>` whose elements are all non-null.

### 6.4 CORRECTED: the corpus's worst help-layout offender was missed

The help-layout agent listed "3 of 10 already past the 80-column threshold" and scored `Find.kt:217` at
**28** characters. Recomputing from `Help.kt:76-80`, `--regextype`'s six choices
(`findutils-default|gnu-awk|posix-awk|posix-basic|posix-egrep|posix-extended`) produce a **92**-character
signature: the widest in the corpus by 32 characters, wide enough that the row itself overflows an
80-column terminal, not merely the page layout. The correct count is **4 of 10** past the threshold
(`Find` 92, `Curl` 60, `Cp` 60, `Git` 58). `Cp.kt:107`'s `--update` is 39, not 41. The finding is
strengthened, not weakened; the corrected table is in §4/F6.

### 6.5 Line-reference drift

Several citations were 1 to 6 lines off. The body of this report uses the verified line. Corrections:

| Agent citation | Verified |
|---|---|
| `README.md:631-648` / `:631-645` (lateinit fix) | `README.md:631-646` |
| `README.md:605-615` (group intro) | example at `:598-613`, prose at `:615` |
| `Tar.kt:82-86` (the `-c` literals) | `Tar.kt:77-80` |
| `Tar.kt:35` / `:52` (mode-set gap comments) | `Tar.kt:38-43` and `:52-53` |
| `Tar.kt:80-102` (the hand-written checks) | `Tar.kt:76-101` |
| `Find.kt:400` / `:425` (symlinkModes computed / printed) | `Find.kt:401` and `:427` |
| `Ssh.kt:155-160` (the `--` note) | `Ssh.kt:153-159` |
| `Chmod.kt:83-91` (the `-h` note) | `Chmod.kt:84-90` |
| `Git.kt:87-89` (`git -v`) | `Git.kt:88-90` |
| `Git.kt:169-173` (`-C, --reuse-message`) | `Git.kt:169-172` |
| `Help.kt:107` / `:56` (Curl's residual-cost note, in-corpus) | `Help.kt:111` and `:56` |
| `HolderSpec.kt:47-52` (`NamedSpec.section`) | `HolderSpec.kt:49-54`, the `var` at `:53` |
| `Parser.kt:149-162` (help short-circuit) | `Parser.kt:150-163` |
| `Converters.kt:245` / `:360` (`spec.complete =`) | declarations at `:245`/`:360`, assignments at `:246`/`:361` |

### 6.6 Corrected corpus counts

| Claim | Verified |
|---|---|
| "Curl.kt:25-40 (16 lateinit)" and "(14)" | **14** declarations; two further `lateinit` mentions at `Curl.kt:19` and `:146` are comments |
| "Ssh.kt:79-81 (3)" vs a grep count of 4 | **3** declarations; `Ssh.kt:78` is a comment |
| "Git.kt:157-161 (5)" vs a grep count of 6 | **5** declarations; `Git.kt:155` is a comment |
| "33 `lateinit` across 7 files" | **29 in the corpus proper**, 33 counting the showcase's `Main.kt:61-64`. Both agents' framing was ambiguous; the corpus-only number is 29 |
| "4 of 10 stubs hit cross-flag arity" vs "6 of 10 hit cross-input rules" | Both are right under different definitions. **6/10** hit a cross-input rule of any kind (`Tar`, `Cp`, `Rm`, `Find`, `Chmod`, `Curl`); **4/10** of those are specifically exclusive-flag sets (`Tar`, `Find`, `Chmod`, `Cp`) |
| "`Cp.kt:161` spells a metavar in the help string" | `Cp.kt:161` is a comment. The real workaround sites are `Find.kt:226`, `Find.kt:269`, `Chmod.kt:98` |

### 6.7 Confirmed exactly, and worth saying so

Several claims that looked like they might be rhetorical checked out verbatim:

- `grep -n USAGE_ERROR_EXIT README.md` returns **zero**; the constant is referenced nowhere in the library
  outside `CliError.kt:4` and `:8`.
- `grep -n "CommandBuilder\|CliBuilder\|ConverterScope" README.md` returns **zero**.
- `grep -n "mapError\|fold(\|onSuccess\|onError\|Result.Success\|Result.Error" README.md` returns **zero**.
- `kotlinc 2.4.10` rejects a contract on an open member with exactly
  `error: contracts are not allowed for open or override functions`.
- 70 of 79 corpus options render `<value>`; 33 hoisted handles (29 + 4); 7 of 10 stubs declare a flag before
  an option; the `--completion` built-in row is exactly 43 characters wide.
- 12 of klap's 18 test files import `com.fromwau.klap.internal.*`.
- `Cp.kt:254` really does interpolate a raw argv operand into an unsanitized `Failure.detail`.
- `Ssh.kt:123`'s `-V` fallback really is unreachable, because `Ssh.kt:138`'s `destination` is Required.
- `README.md:366` really does contradict `CliBuilder.kt:33-35` + `Cli.kt:68-75`, and klap's own
  `BuiltinsTest.kt:157-172` proves the code's behaviour.

### 6.8 A methodological caveat on F11's headline number

The testing agent scored "10/10 corpus stubs already ship in the workaround shape: the action returns a
stringified dump of its inputs". That is factually true (verified: `Mkdir.kt:65-74`, `Rm.kt:98-121`,
`Cp.kt:245`, `Dd.kt:107`, `Find.kt:392`, `Curl.kt:164`, `Chmod.kt:160`, `Git.kt:99/135/207/280/332/352/361`,
`Ssh.kt:163`, `Tar.kt:76`) but it is **not evidence for the friction**: the coverage study *specified* the
stubs as dry, non-functional programs whose actions only print what they would have done. The stubs are
input-dumpers because they were commissioned as input-dumpers. The real evidence for F11 is narrower and
stronger: klap's own `ParseOptionsTest.kt:12-18`/`:31-35` builds a CLI whose action is
`Ok("port=${port()} verbose=${verbose()} ...")` and asserts on stdout, and the showcase's six actions all do
filesystem I/O (`Main.kt:80, 106, 126, 144, 171, 202`), so the same test against a real tool would need a
real filesystem. F11 is ranked on that, not on the 10/10.

## 7. Ranked proposals

Ordered by frictions-removed per unit of invasiveness. "Breaks the DSL" means existing user code stops
compiling or existing output changes.

### P1. Make `group` final, generic, and contract-carrying

**Fixes:** F1 entirely; halves F12's helper; removes the `lateinit`-inside-a-helper tax.
**Frictions removed:** 1 top-tier, 1 partial.
**Invasiveness:** ~6 lines moved from `BuilderImpl.kt:86-91` up into `CommandBuilder.kt:46`, plus
un-privating `currentSection` (`BuilderImpl.kt:47`).
**Breaks the DSL:** no. Verified.

```kotlin
internal abstract var currentSection: String?

@OptIn(ExperimentalContracts::class)
public fun <T> group(title: String, block: CommandBuilder.() -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    val previous = currentSection
    currentSection = title
    return block().also { currentSection = previous }
}
```

`final` is mandatory, not stylistic: kotlinc rejects a contract on an abstract or open member (verified,
§6.7), which is the single reason today's signature cannot express what `BuilderImpl` already does.

Two payoffs. The contract makes plain `val`s definitely-assignable inside the block, so the N-input case
drops `lateinit`, mutability, the `UninitializedPropertyAccessException` risk and the silent-rebind hazard
together. The `<T>` return removes the hand-written type entirely for the one- and two-input case, which is
most of the corpus (`Cp`, `Ssh`, `Chmod`, `Find` each group three or fewer):

```kotlin
val region = group("Details") { option("region", "r", "cloud region").required() }
```

Because same-titled groups already merge into one heading (`Help.kt:169-183`), calling `group("Details")`
once per input is behaviourally identical to one block. That property exists today; this signature is what
turns it into a usable style.

**Verified source-compatible.** A replica compiled clean with kotlinc 2.4.10 across five existing call
shapes: result discarded, empty block, block ending in an assignment (the README's dance, unchanged),
nested groups, and a block whose value is chained. The corpus needs no migration, only the deletion of its
29 `lateinit var` lines.

**Optional companion, four lines, no new concept:** a `.group(title)` chain method parallel to `.hidden()`.
`NamedSpec.section` is already a `var` (`HolderSpec.kt:53`) and `.hidden()` already writes the spec post-hoc
the same way (`Converters.kt:348-350`, `:387-390`).

```kotlin
public fun <T> Opt<T>.group(title: String): Opt<T> { spec.section = title; return this }
public fun Flag.group(title: String): Flag { spec.section = title; return this }
```

It is the only way to put **one** input under a heading declared elsewhere, for example from a shared
`fun CommandBuilder.commonOptions()`. Precedence needs one line of docs: the chain runs after construction,
so it wins over an enclosing block.

### P2. Add a value placeholder

**Fixes:** F2 (70 of 79 corpus options), and defuses F6 without anyone giving up `.choice()`.
**Frictions removed:** 1 top-tier, 1 partial.
**Invasiveness:** one nullable field on `ValueSpec`, one parameter on `option`/`argument` (or a
`.placeholder("FILE")` transformer), one `?:` in `Help.kt:61`.
**Breaks the DSL:** no; new output only where an author opts in.

```kotlin
internal fun ValueSpec.valuePlaceholder(): String =
    placeholder ?: choices?.joinToString("|") ?: "value"
```

Because `helpSections` is the single source, this fixes `--help`, `--help-all`, man, markdown and the
completion signature at once. Its second-order effect on F6 matters: an author with a metavar available
writes `--compress ALGO`, and the 56-character signature disappears.

Two small companions: `public fun <T> Opt<T>.hint(text: String): Opt<T>` writing `spec.valueHint`
(independent of `.range()`), so a reusable converter can annotate the help row; and a display hook for
defaults (`.default(value, display = "4M")`, or a `render` on a converter consulted by `Help.display`,
`Help.kt:64-73`), so a byte-size option stops advertising `(default: 4194304)` for an input language of
`4M`. Today the only lever is the converted type's `toString()`, which forces a domain wrapper class into
existence for a help-rendering reason.

### P3. Iterate declaration order, not `options + flags`

**Fixes:** F4 (7 of 10 stubs), and makes P1's "declare once per group" style order correctly.
**Frictions removed:** 1 top-tier.
**Invasiveness:** three call sites. `Help.kt:161`, `:170-177` and `:180` walk
`specs.filterIsInstance<NamedSpec>()` (one list, source order) instead of `(options + flags)`. `Command`
already keeps the ordered `specs` list (`Cli.kt:17`). No new API.
**Breaks the DSL:** it changes existing help output, so it wants to land **before 0.1.0 or never.**

This makes section order and row order match what the author wrote, which is what every author already
assumes, and it silently improves the help of 7 of the 10 stubs. It is also the reason **not** to add a
`sectionOrder(...)` API: declaration order is the better answer.

### P4. `CliError.Usage(detail)`, plus three README sentences

**Fixes:** F7 and F13 together.
**Frictions removed:** 2.
**Invasiveness:** one data class in `CliError.kt`, one `message()` branch, and moving the verbatim case at
`ErrorRendering.kt:95-98` so `Usage` falls under the stripped `else` arm.
**Breaks the DSL:** no.

`Usage` is a `Failure` whose `exitCode` is `USAGE_ERROR_EXIT` and whose detail **is** sanitized. That kills
the exit-code footgun and the sanitization inversion at once, and the name is what an author greps for.
Then document, in the typed-errors section: that `USAGE_ERROR_EXIT` exists; that **any** `CliError` variant
may be returned from an action, not just `Failure` (the single best answer in the whole study, currently
invisible); and that a multi-line `Failure.detail` renders differently on the two output paths, or better,
fix `--json` to escape a newline properly rather than routing it through `stripTerminalEscapes`.

### P5. Make handle names and the suggestion machinery public

**Fixes:** F10 and F16.
**Frictions removed:** 2.
**Invasiveness:** four one-line accessors plus one re-export.
**Breaks the DSL:** no; purely additive.

```kotlin
public val Opt<*>.name: String        // and Arg, Flag, CountFlag, read straight off the internal spec
public fun suggest(token: String, candidates: List<String>, ignoreCase: Boolean = false): String?
```

The name leaks nothing: it is already printed everywhere. Together they make a user-written
`requiredWhen(opt) { ... }` helper possible without restating the spelling, and they let one tool's
did-you-mean phrasing and threshold stay identical whether the error came from klap or from the action.
This deletes 25 of the ~40 lines the error-handling agent had to copy verbatim.

### P6. A `builtins { }` opt-out block

**Fixes:** F3 (10/10 affected), F9, and the F23 README contradiction as a free side effect.
**Frictions removed:** 3, including the study's only `have-to-fight-it` with universal reach.
**Invasiveness:** high relative to the others. Four coordinated changes.
**Breaks the DSL:** no; every default stays on.

```kotlin
cli("curl") {
    builtins {
        json = Builtin.Off
        color = Builtin.Off
        help = Builtin.On(short = null)      // frees -h
        version = Builtin.On(short = "V")    // -V short-circuits exactly like --version
    }
    option("json", help = "Post this JSON body")   // legal: `json` is no longer reserved
    flag("dereference", "h")                      // legal: `h` is no longer reserved
}
```

1. `RESERVED_LONG`/`RESERVED_SHORT` (`BuilderValidation.kt:12-15`) become sets computed from the resolved
   config and passed into `validateReservedNames` (`:285`). A disabled built-in's name is simply not in the
   set.
2. `Cli` stores the resolved config; `parse` consults it at its four short-circuits (`Parser.kt:70-99`,
   `:101`, `:110-132`, `:150-163`) and `run` at `Runner.kt:20`. `Builtin.On(short = "V")` makes `:101` match
   `-V` too, and because the token set comes from the config the short-cluster walk can pick it up, which is
   exactly the case a hand-written argv rewriter cannot handle.
3. `Help.kt:191-201` and `Completion.kt:105-117` emit a row per **enabled** built-in using the configured
   spelling. Both already read from `BuiltinOptionHelp`, so this is a filter, not a rewrite, and it is the
   change that stops `--help` lying.
4. `cli()`'s injection list (`CliBuilder.kt:33-35`) drops disabled builtin nodes, which makes `Cli`'s
   reserved-subcommand rule (`Cli.kt:68-75`) derive from an explicit config rather than from the accident of
   whether the root has an action. That makes the behaviour match what `README.md:366` already promises.

**If that is too much surface for a 1.0**, the 80% version is two properties on `CliBuilder`, in order of
value: `var versionShort: String? = null` (fixes ssh, curl and git in one line; all three are unexpressible
today) and `var builtinJson: Boolean = true` (fixes dd, rm, chmod, mkdir, curl). Independently of any of
this, `README.md:366` should be corrected today: it is false, and the true rule is the opposite of what a
reader would guess.

### P7. A command-level rule list, or group arity

**Fixes:** F5 (6/10), plus the completion half of it.
**Frictions removed:** 1 top-tier.
**Invasiveness:** moderate. New builder member, new stored state on `Command`, one new `CliError` variant,
one hook in `parse` after binding, plus rendering in `Help`/`Completion` (docs come free, both renderers
consume the same `helpSections`/`usageLine`).
**Breaks the DSL:** no.

Two spellings were proposed independently; they are complementary, not competing.

**(a) Rules as data**, which is the more general and the one that makes help renderable:

```kotlin
public fun CommandBuilder.check(help: String, rule: ActionScope.() -> CliError?)
```

Stored on the `Command`, run by `parse()` after binding and before dispatch, in declaration order, first
non-null wins. It reads the bound `ActionScope`, so no option state enters the positional binder: the
dependency direction the coverage study rightly refused stays untouched. What it buys over the status quo is
everything the action cannot give you: the error surfaces from `parse()` (so `runAction()` embedders see it
too), it fires before any side effect in the action body, and the `help` strings become renderable under a
`Rules:` block in `--help` and in generated docs. Thin sugar covers the six corpus cases without new
machinery, generating both the rule and its help line from the handles so declaration and documentation
cannot drift:

```kotlin
requires(output, "--format=json") { format() == "json" }
conflicts(targetDirectory, noTargetDirectory)
requireOneOf(create, extract, listContents)
```

**(b) Arity on `group`**, which is cheaper because the membership tag already exists:

```kotlin
public enum class GroupArity { ANY, AT_MOST_ONE, EXACTLY_ONE, AT_LEAST_ONE }
public fun group(title: String, arity: GroupArity = ANY, block: CommandBuilder.() -> Unit)
```

`section: String?` is already set on every spec by `BuilderImpl.group` and read by exactly one consumer
today (`Help.kt:169-183`). Record a `Map<String, GroupArity>` alongside, count truthy members per
constrained group after `bindFlagsAndOptions`, and emit a structured `CliError.ExclusiveGroup(title, given,
arity)`. `usageTail()` then renders `(-c|-x|-t)` for `EXACTLY_ONE` and `[-z|-j]` for `AT_MOST_ONE`;
`FlagSpec.metaHint()` (`Help.kt:83-89`) adds `(one of: --create, --extract, --list)` to each member row, the
same treatment a `.choice()` argument already gets at `Help.kt:95`; and `Completion.kt:95-118` filters out
the remaining members of a satisfied group using the `sifted` prefix it already computes at
`Completion.kt:70` and currently ignores.

(b) also incidentally fixes the label duplication of F10, since no spelling is ever re-typed. A
`Flag.conflictsWith(other)` spelling covers pairs but not "exactly one of three" and would require `Flag`
to stop being opaque.

### P8. Open the test seam

**Fixes:** F11 and F17.
**Frictions removed:** 2.
**Invasiveness:** low; four additive changes.
**Breaks the DSL:** no.

1. `public val Invocation.Execute.scope: ActionScope` (`Invocation.kt:17`), or a narrower
   `public fun Invocation.Execute.values(): ValueScope`. The accessors on `ValueScope` are already public;
   only the thing that carries them is not. This turns "argv to values" from impossible into a two-line
   test and removes the probe CLI, the `Any?` cast and the forced side effect. It costs nothing in safety:
   `ActionScope` already has an internal constructor, so a consumer still cannot forge one.
2. Ship `RecordingTerminal` (it exists verbatim at `TestTerminal.kt:4`) in `commonMain` or a `klap-test`
   artifact, plus a `Cli.capture(argv, columns, ansi): (exitCode, stdout, stderr)` helper, plus the
   `run(List<String>, Terminal)` overload `parse` already has.
3. Make `CliError.message()` public (`ErrorRendering.kt:31`), plus a public `Command.helpText(style)`-shaped
   entry and `Cli.completeCandidates(words)`. klap's own tests call all three directly.
4. A minimal public read model: `Command.hidden`, `Command.visibleSubcommands`, and an input view
   (`name`, `short`, `help`, `required`, `hasDefault`, `choices`) so "does `add` declare `--limit` in
   1..100?" is a structural assertion rather than a substring match on prose. Today `cli.subcommands`
   returns klap's injected builtins mixed into the user's own with no public way to tell them apart.

### P9. `reuse(opt)`: attach an already-declared input to a command

**Fixes:** F12.
**Frictions removed:** 1.
**Invasiveness:** ~4 lines in `BuilderImpl` (append the **same** `HolderSpec` into this command's `specs`,
optionally stamping `currentSection` if unset).
**Breaks the DSL:** no.

Everything downstream already supports it: the parse sink is `MutableMap<HolderSpec, Any?>` keyed by spec
identity (`Parser.kt:184`) and `ValueScope.read` does `bound.containsKey(spec)` (`ValueScope.kt:32-37`), so
one `val verbose` handle reads correctly from whichever command ran and is `unbound` everywhere else, which
is exactly the desired semantics. Specs are immutable after build and the sink is per-parse, so sharing one
spec across siblings is safe (only one command binds per parse). Help placement stays per-command
(`Help.kt:161`), and shorts stay per-command, so `-n` stays free for `logs --lines`.

**Honest caveat, to document rather than discover:** `NamedSpec.section` is a single `var` on the shared
spec (`HolderSpec.kt:53`), so a reused input carries one group heading everywhere it appears.

### P10. Cap the shared signature column

**Fixes:** the residual half of F6 with no author action.
**Frictions removed:** 1 partial.
**Invasiveness:** one expression. `Help.kt:314` becomes `min(maxSignature, columns / 3)`, and any signature
past the cap renders on its own line above its description.
**Breaks the DSL:** it changes output for pages that are currently restyled anyway, so it belongs with P3.

The alternative, giving options the treatment arguments already get (`Help.kt:95` emits
`(one of: a, b, c)` into the description column for an `ArgumentSpec` and explicitly skips options), removes
the asymmetry rather than papering over it, but changes more output. The cap is the safer default; P2 makes
either mostly unnecessary.

### P11. Two silent no-ops that should be errors or features

**Fixes:** F14 and F21.
**Frictions removed:** 2 (low severity, but "the DSL accepts a call it entirely ignores" is the worst of the
three available behaviours).
**Invasiveness:** trivial either way.

Give `ArgumentSpec` a `section` and honour it at `Help.kt:160`, or make `argument()` inside a group a
build-time error. Same for `globalOption`/`globalFlag` inside a group: either pass `currentSection` at
`BuilderImpl.kt:75`/`:81` and have `Help.kt` honour a non-null section, or reject the nesting at
construction. Separately, document (or prevent) that a `group` block's receiver is the enclosing command, so
`hidden = true` inside one applies to the whole command; if group-level hiding is wanted,
`group(title, hidden = true)` is the natural spelling.

### P12. Close the two silent converter failures the way klap already closes the cardinality ones

**Fixes:** F24, and makes `Converters.kt:13-15`'s "safe by construction" claim actually true instead of
being contradicted by `internal/parse/Parser.kt:331-333`.
**Frictions removed:** 1 latent.
**Invasiveness:** two build-time `require`s, costing nothing at parse. klap already closed the analogous
shared-holder aliasing hole three times, with `require`s and comments naming it (`Converters.kt:300-305`,
`:313-316`, `:324-327`); this is the same move for converters.

Track "a type converter has been applied" on `ValueSpec` and have `andThenConvert` require it is unset;
have `applyValidate` record that a validator exists and `andThenConvert` require none is registered yet,
with the message "`.validate` must come after every converter". Separately, either make `.completeWith`
append or add `.alsoCompleteWith`, so a reuse-site provider stops deleting a bundled converter's candidates
(F19).

### P13. README fixes

**Fixes:** F18 in full, and roughly half of F1's discoverability cost.
**Frictions removed:** 1 top-tier, several partial.
**Invasiveness:** documentation only.

1. Name the receivers at `README.md:161`: "resolved through the builder receiver, `CommandBuilder` (or
   `CliBuilder` at the root). Both are public, so a declaration shared by several subcommands can be
   factored into an extension", with a two-line snippet. One sentence turns an invisible mechanism into the
   obvious one.
2. Make the `ValueScope` snippet compile: show `README.md:684-698` with its enclosing `cli { }` and both
   declarations local, exactly as `Main.kt:46-56` has them, and say the helper must be a **local** function
   so it can close over the handle the builder just returned.
3. Fix `README.md:255` to `| .file() | argument, option |`.
4. Document the `Result` surface: a table for `map`/`mapError`/`fold`/`getOrElse`/`onSuccess`/`onError` with
   signatures, plus "`Ok` and `Err` are constructor functions; the subtypes you match on in a `when` are
   `Result.Success` and `Result.Error`".
5. Correct `README.md:366` (see F23), and add `USAGE_ERROR_EXIT` to the typed-errors section.
6. Document the postfix context-parameter converter (§6.1) in "Inputs and converters". It works today and
   nobody can find it.
7. Add a short "shared but not global" subsection next to `README.md:343-364`. Today all the advice,
   including the advice baked into `validateActionlessLocalOptions`' error message, points at globals, which
   is the wrong tool whenever the set is not universal.
8. Give the four-line testing note its own `## Testing` heading instead of leaving it under
   `## Escape hatch` (`README.md:754`), which is where a reader looking for "how do I test this" is least
   likely to look.

## 8. Inherent, and should be documented rather than fixed

Not every friction here is a defect. These are consequences of being a declarative parser and should get a
paragraph, not an API:

- **Positional arity that varies with options** (`cp`'s "2 operands normally, 1 with `-t DIR`"). Making the
  positional binder read option state is exactly the dependency direction the coverage study refused, and it
  should stay refused. With P7 it at least becomes one declared, documented rule instead of half a
  cardinality plus half an action body.
- **`runAction()` erasing to `Any?`** (`Runner.kt:95`). A `Cli` is not typed over its actions' return types
  and cannot be without making the tree generic over a union. Document the cast; P8's `scope` accessor
  removes the *need* for it in tests, which is where it hurt.
- **One spec carries one group heading** if P9 lands. That is almost certainly what an author wants; it just
  needs saying.
- **Last-one-wins flag ordering** (`chmod -H/-L/-P`). The sift records a per-spec occurrence count with no
  relative order (`Chmod.kt:109-114` cites the exact structure), which keeps the parse model simple and
  concurrent-safe. Recording order would be a real design change with real cost; the honest move is to
  document that klap models flag sets as unordered, so "last one wins" semantics are not expressible.
- **The `Failure.detail` trust boundary itself.** The decision to let a developer-authored detail carry
  colour is right; P4's `Usage` variant gives the careless path a safe home without removing the deliberate
  one.

## 9. Does anything here block a first release?

No correctness bug in this study blocks a release. The release question is **API-shape lock-in**, and on
that reading exactly three items are cheap now and expensive later:

- **P3 (declaration order)** is the only proposal that changes existing output. It lands before 0.1.0 or it
  never lands.
- **P1 (`group`)** and **P2 (placeholder)** are additive, but they change how every klap program is written
  and how every klap program's help reads. Shipping 0.1.0 without them means the corpus's 29 `lateinit`
  lines and 70 `<value>` placeholders become the documented idiom.

Everything else, including P6, is additive and can land at any point.
