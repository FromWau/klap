package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The K7 rows of the testing matrix that mix klap's kinds of letters in one short cluster: a global, the
 * command's own flags, a value-taking short, and a parent's own flag that a subcommand does not have. Each row
 * is asserted as a parse (what the cluster binds, or its refusal) and as completion (what Tab offers there).
 */
class ClusterMatrixTest {

    private lateinit var verbose: Flag
    private lateinit var store: Opt<String?>
    private lateinit var reverse: Flag
    private lateinit var long: Flag
    private lateinit var limit: Opt<String?>

    private val tasks = cli("tasks") {
        verbose = globalFlag(short = 'v', long = "verbose", help = "log everything")
        store = globalOption(short = 'f', long = "store", help = "store file")
        flag(short = 'X', long = "root-only", help = "the root's own flag")
        command("list") {
            reverse = flag(short = 'r', long = "reverse", help = "newest first")
            long = flag(short = 'l', long = "long", help = "show details")
            limit = option(short = 'n', long = "limit", help = "at most this many")
            action { Ok("verbose=${verbose()} store=${store()} reverse=${reverse()} long=${long()} limit=${limit()}") }
        }
        action { Ok("root") }
    }

    private fun bound(vararg argv: String): String = tasks.bindText(*argv)

    private fun offered(vararg words: String): List<String> = tasks.offered(*words)

    @Test
    fun `K7k a global letter beside the command's own letters binds both in either order`() {
        val expected = "verbose=true store=null reverse=true long=false limit=null"
        assertEquals(expected, bound("list", "-vr"))
        assertEquals(expected, bound("list", "-rv"))
        assertTrue("-vr" in offered("list", "-v"), "a global's cluster continues with the command's own letters")
        assertTrue("-rv" in offered("list", "-r"), "and the command's own with the globals")
    }

    @Test
    fun `K7l a cluster of only global letters before the subcommand routes on`() {
        assertEquals("verbose=true store=x.json reverse=false long=false limit=null", bound("-vf", "x.json", "list"))
        assertTrue("list" in offered("-v", "l"), "the subcommand is still offered after an all-global cluster")
    }

    @Test
    fun `K7m a global value-taking short ends a cluster with its value glued or spaced`() {
        val expected = "verbose=true store=x.json reverse=false long=false limit=null"
        assertEquals(expected, bound("-vfx.json", "list"))
        assertEquals(expected, bound("-vf", "x.json", "list"))
        assertEquals(emptyList(), offered("-vf", ""), "the word after -vf is the store's value, no name")
    }

    @Test
    fun `K7n a global value-taking short after the command's own letters takes the rest`() {
        val expected = "verbose=false store=x.json reverse=true long=true limit=null"
        assertEquals(expected, bound("list", "-rlfx.json"))
        assertEquals(expected, bound("list", "-rlf", "x.json"))
        assertEquals(emptyList(), offered("list", "-rlf", ""), "the word after -rlf is the store's value")
    }

    @Test
    fun `K7h a command's value-taking short in the middle of a cluster takes the rest of it as its value`() {
        assertEquals("verbose=false store=null reverse=true long=false limit=vl", bound("list", "-rnvl"))
        assertEquals(emptyList(), offered("list", "-rn"), "nothing follows a value-taking letter")
    }

    @Test
    fun `K7ag a parent's own letter in a subcommand's cluster names nothing there`() {
        val unknown = CliError.UnknownOption("-X", cluster = "-Xr")
        assertEquals(unknown, tasks.parse(listOf("list", "-Xr")).assertError(), "-X is the root's own, not list's")
        assertEquals(unknown.copy(cluster = "-rX"), tasks.parse(listOf("list", "-rX")).assertError())
        assertEquals(emptyList(), offered("list", "-X"), "nothing continues a letter list does not have")
        assertTrue("-rX" !in offered("list", "-r"), "and no cluster offers it")
    }
}
