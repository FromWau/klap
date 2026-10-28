package com.fromwau.klap.io

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import com.fromwau.kern.terminal.ReadError
import com.fromwau.kern.terminal.shellQuoted
import com.fromwau.kern.terminal.test.RecordingTerminal
import com.fromwau.klap.CliError
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection
import com.fromwau.klap.run
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

private val head = cliOf("head") {
    val config = option(short = 'c', long = "config").input()
    val files = argument("file").input().multiple()
    action { Ok("") }
    projection { config() to files() }
}

private fun inputsOf(vararg words: String): Pair<CliInput?, List<CliInput>> =
    head.parse(words.toList()).assertSuccess()!!

class InputConverterTest {

    private val scratch = scratchDir()

    @AfterTest
    fun deleteScratch() {
        scratch.deleteRecursively().assertSuccess()
    }

    @Test
    fun `a dash is standard input and any other word a file kept as typed`() {
        assertEquals(
            listOf(CliInput.StandardInput, CliInput.File(CliPath("a/")), CliInput.StandardInput),
            inputsOf("-", "a/", "-").second,
        )
    }

    @Test
    fun `an option reads a dash as standard input`() {
        assertEquals(CliInput.StandardInput, inputsOf("-c", "-").first)
        assertEquals(CliInput.File(CliPath("c.toml")), inputsOf("--config=c.toml").first)
        assertEquals(null, inputsOf().first)
    }

    @Test
    fun `an input completes files as an operand and as an option`() {
        assertEquals(listOf(filesAfter()), head.cli.suggestionsFor("head "))
        assertEquals(listOf(filesAfter()), head.cli.suggestionsFor("head -c "))
    }

    @Test
    fun `standard input is read through the terminal byte for byte`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x61, 0x0D, 0x0A)
        val terminal = RecordingTerminal(input = bytes)

        assertContentEquals(bytes, CliInput.StandardInput.readBytes(terminal).assertSuccess())
    }

    @Test
    fun `a file is read whole`() {
        val path = scratch / "a.txt"
        path.writeText("a\r\nb").assertSuccess()

        val read = CliInput.File(CliPath("$path")).readBytes(RecordingTerminal()).assertSuccess()
        assertContentEquals("a\r\nb".encodeToByteArray(), read)
    }

    @Test
    fun `a missing file is a file error naming the path as typed`() {
        val missing = CliPath("${scratch / "missing.txt"}")

        val error = CliInput.File(missing).readBytes(RecordingTerminal()).assertError<InputError.File>()
        assertEquals(missing, error.path)
        assertEquals(FileError.NotFound::class, error.error::class)
    }

    @Test
    fun `a standard input that fails is a standard input error`() {
        val terminal = RecordingTerminal()
        terminal.failWith(ReadError.IsADirectory)

        val error = CliInput.StandardInput.readBytes(terminal).assertError<InputError.StandardInput>()
        assertEquals(ReadError.IsADirectory, error.error)
    }

    @Test
    fun `maxBytes bounds standard input and a file alike`() {
        val path = scratch / "big.txt"
        path.writeText("12345").assertSuccess()
        val terminal = RecordingTerminal(input = "12345".encodeToByteArray())

        val fromInput = CliInput.StandardInput.readBytes(terminal, maxBytes = 4).assertError<InputError.StandardInput>()
        assertEquals(ReadError.TooLarge(4, 5), fromInput.error)
        val fromFile = CliInput.File(CliPath("$path")).readBytes(terminal, maxBytes = 4).assertError<InputError.File>()
        assertEquals(FileError.TooLarge::class, fromFile.error::class)
    }

    @Test
    fun `an action reads a missing operand as standard input when it says so`() {
        val cat = cliOf("cat") {
            val file = argument("file").input().optional()
            action {
                (file() ?: CliInput.StandardInput).readBytes(terminal)
                    .map { it.decodeToString() }
                    .mapError { error ->
                        val name = (error as? InputError.File)?.path?.text?.shellQuoted() ?: "standard input"
                        CliError.Domain(error, "cannot read $name")
                    }
            }
            projection { file() }
        }
        val terminal = RecordingTerminal(input = "piped".encodeToByteArray())

        assertEquals(0, cat.cli.run(emptyList(), terminal))
        assertEquals("piped\n", terminal.out)
    }

    @Test
    fun `the guide's example names a missing file quoted as GNU names it`() {
        val cat = cliOf("cat") {
            val file = argument("file").input().optional()
            action {
                (file() ?: CliInput.StandardInput).readBytes(terminal)
                    .map { it.decodeToString() }
                    .mapError { error ->
                        val name = (error as? InputError.File)?.path?.text?.shellQuoted() ?: "standard input"
                        CliError.Domain(error, "cannot read $name")
                    }
            }
            projection { file() }
        }
        val terminal = RecordingTerminal()

        assertEquals(1, cat.cli.run(listOf("${scratch / "my file"}"), terminal))
        assertEquals("error: cannot read '${scratch / "my file"}'\n", terminal.err)
    }
}
