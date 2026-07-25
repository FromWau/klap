# klap guide

The complete reference for [klap](../README.md). Start at the [README](../README.md) for what klap is and
how to add it; start at [`example/`](../example/README.md) for runnable programs to copy from.

| | |
|---|---|
| [Quick start](#quick-start) | the builder DSL, the three receivers, sharing a declaration |
| [Inputs and converters](#inputs-and-converters) | spellings, dash-led values, numbers, dependent and optional-value operands |
| [Flags](#flags-boolean-counted-negatable) | boolean, counted, negatable |
| [Cross-input constraints](#cross-input-constraints) | `requireExactlyOne`, `requireAtMostOne`, `lastWins`, `requiredIf` |
| [Global / persistent options](#global--persistent-options) | options shared by every subcommand, and declining a built-in |
| [Typed results and errors](#typed-results-and-errors) | `Result`, `CliError`, exit codes, did-you-mean |
| [Structured `--json`](#structured---json) | one `Ok(value)`, two renderings |
| [Color output](#color-output) | styles, `ColorScope`, `NO_COLOR`, `--color` |
| [Command groups and nesting](#command-groups-and-nesting) | subcommand trees and help sections |
| [Help output](#help-output) | layout, wrapping, examples, epilogue |
| [Shell completion](#shell-completion) | bash/zsh/fish/powershell, value-aware providers |
| [Generated docs](#generated-docs) | markdown and man pages |
| [Escape hatch](#escape-hatch) | driving the parser yourself, and testing your CLI |
| [POSIX conformance](#posix-conformance) | what klap guarantees, and the two deliberate exits from it |
## Quick start

`cli(name)` builds the root command. A single-command tool can act at the root, so no subcommand is
required. This is the [README](../README.md)'s front-page program with a `description` and a bound on
`--times` added:

```kotlin
import com.fromwau.klap.Ok
import com.fromwau.klap.cli
import com.fromwau.klap.main

fun main(args: Array<String>) {
    cli("greet") {
        description = "Say hello"
        version = "1.0.0"

        val name = argument("name", "who to greet")
        val loud = flag("--loud", "-l", help = "shout it")
        val times = option("--times", "-n", help = "how many times").int().range(1..10).default(1)

        action {
            val line = "Hello, ${name()}!".let { if (loud()) it.uppercase() else it }
            Ok(List(times()) { line }.joinToString("\n"))
        }
    }.main(args)
}
```

```
$ greet Ada --loud -n 2
HELLO, ADA!
HELLO, ADA!
```

Everything lives in package `com.fromwau.klap`. Import the entry points you name at the top level:
`cli`, `main`, `Ok`, `Err`, `CliError` (plus `Result` / `Opt` / `getOrElse` if you reference them).
Everything you call *inside* a `cli { }` / `command { }` block takes no import, because it is a member
of the block's receiver.

Those receivers have names, and you will want them the first time you factor a declaration out:

| Receiver | Where it is the receiver | What it carries |
|---|---|---|
| `CommandBuilder` | `command(name) { }` | `argument`, `option`, `flag`, `command`, `group`, `example`, `action`; the cross-input rules `requireExactlyOne`, `requireAtMostOne`, `lastWins`, `numericAlias`; the per-command settings `description`, `aliases`, `epilogue`, `hidden`, `optionsEndAtFirstOperand` |
| `CliBuilder` | `cli(name) { }` | everything on `CommandBuilder`, plus the root-only `globalOption`, `globalFlag`, `version`, `author`, `builtins { }` |
| `ConverterScope` | the base of both | every converter: `.int()`, `.map()`, `.default()`, `.range()`, `.count()`, `.negatable()`, `.absentWhen()`, `.completeWith()`, ... |

`CliBuilder` extends `CommandBuilder`, which extends `ConverterScope`, so a root block can call
anything a subcommand block can. All three are abstract classes with internal constructors: klap owns
the implementations, and you write extensions against them rather than implementing them.

Naming the receiver is what lets two subcommands share a declaration instead of repeating it:

```kotlin
// One place to change the spelling, the help text and the converter chain.
private fun CommandBuilder.tagOption() =
    option("--tag", "-t", help = "Filter by tag").multiple()

cli("tasks") {
    command("list") {
        val tags = tagOption()
        action { Ok("listing ${tags()}") }
    }
    command("done") {
        val tags = tagOption()
        action { Ok("closing ${tags()}") }
    }
}
```

The extension returns the handle, so each command gets its own independent input. A handle is bound
per parse, so do not hoist the `val` itself to file scope and share one across commands.

A complete, runnable program lives in
[`example/task-manager/`](../example/task-manager/src/commonMain/kotlin/com/fromwau/example/Main.kt). It is `klapExample`, a small
file-backed task manager (`add` / `list` / `done` / `rm`, plus a nested `tag` group) that exercises
most of the above: subcommands and nested groups, a command alias, a global `--file` option and a
counting global `-v` flag, a group-scoped option read from its action, a plain `--done` flag,
enum/choice/multiple/validate converters, a `.range()`-bounded `--limit`, colorized output via a
`ColorScope` helper, a `hidden` diagnostic subcommand, typed errors with custom exit codes, and
structured `--json`. The `example/task-manager/` module is itself a Kotlin Multiplatform
library: all of its code lives in `commonMain`, and that one source set builds native Linux and Windows
binaries and an Android library (AAR) (see
[Building a native binary](../example/README.md#building-a-native-binary)).

For the other shape, here is a complete single-command tool: a temperature converter. Because its root
has an `action` (no subcommands), `--completion` / `--docs` arrive as options, and a positional like
`docs` stays an ordinary value.

```kotlin
import com.fromwau.klap.*
import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.math.round

enum class Temp { C, F, K }                    // .enum<Temp>() matches these case-insensitively

@Serializable
data class Conversion(val value: Double, val from: String, val to: String, val result: Double)

private fun toCelsius(v: Double, u: Temp) = when (u) { Temp.C -> v; Temp.F -> (v - 32) * 5 / 9; Temp.K -> v - 273.15 }
private fun fromCelsius(c: Double, u: Temp) = when (u) { Temp.C -> c; Temp.F -> c * 9 / 5 + 32; Temp.K -> c + 273.15 }
private fun roundTo(v: Double, places: Int) = (v * 10.0.pow(places)).let(::round) / 10.0.pow(places)

fun main(args: Array<String>) {
    cli("convert") {
        description = "Convert a temperature between Celsius, Fahrenheit, and Kelvin"
        version = "1.0.0"
        example("convert 100 --from c --to f", "boiling point of water, in Fahrenheit")

        val value = argument("value", "the value to convert").double().range(-1_000_000.0..1_000_000.0)
        val from = option("--from", help = "unit to convert from").enum<Temp>().required()
        val to = option("--to", help = "unit to convert to").enum<Temp>().required()
        val precision = option("--precision", help = "decimal places").int().range(0..10).default(2)

        action(human = { r -> "${r.value} ${r.from} = ${r.result} ${r.to}" }) {
            val floor = mapOf(Temp.C to -273.15, Temp.F to -459.67, Temp.K to 0.0).getValue(from())
            if (value() < floor) return@action Err(CliError.Failure("${value()} ${from()} is below absolute zero"))
            val result = roundTo(fromCelsius(toCelsius(value(), from()), to()), precision())
            Ok(Conversion(value(), from().name.lowercase(), to().name.lowercase(), result))
        }
    }.main(args)
}
```

```
$ convert 100 --from c --to f
100.0 c = 212.0 f
$ convert 100 --from c --to f --json
{"value":100.0,"from":"c","to":"f","result":212.0}
```

## Inputs and converters

Declare an input, then chain converters. Each holder returned — an `Arg<T>` (argument), `Opt<T>`
(option), or `Flag` / `CountFlag` — is captured as a `val` and read by invoking it (`name()`) inside
`action { }`; each converter narrows its `T`. A holder declared inside a `group { }` block needs a
different pattern, since a plain `val` there does not compile when read from the enclosing `action`;
see [Help output](#help-output) for the `lateinit var` pattern that does.

### Spellings

`option` and `flag` take **any number of spellings, each written as the token it is**: `--verbose` is a
long, `-v` a short. The dashes are declared, not inferred, so there is no separate `short` parameter and
nothing about a spelling's length changes its meaning.

```kotlin
option("--verbose", "-v")          // --verbose and -v
flag("--recursive", "-r", "-R")    // --recursive, -r and -R, one holder
option("--since", "--after", "-a") // --since, --after and -a, one holder
option("-Z")                       // -Z only, no long form
option("--x")                      // a one-character long, distinct from -x
```

A short is exactly one character, because the parser reads a one-dash token as a cluster (`-xzf` is three
flags); a long may be any length. The first spelling is the **primary**: it is what error messages name and
what `--help` sorts by. Help rows list every spelling, shorts first (`-r, -R, --recursive`).

Because the spellings are a vararg, **`help` is a named-only argument** — `option("--port", "-p", help = "…")`,
never `option("--port", "-p", "…")`. A positionally passed help string would be one more spelling, so klap
rejects it at construction — it carries no dashes — with a message pointing at `help =`.

### Dash-led values

**An option that takes a value takes the next token, whatever it looks like**, the way GNU `getopt` and
git's `parse-options` do. `git commit -m -weird` commits with the subject `-weird`; `tar --exclude -foo`
excludes the pattern `-foo`.

```kotlin
run(listOf("-m", "-weird"))      // message = "-weird"
run(listOf("--message", "-x"))   // message = "-x"
```

Two tokens are never swallowed: there has to *be* a next token (otherwise the option reports a missing
value), and `--` stays the end-of-options marker rather than becoming a value. The cost is that klap cannot
tell a mistyped option from a dash-led value — `--message --verbsoe` binds the typo as the message instead
of reporting an unknown option. Every tool with this rule pays it.

Nothing klap recognizes on its own is an exception, at any depth: `mygrep -e --json f.txt` searches for
the literal string `--json` and reads `f.txt`, exactly as `grep` does; `-e --help` binds `--help` rather
than printing help; and `mytool sub -e --verbose f.txt` binds `--verbose` even when `--verbose` is a
global. The slot belongs to the option — position-independent means *anywhere else*.

### Numbers on the command line

A dash-led number is an **option token like any other**: `-5` is an unknown option unless something
declares it. That matches `ls -5` and `sleep -1`, which both reject.

Three declarations give it a meaning:

```kotlin
flag("-4")                       // curl -4: an ordinary short whose character is a digit
numericAlias(lines)              // head -5, git log -5: -<NUM> is shorthand for another option
argument("n").int()              // app -- -100: a negative OPERAND, written after --
```

`numericAlias` aliases `-<NUM>`, for any N, onto an option you already declared; the digits become its
value and run through its converter, so the count reads back off the same handle:

```kotlin
val lines = option("--lines", "-n", help = "print the first NUM lines").int()
numericAlias(lines)
// head -5 f  ==  head -n 5 f, and `--lines <NUM> (or -NUM)` says so in --help
```

At most one per command, and a short the command declares itself wins: a tree with both `flag("-4")` and an
alias binds `-4` to the flag and `-5` to the alias. A negative **option value** needs no escape at all —
`-n -5` is the dash-led-value rule above — so only a negative *operand* needs the `--`.

```kotlin
val port    = option("--port", "-p").int().default(8080)           //  Int
val level   = option("--level").enum<LogLevel>()                   //  LogLevel?  (case-insensitive)
val timeout = option("--timeout", "-t").map { it.toInt().seconds } //  Duration?
val tags    = option("--tag").multiple(min = 1)                    //  List<String>  (at least one)
val region  = option("--region").required()                        //  String  (must be provided)
val files   = argument("files").file().multiple(min = 1)           //  List<String>  (min 1, path-completed)
```

| Converter | On | Result |
|---|---|---|
| `.int()` `.long()` `.double()` | argument, option | the parsed primitive |
| `.boolean()` | argument, option | `Boolean`, accepting only the exact lowercase literals `true` / `false` (case-sensitive: `True`, `TRUE`, `yes`, `no`, `1`, `0`, and `""` are all rejected) |
| `.enum<E>()` | argument, option | `E`, matched case-insensitively; choices shown lowercase |
| `.choice("a", "b")` | argument, option | the raw string, restricted to the given set (matched case-insensitively, returns the declared spelling) |
| `.map { raw -> T }` | argument, option | any type; a thrown exception becomes a clean parse error |
| `.convert { raw -> Result }` | argument, option | any type, with your own error message |
| `.validate("msg") { it > 0 }` | argument, option | same type; fails with `BadValue` when the predicate is false |
| `.range(1..65535)` | argument, option (`Comparable`) | same type, bounds-checked; shows the range in help |
| `.optional()` | argument | makes a positional nullable (options are already nullable) |
| `.default(v)` | argument, option | binds `v` whenever the value would be null (absent, or a converter that resolved to `null`); `v` itself may be `null`. A default is never re-validated. |
| `.required()` | option | fails if the option is missing |
| `.requiredIf(flag)` | option | fails if the option is missing *and* `flag` was given (see [Cross-input constraints](#cross-input-constraints)) |
| `.optionalValue(whenBare)` | option | `--opt=V` binds `V`, a bare `--opt` binds `whenBare`, and the space form never binds (see [POSIX conformance](#posix-conformance)) |
| `.multiple(min = 0)` | argument, option | collects every occurrence into a `List`; at most one per command, and `min` is enforced |
| `.absentWhen(input)` | argument | removes this operand slot entirely whenever `input` was supplied, so the operands after it keep their own positions (see [Operands that depend on an option](#operands-that-depend-on-an-option)) |
| `.requiredUnless(input)` | argument | drops this operand's declared minimum to zero whenever `input` was supplied; the slot itself stays, so nothing shifts. Only a `.multiple()` operand carries a minimum to relax, so any other cardinality is rejected when the tree is constructed (reach for `.absentWhen()` to remove a slot instead) |
| `.placeholder(name)` | argument, option | the word help and usage show in the value slot: `--out <FILE>` instead of `--out <value>`. On an option it also replaces the choice list, which keeps a long one from widening every other row |
| `.file()` | argument, option | marks a path for shell file-completion (completion only; does not check the path exists or is a file) |
| `.hidden()` | argument, option, flag | still parses, but omitted from help, completion, and docs |
| `.completeWith { ... }` | argument, option | supply completion candidates at runtime via `candidate()` / `candidates()` / `completeFiles()` (see below) |

`.convert { }` reads the raw string and returns `Result<T, String>` yourself (`Ok(value)` or
`Err("message")`), when you want to control the error text instead of letting `.map` derive one from a
thrown exception:

```kotlin
val level = option("--level").convert { raw -> raw.toIntOrNull()?.let { Ok(it) } ?: Err("not a number") } //  Int?
```

`.map` is for total transforms; reach for `.convert { }` (or a thrown exception) when a value can be
*invalid*, so the user sees a parse error instead of a silent fallback. A `.map { }` lambda that returns
`null` for a present value is treated like absence: `.default(v)` substitutes `v` (so
`.map { it.toIntOrNull() }.default(0)` yields `0` on bad input), and with no default the accessor simply
reads back `null`. A converter that *errors* (e.g. `.int()` on `"abc"`) is never masked by a default; it
still surfaces as `BadValue`. Converters also chain by composition, so
`.choice("a", "b").map { it.uppercase() }` validates the choice first and then transforms, and
`.map { }.map { }` runs the stages in order.

Validation runs at parse time and renders like any other input error:

```kotlin
val port = option("--port").int().validate("must be 1..65535") { it in 1..65535 }
```
```
$ app --port 70000
error: invalid value '70000' for port: must be 1..65535     # exit 2
```

An invalid `.enum<E>()` / `.choice(...)` value uses a different shape, listing the valid choices instead
of a `: message` suffix:

```
$ app --level bogus
error: invalid value 'bogus' for level (choose from debug, info, warn, error)     # exit 2
```

`.range(a..b)` is sugar over `.validate` that also prints `(a..b)` in the help row. Keep single-input
constraints here; cross-field or business rules belong in `action { }` with a typed `Err`.

klap appends its own hint to a help row based on the input's shape, so you do not repeat it in your help
text: `(required)`, `(optional)` (any nullable input, not only `.optional()`), `(default: v)`,
`(repeatable)` (from `.multiple()` / `.count()`, shown as `(repeatable, min N)` when a minimum is set),
and the range from `.range(a..b)`. A choice-backed argument shows `(one of: a, b)`, while a choice-backed
option shows its choices inside the value placeholder instead (`--fmt <a|b|c>`). Write only the plain
description.

On an option, `.required()` narrows `Opt<T?>` to `Opt<T>`, so it has to run last in the chain:
`.validate(...).required()` compiles, but `.required().validate(...)` does not, since `.validate` (and
`.range`) are declared on the still-nullable `Opt<T?>` and no longer apply once `.required()` has
already narrowed past it.

Symmetrically, `.default(v)` narrows the accessor to non-null: it always reads back a value, since an
absent input (or a converter that resolves to null) falls back to `v`. The default `v` must be non-null.

A command may declare **one** variadic positional, and it need not be last: `cp SOURCE... DEST` is
`argument("source").multiple(min = 1)` followed by `argument("dest")`. The fixed slots after it bind from
the end, so `cp a b c` gives `[a, b]` and `c`, and `cp a b` gives `[a]` and `b`. Only *required* slots may
follow a variadic — an optional one is genuinely ambiguous, since a single leftover token would have no
rule saying which of the two it feeds, and klap rejects that at construction.

`.multiple()` and `.default()` do not combine either: `.default` is declared on the nullable `Opt<T?>` /
un-narrowed `Arg<T>` receiver, but `.multiple()` already returns a non-nullable `List<T>`-typed holder
that defaults to an empty list on its own. Reach for `.multiple(min = 1)` (or any `min > 0`) instead if
you want a required, non-empty list.

### Operands that depend on an option

Some tools change their operand *shape* when an option fires. `cp -t DIR a b` has no DEST operand at all,
and `rm -f` with nothing to remove is a success rather than a usage error. Two converters declare that, so
`--help`, the usage line and completion learn the rule instead of an `action { }` re-implementing it:

```kotlin
val target = option("--target-directory", "-t").file()
argument("source").placeholder("SOURCE").file().multiple(min = 1)
argument("dest").placeholder("DEST").file().absentWhen(target)   // usage: <SOURCE>... [<DEST>]

val force = flag("--force", "-f")
argument("file").file().multiple(min = 1).requiredUnless(force)  // `rm` errors, `rm -f` exits 0
```

`.absentWhen(input)` **removes** the slot, which is what makes it different from `.optional()` and is the
trap it exists to close: an optional slot still exists, so `chmod --reference=r notes.txt` would bind
`notes.txt` as the mode and silently lose the file. Because the slot genuinely binds nothing on those
lines, the accessor widens to nullable. `.requiredUnless(input)` **keeps** the slot and only relaxes its
count, so nothing shifts position.

"Supplied" means the same thing it means for a constraint: actually typed, never filled in by a
`.default()`. The trigger must be one of the same command's own inputs, `.absentWhen()` cannot be combined
with `.multiple()` in either call order, `.requiredUnless()` applies only to a `.multiple()` operand since
only a variadic carries a minimum to relax (reach for `.absentWhen()` to remove a slot instead), and all
three rules are checked when the tree is constructed. Help
names the trigger rather than only bracketing the slot, since only its spelling says *when*:
`(absent with --target-directory)`, `(optional with --force)`.

### Options whose value is optional

`ls --color[=<WHEN>]`, `git commit -S[<keyid>]`: the option may be given bare or with an attached value.

```kotlin
val color = option("--color").placeholder("WHEN")
    .choice("always", "auto", "never")
    .optionalValue("always")     // bare --color means --color=always
```

**The space form never binds.** `ls --color src` colours the listing of `src` — it does *not* read `src`
as the value. That is what GNU does, and it is the only unambiguous reading available: an optional-value
option cannot tell its own value from the next operand.

Which is why POSIX guideline 7 says option-arguments should not be optional. `.optionalValue()` takes that
one option outside the guideline, knowingly; every option that does not call it stays conforming. Reach for
`.negatable()` first if the tool's real shape is a two-state switch — it costs no conformance and it gives
you `--no-opt` as well.

`--help` renders the row as `--color[=<WHEN>]` with a trailing `bare: always` note, and `.optionalValue()`
cannot be combined with `.multiple()` in either call order — a repeatable holder has no single "the value
when bare" to bind. Tab completion matches the parser: the word after a bare occurrence completes as an
operand, not as this option's value.

## Flags: boolean, counted, negatable

```kotlin
val verbose = flag("--verbose", "-v")         // Boolean
val level   = flag("--verbose", "-v").count() // Int: -vvv or -v -v -v yields 3
val cache   = flag("--cache").negatable()     // Boolean: --cache / --no-cache, default on
```

`.count()` and `.negatable()` are mutually exclusive. A negatable flag renders as `--[no-]cache
(default: on)`; pass `negatable(default = false)` to start off.

**Spelling the negative half yourself.** Pass the spellings to `.negatable()` when the generated
`--no-<long>` is not what the tool answers to, either a *short* that turns the flag off or an asymmetric pair:

```kotlin
flag("--dereference", "-L").negatable("--no-dereference", "-P")   // cp: -L on, -P off
flag("--forward-agent", "-A").negatable("--no-forward-agent", "-a")
flag("--paginate", "-p").negatable("--no-pager", "-P")            // git: and NOT --no-paginate
```

The explicit list **replaces** the generated form rather than adding to it, because a tool that spells its
negative half differently also rejects the generated one: `git` takes `--paginate` and `--no-pager` and
answers to neither `--pager` nor `--no-paginate`. Write the generated spelling out when you want it kept,
as the `cp` line does. Each spelling carries its own dashes, is validated exactly like a positive one, and
may not collide with any declared spelling or with another flag's negation. A negative short clusters like
any other (`-vP`), and help lists every spelling the flag answers to, shorts first, positives before
negatives: `-L, -P, --dereference, --no-dereference`.

**Command-line forms.** An option value is written `--opt value`, `--opt=value`, `-o value`, or attached
to a short as `-ovalue`; a short option does **not** accept `-o=value` (the `=` is taken as part of the
value). Short flags cluster (`-abc` = `-a -b -c`), and a value-taking short ends a cluster, taking the
rest of the token or the next argument (`-n5` or `-n 5`). Local and global short flags cluster together
in any order (`-fv` binds the same as `-vf`); the built-ins `-h` / `--help` / `--version` are recognized
as standalone tokens, not inside a cluster. `--` ends option parsing: every token after it is positional.
An option that takes a value takes the next token whatever it looks like, dash-led or not; see
[Dash-led values](#dash-led-values) above.

**A long option may be abbreviated to any unambiguous prefix**, the way GNU's `getopt_long` does:
`mkdir --par d` reaches `--parents`, and `--no-der` reaches a negatable flag's negative half. An exact
spelling always wins outright, so a pool holding both `--sort` and `--sort-by` keeps the shorter one
reachable. A prefix that reaches more than one spelling is a usage error naming every possibility, in
GNU's own wording:

```
$ chmod --re 700 d
error: option '--re' is ambiguous; possibilities: '--recursive' '--reference'     # exit 2
```

Ambiguity is judged against **everything the token can reach at that point**: the command's own inputs
(hidden ones included, since hiding removes an input from help, not from the parser), the globals, and
klap's own built-ins, so a declared `--header` makes `--he` ambiguous against `--help` rather than
silently choosing. Two consequences follow. `--help-all` is the one exception: it answers to its full
spelling only, because klap injects it rather than you declaring it, and letting it claim the space it
shares with `--help` would cost every klap CLI its `--h`. And a sibling subcommand's `--sort-by` can make
`sub1 --sor` ambiguous even where `sub1` alone is not, which is the price of that pool never being
narrower than the one a rejected token is checked against. Shorts never abbreviate (a one-dash token is a
cluster), and neither do subcommand names.

A single-value option can be given more than once; the last occurrence silently wins (reach for
`.multiple()` if you want every occurrence collected instead). `--opt=` with nothing after the `=` binds
an explicit empty string (`""`), which is distinct from omitting the option entirely, which leaves it at
its default or `null`.

## Cross-input constraints

`requireExactlyOne` / `requireAtMostOne` relate several inputs of one command, over the handles you
already hold:

```kotlin
cli("tar") {
    val create  = flag("--create", "-c", help = "create a new archive")
    val extract = flag("--extract", "-x", help = "extract files from an archive")
    val list    = flag("--list", "-t", help = "list the contents of an archive")
    requireExactlyOne(create, extract, list)

    val gzip  = flag("--gzip", "-z", help = "filter through gzip")
    val bzip2 = flag("--bzip2", "-j", help = "filter through bzip2")
    requireAtMostOne(gzip, bzip2)

    val file = option("--file", "-f", help = "archive to operate on").required()
    action { ... }
}
```

```
$ tar -c -x -f a.tar
error: --create and --extract are mutually exclusive
$ tar -f a.tar
error: exactly one of --create, --extract, --list is required
```

Both exit `2` (`USAGE_ERROR_EXIT`), like every other usage error. The rule is checked **before any input
binds**, so a mode conflict outranks the "missing required option" a later bind would raise: `tar -c -x`
with no `-f` reports the conflict, the way GNU tar does. A malformed token still outranks both — klap
will not judge a set it failed to read.

Members can be options, flags or positionals in any mix. "Supplied" means *actually typed*: a member
carrying `.default()` counts only when the user gave it, never when the default filled it in, and
`--no-cache` on a `.negatable()` flag is an opt-out, not a selection.

Every member's help row names the whole set, so the flags read as a set rather than as independent
toggles — and generated docs say the same, since they render from the same rows:

```
  -c, --create   create a new archive (one of -c, -x, -t; required)
  -x, --extract  extract files from an archive (one of -c, -x, -t; required)
  -z, --gzip     filter through gzip (at most one of -z, -j)
```

The usage line carries the same rule in its own shorthand, ahead of the positionals: parentheses for a
required set, brackets for an optional one, each member named by its short form when it has one. A set
whose members are all `.hidden()` is left out; a partly hidden one lists only what help shows.

```
usage: tar (-c|-x|-t) [-z|-j] --file <value> [options]
```

A **required option** joins it for the same reason: the command will not run without it, and folding it
into `[options]` said the opposite. It renders with its own value placeholder — an explicit
`.placeholder(...)` or the choice list — and a member of a constraint set is named only by its group.

### `lastWins`: the override rule

`rm -i -f` forces and `rm -f -i` prompts. That is an *override* rule, not an exclusivity one, and
`requireAtMostOne` would reject a line both tools accept. `lastWins` is the rule itself:

```kotlin
val interactive = flag("--interactive", "-i", help = "prompt before every removal")
val force       = flag("--force", "-f", help = "never prompt")
lastWins(interactive, force)
```

The member written last keeps what it bound, and every other member binds what it would have bound had
you not written it at all, so an action reads the winner off its own handle with no precedence logic.
That absent value is per kind: a plain flag falls to `false`, a `.count()` flag to `0`, a `.negatable()`
flag to its declared default, and an option to its `.default()` or `null`. Order comes from the line,
including *inside* a cluster, so `-if` forces and `-fi` prompts. A set nobody supplied is left alone.
`find`'s `-P`/`-L`/`-H` and `ls`'s sort shorts are the same shape.

**A set may mix flags and options**, because a tool routinely spells one setting both ways:

```kotlin
val lines = option("--lines", "-n")
val bytes = option("--bytes", "-c")
lastWins(lines, bytes)                 // head -c 5 -n 3 counts lines; head -n 3 -c 5 counts bytes

val bySize = flag("--sort-size", "-S")
val sort   = option("--sort")
lastWins(bySize, sort)                 // ls -S --sort=time sorts by time; ls --sort=time -S by size
```

A positional cannot join one: an operand binds by position rather than by being named, so there is no
occurrence to order and nothing a loser could be reset to. Nor can a member whose absence has no value to
fall back on, a `.required()` or `.multiple()` option, since losing would leave its accessor with
nothing to return; both are rejected when the tree is constructed. Its help hint reads
`(last of -i, -f wins)` and its usage group `[-i|-f]`.

### `requiredIf`: a conditional requirement

```kotlin
val remote = flag("--remote")
val token  = option("--token").requiredIf(remote)   // optional alone, mandatory with --remote
```

Checked after binding, against what was actually typed — a `.default()` on the conditional option does
not satisfy it. It takes a handle rather than a lambda so the help row can say `(required when --remote)`;
the accessor stays nullable, since the option really does bind null whenever the condition is absent.

Tab completion stops offering what the parse would reject: once one member of a set is on the line, the
others leave the candidate list (`tar -c -<TAB>` offers neither `-x`/`--extract` nor `-t`/`--list`). The
member you already typed stays on offer.

A constraint is scoped to **one command's own inputs**: a `globalOption` / `globalFlag` handle, or an
input declared on another command, cannot join one. That, a set of fewer than two inputs, and a repeated
member are all rejected when the command tree is constructed, as an `IllegalArgumentException` thrown at
startup. Constraints are independent of `group(...)`, which is a help heading and nothing else; you can
use either without the other.

## Global / persistent options

Declared once on the root with `globalOption` / `globalFlag`, a global is recognized anywhere on the
line (before or after the subcommand path) and is readable from any nested `action { }`. Anywhere but
one place: a value-taking option's argument slot belongs to that option, so `fleet build -e --verbose`
gives `-e` the literal string `--verbose` and leaves the global at its default — see
[Dash-led values](#dash-led-values).

```kotlin
cli("fleet") {
    val verbose = globalFlag("--verbose", "-v", help = "log everything")
    val config  = globalOption("--config", "-c", help = "path to a config file")

    command("build") {
        action {
            if (verbose()) log("building...")
            Ok("built with ${config() ?: "defaults"}")
        }
    }
}
```

Globals compose with the full converter chain (`.int()`, `.required()`, `.multiple(min = 1)`, ...), and
are shown under a `Global options:` section on the root and every subcommand's help. A subcommand may
not redeclare a global's name or short, nor may any input reuse a reserved built-in name (`help` /
`help-all` / `version` / `json` / `completion` / `docs` / `color` as an option or flag, or
`completion` / `docs` / `__complete` as a root subcommand name).

Which *subcommand* names are reserved depends on your command's shape, because klap reserves exactly
the nodes it injects. `__complete` is always injected, so that name is never available. `completion`
and `docs` are injected only when the root has no `action { }` of its own: a dispatcher reserves them,
and a single-command root does not, since it offers those two as the `--completion` / `--docs`
meta-options instead. So `command("docs") { }` is rejected on a dispatcher and accepted on a root that
carries its own action. Declining a built-in frees its name on either shape.
Such conflicts are rejected when the command tree is constructed, as an `IllegalArgumentException`
thrown at startup before any argument is parsed, not a Gradle-time check. Most of those names can be
freed by declining the built-in that claims them — see [Declining a built-in](#declining-a-built-in).

A first token that literally matches one of these built-in names (`__complete` on either shape, or
`completion` / `docs` on a dispatcher) is routed to that builtin before positional binding, so a
command whose own positional value is meant to be that literal word needs to escape it with a leading
`--`, e.g. `mytool -- __complete`.

The same silent collision applies to the position-independent meta-options `--json` and `--color`: since
they are recognized anywhere in the argument list and stripped before positional binding, a positional
whose literal value is `--json` (or `--color`) is consumed as the meta-option instead of binding, so it
likewise needs a leading `--` to reach the positional, e.g. `mytool note -- --json` to make `--json` the
note's title. Unlike a bare-word builtin, this also forces JSON output as a side effect, and for an
optional or defaulted positional the now-missing value falls back silently to `null` or its default
instead of erroring, which is what makes the escape matter here.

A required global (or any required input) never blocks klap's built-ins: `--help`, `--version`, and the
`completion` / `docs` / `__complete` builtins render before required-input checks run, so
`mytool completion fish` works even when a `--config` global is `required()`.

### Declining a built-in

When your tool's own interface needs one of those names, a root-only `builtins { }` block declines the
built-in that claims it. Every switch defaults to `true`:

```kotlin
cli("curl") {
    builtins {
        json = false        // frees --json for the app's own option
        completion = false  // no `completion` subcommand and no --completion option
        docs = false
        helpShort = false   // frees -h; --help itself remains
    }
    option("--json", "-j", help = "post this JSON body") // now legal
}
```

A declined built-in is gone in every sense: its name stops being reserved (so an option, flag, or
subcommand may claim it), the parser stops recognizing and stripping its token, its subcommand is no
longer injected, and `--help`, the generated docs, tab completion, and did-you-mean suggestions all stop
advertising it. `--json` declined also means `Execute.globals.json` stays `false`. The block is
order-independent: it may sit above or below the `command(...)` declarations it frees names for.

`--help` and `--help-all` cannot be declined (only the `-h` short can), and `--version` needs no switch —
it only exists when you set `version`.

## Typed results and errors

An `action { }` returns `Result<T, CliError>`:

- `Ok(value)` succeeds; klap renders `value` and exits `0`.
- `Err(error)` fails; klap prints the message and exits with `error.exitCode`.

**`Ok` / `Err` are builders, not the types you match on.** `Result<S, E>` is a sealed interface with
two subtypes, `Result.Success(value)` and `Result.Error(error)`; `Ok(v)` and `Err(e)` are top-level
functions that construct them. So you *write* `Ok(x)` and *match* `is Result.Success`, and the names
never line up:

```kotlin
when (val parsed = cli.parse(argv)) {
    is Result.Success -> parsed.value       // not `is Ok`
    is Result.Error -> parsed.error         // not `is Err`
}
```

Six combinators come with it, all `inline`, all extensions on `Result<S, E>`:

| Combinator | Signature | Does |
|---|---|---|
| `map` | `(S) -> T` → `Result<T, E>` | rewrites the success value, leaves an error untouched |
| `mapError` | `(E) -> F` → `Result<S, F>` | rewrites the error, leaves a success untouched |
| `getOrElse` | `(E) -> S` → `S` | unwraps, computing a fallback from the error |
| `fold` | `(S) -> T`, `(E) -> T` → `T` | collapses both sides to one type |
| `onSuccess` | `(S) -> Unit` → `Result<S, E>` | side effect on success, returns the receiver |
| `onError` | `(E) -> Unit` → `Result<S, E>` | side effect on failure, returns the receiver |

`mapError` is the one to reach for at a layer boundary, since it turns your domain's error into a
`CliError` without unwrapping:

```kotlin
action {
    store.load()                                        // Result<List<Task>, StoreError>
        .mapError { CliError.Failure("cannot read the store: $it") }
        .map { tasks -> "loaded ${tasks.size} task(s)" }
}
```

Model *expected* failures as `Err`: klap renders any returned `CliError`, but an exception thrown
inside `action { }` is treated as a programmer error and propagates uncaught (it is not turned into an
`error:` line). The `.map` / `.convert` converters are the deliberate exception, wrapping a thrown
parse error into a clean `BadValue` for you.

The catch-all is `CliError.Failure(detail, exitCode = 1)`. Map your own failures to it at the boundary:

```kotlin
action {
    val vm = machines.firstOrNull { it.name == name() }
        ?: return@action Err(CliError.Failure("no machine named '${name()}'"))
    machines.remove(vm)
    Ok("terminated '${vm.name}'")
}
```

```
$ fleet terminate ghost
error: no machine named 'ghost'          # stderr, exit 1
```

`action { }` is a reified generic, so `T` is inferred from what the body returns. An action whose body
only ever returns `Err(...)` (never `Ok`) infers `T = Nothing` and fails to compile with `Cannot use
'Nothing' as reified type parameter ...`. Give it an explicit type argument instead:
`action<String> { Err(CliError.Failure("x")) }` (or `action<Unit>` if the success type does not matter).

Parser-level problems (unknown option, bad value, missing argument, invalid choice, and so on) are
their own `CliError` variants that klap raises and renders for you, and a typo suggests the nearest
known name:

```
$ fleet lst
error: unknown subcommand 'lst' for 'fleet'. Did you mean list?
```

### Usage errors you detect yourself

`Failure` exits `1`, the conventional code for "the command ran and did not succeed". A *usage* error
means the opposite: the command never ran, because the invocation was wrong. Every parse-level variant
exits `2` for that (`USAGE_ERROR_EXIT`, the POSIX convention), so a rule you enforce yourself should
too. Use `CliError.Usage(detail)` — a `Failure` whose exit code is fixed at `2`:

```kotlin
action {
    val from = since()
    val to = until()
    if (from != null && to != null && from > to)
        return@action Err(CliError.Usage("--since must not be later than --until"))
    Ok(query(from, to))
}
```

Reach for it whenever the rule is about *how the command was invoked* rather than what happened when it
ran. `requireExactlyOne` / `requireAtMostOne` / `lastWins` / `.requiredIf` already cover the declarative
rules; `Usage` is for the ones that need to read values.

You are not limited to `Failure` and `Usage`, either. An action may return **any** `CliError` variant,
and it renders byte-identically to the parse-time original, exit code included — useful when a
value-dependent rule makes an option required after the fact:

```kotlin
if (remote() && token() == null) return@action Err(CliError.MissingRequiredOption(token.name))
// error: missing required option --token          exit 2
```

`token.name` there is the handle's own primary spelling (`--token`, or `file` for a positional): the same
string klap's errors use, so a hand-written rule cannot drift from the declaration it names.

### Carrying your own typed error

`CliError.Domain(error, detail)` carries a value of *your* error type through klap's error path with the
payload intact. klap renders `detail` and exits, exactly as it does for `Failure`; what it adds is that a
`parse()` caller can recover the value and match on it, so a domain hierarchy survives the trip instead of
being flattened into a sentence at the boundary:

```kotlin
sealed interface StoreError { data object DiskFull : StoreError }

action { Err(CliError.Domain(StoreError.DiskFull, "out of space", exitCode = 6)) }

// and at the call site, if you drive parse() yourself:
val payload = (error as? CliError.Domain)?.error as? StoreError
```

The field is `Any` on purpose: klap never inspects it, and typing it would force a klap-owned supertype
onto a hierarchy that already has its own root.

### Did-you-mean, in your own messages

`suggest(token, candidates)` is the same nearest-match helper the parser uses for an unknown option or
subcommand, exposed so a rule you write yourself is phrased and thresholded identically:

```kotlin
suggest("lst", listOf("list", "add"))   // "list"
suggest("zzzzzzzz", listOf("list"))     // null - nothing close enough
```

Both `Usage.detail` and `Failure.detail` are yours to word, so a newline in one renders as a real line
break (a `hint:` continuation line works, and survives `--json` as a proper string escape). Everything
else is neutralized on the way out, including escape codes: a detail almost always interpolates a token
from argv, and klap cannot tell a color you applied from one a caller injected. Color your `Ok` output,
not your errors.

## Structured `--json`

The value you return from `Ok` renders two ways, chosen by the global `--json` flag:

- **Without `--json`:** an optional `human` renderer passed to `action`, falling back to
  `value.toString()`.
- **With `--json`:** `kotlinx.serialization` encoding of the value. A `String` becomes a bare JSON
  string; an `@Serializable` type becomes its object/array.

```kotlin
@Serializable
data class Vm(val name: String, val region: String, val running: Boolean)

command("list") {
    action(human = { vms -> vms.joinToString("\n") { it.name } }) {
        Ok(machines.toList())   // Result<List<Vm>, CliError>
    }
}
```

```
$ fleet list
web-1
worker-1

$ fleet list --json
[{"name":"web-1","region":"us-east","running":true}, ...]
```

The `human` renderer runs with the same `ActionScope` receiver as the `action` body, so it can read the
command's inputs, not just the result value. That makes it the home for presentation-only formatting an
input should drive without leaking into the JSON, for example an `--oneline` flag that reshapes the human
layout while `--json` still emits the full object.

Errors follow suit: under `--json` a failure prints `{"error":"...","code":n}` to stderr, so a
pipeline sees JSON on both streams. Returning a `String` or primitive needs no setup; returning an
`@Serializable` type requires the `kotlin("plugin.serialization")` plugin in the consuming module.

`--json` shapes an action's result (and its errors). It does not apply to `--help` or `--version`, which
always print their normal text; a failure's exit code is clamped to `1..255` (a `Failure(exitCode = 0)`
becomes `1`, since a failure must not report success).

A `@Serializable sealed interface` / `sealed class` works too: `kotlinx.serialization` adds a `"type"`
discriminator field, defaulting to the fully qualified class name (package plus enclosing type and
subtype joined with `.`), for example `{"type":"com.example.Shape.Circle","radius":2.0}` for a `Circle`
subtype nested in a `Shape` sealed interface declared in package `com.example`. Annotate each subtype
with `@SerialName("circle")` if you want a short, stable name instead, one that does not shift when the
class moves package. To keep the discriminator at all, the action must return the SEALED type, not the
concrete subtype: `action<Shape> { Ok(Shape.Circle(2.0)) }`. A bare `action { Ok(Shape.Circle(2.0)) }`
infers `T` as `Shape.Circle` and serializes it without the `"type"` field.

## Color output

klap has one color story: its own help chrome and anything your action colors itself resolve a style
through the same switch and the same mechanism, so the two can never disagree about whether color is on.
(One gap today: klap's own error output is not colored — only the help chrome is. A `CliError` detail is
sanitized on the way out, so styling one has no effect; see [Typed results and errors](#typed-results-and-errors).)

```kotlin
import com.fromwau.klap.yellow

command("build") {
    action { Ok(yellow { "built" }) }
}
```

`action { }`'s receiver (`ActionScope`) exposes a small style palette: colors `black red green yellow
blue magenta cyan white` and attributes `bold dim italic underline`, each a public `Style` constant in
`com.fromwau.klap`. Like the entry points in Quick start, these are top-level declarations, not
builder-scoped members, so a consumer using named imports imports each one used (`import
com.fromwau.klap.yellow` above), plus `import com.fromwau.klap.plus` for the `+` composition operator
below. Apply one with `yellow { "done" }` or `red("failed")` (both a lazy block form and a direct string
form work), and compose several with `+`, opening both and closing with a single reset:
`(bold + red)("error")`.

The palette resolves against the same color switch as klap's help chrome: colored when color is on,
plain text when off, so you never branch on it yourself. It is additionally forced off under `--json`,
even with `--color=always`, so machine output never carries escape codes.

The operators come from `ColorScope`, the capability `ActionScope` implements — so a formatting helper can
take that as its receiver instead of the whole action scope, and gets style resolution and nothing else:

```kotlin
import com.fromwau.klap.ColorScope

// Declaring ColorScope rather than ActionScope makes reading an input from here a compile error.
private fun ColorScope.warn(text: String) = (bold + yellow)("warning: $text")

command("build") {
    action { Ok(warn("no targets matched")) }
}
```

`ColorScope` is sealed, so klap owns its implementations; you write extensions against it, not
implementations of it.

### `--color`

A built-in, position-independent option recognized on every command like `--json`:
`--color=auto|always|never` (or the space form, `--color never`). It is reserved, so you cannot declare
your own `option`/`flag` named `color` unless you decline it with `builtins { color = false }`. Bare
`--color` with no value is a `MissingOptionValue`; an
unrecognized value is an `InvalidChoice`; both are reported before the `--version`/`--help`/completion/docs
short-circuits, so a malformed `--color` is never silently swallowed by one of those.

Absent, it defaults to `auto`. Resolution:

- `--color=always`: color on, even off a TTY.
- `--color=never`: color off.
- `--color=auto` (or omitted): defers to terminal detection, in order: `NO_COLOR` (present and
  non-empty) disables it, `FORCE_COLOR` / `CLICOLOR_FORCE` force it on, `TERM=dumb` disables it, and
  otherwise it follows whether stdout is a real TTY.

An explicit `always`/`never` outranks the whole environment ladder; only `auto` consults it.

On the JVM specifically, `auto` detection also depends on stdin being a terminal (a platform
limitation of the JVM's TTY probe), so piping input into a JVM-run program while its stdout is still a
real terminal can disable auto color there. Force it with `--color=always` or `FORCE_COLOR=1` in that
case; native targets are unaffected, since they check stdout alone.

## Command groups and nesting

A command with subcommands and no `action` is a group; klap prints its help when invoked bare. Nest
with `command(name) { }` to any depth, or `command(name, help) { }` to set the subcommand's description
inline instead of via `description =` inside the block. A group has nothing to read its own
`option`/`flag`, so declaring a local one on an action-less command throws at construction; put the
option on a leaf command's `action`, or use `globalOption` / `globalFlag` to share it across subcommands:

```kotlin
cli("fleet") {
    version = "2.4.0"
    command("list") { action { Ok(machines.toList()) } }
    command("disk") {                       // a group
        command("attach") {                 // fleet disk attach ...
            val machine = argument("machine")
            val disk = argument("disk")
            action { Ok("attached ${disk()} to ${machine()}") }
        }
    }
}
```

You get `fleet --help`, `fleet disk --help`, `fleet --version`, `fleet completion <shell>`, and
`fleet docs <format>` automatically.

A command may have both its own `action` and subcommands (a hybrid). Routing takes precedence over a
free positional there: if the first operand equals a subcommand name, it routes to that subcommand
rather than binding as the action's positional. So a hybrid whose action reads a free-form
`argument("target")` will send `app build` to a `build` subcommand if one exists; pass `app -- build` to
force `build` to bind as the positional. Routing wins over any positional here, free-form or
`.choice(...)`-restricted alike, so `--` is the way to force a positional value that collides with a
sibling subcommand name. A plain leaf command (no subcommands) has no such collision to begin with.

A command can also declare `aliases`, a `var` on the command builder, so an alternate name routes to
the same command:

```kotlin
command("list") {
    aliases = listOf("ls")
    action { Ok(machines.toList()) }
}
```

`fleet ls` now resolves exactly like `fleet list`: alias lookup is part of subcommand routing itself,
not a separate mechanism, and a near-miss typo close to an alias is suggested the same way a near-miss
of the real name is.

## Help output

Beyond the automatic usage/options/subcommands listing, you can shape help:

```kotlin
cli("deploy") {
    description = "Ship a build"
    author = "Jane Doe <jane@example.com>"
    epilogue = "See https://docs.example.com for the full guide."
    example("deploy --host web1 --dry-run", "dry-run against one host")

    group("Networking") {
        option("--host", "-H", help = "target host").required()
        option("--port", "-p", help = "target port").int().default(22)
    }
    flag("--debug", help = "verbose internal logging").hidden() // parses, never shown

    action { Ok("shipped") }
}
```

`group(title) { }` collects its inputs and subcommands under a heading; `example(...)` and `epilogue`
add usage examples and a closing paragraph; `.hidden()` (or `hidden = true` on a command) keeps
something out of help while it still parses. `author` (root-only, like `version`) adds an `Author:`
footer to the root's `--help` and an `AUTHOR` section to the generated man page and markdown docs. Descriptions wrap to the terminal width (`COLUMNS`, else the
detected terminal width, else 80). Headings and usage follow the same color resolution as `action { }`'s
own output (see [Color output](#color-output)): `NO_COLOR` (present and non-empty) disables color,
`FORCE_COLOR` / `CLICOLOR_FORCE` force it on (even off a TTY), `TERM=dumb` disables it, and otherwise it
follows whether stdout is a real TTY, all of which `--color=always` / `--color=never` override.

`--help` is shallow: a command's own args/options plus its immediate subcommands, one line of description
each, the standard drill-down shape. For a bird's-eye view, the built-in `--help-all` renders the current
command and every descendant recursively, each as its own help block, scoped to wherever you place it
(`mytool --help-all` for the whole tree, `mytool remote --help-all` for just that subtree). It is
advertised under Global options on any command that has subcommands, and `docs markdown` / `docs man`
give the same full tree as a document.

`group(title) { }` returns `Unit`, so you cannot capture a holder from its return value. Declare a
`lateinit var` above the block and assign it inside; the block runs synchronously during construction,
so it is already set by the time `action { }` runs:

```kotlin
fun main(args: Array<String>) {
    lateinit var host: Opt<String>

    cli("deploy") {
        group("Networking") {
            host = option("--host", "-H", help = "target host").required()
        }
        action { Ok("shipped to ${host()}") }
    }.main(args)
}
```

## Shell completion

How you invoke completion depends on your `cli { }`'s shape:

- **A dispatcher** (subcommands, no root `action`, like `fleet` above) gets it as a subcommand:
  `myapp completion bash|zsh|fish|powershell`.
- **A single-command tool** (its own root `action`, like `greet` above) gets it as an option instead:
  `myapp --completion bash|zsh|fish|powershell`. It has no `completion` subcommand, so a positional
  literally named `completion` still parses as a value, not a builtin.

The rule keys on the root `action`: a **hybrid** root (its own `action` *and* subcommands) counts as a
single-command tool, so it also gets the `--completion` / `--docs` option form, never a `completion`
subcommand alongside its own.

`pwsh` is accepted as a silent alias for `powershell` in both forms (`completion pwsh` /
`--completion pwsh`), though only `powershell` is listed among `--help`'s choices.

Either way the generated script is byte-identical. Enum/choice values and `.file()` paths complete
automatically, and subcommand and option name completion carry each command's or option's declared `help`
as the description (shown by shells that support it, bash excepted), so `myapp <TAB>` explains each choice
for free. For values known only at runtime, `.completeWith` supplies candidates through a hidden
`__complete` subcommand (present on both shapes) that the script calls:

```kotlin
val branch = argument("branch").completeWith {
    gitBranches().forEach { candidate(it) }   // current = the partial word being completed
}
```

The provider is a `CompletionScope.() -> Unit` block: call `candidate(value, description?)` (or
`candidates(values)` for a plain list of description-less ones) to offer completion candidates.
`current` and `words` are readable on the scope itself.

`completeFiles(nonPathPrefix = "")` is the third call: it hands the slot to the shell's own filesystem
completion, the same thing `.file()` does for a whole input. Reach for it when only *part* of the word is
a path, which `.file()` cannot express:

```kotlin
argument("operand").multiple().completeWith {
    val eq = current.indexOf('=')
    when {
        eq < 0 -> candidates(listOf("if=", "of=", "bs=", "count="))
        current.take(eq) in setOf("if", "of") -> completeFiles(nonPathPrefix = current.take(eq) + "=")
        else -> candidates(valuesFor(current.take(eq)))
    }
}
```

`nonPathPrefix` names the head of `current` that is not part of the path, so `dd if=/dev/ze<TAB>`
completes to `dd if=/dev/zero`: each shell peels the prefix off before running its own path completion and
puts it back on whatever that inserts. The call is **exclusive**: it discards anything `candidate()` or
`candidates()` collected before it, and a call after it is silently dropped, because every generated
script maps a lone file directive to native completion and treats any other line as a literal candidate.

The scope also reads your CLI's own inputs through their accessors, exactly as `action { }` does — every
global, plus whatever the command under the cursor has already been given:

```kotlin
cli("tasks") {
    val store = globalOption("--file", "-f").default("tasks.json")

    // One helper, both scopes: `ValueScope` is the shared base of `ActionScope` and `CompletionScope`.
    // Declared INSIDE the block, as a local extension function, so it can close over the `store`
    // handle the line above just returned. At file scope it would have nothing to close over.
    fun ValueScope.taskStore() = TaskStore(Path(store()))

    command("tag") {
        val id = argument("id").int()
        argument("tag").completeWith {
            val taskId = id()
            val tasks = taskStore().load().getOrElse { return@completeWith }
            candidates(tasks.find { it.id == taskId }?.tags ?: return@completeWith)
        }
        action { Ok("tagged ${id()}") }
    }
}
```

Kotlin allows a local `fun` inside a lambda, including a local extension function, which is what makes
this work: `globalOption` is a `CliBuilder` member, so the declaration has to sit inside the `cli { }`
block, and the helper has to sit there too in order to see the handle it returned. The same shape is
live in [`example/task-manager/`](../example/task-manager/src/commonMain/kotlin/com/fromwau/example/Main.kt),
at `taskStore()` and `taskIdCandidates()`.

so `myapp --file other.json tag 3 <TAB>` offers the tags of task 3 in *that* store, with no re-parsing of
`words` by hand. Defaults apply, so an option the user has not typed reads the value it would have at
runtime. A scalar input the half-typed line has not supplied — a required argument not yet reached, or a
value that failed to convert — stays unbound, and reading it aborts the provider so Tab simply offers
nothing. That is normally what you want: a provider that cannot see the input it depends on has nothing to
offer. A `multiple()` input is the exception: it reads back whatever has been typed so far, which may be
empty or shorter than its declared minimum, so don't assume the arity an `action { }` can rely on.

`ValueScope` is the shared base of `ActionScope` and `CompletionScope`, and the four accessors live on it —
which is what lets `taskStore()` above be written once and used from both. Like `ColorScope`, it is sealed:
you write extensions against it rather than implementations of it.

Two costs worth knowing. Reading any one accessor resolves them all — every input of the command under the
cursor, plus every global, is converted and validated on that keypress — so keep converters and
`.validate { }` blocks cheap and free of side effects. And because the same seam that stops a Tab press from
dumping a stack trace into the terminal also swallows exceptions from your own provider code, a bug in a
provider looks exactly like a provider that had nothing to offer.

`description`, when given, is shown alongside
the value by shells that support it (zsh, fish, PowerShell); bash shows the value alone. klap
prefix-filters the offered candidates by the partial word by default, matching on `value` only, never
`description`. Pass `filterByPrefix = false` to `.completeWith` to skip that filter and match `current`
yourself, for fuzzy, substring, or alias matching. `__complete` is internal on both shapes; the
generated scripts always call it as
`myapp __complete -- <words>`, so if you invoke it by hand to debug a provider, keep the `--` separator
(otherwise a completion word like `--flag` is parsed as an option of `__complete`). One quirk when
debugging by hand: where the slot takes a path (a `.file()` input, or a provider that called
`completeFiles()`), `__complete` prints an internal one-line directive rather than real paths, which the
generated shell script turns into that shell's native file completion. Its exact spelling is klap's to
change and is not part of the public surface; `completeFiles()` is.

## Generated docs

klap renders documentation from the same command tree and the same layout as `--help`, so they never
drift:

```kotlin
val markdown = cli.renderMarkdownDocs()    // a browsable GitHub-Flavored-Markdown page for the whole tree
val man = cli.renderManPage()              // a roff/man page (pass renderManPage(date = "...") to stamp it)
```

The markdown targets GitHub Flavored Markdown: it lays inputs out in pipe tables, so render it with a
GFM-capable processor (a strict base-CommonMark renderer treats those tables as plain text).

The same shape rule as completion applies: a dispatcher gets it as a subcommand, `myapp docs
markdown|man`; a single-command tool gets it as an option instead, `myapp --docs markdown|man` (no
`docs` subcommand, so a positional literally named `docs` still parses as a value). Either way it
prints the same output, so a user can pipe it straight to a file.

Your help text (a `description`, `epilogue`, or example note) is rendered as markdown in the markdown
output: klap escapes a backslash and a backtick (and, in a table cell, a pipe `|` and newline) so a
Windows path, stray backtick, or table-breaking pipe survives, but it
does not neutralize other markdown, so a `#`, `[link](...)`, `*emphasis*`, or raw HTML in your help
text renders as markdown. That is your own authored content, so treat generated docs as trusted: if you
publish a tool's docs, keep in mind the help strings become live markup.

## Escape hatch

klap's rendering is a thin layer over pure functions. Reach past it when you need to:

- `cli.parse(argv): Result<Invocation, CliError>` returns the parsed outcome with no output and no exit.
  `Invocation` is a sealed interface with six cases, so an exhaustive `when` must handle all of them:
  `Execute` (the resolved command plus `Globals`), `ShowHelp`, `ShowVersion`, `ShowCompletion`, `ShowDocs`,
  and `ShowCompleteCandidates` (the hidden `__complete` node behind `.completeWith` providers, which is
  injected into every tree). `parse` resolves *which* command would run and validates its inputs; it does **not** run any
  `action { }`. `Execute.globals` carries only the built-in `--json` flag; your own
  `globalOption` / `globalFlag` values are read through their accessors inside `action { }`, not off
  `Execute`.
- `Invocation.Execute.runAction(): Result<Any?, CliError>?` runs the resolved command's `action { }` with
  its parsed values in scope and hands back the action's own typed result — its `Ok(value)` or a typed `Failure` —
  instead of rendered text and an exit code. This is the hook for embedding klap's parsing and dispatch in
  a larger program: branch over the `Invocation` from `parse`, then call `runAction()` on an `Execute` to
  execute it yourself. It returns null for a node with no action, never writes output or exits, and erases
  the value to `Any?` (a `Cli` is not typed over its actions' return types), so cast it to your type.
- `cli.run(argv, terminal): Int` parses, dispatches, **runs the resolved `action { }`**, and renders its
  result (or any error) to a `Terminal` you supply, returning the exit code without terminating the
  process. Reach for `run` rather than `parse` whenever an action must actually execute. It also makes
  testing easy:

```kotlin
import com.fromwau.klap.Terminal
import com.fromwau.klap.run   // `run` is an extension on Cli; without this import `cli.run(a, b)`
                              // resolves to stdlib `T.run` and fails to compile

class RecordingTerminal : Terminal {
    val out = StringBuilder()
    val err = StringBuilder()
    override fun out(text: String) { out.append(text) }
    override fun err(text: String) { err.append(text) }
}

val term = RecordingTerminal()
val code = cli.run(arrayOf("list", "--json"), term)
// assert on code and term.out
```

Note `run` takes an `Array<String>` while `parse` takes a `List<String>`.

`cli.main(argv)` is the full drop-in: it calls `run` with the platform terminal and exits the process
with the resulting code.

All three take any `Collection<String>`, so a `List`, a `Set`, or whatever collection you already hold
goes straight in. Each also has an `Array<String>` overload, because an `Array` is not a `Collection` and
that is the shape Kotlin's own `fun main(args: Array<String>)` gives you. Going the other way, every
collection klap hands back is a `List`: `Command.aliases`, `Command.subcommands`, a `.multiple()` holder's
value, and the collections inside `CliError`. Liberal in, specific out.

One caveat on the widened parameter: argv is a *sequence*, and the order is the input. A `Collection` with
no meaningful order will parse in whatever order it iterates, which is the caller's error rather than
something klap can detect.

### Testing your CLI

Use `cliOf` instead of `cli`, and end the block with a `projection { }` saying how to read one parse into a
type of your own. A parse then hands back that type, made of ordinary values:

```kotlin
data class TarArgs(val create: Boolean, val file: String?)

@Test
fun archiveFlagsBindFromACluster() {
    val tar = cliOf("tar") {
        val create = flag("--create", "-c", help = "create a new archive")
        val file = option("--file", "-f", help = "archive to operate on")
        action { Ok("") }
        projection { TarArgs(create(), file()) }
    }

    assertEquals(Ok(TarArgs(create = true, file = "out.tar")), tar.parse(listOf("-cf", "out.tar")))
}
```

Every handle stays an ordinary `val` at its point of use, and the assertion compares the **whole** record,
so the inputs the line does *not* supply are pinned too. That is the difference that matters: asserting
field by field only ever tests the fields you remembered to name, and an override rule like `lastWins` is
mostly a claim about the input that *lost*.

`TypedCli.parse` returns `Result<T?, CliError>`. The `null` is "a built-in answered": `--help`,
`--version`, completion and docs resolve without reaching a command, so there is nothing to project. A
parse error is still `Result.Error`, and the projection never runs for one.

For a tree, give each command its own `projection { }` and combine them with `dispatch(...)`. `T` is
inferred as their common supertype, which makes a sealed result the natural shape and the caller's `when`
exhaustive:

```kotlin
sealed interface GitArgs {
    data class Commit(val message: List<String>) : GitArgs
    data class RemoteAdd(val name: String, val url: String) : GitArgs
}

val git = cliOf("git") {
    val commit = command("commit") {
        val message = option("--message", "-m").multiple()
        action { Ok("") }
        projection { GitArgs.Commit(message()) }
    }
    val remote = command("remote") {
        val add = command("add") {
            val name = argument("name")
            val url = argument("url")
            action { Ok("") }
            projection { GitArgs.RemoteAdd(name(), url()) }
        }
        dispatch(add)
    }
    dispatch(commit, remote)
}
```

A parse resolves to a **leaf**, so `git remote add origin url` projects to `RemoteAdd`, not to anything
belonging to `remote`. A command that both acts and nests ends its own block with
`dispatch(child, ..., projection { })`, and the unclaimed part is taken as that command's own reader.

Root globals need no special handling: they are in lexical scope inside every `command { }` and readable
from every command's scope, so each projection reads the ones it wants. When there are many, group them
into one payload read by a local helper, `fun ValueScope.globals() = Globals(gitDir(), workTree(), ...)`,
rather than repeating them across every variant.

A command that can execute but declares no projection is rejected **at construction**, not on the argv that
happens to reach it:

```
cli 'git': no projection for 'commit'. Every command with an action { } must end its block in
projection { }, and the root must combine them with dispatch(...)
```

`cli(name) { }` remains for a program that only needs to run: it returns a plain `Cli` and requires no
projection. Reach for `cliOf` when something other than the action has to read the values.

For a rejection, assert on the `CliError` directly — it is a `data class`, so structural equality works and
you never match on rendered prose:

```kotlin
assertEquals(
    CliError.MissingOptionValue("--file"),
    (tar.parse(listOf("-f")) as Result.Error).error,
)
```

Use `run(argv, terminal)` instead when what you want to pin is the *output* — the rendered text, the exit
code, or a `--json` envelope. `inputs` is for binding, `run` is for rendering.

A `cli { }` tree is immutable after construction: each `parse` / `run` / `runAction` records its resolved
values in a per-call snapshot handed to that call's `action { }` as its receiver, not on the tree, so
**one tree is safe to parse and run from multiple threads at once** — every call sees only its own
values. Because the accessors live on that receiver, an `option(...)` / `argument(...)` / `flag(...)`
reader only compiles where such a receiver is in scope — inside the command's `action { }`, or against an
`Execute.inputs` snapshot (see below). Reading one from arbitrary code is a compile error, not a runtime
surprise. The write side is scoped the same way: the fluent transformers (`.int()`,
`.default(...)`, `.validate(...)`, ...) only compile inside a builder block, so a leaked handle cannot
mutate a built tree.

## POSIX conformance

klap conforms to the **POSIX.1-2024 Utility Syntax Guidelines** — IEEE Std 1003.1-2024 / The Open Group
Base Specifications Issue 8, XBD chapter 12 §12.2:

<https://pubs.opengroup.org/onlinepubs/9799919799.2024edition/basedefs/V1_chap12.html>

Everything klap adds on top of them is additive by construction:

> klap never changes the meaning of a command line the guidelines define. Its own conveniences — long
> options, reading an option after an operand, `--config=value` — only give meaning to input POSIX leaves
> undefined.

So `--` ends options and everything after it is an operand even if it starts with a dash; a lone `-` is an
operand; `-abc value` clusters the way the guidelines describe; a dash-led token is an option, and an
undeclared one is an error rather than a silently-accepted filename. `lastWins` is guideline 11's own
"documented to override any incompatible options preceding it" clause, not an invention.

One deliberate consequence worth knowing: a **single-dash multi-character option** (`find -name`) is not
expressible, because guideline 3 says an option name is one character.

`.optionalValue(whenBare)` is the one place klap steps outside a guideline, and only for the option that
asks for it: guideline 7 says option-arguments should not be optional, because `--color auto` is genuinely
ambiguous between a value and an operand without a rule. Calling it takes that one option outside the
guideline, knowingly; every option that does not call it stays conforming, and `PosixConformanceTest` pins
both halves. See [Options whose value is optional](#options-whose-value-is-optional).

The rest of what klap adds is outside the guidelines' model rather than against it, so a conforming line
has no token for any of it to reach: long options and their unambiguous prefixes and `--config=value`
spellings (guideline 3 makes an option name one character, so `--`-led names lie outside it entirely),
reading an option after an operand, repeated occurrences, a non-alphanumeric or digit short, and a
negatable flag's negative half however it is spelled: the guidelines describe no negation at all, so both
halves are ordinary options to them.

### `optionsEndAtFirstOperand`: the one switch that runs the other way

Everything above adds meaning where POSIX is silent. This one gives an extension **up**:

```kotlin
cli("ssh") {
    optionsEndAtFirstOperand = true
    ...
}
```

Guideline 9 puts every option before the operands, and a strict POSIX `getopt` stops at the first one.
klap's default is GNU's permutation, which keeps reading options after an operand; turning this on trades
that extension back for the conforming rule, so `ssh web1 ls -la` passes `ls -la` through untouched
instead of binding `-l` locally. It is off by default and set per command, since a subcommand that wraps
another program (`git bisect run`) sits beside siblings that must keep permuting. `--` is unaffected and
still ends options wherever options have not already ended.

It has one edge a wrapper should know about: klap's own position-independent built-ins (`--json`,
`--color`, `--help`, `--version`, `--completion`, `--docs`), a long global, and a short cluster made
entirely of global characters are all claimed wherever they sit in the tail, since ending options here
does not end *their* reach. (The one place none of them reach is a value-taking option's argument slot,
which belongs to that option on every command — see [Dash-led values](#dash-led-values).) A cluster that
*mixes* a global character with a local one goes the other way and binds whole into the tail, so the
global silently keeps its default. A tail that must carry one of those spellings literally still needs
its own `--`.

Each guideline is executed as a test in `PosixConformanceTest`, quoting the standard's text at the
assertion, so conformance is verified on every build rather than claimed here. Every extension above is
paired there with the conforming line it must not disturb.
