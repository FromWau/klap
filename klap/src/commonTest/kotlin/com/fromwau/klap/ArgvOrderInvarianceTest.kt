package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * An indivisible run of argv tokens: a flag, a value-taking option WITH its value, a subcommand name,
 * or an operand. Permuting units rather than raw tokens only ever yields lines a user could type.
 */
private typealias ArgvUnit = List<String>

/** Every ordering of [items]. This file caps units at 6, i.e. at most 720 orderings per sweep. */
private fun <T> permutationsOfUnits(items: List<T>): List<List<T>> =
    if (items.size <= 1) {
        listOf(items)
    } else {
        items.indices.flatMap { index ->
            val rest = items.toMutableList().apply { removeAt(index) }
            permutationsOfUnits(rest).map { listOf(items[index]) + it }
        }
    }

/** One line's whole outcome as a comparable string: the error with its fields, or what the line bound. */
private fun outcomeOfOrdering(app: Cli, argv: List<String>, describe: ValueScope.() -> String): String {
    val parsed: Result<Invocation, CliError> = app.parse(argv)
    if (parsed is Result.Error) return "Error(${parsed.error})"
    val invocation = (parsed as Result.Success<Invocation>).value
    return when (invocation) {
        is Invocation.Execute -> {
            // A sweep can resolve to a command whose handles this describe cannot read; that is a
            // divergence to report, not an exception that ends the sweep.
            val bound = runCatching { invocation.inputs.describe() }
                .getOrElse { "unreadable ${it::class.simpleName}" }
            "Execute(${invocation.command.name} json=${invocation.globals.json} $bound)"
        }
        is Invocation.ShowHelp -> "ShowHelp(${invocation.qualifiedName})"
        is Invocation.ShowVersion -> "ShowVersion(json=${invocation.json})"
        is Invocation.ShowCompletion -> "ShowCompletion(${invocation.shell})"
        is Invocation.ShowDocs -> "ShowDocs(${invocation.format})"
        is Invocation.ShowCompleteCandidates -> "ShowCompleteCandidates(${invocation.words})"
    }
}

/**
 * Parses every ordering of [units] that [legal] keeps and reports EVERY divergence from the baseline
 * (the units in the order given) in one failure, since one assertion inside the loop would hide the
 * other 719 and the list of which orderings diverge is the whole point. Returns the baseline outcome
 * so a test can also pin what the invariant holds at.
 */
private fun assertOrderInvariant(
    app: Cli,
    units: List<ArgvUnit>,
    legal: (List<ArgvUnit>) -> Boolean = { true },
    describe: ValueScope.() -> String,
): String {
    val baselineArgv = units.flatten()
    if (!legal(units)) fail("the baseline ordering must itself be legal: $baselineArgv")
    val baseline = outcomeOfOrdering(app, baselineArgv, describe)
    val orderings = permutationsOfUnits(units).filter(legal)
    val divergences = orderings.mapNotNull { ordering ->
        val argv = ordering.flatten()
        val outcome = outcomeOfOrdering(app, argv, describe)
        if (outcome == baseline) null else "$argv -> $outcome"
    }
    if (divergences.isNotEmpty()) {
        fail(
            "${divergences.size} of ${orderings.size} legal orderings diverge from baseline " +
                "$baselineArgv -> $baseline\n" +
                divergences.joinToString("\n"),
        )
    }
    return baseline
}

/**
 * Holds one claim: an argv is a set of instructions, not a script, so reordering instructions that do
 * not depend on one another must not change what binds. Each test builds one CLI, sweeps every legal
 * ordering of its units and compares each outcome against a stated baseline. Orderings that are
 * genuinely a different line (an operand before its command, a local option before its command's name)
 * are excluded by the `legal` predicate with the reason on the line above it, never by weakening the
 * assertion.
 */
class ArgvOrderInvarianceTest {

    // GUIDE: a global "is recognized anywhere on the line (before or after the subcommand path)".
    @Test
    fun `root globals bind the same wherever they sit around a subcommand and its operand`() {
        lateinit var verbose: Flag
        lateinit var config: Opt<String?>
        lateinit var file: Arg<String>
        val app = cli("app") {
            verbose = globalFlag("--verbose", "-v", help = "log everything")
            config = globalOption("--config", "-c", help = "config file")
            command("build") {
                file = argument("file", help = "what to build")
                action { Ok("") }
            }
        }
        val nameUnit = listOf("build")
        val operandUnit = listOf("out.txt")
        val baseline = assertOrderInvariant(
            app = app,
            units = listOf(listOf("-v"), listOf("--config", "prod.toml"), nameUnit, operandUnit),
            // An operand ahead of the name is a different line: it would be the ROOT's operand.
            legal = { it.indexOf(operandUnit) > it.indexOf(nameUnit) },
        ) { "verbose=${verbose()} config=${config()} file=${file()}" }
        assertEquals("Execute(build json=false verbose=true config=prod.toml file=out.txt)", baseline)
    }

    // CONVENTION - git: `git commit -m msg f` and `git commit f -m msg` commit the same file.
    @Test
    fun `a child's local option and operand permute freely after the subcommand name`() {
        lateinit var verbose: Flag
        lateinit var target: Opt<String?>
        lateinit var file: Arg<String>
        val app = cli("app") {
            verbose = globalFlag("--verbose", "-v", help = "log everything")
            command("build") {
                target = option("--target", help = "build target")
                file = argument("file", help = "what to build")
                action { Ok("") }
            }
        }
        val nameUnit = listOf("build")
        val localUnit = listOf("--target", "wasm")
        val operandUnit = listOf("out.txt")
        val baseline = assertOrderInvariant(
            app = app,
            units = listOf(nameUnit, listOf("-v"), localUnit, operandUnit),
            // A command's own option and operand belong after its name; only the global may lead.
            legal = {
                it.indexOf(localUnit) > it.indexOf(nameUnit) && it.indexOf(operandUnit) > it.indexOf(nameUnit)
            },
        ) { "verbose=${verbose()} target=${target()} file=${file()}" }
        assertEquals("Execute(build json=false verbose=true target=wasm file=out.txt)", baseline)
    }

    // GUIDE: "Local and global short flags cluster together in any order (-fv binds the same as -vf)."
    @Test
    fun `a mixed short cluster binds the same wherever it sits and in either spelling`() {
        lateinit var verbose: Flag
        lateinit var force: Flag
        lateinit var config: Opt<String?>
        lateinit var file: Arg<String>
        val app = cli("app") {
            verbose = globalFlag("--verbose", "-v", help = "log everything")
            config = globalOption("--config", "-c", help = "config file")
            command("build") {
                force = flag("--force", "-f", help = "rebuild everything")
                file = argument("file", help = "what to build")
                action { Ok("") }
            }
        }
        val nameUnit = listOf("build")
        val operandUnit = listOf("out.txt")
        val describe: ValueScope.() -> String = {
            "verbose=${verbose()} force=${force()} config=${config()} file=${file()}"
        }
        val expected = "Execute(build json=false verbose=true force=true config=prod.toml file=out.txt)"
        listOf(listOf("-vf"), listOf("-fv")).forEach { clusterUnit ->
            val baseline = assertOrderInvariant(
                app = app,
                units = listOf(nameUnit, clusterUnit, listOf("--config", "prod.toml"), operandUnit),
                // The cluster carries a LOCAL character, so like the operand it belongs after the name.
                legal = {
                    it.indexOf(clusterUnit) > it.indexOf(nameUnit) &&
                        it.indexOf(operandUnit) > it.indexOf(nameUnit)
                },
                describe = describe,
            )
            assertEquals(expected, baseline)
        }
    }

    // GUIDE: options are position-independent; with no operand and no subcommand nothing can depend on
    // where one sits.
    @Test
    fun `options alone bind identically in every single ordering`() {
        lateinit var verbose: Flag
        lateinit var quiet: Flag
        lateinit var out: Opt<String?>
        lateinit var jobs: Opt<Int>
        lateinit var name: Opt<String?>
        val app = cli("app") {
            verbose = flag("--verbose", "-v", help = "log everything")
            quiet = flag("--quiet", "-q", help = "log nothing")
            out = option("--out", "-o", help = "where to write")
            jobs = option("--jobs", help = "parallelism").int().default(1)
            name = option("--name", help = "what to call it")
            action { Ok("") }
        }
        val baseline = assertOrderInvariant(
            app = app,
            units = listOf(
                listOf("-v"),
                listOf("--out", "x"),
                listOf("--jobs", "4"),
                listOf("--name=ada"),
                listOf("-q"),
            ),
        ) { "verbose=${verbose()} quiet=${quiet()} out=${out()} jobs=${jobs()} name=${name()}" }
        assertEquals("Execute(app json=false verbose=true quiet=true out=x jobs=4 name=ada)", baseline)
    }

    // CONVENTION - cp/rsync: SOURCE and DEST are told apart by their order and by nothing else, while
    // GNU permutation lets an option sit anywhere among them.
    @Test
    fun `two operands keep their own order while options move freely between them`() {
        lateinit var force: Flag
        lateinit var quiet: Flag
        lateinit var out: Opt<String?>
        lateinit var from: Arg<String>
        lateinit var to: Arg<String>
        val app = cli("app") {
            force = flag("--force", "-f", help = "overwrite")
            quiet = flag("--quiet", "-q", help = "log nothing")
            out = option("--out", "-o", help = "where to write")
            from = argument("from", help = "source")
            to = argument("to", help = "destination")
            action { Ok("") }
        }
        val fromUnit = listOf("src.txt")
        val toUnit = listOf("dst.txt")
        val optionUnits = listOf(listOf("-f"), listOf("--out", "o"), listOf("-q"))
        val describe: ValueScope.() -> String = {
            "from=${from()} to=${to()} force=${force()} quiet=${quiet()} out=${out()}"
        }
        val forward = assertOrderInvariant(
            app = app,
            units = listOf(fromUnit, toUnit) + optionUnits,
            legal = { it.indexOf(fromUnit) < it.indexOf(toUnit) },
            describe = describe,
        )
        assertEquals("Execute(app json=false from=src.txt to=dst.txt force=true quiet=true out=o)", forward)
        // The same units with the operands swapped: still order-invariant, and it must bind the OTHER way.
        val reversed = assertOrderInvariant(
            app = app,
            units = listOf(toUnit, fromUnit) + optionUnits,
            legal = { it.indexOf(toUnit) < it.indexOf(fromUnit) },
            describe = describe,
        )
        assertEquals("Execute(app json=false from=dst.txt to=src.txt force=true quiet=true out=o)", reversed)
    }

    // GUIDE: a global is recognized "anywhere on the line (before or after the subcommand path)" and is
    // "readable from any nested action { }" - so every level of the path is a place it may sit.
    @Test
    fun `a global reaches a three level path from any position on the line`() {
        lateinit var config: Opt<String?>
        lateinit var verbose: Flag
        lateinit var node: Arg<String>
        val app = cli("app") {
            config = globalOption("--config", "-c", help = "config file")
            verbose = globalFlag("--verbose", "-v", help = "log everything")
            command("cluster") {
                command("node") {
                    command("drain") {
                        node = argument("node", help = "node to drain")
                        action { Ok("") }
                    }
                }
            }
        }
        val clusterUnit = listOf("cluster")
        val nodeUnit = listOf("node")
        val drainUnit = listOf("drain")
        val operandUnit = listOf("n1")
        val baseline = assertOrderInvariant(
            app = app,
            units = listOf(
                clusterUnit,
                nodeUnit,
                drainUnit,
                listOf("--config", "prod.toml"),
                listOf("-v"),
                operandUnit,
            ),
            // The path names are a path: their order IS the route, and the operand belongs to the leaf.
            legal = {
                val clusterAt = it.indexOf(clusterUnit)
                val nodeAt = it.indexOf(nodeUnit)
                val drainAt = it.indexOf(drainUnit)
                clusterAt < nodeAt && nodeAt < drainAt && drainAt < it.indexOf(operandUnit)
            },
        ) { "config=${config()} verbose=${verbose()} node=${node()}" }
        assertEquals("Execute(drain json=false config=prod.toml verbose=true node=n1)", baseline)
    }

    // CONVENTION - cobra (docker, kubectl, gh): which of the two answers does not depend on which side of
    // the other it sits on. GNU coreutils answers whichever came first instead, which is exactly the
    // order-dependence this file exists to find; klap's ladder puts --version above --help.
    @Test
    fun `--help and --version answer the same thing in both orders`() {
        val app = cli("app") {
            version = "1.0.0"
            action { Ok("") }
        }
        val baseline = assertOrderInvariant(
            app = app,
            units = listOf(listOf("--help"), listOf("--version")),
        ) { "" }
        assertEquals("ShowVersion(json=false)", baseline)
    }

    // GUIDE: "--version does honour it, printing {name, version} instead of the plain line", and --json is
    // position-independent, so neither order can change the answer.
    @Test
    fun `--json and --version answer the same thing in both orders`() {
        val app = cli("app") {
            version = "2.4.0"
            action { Ok("") }
        }
        val baseline = assertOrderInvariant(
            app = app,
            units = listOf(listOf("--json"), listOf("--version")),
        ) { "" }
        assertEquals("ShowVersion(json=true)", baseline)
    }

    // GUIDE: --json is "recognized anywhere in the argument list and stripped before positional binding",
    // so it may lead the line or trail the operand and must bind the same either way.
    @Test
    fun `--json binds the same wherever it sits around a subcommand and its operand`() {
        lateinit var verbose: Flag
        lateinit var file: Arg<String>
        val app = cli("app") {
            verbose = globalFlag("--verbose", "-v", help = "log everything")
            command("build") {
                file = argument("file", help = "what to build")
                action { Ok("") }
            }
        }
        val nameUnit = listOf("build")
        val operandUnit = listOf("out.txt")
        val baseline = assertOrderInvariant(
            app = app,
            units = listOf(listOf("--json"), listOf("-v"), nameUnit, operandUnit),
            // Same exclusion as above: an operand ahead of the name would be the root's, not the child's.
            legal = { it.indexOf(operandUnit) > it.indexOf(nameUnit) },
        ) { "verbose=${verbose()} file=${file()}" }
        assertEquals("Execute(build json=true verbose=true file=out.txt)", baseline)
    }

    // CONVENTION - git: `git --oneline log` is refused, so a child's own option ahead of the child's name
    // is an unknown option - and it must be the SAME unknown option wherever the rest of the line sits.
    @Test
    fun `a child's local option before the subcommand name is refused the same way in every ordering`() {
        val app = cli("app") {
            globalFlag("--verbose", "-v", help = "log everything")
            command("build") {
                option("--target", help = "build target")
                argument("file", help = "what to build")
                action { Ok("") }
            }
        }
        assertEquals(
            CliError.UnknownOption("--target"),
            assertIs<Result.Error<CliError>>(app.parse(listOf("--target", "wasm", "-v", "build", "out.txt"))).error,
        )
        val nameUnit = listOf("build")
        val localUnit = listOf("--target", "wasm")
        val operandUnit = listOf("out.txt")
        assertOrderInvariant(
            app = app,
            units = listOf(localUnit, listOf("-v"), nameUnit, operandUnit),
            // Only the orderings that put the child's option ahead of its name; the legal ones are the
            // subject of the test above.
            legal = {
                it.indexOf(localUnit) < it.indexOf(nameUnit) && it.indexOf(operandUnit) > it.indexOf(nameUnit)
            },
        ) { "" }
    }
}
