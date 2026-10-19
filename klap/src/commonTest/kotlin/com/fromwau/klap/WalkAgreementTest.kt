package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.klap.internal.parse.builtinScan
import com.fromwau.klap.internal.parse.globalsFrom
import com.fromwau.klap.internal.parse.hoistCommands
import com.fromwau.klap.internal.parse.siftGlobals
import com.fromwau.klap.internal.parse.walkSubcommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The built-in scan reads each word against the command the parse's subcommand walk puts it under, so a value slot
 * and a built-in are judged where the word binds. Each line names, word by word, the command both walks give it.
 */
class WalkAgreementTest {

    private val app = cli("app") {
        val lines = numberOption("lines").int()
        val workdir = option(short = 'w', long = "workdir")
        command("sub") {
            val expr = option(short = 'e')
            action { Ok("lines=${lines()} e=${expr()} w=${workdir()}") }
        }
        command("remote") {
            val verbose = flag(short = 'v', long = "verbose")
            command("add") {
                val name = argument("name")
                action { Ok("add ${name()} v=${verbose()}") }
            }
        }
        action { Ok("root lines=${lines()}") }
    }

    private val tool = cli("tool") {
        val workdir = option(short = 'w', long = "workdir")
        val tag = globalOption(short = 't', long = "tag")
        val global = globalFlag(short = 'g', long = "global")
        command("sub") {
            val expr = option(short = 'e')
            action { Ok("e=${expr()} w=${workdir()} t=${tag()} g=${global()}") }
        }
        command("remote") {
            val verbose = flag(short = 'v', long = "verbose")
            val depth = numberOption("depth", firstWordOnly = true)
            command("add") {
                val name = argument("name")
                action { Ok("add ${name()} v=${verbose()} d=${depth()}") }
            }
        }
        command("license") {
            prio = true
            action { Ok("MIT") }
        }
        action { Ok("root") }
    }

    private val pac = cli("pac") {
        val verbose = globalFlag(short = 'v', long = "verbose")
        val root = option(short = 'r', long = "root")
        command(short = 'Q', long = "query") {
            val info = flag(short = 'i', long = "info")
            val packages = argument("package").multiple()
            action { Ok("Q i=${info()} v=${verbose()} r=${root()} ${packages()}") }
        }
        command("files") {
            command("list") { action { Ok("list") } }
        }
    }

    private val git = cli("git") {
        abbreviation = Abbreviation.Options
        ownOptions(shortOptions = ShortOptions.Alone, abbreviation = Abbreviation.None)
        val directory = option(short = 'C')
        val paginate = flag(short = 'p', long = "paginate")
        command("remote") {
            val verbose = flag(short = 'v', long = "verbose")
            command("add") {
                val name = argument("name")
                action { Ok("add ${name()} C=${directory()} p=${paginate()} v=${verbose()}") }
            }
        }
        action { Ok("root") }
    }

    /** Compares both commands of each word the walk reads, the line hoisted and stripped as the parse does. */
    private fun Cli.assertWalksAgree(vararg line: String) {
        val (walked, scanned) = walksOf(line.toList())
        assertEquals(walked, scanned, line.joinToString(" "))
    }

    /** Asserts both walks give each word the walk reads the command named in [expected]. */
    private fun Cli.assertWalksRead(
        expected: List<String>,
        vararg line: String,
    ) {
        val (walked, scanned) = walksOf(line.toList())
        assertEquals(expected, walked, "the walk on ${line.joinToString(" ")}")
        assertEquals(expected, scanned, "the scan on ${line.joinToString(" ")}")
    }

    /** The command each word the walk reads stands on, by the subcommand walk and by the built-in scan. */
    private fun Cli.walksOf(line: List<String>): Pair<List<String>, List<String>> {
        val hoisted = hoistCommands(line)
        val scan = builtinScan(hoisted.tokens)
        val withoutJson = if (builtins.json) scan.strip("json") else scan
        val withoutColor = if (builtins.color) withoutJson.stripSpellings("color") else withoutJson
        val withoutMeta = if (metaOptions && hoisted.prio) {
            withoutColor
                .let { if (builtins.completion) it.stripValued("completion") else it }
                .let { if (builtins.docs) it.stripValued("docs") else it }
        } else {
            withoutColor
        }
        val preStrip = globalSpecs.siftGlobals(withoutMeta, shortOptions)
        val walk = walkSubcommands(preStrip.cleaned) { globalsFrom(preStrip.sift) }
        val chain = walk.passed.map { it.command } + walk.cmd
        val walked = preStrip.cleaned.indices.map { k -> chain[walk.names.count { it < k }].name }
        val scanned = preStrip.positions.map { p -> (scan.commandAt(p) ?: this).name }
        return walked to scanned
    }

    @Test
    fun `both walks route past the command's own number`() {
        app.assertWalksAgree("-3", "sub", "-e", "--help")
        app.assertWalksAgree("-3", "-w", "d", "sub", "-e", "x")
    }

    @Test
    fun `both walks route past each level's own options`() {
        app.assertWalksAgree("-w", "d", "remote", "-v", "add", "o")
        app.assertWalksAgree("--workdir=d", "remote", "--verbose", "add", "o")
    }

    @Test
    fun `both walks stop at an operand`() {
        app.assertWalksAgree("x", "sub", "-e", "y")
        app.assertWalksAgree("remote", "-v", "x", "add")
    }

    @Test
    fun `both walks route past a first word only number on the first word`() {
        tool.assertWalksAgree("remote", "-5", "add", "o")
    }

    @Test
    fun `both walks route past a global`() {
        tool.assertWalksAgree("--tag", "x", "remote", "-v", "add", "o")
        tool.assertWalksAgree("-g", "remote", "add", "o")
        tool.assertWalksAgree("remote", "-t", "add", "add", "o")
        tool.assertWalksAgree("remote", "-gv", "add", "o")
        tool.assertWalksAgree("--json", "remote", "--color", "add", "o")
        tool.assertWalksAgree("remote", "--color=never", "add", "o")
    }

    @Test
    fun `both walks give a value slot to its option whatever it is spelled`() {
        tool.assertWalksAgree("-w", "remote", "remote", "add", "o")
        tool.assertWalksAgree("-w", "--", "remote", "add", "o")
        tool.assertWalksAgree("sub", "-e", "--help", "x")
        tool.assertWalksAgree("remote", "--tag", "--verbose", "add", "o")
    }

    @Test
    fun `both walks stop at the end of options`() {
        tool.assertWalksAgree("remote", "--", "add", "o")
        tool.assertWalksAgree("--", "sub")
    }

    @Test
    fun `both walks stop at a refused option`() {
        tool.assertWalksAgree("remote", "--bogus", "add", "o")
        tool.assertWalksAgree("remote", "-vx", "add", "o")
    }

    @Test
    fun `both walks stop at a flag given a value`() {
        tool.parse(listOf("remote", "--verbose=x", "add", "o")).assertError<CliError.FlagTakesNoValue>()
        tool.assertWalksRead(listOf("tool", "remote", "remote", "remote"), "remote", "--verbose=x", "add", "o")
    }

    @Test
    fun `both walks stop at a first word only number past the first word`() {
        val refused = tool.parse(listOf("remote", "-v", "-5", "add", "o")).assertError<CliError.UnknownOption>()
        assertEquals("-5", refused.token)
        tool.assertWalksRead(
            listOf("tool", "remote", "remote", "remote", "remote"),
            "remote", "-v", "-5", "add", "o",
        )
    }

    @Test
    fun `Tab offers none of a child's options past a word its parent refuses`() {
        val tree = cli("tool") {
            command("remote") {
                flag(short = 'v', long = "verbose")
                numberOption("depth", firstWordOnly = true)
                command("add") {
                    flag(short = 'x', long = "extra")
                    argument("name")
                    action { Ok("") }
                }
            }
        }
        val refused = tree.parse(listOf("remote", "-v", "-5", "add", "-x", "o")).assertError<CliError.UnknownOption>()
        assertEquals("-5", refused.token)
        val offered = tree.offered("remote", "-v", "-5", "add", "-")
        assertTrue(offered.none { it == "-x" || it == "--extra" }, "remote's segment offers $offered")
    }

    @Test
    fun `both walks treat a help request alike`() {
        tool.assertWalksAgree("--help", "remote", "add")
        tool.assertWalksAgree("remote", "-vh", "add")
        tool.assertWalksAgree("remote", "--hel", "add")
        tool.assertWalksAgree("-hg", "remote", "add")
    }

    @Test
    fun `both walks treat completion and docs alike`() {
        tool.assertWalksAgree("--completion", "bash", "remote", "add", "o")
        tool.assertWalksAgree("--completion", "bash", "license")
        tool.assertWalksAgree("license", "--docs", "markdown")
    }

    @Test
    fun `both walks route to a prio child`() {
        tool.assertWalksAgree("-w", "d", "license")
        tool.assertWalksAgree("-g", "license")
    }

    @Test
    fun `both walks route to a flag spelled child`() {
        pac.assertWalksAgree("-Q", "pkg")
        pac.assertWalksAgree("-iQ", "pkg")
        pac.assertWalksAgree("-r", "/mnt", "--query", "-i", "pkg")
        pac.assertWalksAgree("-v", "files", "list")
        pac.assertWalksAgree("-r", "files", "files", "list")
    }

    @Test
    fun `both walks read a command's own options by its own reading`() {
        git.assertWalksAgree("-C", "d", "remote", "-v", "add", "o")
        git.assertWalksAgree("-p", "remote", "add", "o")
        git.assertWalksAgree("-pC", "d", "remote", "add", "o")
        git.assertWalksAgree("--pag", "remote", "add", "o")
        git.assertWalksAgree("remote", "--verb", "add", "o")
    }

    @Test
    fun `a global before a first word only number leaves the number its command's first word`() {
        // The parse strips the global before it reads remote's words, so -5 is remote's first word either way.
        tool.assertWalksAgree("remote", "-g", "-5", "add", "o")
        tool.assertWalksAgree("remote", "-t", "x", "-5", "add", "o")
        tool.assertWalksAgree("remote", "--global", "-5", "add", "o")
        assertEquals("add o v=false d=5", tool.bindText("remote", "-g", "-5", "add", "o"))
    }
}
