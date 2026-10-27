package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.terminal.Styled
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.bold
import com.fromwau.kern.terminal.green
import com.fromwau.kern.terminal.out
import com.fromwau.kern.terminal.test.RecordingTerminal
import com.fromwau.kern.terminal.yellow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val ESC = Char(27).toString()

/** Styled text from an action or from help is coloured only when the line's colour decision says so. */
class StyleTest {

    private fun Cli.output(vararg argv: String, ansi: Boolean): String =
        RecordingTerminal(ansi = ansi).also { run(arrayOf(*argv), it) }.out

    private val styled = cli("app") { command("go") { action { Ok(yellow("ok")) } } }

    @Test
    fun `an actions styled output is coloured on a colour terminal`() {
        assertEquals("$ESC[33mok$ESC[0m\n", styled.output("go", ansi = true))
    }

    @Test
    fun `an actions styled output is plain when piped`() {
        assertEquals("ok\n", styled.output("go", ansi = false))
    }

    @Test
    fun `color never prints an actions styled output plain on a colour terminal`() {
        assertEquals("ok\n", styled.output("--color=never", "go", ansi = true))
    }

    @Test
    fun `color always colours an actions styled output when piped`() {
        assertEquals("$ESC[33mok$ESC[0m\n", styled.output("--color=always", "go", ansi = false))
    }

    @Test
    fun `json writes a styled value as its plain text`() {
        assertEquals("\"ok\"\n", styled.output("--color=always", "--json", "go", ansi = true))
    }

    @Test
    fun `a plain string action prints unchanged on a colour terminal`() {
        val plain = cli("app") { command("go") { action { Ok("ok") } } }
        assertEquals("ok\n", plain.output("go", ansi = true))
    }

    @Test
    fun `a human renderer may join styled pieces and strings`() {
        val tree = cli("app") {
            command("go") { action(human = { name -> green("added ") + bold(name) }) { Ok("ada") } }
        }
        assertEquals("$ESC[32madded $ESC[0m$ESC[1mada$ESC[0m\n", tree.output("go", ansi = true))
        assertEquals("added ada\n", tree.output("go", ansi = false))
    }

    @Test
    fun `help is bold on a colour terminal and plain under color never and when piped`() {
        val tree = cli("app") { command("go") { action { Ok("") } } }
        assertTrue(tree.output("--help", ansi = true).startsWith("$ESC[1musage:$ESC[0m app"))
        assertFalse(ESC in tree.output("--color=never", "--help", ansi = true))
        assertFalse(ESC in tree.output("--help", ansi = false))
    }

    private val ticking = cli("app") {
        command("go") {
            action {
                terminal.out(yellow("tick") + "\n")
                Ok("done")
            }
        }
    }

    @Test
    fun `an action writes coloured text through its terminal ahead of its returned output`() {
        assertEquals("$ESC[33mtick$ESC[0m\ndone\n", ticking.output("go", ansi = true))
    }

    @Test
    fun `an actions own writes are plain under color never`() {
        assertEquals("tick\ndone\n", ticking.output("--color=never", "go", ansi = true))
    }

    @Test
    fun `an actions own writes are plain when piped`() {
        assertEquals("tick\ndone\n", ticking.output("go", ansi = false))
    }

    @Test
    fun `color always colours an actions own writes when piped`() {
        assertEquals("$ESC[33mtick$ESC[0m\ndone\n", ticking.output("--color=always", "go", ansi = false))
    }

    @Test
    fun `a suspending action writes through the same terminal`() = runTest {
        val tree = cli("app") {
            actionSuspending {
                terminal.out(yellow("tick") + "\n")
                Ok("done")
            }
        }
        val colour = RecordingTerminal(ansi = true)
        tree.runSuspending(emptyArray(), colour)
        assertEquals("$ESC[33mtick$ESC[0m\ndone\n", colour.out)
        val never = RecordingTerminal(ansi = true)
        tree.runSuspending(arrayOf("--color=never"), never)
        assertEquals("tick\ndone\n", never.out)
    }

    @Test
    fun `without color an action is handed the callers own terminal`() {
        val recorder = RecordingTerminal()
        var seen: Terminal? = null
        cli("app") {
            action {
                seen = terminal
                Ok("")
            }
        }.run(emptyArray(), recorder)
        assertSame(recorder, seen)
    }

    @Test
    fun `without color the callers terminal decides colour when each line is written`() {
        val recorder = RecordingTerminal()
        val switching = object : Terminal by recorder {
            var colour = false
            override val ansi: Boolean get() = colour
        }
        val tree = cli("app") {
            action {
                switching.colour = true
                Ok(yellow("ok"))
            }
        }
        tree.run(emptyArray(), switching)
        assertEquals("$ESC[33mok$ESC[0m\n", recorder.out)
    }

    @Test
    fun `json writes any char sequence value as its text`() {
        val tree = cli("app") { action { Ok(StringBuilder("ok")) } }
        assertEquals("\"ok\"\n", tree.output("--json", ansi = false))
    }

    @Test
    fun `json writes a null styled value as null like any other null`() {
        val styledNull = cli("app") { action<Styled?> { Ok(null) } }
        val stringNull = cli("app") { action<String?> { Ok(null) } }
        assertEquals(stringNull.output("--json", ansi = false), styledNull.output("--json", ansi = false))
        assertEquals("null\n", styledNull.output("--json", ansi = false))
    }
}
