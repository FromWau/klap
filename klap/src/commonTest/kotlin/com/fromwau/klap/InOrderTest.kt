package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.terminal.test.RecordingTerminal
import com.fromwau.klap.internal.render.completeCandidates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `inOrder(...)`: the occurrences of some inputs in the order the line gives them, each with its value, for a
 * reader that replays them as a getopt loop assigns its settings (`cp -d -L` follows links, `cp -L -d` does not).
 */
class InOrderTest {

    private fun cp() = cliOf("cp") {
        val archive = flag(short = 'a', long = "archive")
        val dereference = flag(short = 'L')
        val noDereference = flag(short = 'P')
        val preserve = option(long = "preserve").optionalValue().multiple()
        val suffix = option(short = 'S', long = "suffix")
        argument("source").multiple()
        action { Ok("") }
        projection {
            inOrder(archive, dereference, noDereference, preserve, suffix).map { "${it.name}=${it.value}" }
        }
    }

    private fun bound(vararg argv: String): List<String>? = cp().parse(argv.toList()).assertSuccess()

    @Test
    fun `occurrences come in line order with their values`() {
        assertEquals(
            listOf("--archive=true", "-L=true", "--preserve=Given(value=mode)", "--archive=true"),
            bound("-a", "-L", "--preserve=mode", "x", "--archive"),
        )
    }

    @Test
    fun `a short cluster orders its letters as written`() {
        assertEquals(listOf("-L=true", "--archive=true"), bound("-La"))
        assertEquals(listOf("--archive=true", "-L=true"), bound("-aL"))
    }

    @Test
    fun `every occurrence of a single valued option is listed`() {
        assertEquals(listOf("--suffix=.x", "-L=true", "--suffix=.y"), bound("-S", ".x", "-L", "--suffix=.y"))
    }

    @Test
    fun `a bare occurrence carries Bare`() {
        assertEquals(listOf("--preserve=Bare", "-P=true"), bound("--preserve", "-P"))
    }

    @Test
    fun `a line without the inputs gives no occurrences`() {
        assertEquals(emptyList(), bound("x"))
    }

    @Test
    fun `an input left out of the call is left out of the list`() {
        val tree = cliOf("cp") {
            val dereference = flag(short = 'L')
            val noDereference = flag(short = 'P')
            action { Ok("") }
            projection { inOrder(noDereference).map { it.name } }
        }
        assertEquals(listOf("-P"), tree.parse(listOf("-L", "-P", "-L")).assertSuccess())
    }

    @Test
    fun `a when over the input replays the line`() {
        val tree = cliOf("cp") {
            val noDereferencePreserveLinks = flag(short = 'd')
            val dereference = flag(short = 'L')
            action { Ok("") }
            projection {
                var follows: Boolean? = null
                for (occurrence in inOrder(noDereferencePreserveLinks, dereference)) {
                    when (occurrence.input) {
                        noDereferencePreserveLinks -> follows = false
                        dereference -> follows = true
                    }
                }
                follows
            }
        }
        assertEquals(true, tree.parse(listOf("-d", "-L")).assertSuccess())
        assertEquals(false, tree.parse(listOf("-L", "-d")).assertSuccess())
    }

    @Test
    fun `a negative spelling carries false`() {
        val tree = cliOf("ssh") {
            val forward = flag(short = 'A').negatable(short = 'a')
            action { Ok("") }
            projection { inOrder(forward).map { it.value } }
        }
        assertEquals(listOf(true, false, true), tree.parse(listOf("-A", "-a", "-A")).assertSuccess())
    }

    @Test
    fun `a converted value is what an occurrence carries`() {
        val tree = cliOf("head") {
            val lines = option(short = 'n').int()
            action { Ok("") }
            projection { inOrder(lines).map { it.value } }
        }
        assertEquals(listOf(1, 2), tree.parse(listOf("-n", "1", "-n2")).assertSuccess())
    }

    @Test
    fun `a last wins loser keeps its occurrence`() {
        val tree = cliOf("cp") {
            val interactive = flag(short = 'i')
            val noClobber = flag(short = 'n')
            lastWins(interactive, noClobber)
            action { Ok("") }
            projection { inOrder(interactive, noClobber).map { it.name } to interactive() }
        }
        assertEquals(listOf("-i", "-n") to false, tree.parse(listOf("-i", "-n")).assertSuccess())
    }

    @Test
    fun `a global orders against the command's own inputs`() {
        val tree = cliOf("git") {
            val quiet = globalFlag(short = 'q')
            val pager = globalOption(long = "pager")
            command("log") {
                val oneline = flag(long = "oneline")
                action { Ok("") }
                projection { inOrder(quiet, oneline, pager).map { it.name } }
            }
        }
        assertEquals(
            listOf("-q", "--oneline", "--pager", "-q"),
            tree.parse(listOf("-q", "log", "--oneline", "--pager=less", "-q")).assertSuccess(),
        )
    }

    @Test
    fun `an action reads the same order`() {
        val tree = cli("cp") {
            val dereference = flag(short = 'L')
            val noDereference = flag(short = 'P')
            action { Ok(inOrder(dereference, noDereference).joinToString(",") { it.name }) }
        }
        val term = RecordingTerminal()
        tree.run(arrayOf("-P", "-L"), term)
        assertEquals("-P,-L\n", term.out)
    }

    @Test
    fun `a completion provider reads the order of the line`() {
        val tree = cli("cp") {
            val dereference = flag(short = 'L')
            val noDereference = flag(short = 'P')
            argument("source").multiple().completeWith {
                candidate(inOrder(dereference, noDereference).joinToString("") { it.name })
            }
            action { Ok("") }
        }
        assertEquals(listOf("-P-L"), tree.completeCandidates(listOf("-P", "-L", "")).map { it.value })
    }

    @Test
    fun `an operand has no occurrences to give`() {
        val tree = cliOf("cp") {
            val source = argument("source")
            action { Ok("") }
            projection { inOrder(source) }
        }
        assertFailsWith<IllegalArgumentException> { tree.parse(listOf("a")) }
    }
}

/** The spelling an occurrence's handle was declared under, to show a list of them compactly. */
private val Occurrence.name: String get() = (input as Input).name
