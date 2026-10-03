package com.fromwau.klap

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Under `--json` a failure is data: its variant under `type` and its fields, with no sentence anywhere. */
class JsonErrorTest {

    private data object DiskFull : IError

    private fun stderr(vararg args: String, build: CliBuilder.() -> Unit): Pair<Int, String> {
        val t = RecordingTerminal()
        val code = cli("tool", build).run(arrayOf(*args), t)
        return code to t.err.toString()
    }

    @Test
    fun `an unknown option is its fields`() {
        val (code, err) = stderr("--verbos", "--json") {
            flag("--verbose")
            action { Ok("") }
        }
        assertEquals(2, code)
        assertEquals("""{"error":{"type":"UnknownOption","token":"--verbos","suggestion":"--verbose"},"code":2}""" + "\n", err)
    }

    @Test
    fun `a bad value carries its converter cause`() {
        val (_, err) = stderr("--port", "x", "--json") {
            val port = option("--port").int()
            action { Ok("${port()}") }
        }
        assertEquals(
            """{"error":{"type":"BadValue","name":"--port","value":"x","cause":{"type":"NotAnInteger"}},"code":2}""" + "\n",
            err,
        )
    }

    @Test
    fun `a domain error writes the json its action gave`() {
        val encoded = buildJsonObject {
            put("type", "DiskFull")
            put("free", 0)
        }
        val (code, err) = stderr("--json") {
            action<String> { Err(CliError.Domain(DiskFull, "disk full", exitCode = 6, json = encoded)) }
        }
        assertEquals(6, code)
        assertEquals("""{"error":{"type":"DiskFull","free":0},"code":6}""" + "\n", err)
    }

    @Test
    fun `a domain error without json writes its detail`() {
        val (_, err) = stderr("--json") { action<String> { Err(CliError.Domain(DiskFull, "disk full")) } }
        assertEquals("""{"error":{"type":"Domain","detail":"disk full"},"code":1}""" + "\n", err)
    }

    @Test
    fun `a conversion domain error writes the json its converter gave`() {
        val (_, err) = stderr("--size", "huge", "--json") {
            val size = option("--size").convert { Err(ConversionError.Domain(DiskFull, "too big", JsonPrimitive(1))) }
            action { Ok("${size()}") }
        }
        assertEquals("""{"error":{"type":"BadValue","name":"--size","value":"huge","cause":1},"code":2}""" + "\n", err)
    }

    @Test
    fun `a DEL echoed from argv stays escaped`() {
        val (_, err) = stderr("--a${Char(127)}", "--json") { action { Ok("") } }
        assertEquals("""{"error":{"type":"UnknownOption","token":"--a\u007f"},"code":2}""" + "\n", err)
    }

    @Test
    fun `a group run without a subcommand under json is a usage error`() {
        val (code, err) = stderr("--json") {
            command("get") { action { Ok("") } }
        }
        assertEquals(2, code)
        assertEquals("""{"error":{"type":"MissingSubcommand","parent":"tool"},"code":2}""" + "\n", err)
    }

    @Test
    fun `a nested group run without a subcommand under json names the group`() {
        val (code, err) = stderr("remote", "--json") {
            command("remote") { command("add") { action { Ok("") } } }
        }
        assertEquals(2, code)
        assertEquals("""{"error":{"type":"MissingSubcommand","parent":"tool remote"},"code":2}""" + "\n", err)
    }

    @Test
    fun `a group run without a subcommand shows its help without json`() {
        val t = RecordingTerminal()
        val code = cli("tool") { command("get") { action { Ok("") } } }.run(arrayOf(), t)
        assertEquals(0, code)
        assertEquals("", t.err.toString())
        assertEquals(true, t.out.toString().startsWith("usage: tool"), t.out.toString())
    }
}
