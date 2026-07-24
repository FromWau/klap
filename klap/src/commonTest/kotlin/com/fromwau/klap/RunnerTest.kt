package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun app(): Cli = cli("todo") {
    version = "1.0.0"
    command("ping") { action { Ok("pong") } }
    // A fail-only action can't infer T from Err (that would be Nothing); name it explicitly.
    command("fail") { action<String> { Err(CliError.Failure("fail", exitCode = 3)) } }
    command("add") {
        val text = argument("text")
        action { Ok("added ${text()}") }
    }
}

class RunnerTest {

    @Test
    fun successRunsBlockAndReturnsZero() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("ping"), t)
        assertEquals(0, code)
        assertEquals("pong\n", t.out.toString())
    }

    @Test
    fun exitPropagatesCode() {
        val code = app().run(arrayOf("fail"), RecordingTerminal())
        assertEquals(3, code)
    }

    @Test
    fun usageErrorPrintsToErrAndReturnsTwo() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("add"), t)
        assertEquals(2, code)
        assertTrue("missing required argument <text>" in t.err.toString(), t.err.toString())
    }

    @Test
    fun jsonErrorEnvelopeOnStderr() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("add", "--json"), t)
        assertEquals(2, code)
        assertTrue(t.err.toString().trim().startsWith("{\"error\":"), t.err.toString())
    }

    @Test
    fun versionPrintsAndReturnsZero() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("--version"), t)
        assertEquals(0, code)
        assertTrue("1.0.0" in t.out.toString(), t.out.toString())
    }

    @Test
    fun helpPrintsAndReturnsZero() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("add", "--help"), t)
        assertEquals(0, code)
        assertTrue("usage: todo add <text>" in t.out.toString(), t.out.toString())
    }

    @Test
    fun nestedHelpShowsFullPath() {
        val vcs = cli("vcs") {
            command("remote") {
                command("add") {
                    argument("name")
                    argument("url")
                    action { Ok("") }
                }
            }
        }
        val t = RecordingTerminal()
        vcs.run(arrayOf("remote", "add", "-h"), t)
        assertTrue("usage: vcs remote add <name> <url>" in t.out.toString(), t.out.toString())
    }
}
