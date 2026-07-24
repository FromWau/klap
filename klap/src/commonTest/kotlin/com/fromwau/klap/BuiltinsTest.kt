package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun app(): Cli = cli("todo") {
    command("ping") { action { Ok("pong") } }
}

class BuiltinsTest {

    @Test
    fun completionCommandIsAutoAdded() {
        assertTrue(app().subcommand("completion") != null)
    }

    @Test
    fun completionPrintsFishScript() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("completion", "fish"), t)
        assertEquals(0, code)
        assertTrue("complete -c todo" in t.out.toString(), t.out.toString())
    }

    @Test
    fun completionRejectsUnknownShell() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("completion", "ksh"), t)
        assertEquals(2, code)
        assertTrue("invalid value 'ksh'" in t.err.toString(), t.err.toString())
    }

    @Test
    fun completionScriptIncludesTheCompletionCommandItself() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("completion", "fish"), t)
        assertEquals(0, code)
        // The generated script must offer `completion` itself for tab-completion (self-referential).
        assertTrue("-a 'completion'" in t.out.toString(), t.out.toString())
    }
}
