package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A group run without a command is a usage error, as GNU tools treat one: help on standard error, exit 2. */
class MissingCommandTest {

    private val tool = cli("tool") {
        command("go") { action { Ok("done") } }
        command("group") { command("leaf") { action { Ok("leaf") } } }
    }

    private fun run(vararg args: String): Triple<Int, String, String> {
        val t = RecordingTerminal()
        return Triple(tool.run(args.toList(), t), t.out, t.err)
    }

    @Test
    fun `no command prints help on standard error and exits 2`() {
        for (args in listOf(emptyList(), listOf("group"))) {
            val (code, out, err) = run(*args.toTypedArray())
            assertEquals(USAGE_ERROR_EXIT, code, args.toString())
            assertEquals("", out, args.toString())
            assertTrue(err.startsWith("usage: tool"), err)
        }
    }

    @Test
    fun `help on standard error is styled as help asked for is`() {
        val terminal = RecordingTerminal(errAnsi = true)
        assertEquals(USAGE_ERROR_EXIT, tool.run(emptyList(), terminal))
        assertTrue("\u001B[" in terminal.err, terminal.err)
    }

    @Test
    fun `parse tells a group run without a command from help asked for`() {
        assertTrue(assertIs<Invocation.ShowHelp>(tool.parse(listOf("group")).assertSuccess()).missingCommand)
        assertFalse(assertIs<Invocation.ShowHelp>(tool.parse(listOf("group", "--help")).assertSuccess()).missingCommand)
    }

    @Test
    fun `help asked for prints on standard output and exits 0`() {
        val (code, out, err) = run("--help")
        assertEquals(0, code)
        assertTrue(out.startsWith("usage: tool"), out)
        assertEquals("", err)
    }
}
