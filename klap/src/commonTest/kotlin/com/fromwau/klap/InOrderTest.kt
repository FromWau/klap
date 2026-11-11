package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.terminal.test.RecordingTerminal
import com.fromwau.klap.internal.render.completeCandidates
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `an operand occurs once for each value at its place on the line`() {
        val tree = cliOf("check") {
            val file = option(short = 'f').multiple()
            val names = argument("file").multiple()
            action { Ok("") }
            projection { inOrder(file, names).map { "${it.name}=${it.value}" } }
        }
        assertEquals(listOf("file=b", "-f=a", "file=c"), tree.parse(listOf("b", "-f", "a", "c")).assertSuccess())
    }

    @Test
    fun `an operand occurrence carries its converted value`() {
        val tree = cliOf("sum") {
            val first = argument("first").int()
            val rest = argument("rest").int().multiple()
            action { Ok("") }
            projection { inOrder(rest, first).map { it.value } }
        }
        assertEquals(listOf(1, 2, 3), tree.parse(listOf("1", "2", "3")).assertSuccess())
    }

    @Test
    fun `a completion provider leaves out the operand being typed`() {
        val tree = cli("cp") {
            val dereference = flag(short = 'L')
            val source = argument("source").multiple()
            source.completeWith {
                candidate(inOrder(dereference, source).joinToString("") { "${it.value}" })
            }
            action { Ok("") }
        }
        assertEquals(listOf("xtrue"), tree.completeCandidates(listOf("x", "-L", "")).map { it.value })
    }

    private fun chmod() = cliOf("chmod") {
        val verbose = flag(short = 'v')
        val mode = argument("mode").dashLed(",") { it.startsWith("-w") || it.startsWith("-x") }
        val file = argument("file").multiple()
        action { Ok("") }
        projection { inOrder(mode, verbose, file).map { "${it.name}=${it.value}" } }
    }

    @Test
    fun `a gathering operand occurs once at its first word with the value it bound`() {
        assertEquals(
            listOf("mode=-w,-x", "-v=true", "file=f"),
            chmod().parse(listOf("-w", "-v", "-x", "f")).assertSuccess(),
        )
    }

    @Test
    fun `a gathering operand filled by a plain word occurs as any operand does`() {
        assertEquals(
            listOf("-v=true", "mode=u+x", "file=f"),
            chmod().parse(listOf("-v", "u+x", "f")).assertSuccess(),
        )
    }

    @Test
    fun `a gathering operand that takes nothing does not occur`() {
        val tree = cliOf("chmod") {
            val verbose = flag(short = 'v')
            val mode = argument("mode").dashLed(",") { it.startsWith("-w") }.optional()
            action { Ok("") }
            projection { inOrder(mode, verbose).map { "${it.name}=${it.value}" } }
        }
        assertEquals(listOf("-v=true"), tree.parse(listOf("-v")).assertSuccess())
    }

    @Test
    fun `a provider sees the words a gathering operand finished`() {
        val tree = cli("chmod") {
            val mode = argument("mode").dashLed(",") { it.startsWith("-w") || it.startsWith("-x") }
            val file = argument("file").multiple()
            file.completeWith {
                candidate("-x" + inOrder(mode).joinToString(",") { "${it.value}" } + ":" + mode.isDashLed)
            }
            action { Ok("") }
        }
        assertEquals(listOf("-x-w:true"), tree.completeCandidates(listOf("-w", "")).map { it.value })
    }

    @Test
    fun `a provider leaves out the single operand being typed as it does a variadic one`() {
        val tree = cli("cp") {
            val dereference = flag(short = 'L')
            val source = argument("source")
            source.completeWith {
                candidate("ca" + inOrder(dereference, source).joinToString(",") { "${it.value}" })
            }
            action { Ok("") }
        }
        assertEquals(listOf("catrue"), tree.completeCandidates(listOf("-L", "")).map { it.value })
        assertEquals(listOf("catrue"), tree.completeCandidates(listOf("-L", "ca")).map { it.value })
    }
}

/** The spelling an occurrence's handle was declared under, to show a list of them compactly. */
private val Occurrence.name: String get() = (input as Input).name
