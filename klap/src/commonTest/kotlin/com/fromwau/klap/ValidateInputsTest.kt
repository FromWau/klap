package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `validateInputs {}` where `GuideValidateInputsSnippetTest` does not reach: the never-throw boundary, the
 * built-ins beyond `--help`/`--version`, the input kinds a block can read, and what a refusal stops.
 */
class ValidateInputsTest {

    @Test
    fun `a throwing block is reported rather than thrown`() {
        // `.validate()` is the counterpart these docs point authors at, and it reports a throwing
        // predicate rather than letting it out; a rule moved between the two must not lose that.
        val tree = cli("app") {
            val name = option("--name").required()
            validateInputs { error("boom") }
            action { Ok("name=${name()}") }
        }
        val failure = tree.parse(listOf("--name", "x")).assertError<CliError.Failure>()
        assertTrue("boom" in failure.detail, failure.detail)
    }

    @Test
    fun `completion and docs render without consulting a refusing block`() {
        // The same reason `--help` does not: neither produces the bound values a block reads, so a rule
        // that refuses every line must not stop a shell script or a man page from being written.
        val tree = cli("app") {
            option("--name").required()
            validateInputs { CliError.Usage("always refuses") }
            action { Ok("ran") }
        }
        tree.parse(listOf("--completion", "bash")).assertSuccess()
        tree.parse(listOf("--docs", "man")).assertSuccess()
        tree.parse(listOf("__complete", "--na")).assertSuccess()
    }

    @Test
    fun `a block reads a global the same way it reads a local`() {
        val tree = cli("app") {
            val verbose = globalFlag("--verbose", "-v")
            command("go") {
                val name = option("--name").required()
                validateInputs { if (verbose()) CliError.Usage("saw the global") else null }
                action { Ok("name=${name()}") }
            }
        }
        tree.parse(listOf("go", "--name", "x")).assertSuccess()
        assertEquals(
            CliError.Usage("saw the global"),
            tree.parse(listOf("go", "--name", "x", "-v")).assertError<CliError.Usage>(),
        )
    }

    @Test
    fun `a block reads a positional bound value`() {
        val tree = cli("app") {
            val rest = argument("rest").multiple(min = 0)
            validateInputs { if (rest().size > 2) CliError.Usage("at most two") else null }
            action { Ok("rest=${rest()}") }
        }
        tree.parse(listOf("a", "b")).assertSuccess()
        assertEquals(
            CliError.Usage("at most two"),
            tree.parse(listOf("a", "b", "c")).assertError<CliError.Usage>(),
        )
    }

    @Test
    fun `a refused line does not run the action`() {
        // The whole point of running before the action rather than inside it: the refusal has to land
        // before any side effect, not after one.
        var ran = false
        val tree = cli("app") {
            validateInputs { CliError.Usage("refused") }
            action {
                ran = true
                Ok("ran")
            }
        }
        tree.parse(listOf()).assertError<CliError.Usage>()
        assertEquals(false, ran, "the action ran despite a refusal")
    }
}
