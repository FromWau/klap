package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.klap.internal.render.completeCandidates
import com.fromwau.klap.internal.render.helpText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `.optionalValue()` with no argument: a bare occurrence names no value (`cp --backup` takes its control from
 * `VERSION_CONTROL`, `cp --context` is "like -Z"), so it binds [OptionalValue.Bare] and an attached value binds
 * [OptionalValue.Given].
 */
class BareOptionalValueTest {

    private data class Bound(val backup: OptionalValue<String>?, val sources: List<String>)

    private fun cp() = cliOf("cp") {
        val backup = option(long = "backup", help = "make a backup").placeholder("CONTROL").optionalValue()
        val sources = argument("source").multiple()
        action { Ok("") }
        projection { Bound(backup(), sources()) }
    }

    private fun TypedCli<Bound>.bound(vararg argv: String): Bound? = parse(argv.toList()).assertSuccess()

    @Test
    fun `a bare occurrence binds Bare`() {
        assertEquals(Bound(OptionalValue.Bare, listOf("a")), cp().bound("--backup", "a"))
    }

    @Test
    fun `an attached value binds Given`() {
        assertEquals(Bound(OptionalValue.Given("t"), listOf("a")), cp().bound("--backup=t", "a"))
    }

    @Test
    fun `an empty attached value is given and empty`() {
        assertEquals(Bound(OptionalValue.Given(""), listOf("a")), cp().bound("--backup=", "a"))
    }

    @Test
    fun `an absent option binds null`() {
        assertEquals(Bound(null, listOf("a")), cp().bound("a"))
    }

    @Test
    fun `the next word stays an operand`() {
        assertEquals(Bound(OptionalValue.Bare, listOf("t", "a")), cp().bound("--backup", "t", "a"))
    }

    @Test
    fun `a short binds bare alone and given when glued`() {
        val tree = cliOf("git") {
            val verbose = flag(short = 'v')
            val sign = option(short = 'S').optionalValue()
            action { Ok("") }
            projection { verbose() to sign() }
        }
        assertEquals(false to OptionalValue.Bare, tree.parse(listOf("-S")).assertSuccess())
        assertEquals(true to OptionalValue.Bare, tree.parse(listOf("-vS")).assertSuccess())
        assertEquals(false to OptionalValue.Given("abc"), tree.parse(listOf("-Sabc")).assertSuccess())
    }

    @Test
    fun `a converter declared before it converts the attached value`() {
        val tree = cliOf("app") {
            val depth = option(long = "depth").int().optionalValue()
            action { Ok("") }
            projection { depth() }
        }
        assertEquals(OptionalValue.Given(5), tree.parse(listOf("--depth=5")).assertSuccess())
        assertEquals(OptionalValue.Bare, tree.parse(listOf("--depth")).assertSuccess())
        assertEquals(
            CliError.BadValue("--depth", "abc", "not an integer", ConversionError.NotAnInteger),
            tree.parse(listOf("--depth=abc")).assertError<CliError.BadValue>(),
        )
    }

    @Test
    fun `choices declared before it judge the attached value`() {
        val tree = cliOf("app") {
            abbreviation = Abbreviation.Options
            val mode = option(long = "mode").choice("fast", "slow").optionalValue()
            action { Ok("") }
            projection { mode() }
        }
        assertEquals(OptionalValue.Given("slow"), tree.parse(listOf("--mode=sl")).assertSuccess())
        assertEquals(OptionalValue.Bare, tree.parse(listOf("--mode")).assertSuccess())
        tree.parse(listOf("--mode=bogus")).assertError<CliError.InvalidChoice>()
    }

    @Test
    fun `a validation declared after it sees the given value`() {
        val tree = cliOf("app") {
            val tag = option(long = "tag")
                .optionalValue()
                .validate("too long") { it !is OptionalValue.Given || it.value.length < 4 }
            action { Ok("") }
            projection { tag() }
        }
        assertEquals(OptionalValue.Given("abc"), tree.parse(listOf("--tag=abc")).assertSuccess())
        assertEquals(OptionalValue.Bare, tree.parse(listOf("--tag")).assertSuccess())
        assertEquals("too long", tree.parse(listOf("--tag=abcd")).assertError<CliError.BadValue>().reason)
    }

    @Test
    fun `with multiple every occurrence binds in line order`() {
        val tree = cliOf("cp") {
            val context = option(long = "context").optionalValue().multiple()
            action { Ok("") }
            projection { context() }
        }
        assertEquals(
            listOf(OptionalValue.Bare, OptionalValue.Given("x"), OptionalValue.Bare),
            tree.parse(listOf("--context", "--context=x", "--context")).assertSuccess(),
        )
    }

    @Test
    fun `a global binds bare before and after its subcommand`() {
        val tree = cliOf("git") {
            val execPath = globalOption(short = 'e', long = "exec-path").optionalValue()
            command("log") {
                action { Ok("") }
                projection { execPath() }
            }
        }
        assertEquals(OptionalValue.Bare, tree.parse(listOf("--exec-path", "log")).assertSuccess())
        assertEquals(OptionalValue.Bare, tree.parse(listOf("log", "--exec-path")).assertSuccess())
        assertEquals(OptionalValue.Bare, tree.parse(listOf("-e", "log")).assertSuccess())
        assertEquals(OptionalValue.Given("/opt"), tree.parse(listOf("--exec-path=/opt", "log")).assertSuccess())
    }

    @Test
    fun `help shows the optional value with no bare hint`() {
        val help = cp().cli.helpText()
        val row = help.lines().single { "--backup" in it }
        assertTrue("--backup[=<CONTROL>]" in row, help)
        assertFalse("bare:" in row, help)
    }

    @Test
    fun `the word after a bare occurrence completes as an operand`() {
        val tree = cli("cp") {
            option(long = "backup").choice("none", "numbered").optionalValue()
            argument("source").multiple().completeWith { candidate("SOURCE") }
            action { Ok("") }
        }
        assertEquals(listOf("SOURCE"), tree.completeCandidates(listOf("--backup", "")).map { it.value })
        assertEquals(
            listOf("--backup=none", "--backup=numbered"),
            tree.completeCandidates(listOf("--backup=")).map { it.value },
        )
    }

    @Test
    fun `it cannot be combined with a bare value`() {
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                option(long = "color").optionalValue("always").optionalValue()
                action { Ok("") }
            }
        }
    }
}
