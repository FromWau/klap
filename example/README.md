# klap by example

Runnable klap programs to copy from. Three kinds live here:

- **[`task-manager/`](task-manager/)** is a complete, installable CLI. Start here if you want to see a
  whole program: command tree, storage, colors, completion, packaging.
- **[`pulse/`](pulse/)** is a complete program too, but built to show one capability rather than a whole
  surface: suspending actions, real cancellation, a background loop running alongside the CLI. Start
  here if that is the part you need.
- **The fifteen tool directories** reproduce the command-line surface of a real tool (`pacman`, `git`,
  `tar`, `find`, ...) in klap. Their actions are stubs; the parsing surface is the point. Start here if
  you already know the behaviour you want and need to see how it is spelled.

For the concepts behind any of this, read the [guide](../docs/guide.md).

## "How do I make it behave like ...?"

Find the behaviour, open the file. Every path below is `example/<tool>/src/main/kotlin/.../<Tool>.kt`,
except pulse's row, which points at its module rather than a single file.

| You want                                                                                                    | Look at                                                         | The construct                       |
|-------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------|-------------------------------------|
| Mutually exclusive operation modes, exactly one required (`tar -c` vs `-x` vs `-t`)                         | [`tar`](tar/)                                                   | `exactlyOne`                        |
| Operations spelled as flags, each with its own options (`pacman -S` vs `-R` vs `-Q`)                        | [`pacman`](pacman/)                                             | `command(short, long)`              |
| Two options that conflict but are both optional (`tar -z` vs `-j`)                                          | [`tar`](tar/)                                                   | `atMostOne`                         |
| A flag repeated to raise a level (`-v`, `-vv`, `-vvv`)                                                      | [`pacman`](pacman/), [`ssh`](ssh/), [`curl`](curl/)             | `.count()`                          |
| A cluster whose last character takes a value (`tar -cvf out.tar`)                                           | [`tar`](tar/)                                                   | short clustering                    |
| An option with an explicit "off" spelling (`git --no-pager`)                                                | [`git`](git/), [`chmod`](chmod/)                                | `.negatable()`                      |
| The later of two conflicting flags winning (`head -q -v`)                                                   | [`cp`](cp/), [`head`](head/), [`ls`](ls/), [`find`](find/)      | `lastWins`                          |
| Passing a trailing command through untouched (`ssh host ls -la`)                                            | [`ssh`](ssh/)                                                   | `.endsOptions()`                    |
| An operand that disappears when an option supplies it (`cp -t DIR`)                                         | [`cp`](cp/), [`mv`](mv/), [`chmod`](chmod/)                     | `.absentWhen()`                     |
| An input required only when another is absent                                                               | [`rm`](rm/)                                                     | `.optionalWhen()`                   |
| An option whose value may be omitted (`ls --color` vs `--color=never`)                                      | [`cp`](cp/), [`git`](git/), [`ls`](ls/), [`mv`](mv/)            | `.optionalValue()`                  |
| A short whose value must be glued (`find -O3`, where `-O 3` is refused)                                     | [`find`](find/)                                                 | `.gluedValue()`                     |
| Bare `key=value` operands, no dashes anywhere (`dd if=x bs=4M`)                                             | [`dd`](dd/)                                                     | operand-only surfaces               |
| The obsolete digit-short form (`head -20`)                                                                  | [`head`](head/), [`git`](git/), [`task-manager`](task-manager/) | `numberOption`                      |
| Two spellings of one quantity read off one handle (`head -5` / `head -n 5`)                                 | [`head`](head/), [`git`](git/), [`task-manager`](task-manager/) | `lastOf`                            |
| Single-dash words that bind as one operand, not as options (`chmod -w -x f`)                                | [`chmod`](chmod/)                                               | `.dashLed(gather) { }`              |
| A subcommand tree with aliases (`git add` / `git stage`)                                                    | [`git`](git/)                                                   | `command { }`, `aliases`            |
| Help split into sections on a wide flat tool                                                                | [`curl`](curl/)                                                 | `group { }`                         |
| A value restricted to a fixed set (`cp --sparse=WHEN`)                                                      | [`cp`](cp/), [`git`](git/)                                      | `.choice()`                         |
| A value parsed into your own enum (`ls --sort=WORD`)                                                        | [`ls`](ls/), [`mv`](mv/), [`task-manager`](task-manager/)       | `.enum<E>()`                        |
| A number with bounds (`--max-time`, `-p PORT`)                                                              | [`ssh`](ssh/), [`curl`](curl/)                                  | `.int()`, `.range()`                |
| Rejecting a malformed value with your own message                                                           | [`head`](head/), [`git`](git/)                                  | `.validate()`                       |
| Rejecting a value only wrong because of another input (`--due` in the past unless `--done`)                 | [`task-manager`](task-manager/)                                 | `validateInputs { }`                |
| An input kept out of `--help`                                                                               | [`git`](git/), [`task-manager`](task-manager/)                  | `hidden`                            |
| `-h` as the help, as `git -h` and `pacman -h` are                                                           | [`git`](git/), [`pacman`](pacman/)                              | `Help.ByCommand(short = 'h')`       |
| Tab completion that reads your CLI's own parsed inputs                                                      | [`task-manager`](task-manager/), [`chmod`](chmod/), [`dd`](dd/) | `completeWith { }`                  |
| An option that may appear many times (`curl -H a -H b`)                                                     | [`git`](git/), [`ssh`](ssh/), [`curl`](curl/)                   | `.multiple()`                       |
| An action that suspends, with real cancellation reaching it from the caller's scope (`pulse watch`, Ctrl-C) | [`pulse`](pulse/)                                               | `actionSuspending`, `runSuspending` |

Every row above was checked against a real call, not a mention in a comment. One construct klap offers
has **no example here yet**, so read the guide for it:
[`requiredWhen`](../docs/guide.md#cross-input-constraints) (required only when another input is present).

## The tool fixtures

Each tool directory is one Gradle module with two files:

```
example/pacman/
  src/main/kotlin/.../Pacman.kt                the klap CLI
  src/test/kotlin/.../PacmanProjectionTest.kt  what it accepts, rejects and binds
```

The `.kt` file shows you how a surface is declared. The parity test shows you what that declaration
actually does, line by line, which is often the faster read:

```kotlin
parity.binds("-n", "20", "notes.txt", expected = NOTHING_BOUND.copy(lines = "20", files = listOf("notes.txt")))
parity.rejects("--lines", because = "real head: option '--lines' requires an argument")
```

`parity` is a [`ParitySuite`](parity/src/main/kotlin/com/fromwau/klap/fixture/ParitySuite.kt), the small
DSL in [`example/parity/`](parity/) that every fixture test is written in. Its verbs are the claims a
fixture can make:

| verb                                    | the claim                                                               |
|-----------------------------------------|-------------------------------------------------------------------------|
| `binds(argv, expected)`                 | parses, dispatches, and binds exactly this record                       |
| `bindsLoosely(argv, because, expected)` | klap binds it but the **real tool rejects it**: invented surface        |
| `rejects(argv, because)`                | does not parse; `because` names the real tool's answer, or the klap gap |
| `showsHelp(argv, because)`              | resolves to help specifically                                           |
| `shortCircuits(argv, because)`          | a built-in swallowed the line before the command saw it                 |

`accepts` / `acceptsLoosely` also exist, taking a lambda over the bound record instead of a whole
expectation. No fixture currently uses either: whole-record comparison won everywhere.

The suite never runs an action, which is what lets a fixture recreate `rm` or `dd` without owning an
implementation of it.

**`binds` compares the whole record**, and that is the point rather than a detail. `NOTHING_BOUND` is the
fixture's no-arguments baseline, so a case names only what its line supplies and every other field is
pinned to its default by omission. That is what catches the inputs a line affects *indirectly*: `cp`'s
`-dbuHpZ` sets `backup` and `update` without naming either, and `pacman`'s `-Syyu` implies
`sync`. Assertions written field by field only ever test the fields someone remembered to name.

### Fixture progress

A fixture is done when its tests cover the [testing matrix](../.claude/skills/fixture-parity/references/testing-matrix.md)
with expectations taken from the installed tool, and every target among them passes. A target is a test the fixture
or klap fails today, kept under `@Ignore("TDD target: ...")` and filed in [`todo.md`](../todo.md).

| tool   | tested against       | fixture progress                                                                           |
|--------|----------------------|--------------------------------------------------------------------------------------------|
| pacman | pacman 7.1.0         | done: 171 tests, every target passes; 3 kept as records of pacman quirks (todo 36, 61)     |
| cp     | GNU coreutils 9.12   | done: 179 tests, every target passes; 1 kept as a record (todo 89)                         |
| chmod  | GNU coreutils 9.12   | done: 103 tests, every target passes                                                       |
| curl   | curl 8.23.0          | not yet done                                                                               |
| dd     | GNU coreutils 9.12   | done: 98 tests, every target passes                                                        |
| find   | GNU findutils 4.11.0 | done: 201 tests, every target passes; 9 kept as records (F1, todo 48a, 67, 72, 75)         |
| git    | git 2.56.0           | not yet done                                                                               |
| head   | GNU coreutils 9.12   | done: 119 tests, every target passes; 1 kept as a record (todo 145)                        |
| ls     | GNU coreutils 9.12   | done: 152 tests, every target passes                                                       |
| mv     | GNU coreutils 9.12   | done: 174 tests, every target passes; 1 kept as a record (todo 89)                         |
| rm     | GNU coreutils 9.12   | done: 147 tests, every target passes; 2 kept as records (todo 118, 121)                    |
| ssh    | OpenSSH 10.6p1       | not yet done                                                                               |
| tar    | GNU tar 1.35         | not yet done                                                                               |

### How a fixture is shaped

Every fixture follows the same three-part shape, and it is worth naming because nothing else in the docs
does. A fixture uses [`cliOf`](../docs/guide.md#testing-your-cli) rather than `cli`, so that a test can read
what an argv bound without running the action:

```kotlin
public fun headCli(): TypedCli<HeadInputs> = cliOf("head") {
    val lines = option(short = 'n', long = "lines") // 1. handles are ordinary vals
    val files = argument("file").multiple()
    action { ... }
    projection { HeadInputs(lines(), files()) }   // 2. the block ends by reading them into your type
}

public data class HeadInputs(val lines: String?, val files: List<String>)   // 3. resolved values, not handles

public val NOTHING_BOUND: HeadInputs = HeadInputs(lines = null, files = emptyList())
```

`HeadInputs` holds **values**, not the handles that read them, which is what lets a case compare a whole
invocation. `NOTHING_BOUND` is the no-arguments baseline every case `.copy()`s from.

`git` is the only fixture with subcommands, so it is the only one that differs: each command ends in its
own `projection { }` and the root combines them with `dispatch(...)`, giving a sealed `GitInputs` whose
variants a caller matches exhaustively. `remote` both acts and nests, so its own block ends in
`dispatch(remoteAdd, remoteRemove, projection { ... })`.

Every fixture is measured against the real tool. Its `version =` line prints, word for word, what that tool's
`--version` printed when it was pinned (2026-10-10; curl 2026-10-17), and a `<Tool>VersionTest` holds the text: GNU
coreutils 9.12, GNU findutils 4.11.0, git 2.56.0, curl 8.23.0, OpenSSH 10.6p1, pacman 7.1.0, GNU tar 1.35.
`ssh` answers `-V` and has no `--version`, as `Version.Of("10.6p1", short = 'V')` declares. `find` transliterates find's
expression grammar rather than reproducing it, but prints the installed find's version all the same. Where klap
cannot reproduce something, the file says so in a `KLAP-GAP` comment rather than quietly working around
it, so a gap stays visible.

Run them all, or one:

```bash
./gradlew check                 # everything, every fixture's parity suite included
./gradlew :example:pacman:test  # just one fixture
```

The tools, and why each is in the suite:

| Tool                     | The shape it contributes                                                                                                                                   |
|--------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [`pacman`](pacman/)      | one required operation mode out of seven, each with its own option set                                                                                     |
| [`git`](git/)            | a deep subcommand tree, root options read before the first subcommand only, nine negatable flags                                                           |
| [`find`](find/)          | an expression language rather than an option list, and where that stops fitting                                                                            |
| [`ssh`](ssh/)            | a wrapper: its options run on past the destination, and everything from the remote command's first word belongs to it                                       |
| [`curl`](curl/)          | a wide flat tool whose help needs sections, repeatable headers                                                                                             |
| [`tar`](tar/)            | value-taking clusters (`-cvf out.tar`), and both exclusivity shapes side by side: `exactlyOne` for `-c`/`-x`/`-t`, `atMostOne` for `-z`/`-j`               |
| [`dd`](dd/)              | no flags and no options at all, only bare `key=value` operands                                                                                             |
| [`cp`](cp/), [`mv`](mv/) | three synopsis forms, and an operand that moves into an option                                                                                             |
| [`chmod`](chmod/)        | a mode operand that looks like an option, gathered with `dashLed(gather) { }` (`chmod -w -x notes.txt`)                                                    |
| [`rm`](rm/)              | prompt options the last of which wins, an operand optional when another input is present                                                                   |
| [`ls`](ls/)              | many small format/sort flags that override one another                                                                                                     |
| [`head`](head/)          | the obsolete `head -20` digit short, dash-led values (`-n -5`)                                                                                             |

### What each fixture declares about `abbreviation`

`Abbreviation` (see the [guide](../docs/guide.md#abbreviation)) is root-only and defaults to `LongOptions`,
getopt_long's rule. Every fixture but one states its mode explicitly, so the tool's abbreviation behaviour is
visible in its file:

| Mode                                          | Fixtures                                                                                                                                                                                                                                                                                                                                                     |
|-----------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Abbreviation.Options`                        | `chmod`, `cp`, `dd`, `git`, `head`, `ls`, `mv`, `rm`, `tar` — nine, including **all seven coreutils stubs**, which go through `getopt_long` without exception, plus `git`, whose subcommands' options abbreviate the same way, while its root's own options match whole through `ownOptions(ShortOptions.Alone, Abbreviation.None)`                          |
| `Abbreviation.LongOptions` (declared)         | `pacman`: a long option abbreviates but a value only in full, so `pacman --quer` is `--query` and `pacman -Q --color=al` is refused                                                                                                                                                                                                                          |
| `Abbreviation.None` (declared, not inherited) | `curl`, `find` — each matches long options exactly, and each says so rather than inheriting the default silently                                                                                                                                                                                                                                    |
| `Abbreviation.All`                            | `task-manager`, the showcase                                                                                                                                                                                                                                                                                                                                 |
| left on the ambient default                   | `ssh`                                                                                                                                                                                                                                                                                                                                                        |

The last row is exempt because **`ssh`** has no long options at all — `ssh --help` itself answers `unknown
option -- -` — so abbreviation would be a no-op on the real tool. The fixture's `--port`, `--login` and the rest
are klap's own invented spellings for readability, not surface `ssh` has to abbreviate.

A long abbreviates against the longs of the command it is typed at, its globals and klap's built-ins, as each GNU
tool hands getopt_long its own table: `git branch --f` weighs only `git branch`'s options, never a sibling's.
pacman's operations are flag-spelled commands, which share one table with the root as pacman's are options of one
getopt_long table, so `pacman -Q --no` stays ambiguous among every `--no...` pacman has.

## The task-manager showcase

[`task-manager/`](task-manager/src/commonMain/kotlin/com/fromwau/example/Main.kt) is `klapExample`,
a file-backed task manager with `add` / `list` / `done` / `rm` / `tags` and a nested `tag` group.
The two tag commands are deliberately a pair: `tag add` / `tag rm` act on one task's labels, while
`tags` reports every label in the store. It exercises most of klap in one program: subcommands and
nested groups, a command alias, a global `--file` and a counting global `-v`, group-scoped options,
enum/choice/multiple/validate converters, `validateInputs` rules for the checks no single input can
make, a `.range()`-bounded `--limit`, colorized output through a `Styled` helper, a `hidden`
diagnostic subcommand, typed errors with custom exit codes, structured `--json`, and value-aware
completion that resolves `--file` through the same accessor the action uses.
Unlike the fixtures it stays on plain `cli(name) { }`. It is a program: its values are read inside
`action { }` and nowhere else, so it needs no projection. Its tests drive `run(argv, terminal)` and
assert rendered output and exit codes, which is the other half of the same rule — `inputs` is for binding,
`run` is for rendering.

The JSON store is not a klap concern, but it is worth a look if you are copying this: every
load-modify-save runs under a lock, so two invocations sharing one `--file` cannot silently drop each
other's writes.

It is also where to look for **clustered short options**, the most recognisable thing about a GNU-style CLI:

```bash
klapExample list -rl                 # a pure boolean cluster: --reverse --long
klapExample list -rln 5              # ...ending in a value-taker, so 5 binds to --limit
klapExample add "Ship it" -Dp high   # --done, then --priority takes high
klapExample -vv list -rl             # a counted global repeated, plus a local cluster
klapExample list -rnl                # error: invalid value 'l' for --limit
```

That last line is the rule people get wrong, and the reason the others are worth showing: a value-taking
option in a cluster must come **last**, or it swallows the rest of the token as its value.

```bash
./gradlew :example:task-manager:runReleaseExecutableLinuxX64
```

### Building a native binary

All of its code lives in `commonMain`, and that one source set builds native Linux and Windows binaries.
Kotlin/Native run tasks do not accept `--args`, so for a CLI the built executable
is what you actually use:

```bash
./gradlew :example:task-manager:linkReleaseExecutableLinuxX64
./example/task-manager/build/bin/linuxX64/releaseExecutable/klapExample.kexe add "Ship it" -p high
./example/task-manager/build/bin/linuxX64/releaseExecutable/klapExample.kexe list
```

The result is an ordinary executable that starts in single-digit milliseconds, with no JVM and no runtime
on the target machine. `mingwX64` cross-compiles from Linux, so both come out of one build:

```
$ file klapExample.kexe klapExample.exe
klapExample.kexe: ELF 64-bit LSB executable, x86-64, dynamically linked
klapExample.exe:  PE32+ executable for MS Windows 6.00 (console), x86-64

$ time ./klapExample.kexe --version
klapExample <version>
0.004 total
```

Anything in `commonMain` must itself be multiplatform. The most common thing that is not is file I/O:
`java.io.File` will not compile there. This module uses [kotlinx-io](https://github.com/Kotlin/kotlinx-io)
(`SystemFileSystem`, `Path`) so its storage layer stays in `commonMain`; `expect`/`actual` or Okio work
equally well.

### Packaging it

[`task-manager/packaging/arch/PKGBUILD`](task-manager/packaging/arch/PKGBUILD) is a reference PKGBUILD for
shipping a klap CLI on Arch. Because the binary is self-contained, the package depends on `glibc` and
nothing else: no JVM, no runtime. It generates the shell completions and the man page by running the
binary it just built, so neither can drift from the shipped CLI.

```bash
cd example/task-manager/packaging/arch && makepkg -si
```

## The pulse showcase

[`pulse/`](pulse/src/main/kotlin/com/fromwau/klap/fixture/pulse/Main.kt) is a service monitor whose work
actually suspends: six simulated services with their own latencies and failure modes, an `async` fan-out
gathered with `awaitAll`, `withTimeoutOrNull` at two different scopes, a metrics loop running alongside
the CLI in the caller's own scope, and a tree that mixes `actionSuspending { }` commands with one plain
synchronous `action { }`. Times are `kotlin.time.Duration` end to end, parsed from the command line
through a `.convert { }` that fails with its own typed error.

Its `main()` is where the parts klap deliberately does not own live: the caller's `Job`, a shutdown hook
that cancels it on Ctrl-C, and the exit code. That path is what makes a Ctrl-C reach both the suspended
action and the independent metrics loop, letting the loop's `finally` run before the process exits:

```
[pulse] shutdown signal received, cancelling...
[metrics] loop shut down cleanly after 13 tick(s)
[pulse] cancelled
```

None of that machinery is klap's, which is the point: klap declares suspending functions and never runs,
blocks on, or cancels a coroutine itself. The [guide](../docs/guide.md#suspending-actions) explains that
split; this module is it, working.
