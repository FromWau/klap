package com.fromwau.klap.snippets

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Cli
import com.fromwau.klap.CliError
import com.fromwau.klap.CommandBuilder
import com.fromwau.klap.ConversionError
import com.fromwau.klap.Invocation
import com.fromwau.klap.Opt
import com.fromwau.klap.RecordingTerminal
import com.fromwau.klap.USAGE_ERROR_EXIT
import com.fromwau.klap.ValueScope
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
            action<String>(human = { it }) { Ok("ran") }
        }
        assertEquals(
            CliError.UnknownOption("--h", "--help"),
            assertIs<Result.Error<CliError>>(strict.parse(listOf("--h"))).error,
        )
    }

    // --- "Inputs and converters": the two rendered error shapes ---

    @Test
    fun `the guides rendered value errors name the dashed option`() {
        val tree = cli("app") {
            val port = option("--port").int().validate("must be 1..65535") { it in 1..65535 }
            val level = option("--level").choice("debug", "info", "warn", "error")
            action { Ok("${port()} ${level()}") }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("--port", "70000"), t)
        assertEquals("error: invalid value '70000' for --port: must be 1..65535\n", t.err.toString())

        val t2 = RecordingTerminal()
        tree.run(arrayOf("--level", "bogus"), t2)
        assertEquals(
            "error: invalid value 'bogus' for --level (choose from debug, info, warn, error)\n",
            t2.err.toString(),
        )
    }

    // --- "Help output": group returns its block's value, so a plain val captures the handle ---

    @Test
    fun `the guides group snippet captures a handle by plain val`() {
        val tree = cli("deploy") {
            val host = group("Networking") {
                option("--host", "-H", help = "target host").required()
            }
            action { Ok("shipped to ${host()}") }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("--host", "example.com"), t)
        assertEquals("shipped to example.com\n", t.out.toString())
    }

    @Test
    fun `the guides multi handle group snippet infers each type`() {
        val tree = cli("build") {
            val jobs: Opt<Int>
            val tags: Opt<List<String>>
            group("Tuning") {
                jobs = option("--jobs", "-j", help = "parallelism").int().default(1)
                tags = option("--tag", "-t", help = "labels").multiple()
            }
            action { Ok("jobs=${jobs()} tags=${tags()}") }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("-j", "4", "-t", "a", "-t", "b"), t)
        assertEquals("jobs=4 tags=[a, b]\n", t.out.toString())
    }

    // --- "Inputs and converters": the declaration-order rules the guide states as prose ---

    @Test
    fun `the guides converter order rule is what the builder enforces`() {
        // The guide's example of the wrong order, and of the right one it tells you to write instead.
        val wrongOrder = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                argument("n").validate("must not be blank") { it.isNotBlank() }.int()
                action { Ok("") }
            }
        }
        assertContains(wrongOrder.message.orEmpty(), "after every type-changing converter")

        val rightOrder = cli("app") {
            val n = argument("n").int().range(1..10)
            action { Ok("${n()}") }
        }
        val t = RecordingTerminal()
        rightOrder.run(arrayOf("7"), t)
        assertEquals("7\n", t.out.toString())
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

private sealed interface PortError : IError {
    data class NotANumber(val given: String) : PortError

    data class OutOfRange(val given: Int) : PortError
}

private fun guidePortCli() = cli("net") {
    val port = option("--port").convert { raw ->
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
        val err = assertIs<Result.Error<CliError>>(guidePortCli().parse(listOf("--port", "70000"))).error
        val bad = assertIs<CliError.BadValue>(err)
        assertEquals(PortError.OutOfRange(70000), assertIs<ConversionError.Domain>(bad.cause).error)
        assertEquals("70000 is outside 1..65535", bad.reason)

        val nan = assertIs<Result.Error<CliError>>(guidePortCli().parse(listOf("--port", "http"))).error
        assertEquals(
            PortError.NotANumber("http"),
            assertIs<ConversionError.Domain>(assertIs<CliError.BadValue>(nan).cause).error,
        )
    }

    @Test
    fun `a builtin converter reports its own case rather than a domain error`() {
        val tree = cli("lvl") {
            option("--level").int()
            action { Ok("ok") }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--level", "x"))).error
        val bad = assertIs<CliError.BadValue>(err)
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
        val due = option("--due", "-d").validate("must be YYYY-MM-DD") { it.looksLikeDate() }
        val done = flag("--done", "-D")

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
        assertEquals("error: --due 2020-01-01 is earlier than today; pass --done\n", refused.err.toString())

        val excused = RecordingTerminal()
        assertEquals(
            0,
            guideValidateInputsCli().run(arrayOf("add", "Ship it", "--due", "2020-01-01", "--done"), excused),
        )
        assertEquals("added Ship it\n", excused.out.toString())
    }

    @Test
    fun `a per-value validate still reports ahead of the deferred block`() {
        // The guide's "prefer .validate whenever a value can be judged alone": a malformed date is caught
        // during conversion, so --done never gets the chance to excuse it.
        val t = RecordingTerminal()
        guideValidateInputsCli().run(arrayOf("add", "x", "--due", "nope", "--done"), t)
        assertEquals("error: invalid value 'nope' for --due: must be YYYY-MM-DD\n", t.err.toString())
    }

    @Test
    fun `the guides Input name idiom renders like klaps own value errors`() {
        val tree = cli("app") {
            val tag = option("--tag")
            validateInputs {
                val typed = tag() ?: return@validateInputs null
                if (typed == "known") null else CliError.BadValue(tag.name, typed, "no such tag")
            }
            action { Ok("ok") }
        }
        val t = RecordingTerminal()
        assertEquals(USAGE_ERROR_EXIT, tree.run(arrayOf("--tag", "bogus"), t))
        // Indistinguishable from a `.validate` rejection, which is the point of building it off `tag.name`.
        assertEquals("error: invalid value 'bogus' for --tag: no such tag\n", t.err.toString())
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
        assertEquals("error: first\n", t.err.toString())
    }

    @Test
    fun `a block never runs for --help or --version`() {
        val tree = cli("app") {
            version = "1.0.0"
            validateInputs { CliError.Usage("must not reach this") }
            action { Ok("ran") }
        }
        for (builtin in listOf("--help", "--version")) {
            val t = RecordingTerminal()
            assertEquals(0, tree.run(arrayOf(builtin), t), builtin)
            assertEquals("", t.err.toString(), builtin)
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
        assertEquals("sub ran\n", t.out.toString())
    }
}
