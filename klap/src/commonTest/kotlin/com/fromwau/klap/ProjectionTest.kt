package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `cliOf` over a subcommand tree: one projection per command, dispatched by whichever command ran.
 *
 * The flat single-command case is covered by the fixtures (`example/head`); these are the tree-shaped
 * claims nothing else exercises — nested leaves, globals reaching every variant, and the construction-time
 * rule that an executable command must say how to read itself.
 */
class SubcommandProjectionTest {

    /**
     * The consumer's own type. Each command projects to its own variant, so `dispatch` infers `GitArgs`
     * as their common supertype and a caller's `when` over the result is exhaustive.
     *
     * `gitDir` sits on the interface rather than on `parse`: a root global is readable from every command's
     * scope, so each variant reads it in its own projection. That is question 1's answer, and it needed no
     * mechanism from klap.
     */
    private sealed interface GitArgs {
        val gitDir: String

        data class Commit(override val gitDir: String, val message: List<String>, val amend: Boolean) : GitArgs

        data class Status(override val gitDir: String, val short: Boolean) : GitArgs

        data class RemoteAdd(override val gitDir: String, val name: String, val url: String) : GitArgs

        data class RemoteRemove(override val gitDir: String, val name: String) : GitArgs
    }

    private fun git() = cliOf("git") {
        val gitDir = globalOption("--git-dir").default(".git")

        val commit = command("commit") {
            val message = option("--message", "-m").multiple()
            val amend = flag("--amend")
            action { Ok("committed") }
            projection { GitArgs.Commit(gitDir(), message(), amend()) }
        }

        val status = command("status") {
            val short = flag("--short", "-s")
            action { Ok("status") }
            projection { GitArgs.Status(gitDir(), short()) }
        }

        // Nested: `remote` is a pure group with no action of its own, so it declares no reader. Its own
        // block ends in a dispatch, and those leaves are what a parse actually resolves to.
        val remote = command("remote") {
            val add = command("add") {
                val name = argument("name")
                val url = argument("url")
                action { Ok("added") }
                projection { GitArgs.RemoteAdd(gitDir(), name(), url()) }
            }
            val remove = command("remove") {
                val name = argument("name")
                action { Ok("removed") }
                projection { GitArgs.RemoteRemove(gitDir(), name()) }
            }
            dispatch(add, remove)
        }

        dispatch(commit, status, remote)
    }

    @Test
    fun `each command projects to its own variant`() {
        assertEquals(
            Ok(GitArgs.Commit(gitDir = ".git", message = listOf("hello"), amend = false)),
            git().parse(listOf("commit", "-m", "hello")),
        )
        assertEquals(
            Ok(GitArgs.Status(gitDir = ".git", short = true)),
            git().parse(listOf("status", "-s")),
        )
    }

    @Test
    fun `a nested leaf projects rather than its parent group`() {
        assertEquals(
            Ok(GitArgs.RemoteAdd(gitDir = ".git", name = "origin", url = "git@example.com:x.git")),
            git().parse(listOf("remote", "add", "origin", "git@example.com:x.git")),
        )
        assertEquals(
            Ok(GitArgs.RemoteRemove(gitDir = ".git", name = "origin")),
            git().parse(listOf("remote", "remove", "origin")),
        )
    }

    @Test
    fun `a root global reaches every variant including a nested one`() {
        assertEquals(
            Ok(GitArgs.Status(gitDir = "/tmp/x", short = false)),
            git().parse(listOf("--git-dir", "/tmp/x", "status")),
        )
        assertEquals(
            Ok(GitArgs.RemoteRemove(gitDir = "/tmp/x", name = "origin")),
            git().parse(listOf("--git-dir", "/tmp/x", "remote", "remove", "origin")),
        )
    }

    @Test
    fun `the result is exhaustively matchable by the caller`() {
        val described = when (val args = git().parse(listOf("remote", "add", "o", "u")).getOrElse { null }) {
            is GitArgs.Commit -> "commit"
            is GitArgs.Status -> "status"
            is GitArgs.RemoteAdd -> "remote add ${args.name}"
            is GitArgs.RemoteRemove -> "remote remove"
            null -> "a built-in answered"
        }
        assertEquals("remote add o", described)
    }

    @Test
    fun `a built in answers with null because no command ran`() {
        assertEquals(Ok(null), git().parse(listOf("--help")))
        assertEquals(Ok(null), git().parse(listOf("commit", "--help")))
    }

    @Test
    fun `a parse error stays typed and never reaches a projection`() {
        assertIs<Result.Error<CliError>>(git().parse(listOf("commit", "--zzz")))
        assertIs<Result.Error<CliError>>(git().parse(listOf("nosuchcommand")))
    }

    @Test
    fun `an executable command with no projection fails at construction`() {
        val failure = try {
            cliOf("git") {
                val status = command("status") {
                    val short = flag("-s")
                    action { Ok("status") }
                    projection { GitArgs.Status(".git", short()) }
                }
                // Declared with an action but no projection, and not part of the dispatch.
                command("commit") { action { Ok("committed") } }
                dispatch(status)
            }
            fail("expected construction to reject the unprojected command")
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue(
            failure.message.orEmpty().contains("no projection for 'commit'"),
            "unexpected message: ${failure.message}",
        )
    }
}

/**
 * A command that both acts and nests: `git remote -v` runs `remote` itself, `git remote add x u` runs its
 * child. Its block ends in `dispatch(child, ..., projection { })`, and the claim it pins is that the
 * unclaimed part belongs to the command that returned it rather than to the root.
 */
class ActingGroupProjectionTest {

    private sealed interface R {
        data class Group(val verbose: Boolean) : R
        data class Add(val name: String) : R
    }

    private fun cli() = cliOf("git") {
        val remote = command("remote") {
            val verbose = flag("--verbose", "-v")
            val add = command("add") {
                val name = argument("name")
                action { Ok("added") }
                projection { R.Add(name()) }
            }
            action { Ok("listed") }
            dispatch(add, projection { R.Group(verbose()) })
        }
        dispatch(remote)
    }

    @Test
    fun `the groups own projection answers when no child ran`() {
        assertEquals(Ok(R.Group(verbose = true)), cli().parse(listOf("remote", "-v")))
    }

    @Test
    fun `the childs projection still answers when it runs`() {
        assertEquals(Ok(R.Add(name = "origin")), cli().parse(listOf("remote", "add", "origin")))
    }
}
