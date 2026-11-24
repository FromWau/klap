package com.fromwau.klap

import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.terminal.Stream
import com.fromwau.klap.testing.tab
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CallerTest {

    private val ada = Caller(
        workingDirectory = "/home/ada/project",
        environment = mapOf("HOME" to "/home/ada", "XDG_CONFIG_HOME" to "/home/ada/.cfg"),
    )

    private val tree = cli("app") {
        command("where") {
            action { Ok(caller.workingDirectory) }
        }
        command("config") {
            action {
                val dirs = BaseDirs.fromXdg(caller.environment, accountHome = null)
                    .getOrElse { return@action Err(CliError.Failure("no home directory")) }
                Ok("${dirs.home} ${dirs.configHome}")
            }
        }
        command("open") {
            argument("place").completeWith { candidates(listOf(caller.workingDirectory)) }
            action { Ok("") }
        }
    }

    @Test
    fun `an action reads the working directory of the caller it runs for`() = runTest {
        assertEquals(Seen(0, listOf(Stream.Out to "/home/ada/project\n")), tree.forwarded(listOf("where"), piped, ada))
    }

    @Test
    fun `an action resolves its directories through kern dirs from the caller's environment`() = runTest {
        assertEquals(
            Seen(0, listOf(Stream.Out to "/home/ada /home/ada/.cfg\n")),
            tree.forwarded(listOf("config"), piped, ada),
        )
    }

    @Test
    fun `a Tab provider reads the caller the line is completed for`() = runTest {
        val line = listOf("__complete", "--cursor", "1", "--", "open", "")
        assertEquals(Seen(0, listOf(Stream.Out to "/home/ada/project\n")), tree.forwarded(line, piped, ada))
    }

    @Test
    fun `one Tab press completes from the caller it is given`() {
        assertEquals("app open /home/ada/project", tree.tab("app open ", caller = ada))
    }

    @Test
    fun `a caller prints its folder and its variable names but never their values`() {
        val caller = Caller("/home/ada/work", mapOf("HOME" to "/home/ada", "API_TOKEN" to "s3cret"))
        assertEquals("Caller(workingDirectory=/home/ada/work, environment=[API_TOKEN, HOME])", caller.toString())
    }

    @Test
    fun `a run given no caller is this process's own`() {
        val process = Caller.process()
        assertEquals("${process.workingDirectory}\n", tree.bindText("where") + "\n")
    }
}
