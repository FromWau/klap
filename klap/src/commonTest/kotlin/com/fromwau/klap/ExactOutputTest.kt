package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** `action(endsLine = false)`: a command printing a file prints it as it is; every other answer ends its line. */
class ExactOutputTest {

    @Test
    fun `a human answer ends its line unless the command says it is printed as it is`() {
        val t = RecordingTerminal()
        assertEquals(0, cli("app") { action { Ok("done") } }.run(emptyList(), t))
        assertEquals("done\n", t.out)

        val exact = RecordingTerminal()
        assertEquals(0, cli("cat") { action(endsLine = false) { Ok("a = 1") } }.run(emptyList(), exact))
        assertEquals("a = 1", exact.out)
    }

    @Test
    fun `text printed as it is keeps a line break of its own once`() {
        val t = RecordingTerminal()
        cli("cat") { action(endsLine = false) { Ok("a = 1\r\n") } }.run(emptyList(), t)
        assertEquals("a = 1\r\n", t.out)
    }

    @Test
    fun `json still ends its line when the human text is printed as it is`() {
        val t = RecordingTerminal()
        cli("cat") { action(endsLine = false) { Ok("a = 1") } }.run(listOf("--json"), t)
        assertEquals("\"a = 1\"\n", t.out)
    }

    @Test
    fun `a suspending action with endsLine false prints its text as it is`() = runTest {
        val t = RecordingTerminal()
        cli("cat") { actionSuspending(endsLine = false) { Ok("a = 1") } }.runSuspending(emptyList(), t)
        assertEquals("a = 1", t.out)
    }
}
