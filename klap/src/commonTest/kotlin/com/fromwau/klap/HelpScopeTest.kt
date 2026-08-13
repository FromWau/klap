package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Help documents the deepest command the line names, wherever the request itself sits, so a tree can be
 * explored by appending: `app --help`, then `app --help remote`, then `app --help remote add`. The walk
 * steps over a help request and over globals because both resolve before it knows which command it
 * reaches; a short belonging to a command not yet reached stops it.
 */
class HelpScopeTest {

    private fun tree() = cli("app") {
        globalFlag("--verbose", "-v")
        command("remote") {
            command("add") {
                flag("--force", "-f")
                argument("url")
                action { Ok("") }
            }
        }
    }

    private fun helpFor(vararg argv: String): String =
        assertIs<Invocation.ShowHelp>(
            assertIs<Result.Success<Invocation>>(tree().parse(argv.toList())).value,
        ).qualifiedName

    @Test
    fun `help after a path documents the command that path names`() {
        assertEquals("app", helpFor("--help"))
        assertEquals("app remote", helpFor("remote", "--help"))
        assertEquals("app remote add", helpFor("remote", "add", "--help"))
    }

    @Test
    fun `the tree is explorable by appending names after the request`() {
        assertEquals("app remote", helpFor("--help", "remote"))
        assertEquals("app remote add", helpFor("--help", "remote", "add"))
        assertEquals("app remote add", helpFor("remote", "--help", "add"))
    }

    @Test
    fun `a global alongside the request does not change what it documents`() {
        assertEquals("app", helpFor("-v", "--help"))
        assertEquals("app remote add", helpFor("--verbose", "--help", "remote", "add"))
        assertEquals("app remote add", helpFor("remote", "add", "-v", "--help"))
    }

    @Test
    fun `the help short behaves exactly like the long`() {
        assertEquals("app", helpFor("-h"))
        assertEquals("app remote add", helpFor("-h", "remote", "add"))
        assertEquals("app remote add", helpFor("remote", "add", "-h"))
    }

    @Test
    fun `the help short clustered with a global still reaches the named command`() {
        assertEquals("app remote", helpFor("-vh", "remote"))
        assertEquals("app remote", helpFor("-hv", "remote"))
        assertEquals("app remote add", helpFor("-vh", "remote", "add"))
    }

    @Test
    fun `the help short clustered with a local documents the command that declares it`() {
        assertEquals("app remote add", helpFor("remote", "add", "-fh"))
        assertEquals("app remote add", helpFor("remote", "add", "-hf"))
        assertEquals("app remote add", helpFor("remote", "add", "-vfh"))
    }

    @Test
    fun `help-all scopes the same way`() {
        assertEquals("app", helpFor("--help-all"))
        assertEquals("app remote", helpFor("--help-all", "remote"))
        assertEquals("app remote", helpFor("remote", "--help-all"))
    }

    @Test
    fun `a name after the request that is no command is reported rather than answered`() {
        // Help would otherwise render the root's at exit 0 and drop the word, teaching the user that
        // `bogus` is a command.
        assertEquals(
            CliError.UnknownSubcommand("app", "bogus", null),
            assertIs<Result.Error<CliError>>(tree().parse(listOf("--help", "bogus"))).error,
        )
        assertEquals(
            CliError.UnknownSubcommand("app", "remot", "remote"),
            assertIs<Result.Error<CliError>>(tree().parse(listOf("-vh", "remot"))).error,
        )
        assertEquals(
            CliError.UnknownSubcommand("app remote", "bogus", null),
            assertIs<Result.Error<CliError>>(tree().parse(listOf("remote", "--help", "bogus"))).error,
        )
    }

    @Test
    fun `a short of a command not yet reached stops the walk`() {
        // The boundary the step-over rule keeps: `-f` belongs to `add`, so written before the path it is
        // an option of a command this line has not reached, and the cluster is refused rather than skipped.
        val err = assertIs<Result.Error<CliError>>(tree().parse(listOf("-vf", "remote", "add"))).error
        assertEquals(CliError.UnknownOption("-f", cluster = "-vf"), err)
    }
}
