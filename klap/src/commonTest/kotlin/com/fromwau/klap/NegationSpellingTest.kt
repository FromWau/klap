package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NegationSpellingTest {

    private fun cpLike() = cli("cp") {
        val dereference = flag("--dereference", "-L").negatable("--no-dereference", "-P")
        argument("file")
        action<String>(human = { it }) { Ok("deref=${dereference()}") }
    }

    @Test
    fun `the generated form is still the default with no spellings`() {
        // Not `--color`: klap reserves that long name for its own built-in (BuilderValidation.kt's
        // reservedLongNames), which is orthogonal to what this test is checking.
        val tree = cli("t") {
            val debug = flag("--debug").negatable(default = true)
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
            val v = flag("--verbose", "-v")
            val deref = flag("--dereference", "-L").negatable("-P")
            argument("file")
            action<String>(human = { it }) { Ok("v=${v()} deref=${deref()}") }
        }
        assertEquals("v=true deref=false", tree.bindText("-vP", "f"))
    }

    @Test
    fun `explicit spellings replace the generated form`() {
        // git spells the negative half `--no-pager` against `--paginate`, and rejects `--no-paginate`.
        val tree = cli("git") {
            val paginate = flag("--paginate", "-p").negatable("--no-pager", "-P")
            action<String>(human = { it }) { Ok("paginate=${paginate()}") }
        }
        assertEquals("paginate=false", tree.bindText("--no-pager"))
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--no-paginate"))).error
        assertIs<CliError.UnknownOption>(err)
    }

    @Test
    fun `a negative spelling colliding with a declared name is rejected at construction`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            cli("t") {
                flag("--quiet", "-q")
                flag("--verbose", "-v").negatable("-q")
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
                flag("--verbose", "-v").negatable("-h")
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
