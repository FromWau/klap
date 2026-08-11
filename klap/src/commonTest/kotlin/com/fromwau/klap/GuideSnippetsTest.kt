package com.fromwau.klap

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The guide's abbreviation snippets, transcribed and executed. `docs/guide.md`'s abbreviation section makes
 * specific claims about what each `Abbreviation` mode resolves and what a miss suggests; these pin those
 * claims against the real parser so a change that breaks one shows up here, not only in prose.
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
