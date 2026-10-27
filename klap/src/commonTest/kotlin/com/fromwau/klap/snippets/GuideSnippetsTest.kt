package com.fromwau.klap.snippets

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.ResultError
import com.fromwau.kern.result.assertError
import com.fromwau.kern.terminal.Styled
import com.fromwau.kern.terminal.bold
import com.fromwau.kern.terminal.out
import com.fromwau.kern.terminal.test.RecordingTerminal
import com.fromwau.kern.terminal.yellow
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Cli
import com.fromwau.klap.CliError
import com.fromwau.klap.CommandBuilder
import com.fromwau.klap.ConversionError
import com.fromwau.klap.Invocation
import com.fromwau.klap.Opt
import com.fromwau.klap.USAGE_ERROR_EXIT
import com.fromwau.klap.ValueScope
import com.fromwau.klap.Version
import com.fromwau.klap.bindText
import com.fromwau.klap.cli
import com.fromwau.klap.cliOf
import com.fromwau.klap.name
import com.fromwau.klap.parse
import com.fromwau.klap.run
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.serialization.Serializable

/**
 * The guide's abbreviation snippets, transcribed and executed. `docs/guide.md`'s abbreviation section makes
 * specific claims about what each `Abbreviation` mode resolves and what a miss suggests; these pin those
 * claims against the real parser so a change that breaks one shows up here, not only in prose.
 *
 * Kept out of `com.fromwau.klap` on purpose. Inside klap's own package every klap symbol resolves with no
 * import at all, so a snippet naming one that does not exist still compiled and the import half of a
 * snippet went untested. Moving these back would take that check away again.
 */
class GuideSnippetsTest {

    // --- "Flags: boolean, counted, negatable": the abbreviation table's own worked example ---

    private fun guideAbbreviationExample() = cli("tasks") {
        abbreviation = Abbreviation.All
        command("list") { action<String>(human = { it }) { Ok("list") } }
        command("listen") { action<String>(human = { it }) { Ok("listen") } }
    }

    @Test
    fun `the guides abbreviation table is what the modes do`() {
        // The guide's table claims All resolves a subcommand prefix and that exact still wins outright.
        assertEquals("listen", guideAbbreviationExample().bindText("liste"))
        assertEquals("list", guideAbbreviationExample().bindText("list"))
    }

    @Test
    fun `the guides strict help suggestion is real`() {
        val strict = cli("tasks") {
            abbreviation = Abbreviation.None
            action<String>(human = { it }) { Ok("ran") }
        }
        assertEquals(
            CliError.UnknownOption("--h", "--help"),
            strict.parse(listOf("--h")).assertError<CliError.UnknownOption>(),
        )
    }

    private enum class Interactive(val words: List<String>) {
        Never(listOf("never", "no", "none")),
        Once(listOf("once")),
        Always(listOf("always", "yes")),
    }

    @Test
    fun `the guides enum words resolve a prefix of one entry`() {
        val rm = cli("rm") {
            abbreviation = Abbreviation.Options
            val interactive = option(long = "interactive").enum<Interactive>(spellings = { it.words })
            action<String>(human = { it }) { Ok("${interactive()}") }
        }
        assertEquals("Never", rm.bindText("--interactive=n"))
        assertEquals("Always", rm.bindText("--interactive=yes"))
    }

    private enum class RootScope { All }

    @Test
    fun `the guides whole long and whole enum value refuse a prefix`() {
        val rm = cli("rm") {
            abbreviation = Abbreviation.Options
            val noPreserveRoot = flag(long = "no-preserve-root").spelledInFull()
            val preserveRoot = option(long = "preserve-root").enum<RootScope>(abbreviates = false).optionalValue()
            val verbose = flag(long = "verbose")
            argument("file")
            action<String>(human = { it }) { Ok("${noPreserveRoot()} ${preserveRoot()} ${verbose()}") }
        }
        val unknown = RecordingTerminal()
        rm.run(arrayOf("--no-p", "d"), unknown)
        assertContains(unknown.err, "error: unknown option '--no-p'")
        val prefix = RecordingTerminal()
        rm.run(arrayOf("--preserve-root=al", "d"), prefix)
        assertContains(
            prefix.err,
            "error: invalid value 'al' for --preserve-root (choose from all). Did you mean all?\n",
        )
        assertEquals("false Given(value=All) false", rm.bindText("--pre=all", "d"))
        assertEquals("false null true", rm.bindText("--verb", "d"))
    }

    // --- "Inputs and converters": the two rendered error shapes ---

    @Test
    fun `the guides rendered value errors name the dashed option`() {
        val tree = cli("app") {
            val port = option(long = "port").int().validate("must be 1..65535") { it in 1..65535 }
            val level = option(long = "level").choice("debug", "info", "warn", "error")
            action { Ok("${port()} ${level()}") }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("--port", "70000"), t)
        assertEquals("error: invalid value '70000' for --port: must be 1..65535\n", t.err)

        val t2 = RecordingTerminal()
        tree.run(arrayOf("--level", "bogus"), t2)
        assertEquals(
            "error: invalid value 'bogus' for --level (choose from debug, info, warn, error)\n",
            t2.err,
        )
    }

    // --- "Help output": group returns its block's value, so a plain val captures the handle ---

    @Test
    fun `the guides group snippet captures a handle by plain val`() {
        val tree = cli("deploy") {
            val host = group("Networking") {
                option(short = 'H', long = "host", help = "target host").required()
            }
            action { Ok("shipped to ${host()}") }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("--host", "example.com"), t)
        assertEquals("shipped to example.com\n", t.out)
    }

    @Test
    fun `the guides multi handle group snippet infers each type`() {
        val tree = cli("build") {
            val jobs: Opt<Int>
            val tags: Opt<List<String>>
            group("Tuning") {
                jobs = option(short = 'j', long = "jobs", help = "parallelism").int().default(1)
                tags = option(short = 't', long = "tag", help = "labels").multiple()
            }
            action { Ok("jobs=${jobs()} tags=${tags()}") }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("-j", "4", "-t", "a", "-t", "b"), t)
        assertEquals("jobs=4 tags=[a, b]\n", t.out)
    }

    // --- "Inputs and converters": the declaration-order rules the guide states as prose ---

    @Test
    fun `the guides check order is the order a value is judged in`() {
        // The guide's two chains: a check before .int() reads the text, a range after it reads the number.
        val textChecked = cli("app") {
            val n = argument("n").validate("must not be blank") { it.isNotBlank() }.int()
            action { Ok("${n()}") }
        }
        assertEquals("must not be blank", textChecked.parse(listOf("")).assertError<CliError.BadValue>().reason)

        val numberChecked = cli("app") {
            val n = argument("n").int().range(1..10)
            action { Ok("${n()}") }
        }
        val t = RecordingTerminal()
        numberChecked.run(arrayOf("7"), t)
        assertEquals("7\n", t.out)
        assertEquals("must be in 1..10", numberChecked.parse(listOf("11")).assertError<CliError.BadValue>().reason)
    }

    @Test
    fun `the guides aliased handle rule is what the builder enforces`() {
        val thrown = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                val n = argument("n")
                n.int()
                n.long()
                action { Ok("") }
            }
        }
        assertContains(thrown.message.orEmpty(), "cannot add another converter")
    }

    @Test
    fun `the guides default row is true for a choice set in both orders`() {
        // The row claims a choice set is checked whichever side the default is declared on.
        for (build in listOf<() -> Unit>(
            { cli("app") { argument("m").choice("fast", "slow").default("bogus"); action { Ok("") } } },
            { cli("app") { argument("m").default("bogus").choice("fast", "slow"); action { Ok("") } } },
        )) {
            val thrown = assertFailsWith<IllegalArgumentException> { build() }
            assertContains(thrown.message.orEmpty(), "not one of fast, slow")
        }
    }
}

// --- "Converters and validators": the guide's typed-ConversionError snippet, transcribed ---

private sealed interface PortError : ResultError {
    data class NotANumber(val given: String) : PortError

    data class OutOfRange(val given: Int) : PortError
}

private fun guidePortCli() = cli("net") {
    val port = option(long = "port").convert { raw ->
        val n = raw.toIntOrNull()
            ?: return@convert Err(ConversionError.Domain(PortError.NotANumber(raw), "'$raw' is not a number"))
        if (n in 1..65535) Ok(n)
        else Err(ConversionError.Domain(PortError.OutOfRange(n), "$n is outside 1..65535"))
    }
    action { Ok("port ${port()}") }
}

class GuideConversionErrorSnippetTest {

    @Test
    fun `the guides typed converter snippet keeps the callers case and its words`() {
        val bad = guidePortCli().parse(listOf("--port", "70000")).assertError<CliError.BadValue>()
        assertEquals(PortError.OutOfRange(70000), assertIs<ConversionError.Domain>(bad.cause).error)
        assertEquals("70000 is outside 1..65535", bad.reason)

        val nan = guidePortCli().parse(listOf("--port", "http")).assertError<CliError.BadValue>()
        assertEquals(
            PortError.NotANumber("http"),
            assertIs<ConversionError.Domain>(nan.cause).error,
        )
    }

    @Test
    fun `a builtin converter reports its own case rather than a domain error`() {
        val tree = cli("lvl") {
            option(long = "level").int()
            action { Ok("ok") }
        }
        val bad = tree.parse(listOf("--level", "x")).assertError<CliError.BadValue>()
        assertEquals(ConversionError.NotAnInteger, bad.cause)
        assertEquals("not an integer", bad.reason)
    }
}

// --- "Cross-input constraints": the validateInputs snippets, transcribed ---

/**
 * Stands in for the guide snippet's date helpers. Only the ordering the snippet relies on is modelled, and
 * against a fixed day rather than the clock, so the transcription cannot start failing on a date.
 */
private const val GUIDE_TODAY = "2026-08-15"

private fun String.looksLikeDate(): Boolean = Regex("""\d{4}-\d{2}-\d{2}""").matches(this)

private fun String.isPast(): Boolean = this < GUIDE_TODAY

private fun guideValidateInputsCli() = cli("app") {
    command("add") {
        val title = argument("title")
        val due = option(short = 'd', long = "due").validate("must be YYYY-MM-DD") { it.looksLikeDate() }
        val done = flag(short = 'D', long = "done")

        validateInputs {
            val d = due()
            if (d != null && !done() && d.isPast()) {
                CliError.Usage("--due $d is earlier than today; pass --done")
            } else {
                null
            }
        }

        action { Ok("added ${title()}") }
    }
}

class GuideValidateInputsSnippetTest {

    @Test
    fun `the guides validateInputs snippet rejects a past due date only without --done`() {
        val refused = RecordingTerminal()
        assertEquals(
            USAGE_ERROR_EXIT,
            guideValidateInputsCli().run(arrayOf("add", "Ship it", "--due", "2020-01-01"), refused),
        )
        // The message the guide prints under the snippet, verbatim.
        assertEquals("error: --due 2020-01-01 is earlier than today; pass --done\n", refused.err)

        val excused = RecordingTerminal()
        assertEquals(
            0,
            guideValidateInputsCli().run(arrayOf("add", "Ship it", "--due", "2020-01-01", "--done"), excused),
        )
        assertEquals("added Ship it\n", excused.out)
    }

    @Test
    fun `a per-value validate still reports ahead of the deferred block`() {
        // The guide's "prefer .validate whenever a value can be judged alone": a malformed date is caught
        // during conversion, so --done never gets the chance to excuse it.
        val t = RecordingTerminal()
        guideValidateInputsCli().run(arrayOf("add", "x", "--due", "nope", "--done"), t)
        assertEquals("error: invalid value 'nope' for --due: must be YYYY-MM-DD\n", t.err)
    }

    @Test
    fun `the guides Input name idiom renders like klaps own value errors`() {
        val tree = cli("app") {
            val tag = option(long = "tag")
            validateInputs {
                val typed = tag() ?: return@validateInputs null
                if (typed == "known") null else CliError.BadValue(tag.name, typed, "no such tag")
            }
            action { Ok("ok") }
        }
        val t = RecordingTerminal()
        assertEquals(USAGE_ERROR_EXIT, tree.run(arrayOf("--tag", "bogus"), t))
        // Indistinguishable from a `.validate` rejection, which is the point of building it off `tag.name`.
        assertEquals("error: invalid value 'bogus' for --tag: no such tag\n", t.err)
    }

    @Test
    fun `several blocks run in declaration order and report the first failure`() {
        val tree = cli("app") {
            validateInputs { CliError.Usage("first") }
            validateInputs { CliError.Usage("second") }
            action { Ok("unreachable") }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf<String>(), t)
        assertEquals("error: first\n", t.err)
    }

    @Test
    fun `a block never runs for --help or --version`() {
        val tree = cli("app") {
            version = Version.Of("1.0.0")
            validateInputs { CliError.Usage("must not reach this") }
            action { Ok("ran") }
        }
        for (builtin in listOf("--help", "--version")) {
            val t = RecordingTerminal()
            assertEquals(0, tree.run(arrayOf(builtin), t), builtin)
            assertEquals("", t.err, builtin)
        }
    }

    @Test
    fun `a root block does not run when a subcommand does`() {
        val tree = cli("app") {
            validateInputs { CliError.Usage("root rule") }
            command("sub") { action { Ok("sub ran") } }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(arrayOf("sub"), t))
        assertEquals("sub ran\n", t.out)
    }

    @Test
    fun `the endsOptions snippet reads options until the remote command`() {
        val ssh = cli("ssh") {
            val port = option(short = 'p', long = "port")
            argument("destination")
            val command = argument("command").multiple().endsOptions()
            action<String>(human = { it }) { Ok("port=${port()} command=${command()}") }
        }
        assertEquals("port=2222 command=[]", ssh.bindText("web1", "-p", "2222"))
        assertEquals("port=null command=[ls, -p, 2222]", ssh.bindText("web1", "ls", "-p", "2222"))
        assertEquals("port=null command=[-p, 2222]", ssh.bindText("web1", "--", "-p", "2222"))
    }
}

// --- "Actions that print their own output": the action writes its human half through the run's terminal ---

@Serializable
data class Summary(val ticks: Int)

class ActionsThatPrintSnippetTest {

    private val app = cli("app") {
        command("run") {
            action(human = { "ticked ${it.ticks} times" }) {
                val ticks = 3
                repeat(ticks) { tick ->
                    if (!json) terminal.out(yellow("tick $tick") + "\n") // the human half: suppressed under --json
                }
                Ok(Summary(ticks))                                     // the value: rendered by human, or serialized
            }
        }
    }

    private fun output(vararg argv: String): String =
        RecordingTerminal(ansi = true).also { app.run(arrayOf(*argv), it) }.out

    @Test
    fun `the guides ticking action colours its own lines ahead of the returned value`() {
        val esc = Char(27)
        val ticks = (0..2).joinToString("") { "$esc[33mtick $it$esc[0m\n" }
        assertEquals("${ticks}ticked 3 times\n", output("run"))
        assertEquals("tick 0\ntick 1\ntick 2\nticked 3 times\n", output("--color=never", "run"))
        assertEquals("{\"ticks\":3}\n", output("--json", "run"))
    }
}

// --- "Color output": a formatting helper returns Styled, which klap colors only when color is on ---

private fun warn(text: String): Styled = (bold + yellow)("warning: $text")

class ColorOutputSnippetTest {

    private val app = cli("app") {
        command("build") {
            action { Ok(warn("no targets matched")) }
        }
    }

    private fun output(vararg argv: String): String =
        RecordingTerminal(ansi = true).also { app.run(arrayOf(*argv), it) }.out

    @Test
    fun `the guides warn helper is colored when color is on and plain when it is off`() {
        val esc = Char(27)
        assertEquals("$esc[1;33mwarning: no targets matched$esc[0m\n", output("build"))
        assertEquals("warning: no targets matched\n", output("--color=never", "build"))
        assertEquals("\"warning: no targets matched\"\n", output("--json", "build"))
    }
}
