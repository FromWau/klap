package com.fromwau.klap.io.snippets

import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.terminal.test.RecordingTerminal
import com.fromwau.klap.Caller
import com.fromwau.klap.CliError
import com.fromwau.klap.cli
import com.fromwau.klap.io.path
import com.fromwau.klap.run
import kotlin.test.Test
import kotlin.test.assertEquals

/** The guide's "Who a run is for" snippet, transcribed outside klap's packages so its imports are checked too. */
class CallerSnippetTest {

    private val app = cli("app") {
        command("config") {
            val file = argument("file").path()
            action {
                val dirs = BaseDirs.fromXdg(caller.environment, accountHome = null)
                    .getOrElse { return@action Err(CliError.Failure("no home directory")) }
                Ok("${file().resolvedFor(caller)} under ${dirs.configHome}")
            }
        }
    }

    private val ada = Caller("/home/ada/work", mapOf("HOME" to "/home/ada"))

    private fun config(file: String): String =
        RecordingTerminal().also { app.run(listOf("config", file), it, ada) }.out

    @Test
    fun `the guides config action reads a relative file from the caller's folder`() {
        assertEquals("/home/ada/work/app.toml under /home/ada/.config\n", config("app.toml"))
    }

    @Test
    fun `the guides config action keeps an absolute file as it is`() {
        assertEquals("/etc/app.toml under /home/ada/.config\n", config("/etc/app.toml"))
    }
}
