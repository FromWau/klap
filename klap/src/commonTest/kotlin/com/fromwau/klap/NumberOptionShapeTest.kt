package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A `sample -<NUM>` whose count is read only as the command's first word, as GNU head reads its own. */
private fun firstOnly(): Cli = cli("sample") {
    val count = numberOption(firstWordOnly = true)
    val verbose = flag(short = 'v')
    val name = option(short = 'n')
    val files = argument("file").multiple(min = 0)
    action { Ok("count=${count()} v=${verbose()} n=${name()} files=${files()}") }
}

/**
 * A `sample -<NUM>[kx]` whose count keeps the letters k and x after its digits, as head's `-3b` keeps its unit; `-k`
 * is also a value-taking short of its own, as head's `-c` is.
 */
private fun withSuffixes(shortOptions: ShortOptions = ShortOptions.Clustered): Cli = cli("sample") {
    this.shortOptions = shortOptions
    val count = numberOption(suffixes = "kx")
    val quiet = flag(short = 'q')
    val verbose = flag(short = 'v')
    val name = option(short = 'n').choice("one", "two")
    val keep = option(short = 'k')
    val files = argument("file").file().multiple(min = 0)
    action { Ok("count=${count()} q=${quiet()} v=${verbose()} n=${name()} k=${keep()} files=${files()}") }
}

private fun Cli.unknown(vararg argv: String): CliError.UnknownOption =
    parse(argv.toList()).assertError<CliError.UnknownOption>()

class NumberOptionFirstWordOnlyTest {

    @Test
    fun `a run in the first word binds the number`() {
        assertEquals("count=5 v=false n=null files=[f]", firstOnly().bindText("-5", "f"))
        assertEquals("count=5 v=true n=null files=[f]", firstOnly().bindText("-5v", "f"))
    }

    @Test
    fun `a run in a later word is an unknown option`() {
        assertEquals(CliError.UnknownOption("-5"), firstOnly().unknown("-v", "-5", "f"))
        assertEquals(CliError.UnknownOption("-5"), firstOnly().unknown("f", "-5"))
        assertEquals(CliError.UnknownOption("-1"), firstOnly().unknown("-5", "-1"))
        assertEquals(CliError.UnknownOption("-3"), firstOnly().unknown("-n", "x", "-3"))
    }

    @Test
    fun `a run after a letter of the first word is an unknown option`() {
        assertEquals(CliError.UnknownOption("-1", cluster = "-v12"), firstOnly().unknown("-v12", "f"))
    }

    @Test
    fun `a value word is never a run`() {
        assertEquals("count=null v=false n=-5 files=[]", firstOnly().bindText("-n", "-5"))
    }

    @Test
    fun `a subcommand reads its own first word`() {
        val tree = cli("tool") {
            command("log") {
                val count = numberOption(firstWordOnly = true)
                flag(short = 'p')
                action { Ok("count=${count()}") }
            }
        }
        assertEquals("count=3", tree.bindText("log", "-3"))
        assertEquals(CliError.UnknownOption("-3"), tree.unknown("log", "-p", "-3"))
    }

    @Test
    fun `Tab continues a run in the first word only`() {
        assertTrue("-5v" in firstOnly().offered("-5"), "${firstOnly().offered("-5")}")
        assertEquals(emptyList(), firstOnly().offered("-v", "-5"))
        assertEquals(emptyList(), firstOnly().offered("-v12"))
    }
}

class NumberOptionSuffixesTest {

    @Test
    fun `suffix letters after the digits stay in the value in the order typed`() {
        assertEquals("count=3k q=false v=false n=null k=null files=[f]", withSuffixes().bindText("-3k", "f"))
        assertEquals("count=3kx q=false v=false n=null k=null files=[f]", withSuffixes().bindText("-3kx", "f"))
        assertEquals("count=3xk q=false v=false n=null k=null files=[f]", withSuffixes().bindText("-3xk", "f"))
    }

    @Test
    fun `the other letters of the word are the cluster's flags`() {
        assertEquals("count=3 q=true v=true n=null k=null files=[f]", withSuffixes().bindText("-3vq", "f"))
        assertEquals("count=3k q=false v=true n=null k=null files=[f]", withSuffixes().bindText("-3kv", "f"))
        assertEquals("count=3k q=false v=true n=null k=null files=[f]", withSuffixes().bindText("-3vk", "f"))
    }

    @Test
    fun `a value taking short after the digits is refused`() {
        assertEquals(CliError.UnknownOption("-n", cluster = "-3n"), withSuffixes().unknown("-3n", "one"))
        assertEquals(CliError.UnknownOption("-n", cluster = "-3vn"), withSuffixes().unknown("-3vn", "one"))
    }

    @Test
    fun `a suffix letter without digits before it is its own short`() {
        assertEquals("count=null q=false v=false n=null k=5 files=[]", withSuffixes().bindText("-k", "5"))
        assertEquals("count=null q=false v=true n=null k=5 files=[]", withSuffixes().bindText("-vk5"))
    }

    @Test
    fun `a run with its suffixes is one word when shorts stand alone`() {
        val alone = withSuffixes(ShortOptions.Alone)
        assertEquals("count=3kx q=false v=false n=null k=null files=[]", alone.bindText("-3kx"))
        assertEquals(CliError.UnknownOption("-3v"), alone.unknown("-3v"))
    }

    @Test
    fun `a suffix must be a letter`() {
        assertFailsWith<IllegalArgumentException> {
            cli("sample") {
                numberOption(suffixes = "k1")
                action { Ok("") }
            }
        }
    }

    @Test
    fun `Tab after a run offers the suffixes and the flags but no value taking short`() {
        val offered = withSuffixes().offered("-2")
        listOf("-2k", "-2x", "-2q", "-2v").forEach { assertTrue(it in offered, "expected $it among $offered") }
        assertTrue("-2n" !in offered, "-2n is refused: $offered")
    }

    @Test
    fun `Tab after a run and a flag still offers the suffixes`() {
        val offered = withSuffixes().offered("-2v")
        listOf("-2vk", "-2vx", "-2vq").forEach { assertTrue(it in offered, "expected $it among $offered") }
        assertTrue("-2vn" !in offered && "-2vv" !in offered, "$offered")
    }

    @Test
    fun `the word after a run ending in a value taking short is an operand`() {
        assertEquals(listOf(COMPLETE_FILES), withSuffixes().offered("-5n", ""))
    }
}
