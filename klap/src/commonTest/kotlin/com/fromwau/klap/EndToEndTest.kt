package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

private fun todo(): Cli = cli("todo") {
    description = "A tiny todo CLI"
    version = "1.0.0"
    command("add") {
        description = "Add a task"
        val text = argument("text").multiple(min = 1)
        val priority = option("--priority", "-p").int().default(0)
        val done = flag("--done", "-d")
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

    @Test
    fun aParsedInvocationExposesItsBoundValuesWithoutRunningTheAction() {
        // The escape hatch that makes a consumer's own parsing testable: assert an argv binds what you
        // expect, with no action, no output and no exit. Reading a value must not have side effects.
        var ran = false
        lateinit var name: Opt<String?>
        lateinit var loud: Flag
        val tree = cli("app") {
            name = option("--name", "-n", help = "who")
            loud = flag("--loud", "-l", help = "shout")
            action { ran = true; Ok("") }
        }
        val parsed = assertIs<Result.Success<Invocation>>(tree.parse(listOf("-n", "ada", "--loud"))).value
        val exec = assertIs<Invocation.Execute>(parsed)
        with(exec.inputs) {
            assertEquals("ada", name())
            assertEquals(true, loud())
        }
        assertFalse(ran, "reading inputs must not run the action")
    }
}
