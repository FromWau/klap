# klap

A reflection-free Kotlin Multiplatform command-line framework. It gives you a builder DSL for
commands and subcommands, typed arguments/options/flags, auto-generated help, bash/zsh/fish
completion, structured `--json` output, and typed `Result` errors, with no annotations and no
reflection.

**Targets:** JVM, Android, linuxX64, mingwX64, and the Apple ARM targets (macosArm64, iosArm64,
iosSimulatorArm64).

## Highlights

- **Builder DSL:** describe a command tree with `cli { }` / `command { }`, with no annotation processing.
- **Typed inputs:** declare `argument`, `option`, `flag`, then chain converters (`.int()`,
  `.enum<E>()`, `.map { }`, and so on). The accessor returns the converted type.
- **Typed errors:** an `action { }` returns `Result<T, CliError>`; klap renders the message and picks
  the exit code, so there is no manual `println`/`exitProcess`.
- **Structured `--json`:** the same `Ok(value)` prints a human line normally and real
  `kotlinx.serialization` JSON under `--json`.
- **Batteries included:** `--help`, `--version`, `--json`, and a `completion <shell>` generator are
  wired in for free.

## Add to your build

klap publishes locally with `./gradlew publishToMavenLocal` as `com.fromwau.klap:klap:0.1.0`.

```kotlin
plugins {
    kotlin("jvm")                    // or kotlin("multiplatform")
    kotlin("plugin.serialization")   // only if an action returns an @Serializable type
}

dependencies {
    implementation("com.fromwau.klap:klap:0.1.0")
}
```

The JSON runtime is exposed as `api`, so it arrives transitively and you do not declare
`kotlinx-serialization-json` yourself. KMP consumers put the dependency in `commonMain`.

## Quick start

`cli(name)` builds the root command. A single-command tool can act at the root, so no subcommand is
required:

```kotlin
fun main(args: Array<String>) {
    cli("greet") {
        description = "Say hello"
        version = "1.0.0"

        val name = argument("name", "who to greet")
        val loud = flag("loud", "l", "shout it")
        val times = option("times", "n", "how many times").int().default(1)

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

A complete, runnable program lives in [`example/`](example/src/main/kotlin/com/fromwau/example/Main.kt).
It is `tally`, a `wc`-style file counter that reads real files, maps I/O failures to typed errors, and
emits structured JSON.

## Inputs and converters

Declare an input, then chain converters. Each converter narrows the accessor's return type; the value
is read by invoking the holder (`name()`) inside `action { }`.

```kotlin
val port    = option("port", "p").int().default(8080)            //  Int
val level   = option("level").enum<LogLevel>()                   //  LogLevel?  (case-insensitive)
val timeout = option("timeout", "t").map { it.toInt().seconds }  //  Duration?
val tags    = option("tag").multiple()                           //  List<String>  (repeatable)
val region  = option("region").required()                        //  String  (must be provided)
val files   = argument("files").file().multiple(min = 1)         //  List<String>  (min 1, path-completed)
```

| Converter | On | Result |
|---|---|---|
| `.int()` `.long()` `.double()` `.boolean()` | argument, option | the parsed primitive |
| `.enum<E>()` | argument, option | `E`, matched case-insensitively; choices shown lowercase |
| `.choice("a", "b")` | argument, option | the raw string, restricted to the given set (exact case) |
| `.map { raw -> T }` | argument, option | any type; a thrown exception becomes a clean parse error |
| `.convert { raw -> Result }` | argument, option | any type, with your own error message |
| `.optional()` | argument | makes a positional nullable (options are already nullable) |
| `.default(v)` | argument, option | supplies `v` when absent |
| `.required()` | option | fails if the option is missing |
| `.multiple(min = 0)` | argument | collects into a `List`; `min` is enforced |
| `.multiple()` | option | collects every occurrence into a `List` |
| `.file()` | argument | marks a path so shell completion suggests files |

## Typed results and errors

An `action { }` returns `Result<T, CliError>`:

- `Ok(value)` succeeds; klap renders `value` and exits `0`.
- `Err(error)` fails; klap prints the message and exits with `error.exitCode`.

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

Parser-level problems (unknown option, bad value, missing argument, invalid choice, and so on) are
their own `CliError` variants that klap raises and renders for you.

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

Errors follow suit: under `--json` a failure prints `{"error":"...","code":n}` to stderr, so a
pipeline sees JSON on both streams. Returning a `String` or primitive needs no setup; returning an
`@Serializable` type requires the `kotlin("plugin.serialization")` plugin in the consuming module.

## Command groups and nesting

A command with subcommands and no `action` is a group; klap prints its help when invoked bare. Nest
with `command(name) { }` to any depth:

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

You get `fleet --help`, `fleet disk --help`, `fleet --version`, and
`fleet completion <bash|zsh|fish>` automatically.

## Escape hatch

klap's rendering is a thin layer over pure functions. Reach past it when you need to:

- `cli.parse(argv): Result<Invocation, CliError>` returns the parsed outcome (`Execute`, `ShowHelp`,
  or `ShowVersion`) with no output and no exit.
- `cli.run(argv, terminal): Int` parses, dispatches, and renders to a `Terminal` you supply, returning
  the exit code without terminating the process. That makes it ideal for tests:

```kotlin
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

`cli.main(argv)` is the full drop-in: it calls `run` with the platform terminal and exits the process
with the resulting code.
