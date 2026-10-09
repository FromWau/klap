package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.klap.internal.render.completeCandidates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Completion with words after the cursor: `get .ow<TAB> conf.toml`, where the slot under the cursor needs an
 * input the user typed further along the line.
 */
class CompletionWholeLineTest {

    private fun tool(): Cli = cli("tool") {
        command("get") {
            val filter = argument("filter")
            val file = argument("file").optional()
            val raw = flag("--raw", "-r")
            filter.completeWith(filterByPrefix = false) { candidate("$current|${file()}|${raw()}") }
            action { Ok("") }
        }
        command("query") {
            val filter = argument("filter")
            val file = argument("file")
            filter.completeWith(filterByPrefix = false) { candidate("${filter()}|${file()}") }
            action { Ok("") }
        }
    }

    private fun Cli.completionsAt(cursor: Int, vararg words: String): List<String> =
        completeCandidates(words.toList(), cursor).map { it.value }

    @Test
    fun `a provider reads an input typed after the cursor`() {
        assertEquals(listOf(".ow|conf.toml|false"), tool().completionsAt(1, "get", ".ow", "conf.toml"))
        assertEquals(listOf(".ow|conf.toml|true"), tool().completionsAt(1, "get", ".ow", "-r", "conf.toml"))
    }

    @Test
    fun `a provider reads a global typed after the cursor instead of its default`() {
        val tasks = cli("tasks") {
            val store = globalOption("--file", "-f").default("tasks.json")
            command("done") {
                argument("id").completeWith { candidate(store()) }
                action { Ok("") }
            }
        }
        assertEquals(listOf("other.json"), tasks.completionsAt(1, "done", "", "--file", "other.json"))
        assertEquals(listOf("tasks.json"), tasks.completionsAt(1, "done", ""))
    }

    @Test
    fun `the word under the cursor keeps its slot so a later word lands in its own`() {
        assertEquals(listOf("|conf.toml|false"), tool().completionsAt(1, "get", "", "conf.toml"))
    }

    @Test
    fun `an input not on the line reads as absent`() {
        assertEquals(listOf(".ow|null|false"), tool().completionsAt(1, "get", ".ow"))
    }

    @Test
    fun `the planner reads only the words before the cursor`() {
        // `tool <TAB> get`: the slot under the cursor is still the subcommand's, whatever follows it.
        assertEquals(tool().completionsAt(0, ""), tool().completionsAt(0, "", "get"))
    }

    @Test
    fun `a provider reads the word under the cursor through its own input`() {
        assertEquals(listOf(".ow|conf.toml"), tool().completionsAt(1, "query", ".ow", "conf.toml"))
    }

    @Test
    fun `a required input not yet on the line offers nothing`() {
        assertEquals(emptyList(), tool().completionsAt(1, "query", ".ow"))
    }

    @Test
    fun `a variadic input leaves out the word under the cursor`() {
        val tags = cli("tasks") {
            command("add") {
                val tag = option("--tag").multiple()
                val labels = argument("label").multiple()
                labels.completeWith { candidates(listOf("red", "blue") - labels().toSet()) }
                tag.completeWith { candidates(listOf("red", "blue") - tag().toSet()) }
                action { Ok("") }
            }
        }
        // The word is typed in full, as when Tab is pressed to finish it with a space.
        assertEquals(listOf("red"), tags.completionsAt(1, "add", "red"))
        assertEquals(listOf("red"), tags.completionsAt(2, "add", "blue", "red"))
        assertEquals(listOf("red"), tags.completionsAt(1, "add", "red", "blue"))
        assertEquals(listOf("red"), tags.completionsAt(2, "add", "--tag", "red"))
        assertEquals(listOf("red"), tags.completionsAt(1, "add", "--tag=red"))
    }

    @Test
    fun `a variadic with a minimum leaves the operands after the cursor in their own slots`() {
        val cp = cli("cp") {
            command("cp") {
                val sources = argument("source").multiple(1)
                val target = argument("target")
                sources.completeWith(filterByPrefix = false) { candidate("${sources()}|${target()}") }
                action { Ok("") }
            }
        }
        assertEquals(listOf("[]|dest"), cp.completionsAt(1, "cp", "", "dest"))
    }

    @Test
    fun `a variadic option leaves out only the value under the cursor`() {
        val git = cli("git") {
            command("remote") {
                val tag = option("--tag").multiple()
                argument("name").optional()
                tag.completeWith(filterByPrefix = false) { candidate("tag=${tag()}") }
                command("add") { action { Ok("") } }
                action { Ok("") }
            }
        }
        assertEquals(listOf("tag=[]"), git.completionsAt(2, "remote", "--tag", "", "add"))
    }

    @Test
    fun `a value glued to a short cluster keeps the flags before it`() {
        val tool = cli("tool") {
            command("add") {
                val verbose = flag("--verbose", "-v")
                val tag = option("--tag", "-t").multiple()
                tag.completeWith(filterByPrefix = false) { candidate("verbose=${verbose()} tag=${tag()}") }
                action { Ok("") }
            }
        }
        assertEquals(listOf("verbose=true tag=[]"), tool.completionsAt(1, "add", "-vtre"))
        assertEquals(listOf("verbose=true tag=[]"), tool.completionsAt(2, "add", "-vt", "re"))
    }

    @Test
    fun `a slot removed by a trigger after the cursor is not offered`() {
        val chmod = cli("chmod") {
            val reference = option("--reference")
            argument("mode").absentWhen(reference).completeWith(filterByPrefix = false) { candidate("MODE") }
            argument("file").multiple(1).completeWith(filterByPrefix = false) { candidate("FILE") }
            action { Ok("") }
        }
        assertEquals(listOf("FILE"), chmod.completionsAt(0, "", "f", "--reference", "r"))
    }

    @Test
    fun `the slot under the cursor counts the operands after it`() {
        val cp = cli("cp") {
            command("cp") {
                val sources = argument("source").multiple()
                val target = argument("target")
                sources.completeWith(filterByPrefix = false) { candidate("source|${target()}") }
                target.completeWith(filterByPrefix = false) { candidate("target") }
                action { Ok("") }
            }
        }
        // Between `a` and `b` the word is a second source: `b`, the last operand, is the target.
        assertEquals(listOf("source|b"), cp.completionsAt(2, "cp", "a", "", "b"))
        assertEquals(listOf("target"), cp.completionsAt(2, "cp", "a", ""))
    }

    @Test
    fun `a word under the cursor naming a subcommand does not take the parents provider into it`() {
        val git = cli("git") {
            abbreviation = Abbreviation.All
            command("remote") {
                val url = option("--url")
                argument("name").optional().completeWith(filterByPrefix = false) { candidate("$current|${url()}") }
                command("add") { action { Ok("") } }
                action { Ok("") }
            }
        }
        val offered = git.completionsAt(1, "remote", "a", "--url", "x")
        assertTrue("a|x" in offered, "$offered")
    }

    @Test
    fun `a cursor that is no index into the words is the last word`() {
        val terminal = RecordingTerminal()
        tool().run(arrayOf("__complete", "--cursor", "x", "--", "get", ".ow"), terminal)
        assertEquals(".ow|null|false", terminal.out.toString().trim())
    }

    @Test
    fun `complete takes the cursor before the end of options marker`() {
        val terminal = RecordingTerminal()
        val code = tool().run(arrayOf("__complete", "--cursor", "1", "--", "get", ".ow", "conf.toml"), terminal)
        assertEquals(0, code)
        assertEquals(".ow|conf.toml|false", terminal.out.toString().trim())
    }

    @Test
    fun `complete without a cursor takes the last word as the one under it`() {
        val terminal = RecordingTerminal()
        val code = tool().run(arrayOf("__complete", "--", "get", ".ow"), terminal)
        assertEquals(0, code)
        assertEquals(".ow|null|false", terminal.out.toString().trim())
    }

    @Test
    fun `every shell script passes the cursor`() {
        for (shell in CompletionShell.entries) {
            val script = tool().renderCompletion(shell)
            assertTrue("__complete --cursor" in script, "$shell:\n$script")
        }
    }
}
