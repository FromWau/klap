package com.fromwau.klap.io

import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import com.fromwau.kern.terminal.test.RecordingTerminal
import com.fromwau.klap.Caller
import com.fromwau.klap.CliError
import com.fromwau.klap.cli
import com.fromwau.klap.run
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.suggestionsFor
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CallerInputTest {

    private val scratch = scratchDir()

    @AfterTest
    fun deleteScratch() {
        scratch.deleteRecursively().assertSuccess()
    }

    @Test
    fun `outside an action readBytes reads a relative file from the caller it is given`() {
        (scratch / "notes.txt").writeText("from the caller").assertSuccess()
        val read = CliInput.File(CliPath("notes.txt"))
            .readBytes(RecordingTerminal(), Caller(scratch.toString(), emptyMap()))
            .map { it.decodeToString() }
        assertEquals(Ok("from the caller"), read)
    }

    @Test
    fun `inside an action readBytes reads from the folder of the caller the run is for`() {
        (scratch / "notes.txt").writeText("from the caller").assertSuccess()
        val tree = cli("cat") {
            val file = argument("file").input()
            action {
                file().readBytes()
                    .map { it.decodeToString() }
                    .mapError { CliError.Domain(it, "cannot read ${file()}") }
            }
        }
        val terminal = RecordingTerminal()
        val code = tree.run(listOf("notes.txt"), terminal, Caller(scratch.toString(), emptyMap()))
        assertEquals(0 to "from the caller\n", code to terminal.out + terminal.err)
    }

    @Test
    fun `inside an action readBytes reads standard input from the run's terminal`() {
        val tree = cli("cat") {
            val file = argument("file").input()
            action {
                file().readBytes()
                    .map { it.decodeToString() }
                    .mapError { CliError.Domain(it, "unread") }
            }
        }
        val terminal = RecordingTerminal(input = "piped".encodeToByteArray())
        assertEquals(0 to "piped\n", tree.run(listOf("-"), terminal) to terminal.out)
    }

    @Test
    fun `a path is resolved for a caller inside its folder unless it is absolute`() {
        val ada = Caller("/home/ada/work", emptyMap())
        assertEquals(Path("/home/ada/work/notes.txt"), CliPath("notes.txt").resolvedFor(ada))
        assertEquals(Path("/etc/hosts"), CliPath("/etc/hosts").resolvedFor(ada))
    }

    @Test
    fun `a Tab provider lists the caller's working directory`() {
        (scratch / "a.toml").writeText("").assertSuccess()
        (scratch / "b.toml").writeText("").assertSuccess()
        val tree = cli("ls") {
            argument("entry").completeWith {
                candidates(SystemFileSystem.list(Path(caller.workingDirectory)).map { it.name }.sorted())
            }
            action { Ok("") }
        }
        assertEquals(
            listOf(Suggestion.Word("a.toml"), Suggestion.Word("b.toml")),
            tree.suggestionsFor("ls ", caller = Caller(scratch.toString(), emptyMap())),
        )
    }
}
