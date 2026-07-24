package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals

private fun todo(): Cli = cli("todo") {
    description = "A tiny todo CLI"
    version = "1.0.0"
    command("add") {
        description = "Add a task"
        val text = argument("text").multiple(min = 1)
        val priority = option("priority", "p").int().default(0)
        val done = flag("done", "d")
        action {
            val label = "[${priority()}] ${text().joinToString(" ")}"
            Ok(if (done()) "$label (done)" else "added $label")
        }
    }
}

class EndToEndTest {

    @Test
    fun addWithOptionsAndVariadic() {
        val t = RecordingTerminal()
        val code = todo().run(arrayOf("add", "-p", "5", "-d", "buy", "milk"), t)
        assertEquals(0, code)
        assertEquals("[5] buy milk (done)\n", t.out.toString())
    }

    @Test
    fun rootHelpFallsBackWhenNoSubcommand() {
        val t = RecordingTerminal()
        val code = todo().run(arrayOf(), t)
        assertEquals(0, code)
        assertEquals(true, t.out.toString().startsWith("usage: todo"))
    }
}
