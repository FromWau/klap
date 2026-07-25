package com.fromwau.klap

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
    fun theGeneratedFormIsStillTheDefaultWithNoSpellings() {
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
    fun anExplicitShortReachesTheNegativeHalf() {
        assertEquals("deref=false", cpLike().bindText("-P", "f"))
        assertEquals("deref=true", cpLike().bindText("-L", "f"))
    }

    @Test
    fun anExplicitShortNegationWorksInsideACluster() {
        val tree = cli("t") {
            val v = flag("--verbose", "-v")
            val deref = flag("--dereference", "-L").negatable("-P")
            argument("file")
            action<String>(human = { it }) { Ok("v=${v()} deref=${deref()}") }
        }
        assertEquals("v=true deref=false", tree.bindText("-vP", "f"))
    }

    @Test
    fun explicitSpellingsReplaceTheGeneratedForm() {
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
    fun aNegativeSpellingCollidingWithADeclaredNameIsRejectedAtConstruction() {
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
    fun helpShowsTheRealNegativeSpellingsRatherThanTheGeneratedOne() {
        val text = cpLike().helpText()
        assertTrue("-L, -P, --dereference, --no-dereference" in text, text)
    }

    @Test
    fun aNegativeSpellingReservedByABuiltinIsRejectedAtConstruction() {
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
