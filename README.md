# klap

A reflection-free Kotlin Multiplatform command-line framework. A builder DSL for commands and
subcommands, typed and validated arguments/options/flags, global options, opt-in GNU-style prefix
abbreviation, auto-generated help with sections and color, value-aware shell completion, generated
man/markdown docs, structured `--json` output, and typed `Result` errors. No annotations, no reflection,
no annotation processing.

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64.

## Add to your build

Releases are published as `com.fromwau:klap` to
[maven.frommhund.xyz](https://maven.frommhund.xyz/#/releases/com/fromwau/klap), which needs no
credentials to read. The current version is `0.1.0`.

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.frommhund.xyz/releases")
}

dependencies {
    implementation("com.fromwau:klap:0.1.0")
}
```

Add `kotlin("plugin.serialization")` only if an action returns an `@Serializable` type. The JSON runtime
is exposed as `api`, so it arrives transitively. KMP consumers put the dependency in `commonMain`.

klap's `Result<S, E>` is not its own: it is
[`com.fromwau.kern:result`](https://github.com/FromWau/kern/blob/master/result/README.md), also exposed as
`api` and published to the same repository, so it needs no separate dependency line. Import it from
`com.fromwau.kern.result`. An error type is anything implementing kern's `IError` marker, `CliError`
included.

Sources are published for every target, so an API question is answered by your IDE's own quick-doc and
step-into rather than by reading a decompiled stub.

**Toolchain floor.** The JVM and Android artifacts are Java 25 bytecode. The JVM variant declares that as
`org.gradle.jvm.version`, so Gradle reports an unmet requirement instead of letting it surface later as a
`class file has wrong version` error from `javac`. The Android side has no equivalent attribute — what
constrains it is the dexer, so consuming the AAR needs Android build-tools 36.1.0 or newer. The native
targets have no floor.

## A whole program

```kotlin
fun main(args: Array<String>) = cli("greet") {
    version = "1.0.0"

    val name = argument("name", "who to greet")
    val times = option("--times", "-n", help = "repeat count").int().default(1)
    val loud = flag("--loud", "-l", help = "shout it")

    action {
        val line = if (loud()) "HELLO, ${name().uppercase()}!" else "Hello, ${name()}."
        Ok(List(times()) { line }.joinToString("\n"))
    }
}.main(args)
```

That is the finished tool. `greet --help` is written for you, `greet Ada -n 2 --loud` prints two shouted
lines, `greet` alone reports the missing operand and exits non-zero, `greet --tmies 2` suggests `--times`,
and `greet --completion bash` emits a completion script. You did not write a `println`, an `exitProcess`,
or a usage string.

(A tool that acts at its root gets completion and docs as the `--completion` / `--docs` options. One with
subcommands gets them as `completion` / `docs` subcommands instead, since a positional is free there.)

## Reading the values from outside

`cli(name) { }` is all a program needs: the action reads the values and nothing else does. When something
*else* has to read them — a test, or an embedder driving the parse itself — use `cliOf`, and end the block
by saying how one parse maps into a type of your own:

```kotlin
data class GreetArgs(val name: String, val times: Int, val loud: Boolean)

val greet = cliOf("greet") {
    val name = argument("name")
    val times = option("--times", "-n").int().default(1)
    val loud = flag("--loud", "-l")
    action { Ok("...") }
    projection { GreetArgs(name(), times(), loud()) }
}

greet.parse(listOf("Ada", "-n", "2"))   // Ok(GreetArgs(name = "Ada", times = 2, loud = false))
```

What comes back is plain values, so a whole invocation compares in one assertion. For a subcommand tree,
each command gets its own `projection { }` and `dispatch(...)` combines them into a sealed result the
caller matches exhaustively. See [the guide](docs/guide.md#testing-your-cli).

Entry points take any `Collection<String>` (and the `Array<String>` that `fun main` hands you), and every
collection klap hands back is a `List`.

## Where to go next

- **[The guide](docs/guide.md)** is the full reference: converters and validators, flags, cross-input
  constraints, global options, typed errors, `--json`, color, subcommand trees, help layout, completion,
  generated docs, and the escape hatch.
- **[`example/`](example/README.md)** is a behaviour index: "how do I get an operation mode like pacman's
  `-S`" is a table lookup that names the tool doing it. Behind the table are an installable task-manager
  CLI and fifteen real tools (`pacman`, `git`, `tar`, `find`, `dd`, ...) reproduced in klap, so every
  answer is runnable code to copy from.

## POSIX conformance

klap follows the [POSIX.1-2024 Utility Syntax
Guidelines](https://pubs.opengroup.org/onlinepubs/9799919799.2024edition/basedefs/V1_chap12.html) (IEEE Std
1003.1-2024, XBD chapter 12 §12.2). The conveniences it adds over them, long options, reading an option
after an operand, `--opt=value`, only give meaning to input POSIX leaves undefined:

> klap never changes the meaning of a command line the guidelines define.

Each guideline is executed as a test in `PosixConformanceTest`, quoting the standard's text at the
assertion, so this is verified on every build rather than claimed here. Every extension is paired there
with the conforming line it must not disturb. The full account, including the one option-level opt-out and
the one switch that trades an extension back, is in [the guide](docs/guide.md#posix-conformance).

## License

Apache-2.0. See [LICENSE](LICENSE).
