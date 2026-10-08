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
