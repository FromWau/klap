package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.klap.internal.render.helpText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `.gluedValue()`: the value is glued to the short (`-O3`) or attached to the long with `=` (`--optimise=3`), and
 * the option without one is refused, as `find -O` is ("must be immediately followed by a decimal integer").
 */
class GluedValueTest {

    private data class Bound(val verbose: Boolean, val level: Int?, val files: List<String>)

    private fun cc(shortOptions: ShortOptions = ShortOptions.Clustered) = cliOf("cc") {
        this.shortOptions = shortOptions
        val verbose = flag(short = 'v', help = "be loud")
        val level = option(short = 'O', long = "optimise", help = "optimisation level")
            .placeholder("LEVEL")
            .gluedValue()
            .map { it.toInt() }
            .completeWith { candidates(listOf("0", "1", "2", "3")) }
        val files = argument("file").multiple().completeWith { candidate("FILE") }
        action { Ok("") }
        projection { Bound(verbose(), level(), files()) }
    }

    private fun TypedCli<Bound>.bound(vararg argv: String): Bound? = parse(argv.toList()).assertSuccess()

    private fun TypedCli<Bound>.missing(vararg argv: String): CliError.MissingOptionValue =
        parse(argv.toList()).assertError<CliError.MissingOptionValue>()

    private fun TypedCli<Bound>.offered(vararg words: String): List<String> = cli.offered(*words)

    @Test
    fun `a value glued to the short binds`() {
        assertEquals(Bound(false, 3, listOf("a")), cc().bound("-O3", "a"))
    }

    @Test
    fun `a value attached to the long with an equals sign binds`() {
        assertEquals(Bound(false, 3, listOf("a")), cc().bound("--optimise=3", "a"))
    }

    @Test
    fun `an absent option binds null`() {
        assertEquals(Bound(false, null, listOf("a")), cc().bound("a"))
    }

    @Test
    fun `a bare short is refused as a missing value`() {
        // A short's refusal names its letter, as getopt_long does: `ls -I` is "option requires an argument -- 'I'".
        assertEquals(CliError.MissingOptionValue("-O"), cc().missing("-O"))
    }

    @Test
    fun `a bare long is refused as a missing value`() {
        assertEquals(CliError.MissingOptionValue("--optimise"), cc().missing("--optimise"))
    }

    @Test
    fun `the next word is no value of the short`() {
        assertEquals(CliError.MissingOptionValue("-O"), cc().missing("-O", "3"))
    }

    @Test
    fun `the next word is no value of the long`() {
        assertEquals(CliError.MissingOptionValue("--optimise"), cc().missing("--optimise", "3"))
    }

    @Test
    fun `a glued value ends a cluster`() {
        assertEquals(Bound(true, 2, emptyList()), cc().bound("-vO2"))
    }

    @Test
    fun `a cluster ending in the bare short is refused`() {
        assertEquals(CliError.MissingOptionValue("-O"), cc().missing("-vO", "2"))
    }

    @Test
    fun `a glued value binds when shorts stand alone`() {
        assertEquals(Bound(false, 3, emptyList()), cc(ShortOptions.Alone).bound("-O3"))
    }

    @Test
    fun `a bare short is refused when shorts stand alone`() {
        assertEquals(CliError.MissingOptionValue("-O"), cc(ShortOptions.Alone).missing("-O", "3"))
    }

    @Test
    fun `with multiple every glued occurrence binds in line order`() {
        val tree = cliOf("find") {
            val levels = option(short = 'O').gluedValue().map { it.toInt() }.multiple()
            action { Ok("") }
            projection { levels() }
        }
        assertEquals(listOf(1, 3), tree.parse(listOf("-O1", "-O3")).assertSuccess())
        assertEquals(
            CliError.MissingOptionValue("-O"),
            tree.parse(listOf("-O1", "-O")).assertError<CliError.MissingOptionValue>(),
        )
    }

    @Test
    fun `a global binds glued before and after its subcommand`() {
        val tree = cliOf("tool") {
            val level = globalOption(short = 'O').gluedValue()
            command("build") {
                action { Ok("") }
                projection { level() }
            }
        }
        assertEquals("2", tree.parse(listOf("-O2", "build")).assertSuccess())
        assertEquals("2", tree.parse(listOf("build", "-O2")).assertSuccess())
        tree.parse(listOf("-O", "build")).assertError<CliError.MissingOptionValue>()
    }

    @Test
    fun `the refusal renders as a missing value`() {
        val t = RecordingTerminal()
        assertEquals(2, cc().cli.run(arrayOf("-O"), t))
        assertTrue("option -O requires a value" in t.err.toString(), t.err.toString())
    }

    @Test
    fun `the refusal renders as json`() {
        val t = RecordingTerminal()
        cc().cli.run(arrayOf("--optimise", "--json"), t)
        assertEquals(
            """{"error":{"type":"MissingOptionValue","option":"--optimise"},"code":2}""" + "\n",
            t.err.toString(),
        )
    }

    @Test
    fun `help shows the value glued to the long`() {
        val help = cc().cli.helpText()
        assertTrue("-O, --optimise=LEVEL" in help, help)
    }

    @Test
    fun `help shows the value glued to a short alone`() {
        val tree = cli("find") {
            option(short = 'O', help = "optimisation level").placeholder("LEVEL").gluedValue()
            action { Ok("") }
        }
        val help = tree.helpText()
        assertTrue("-OLEVEL" in help, help)
        assertTrue("-O LEVEL" !in help, help)
    }

    @Test
    fun `the usage line shows a required glued value`() {
        val tree = cli("cc") {
            option(long = "level").placeholder("N").gluedValue().required()
            action { Ok("") }
        }
        assertTrue("usage: cc --level=N" in tree.helpText(), tree.helpText())
    }

    @Test
    fun `the bare short offers its candidates glued`() {
        assertEquals((0..3).map { "-O$it" }, cc().offered("-O"))
    }

    @Test
    fun `a cluster ending in the short offers its candidates glued`() {
        assertEquals((0..3).map { "-vO$it" }, cc().offered("-vO"))
    }

    @Test
    fun `the long offers its candidates attached`() {
        assertEquals((0..3).map { "--optimise=$it" }, cc().offered("--optimise="))
    }

    @Test
    fun `nothing is offered after a bare short`() {
        assertEquals(emptyList(), cc().offered("-O", ""))
    }

    @Test
    fun `nothing is offered after a bare long`() {
        assertEquals(emptyList(), cc().offered("--optimise", ""))
    }

    @Test
    fun `nothing is offered after a cluster ending in the bare short`() {
        assertEquals(emptyList(), cc().offered("-vO", ""))
    }

    @Test
    fun `a short alone offers its candidates glued and nothing after it bare`() {
        assertEquals((0..3).map { "-O$it" }, cc(ShortOptions.Alone).offered("-O"))
        assertEquals(emptyList(), cc(ShortOptions.Alone).offered("-O", ""))
    }

    @Test
    fun `the word after a glued value completes as an operand`() {
        assertEquals(listOf("FILE"), cc().offered("-O3", ""))
    }

    @Test
    fun `it cannot be combined with an optional value`() {
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                option(long = "level").optionalValue().gluedValue()
                action { Ok("") }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                option(long = "level").gluedValue().optionalValue("1")
                action { Ok("") }
            }
        }
    }
}
