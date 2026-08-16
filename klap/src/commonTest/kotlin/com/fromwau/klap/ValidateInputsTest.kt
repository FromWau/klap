package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        val error = assertIs<Result.Error<CliError>>(tree.parse(listOf("--name", "x"))).error
        val failure = assertIs<CliError.Failure>(error)
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
        assertIs<Result.Success<Invocation>>(tree.parse(listOf("--completion", "bash")))
        assertIs<Result.Success<Invocation>>(tree.parse(listOf("--docs", "man")))
        assertIs<Result.Success<Invocation>>(tree.parse(listOf("__complete", "--na")))
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
        assertIs<Result.Success<Invocation>>(tree.parse(listOf("go", "--name", "x")))
        assertEquals(
            CliError.Usage("saw the global"),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("go", "--name", "x", "-v"))).error,
        )
    }

    @Test
    fun `a block reads a positional bound value`() {
        val tree = cli("app") {
            val rest = argument("rest").multiple(min = 0)
            validateInputs { if (rest().size > 2) CliError.Usage("at most two") else null }
            action { Ok("rest=${rest()}") }
        }
        assertIs<Result.Success<Invocation>>(tree.parse(listOf("a", "b")))
        assertEquals(
            CliError.Usage("at most two"),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("a", "b", "c"))).error,
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
        assertIs<Result.Error<CliError>>(tree.parse(listOf()))
        assertEquals(false, ran, "the action ran despite a refusal")
    }
}
