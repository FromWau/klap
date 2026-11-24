package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandSpecTest {

    private object Greet : CommandSpec {
        override val name = "greet"

        override fun CommandBuilder.build() {
            help = "say hello"
            aliases = listOf("hi")
            val who = argument("name", "who to greet")
            val loud = flag(short = 'l', long = "loud", help = "shout it")
            action { Ok(if (loud()) "HELLO, ${who().uppercase()}" else "hello, ${who()}") }
        }
    }

    private class Group(override val name: String, private val children: List<CommandSpec>) : CommandSpec {
        override fun CommandBuilder.build() {
            help = "a group of $name"
            children.forEach { command(it) }
        }
    }

    private fun Cli.runText(vararg args: String): Pair<Int, String> {
        val terminal = RecordingTerminal()
        val code = run(args.toList(), terminal)
        return code to terminal.out + terminal.err
    }

    @Test
    fun `a mounted spec routes and runs as its name`() {
        val tree = cli("app") { command(Greet) }
        assertEquals(0 to "HELLO, ADA\n", tree.runText("greet", "-l", "Ada"))
    }

    @Test
    fun `the help and aliases a spec sets inside build are the command's`() {
        val tree = cli("app") { command(Greet) }
        assertEquals(0 to "hello, Ada\n", tree.runText("hi", "Ada"))
        assertEquals("say hello", tree.subcommand("greet")?.description)
    }

    @Test
    fun `a spec mounts the same tree as the command block it stands for`() {
        val mounted = cli("app") { command(Group("tools", listOf(Greet))) }
        val inline = cli("app") {
            command("tools") {
                help = "a group of tools"
                command("greet") { with(Greet) { build() } }
            }
        }
        for (line in listOf(listOf("--help"), listOf("tools", "--help"), listOf("tools", "greet", "--help"))) {
            assertEquals(inline.runText(*line.toTypedArray()), mounted.runText(*line.toTypedArray()), "$line")
        }
        assertEquals(0 to "hello, Ada\n", mounted.runText("tools", "greet", "Ada"))
    }

    @Test
    fun `one spec mounted under two parents gets its own inputs at each`() {
        val tree = cli("app") {
            command(Group("left", listOf(Greet)))
            command(Group("right", listOf(Greet)))
        }
        assertEquals(0 to "HELLO, ADA\n", tree.runText("left", "greet", "-l", "Ada"))
        assertEquals(0 to "hello, bo\n", tree.runText("right", "greet", "bo"))
    }
}
