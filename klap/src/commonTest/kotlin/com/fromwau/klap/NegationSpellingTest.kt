package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.klap.internal.render.Candidate
import com.fromwau.klap.internal.render.completeCandidates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NegationSpellingTest {

    private fun cpLike() = cli("cp") {
        val dereference = flag(short = 'L', long = "dereference").negatable(short = 'P', long = "no-dereference")
        argument("file")
        action<String>(human = { it }) { Ok("deref=${dereference()}") }
    }

    @Test
    fun `a negation with its own help has a row and a description of its own`() {
        // pacman: "--noconfirm  do not ask for any confirmation" and "--confirm  always ask for confirmation".
        val tree = cli("pac") {
            val noConfirm = globalFlag(long = "noconfirm", help = "do not ask for any confirmation")
                .negatable(long = "confirm", default = false, help = "always ask for confirmation")
            action<String>(human = { it }) { Ok("noconfirm=${noConfirm()}") }
        }
        assertEquals("noconfirm=false", tree.bindText("--noconfirm", "--confirm"))
        assertEquals("noconfirm=true", tree.bindText("--confirm", "--noconfirm"))
        val help = RecordingTerminal().also { tree.run(arrayOf("--help"), it) }.out.toString()
        assertTrue(help.lines().any { it.trim().startsWith("--noconfirm") && "do not ask" in it }, help)
        assertTrue(help.lines().any { it.trim().startsWith("--confirm") && "always ask" in it }, help)
        val offered = tree.completeCandidates(listOf("--con"))
        assertEquals(listOf(Candidate("--confirm", "always ask for confirmation")), offered)
    }

    @Test
    fun `the generated form is still the default with no spellings`() {
        // Not `--color`: klap reserves that long name for its own built-in (BuilderValidation.kt's
        // reservedLongNames), which is orthogonal to what this test is checking.
        val tree = cli("t") {
            val debug = flag(long = "debug").negatable(default = true)
            action<String>(human = { it }) { Ok("debug=${debug()}") }
        }
        assertEquals("debug=false", tree.bindText("--no-debug"))
        assertEquals("debug=true", tree.bindText())
    }

    @Test
    fun `an explicit short reaches the negative half`() {
        assertEquals("deref=false", cpLike().bindText("-P", "f"))
        assertEquals("deref=true", cpLike().bindText("-L", "f"))
    }

    @Test
    fun `an explicit short negation works inside a cluster`() {
        val tree = cli("t") {
            val v = flag(short = 'v', long = "verbose")
            val deref = flag(short = 'L', long = "dereference").negatable(short = 'P')
            argument("file")
            action<String>(human = { it }) { Ok("v=${v()} deref=${deref()}") }
        }
        assertEquals("v=true deref=false", tree.bindText("-vP", "f"))
    }

    @Test
    fun `explicit spellings replace the generated form`() {
        // git spells the negative half `--no-pager` against `--paginate`, and rejects `--no-paginate`.
        val tree = cli("git") {
            val paginate = flag(short = 'p', long = "paginate").negatable(short = 'P', long = "no-pager")
            action<String>(human = { it }) { Ok("paginate=${paginate()}") }
        }
        assertEquals("paginate=false", tree.bindText("--no-pager"))
        tree.parse(listOf("--no-paginate")).assertError<CliError.UnknownOption>()
    }

    @Test
    fun `a negative spelling colliding with a declared name is rejected at construction`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            cli("t") {
                flag(short = 'q', long = "quiet")
                flag(short = 'v', long = "verbose").negatable(short = 'q')
                action<String>(human = { it }) { Ok("x") }
            }
        }
        assertTrue("-q" in failure.message.orEmpty())
    }

    @Test
    fun `help shows the real negative spellings rather than the generated one`() {
        val text = cpLike().helpText()
        assertTrue("-L, -P, --dereference, --no-dereference" in text, text)
    }

    @Test
    fun `a negative spelling reserved by a builtin is rejected at construction`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            cli("t") {
                flag(short = 'v', long = "verbose").negatable(short = 'h')
                action<String>(human = { it }) { Ok("x") }
            }
        }
        assertTrue("-h" in failure.message.orEmpty())
    }
}

/**
 * Runs [args] against this tree via [Cli.run] and returns the rendered stdout, minus the trailing newline
 * [run] always appends. Shared across test files (not file-private): any test that wants a tree's bound
 * human-readable output without hand-rolling a [RecordingTerminal] can reuse this one.
 */
internal fun Cli.bindText(vararg args: String): String {
    val terminal = RecordingTerminal()
    run(arrayOf(*args), terminal)
    return terminal.out.toString().removeSuffix("\n")
}

/** This tree's own `--help` output via [Cli.run], for tests asserting on rendered help text. */
internal fun Cli.helpText(): String {
    val terminal = RecordingTerminal()
    run(arrayOf("--help"), terminal)
    return terminal.out.toString().removeSuffix("\n")
}
