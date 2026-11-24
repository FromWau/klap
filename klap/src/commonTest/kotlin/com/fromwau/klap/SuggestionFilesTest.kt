package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where Tab hands the path to the shell, the testing API reports files offered after a head, never a directive. */
class SuggestionFilesTest {

    private val c = cli("c") {
        option(short = 'O', long = "out", help = "where to write").file()
        argument("source").completeWith { completeFiles() }
        action { Ok("") }
    }

    private val lines = listOf("c --out=", "c --out=re", "c -O", "c -Ore", "c --out ", "c ")

    @Test
    fun `no suggestion carries the shell protocol text`() {
        for (line in lines) {
            val shown = c.suggestionsFor(line).toString()
            assertTrue(listOf(COMPLETE_FILES, ATTACHED_NAME).none { it in shown }, "`$line` offers $shown")
        }
    }

    @Test
    fun `a file option offers files after its attached name`() {
        assertEquals(listOf(Suggestion.Files("--out=")), c.suggestionsFor("c --out="))
        assertEquals(listOf(Suggestion.Files("--out=")), c.suggestionsFor("c --out=re"))
    }

    @Test
    fun `a file option offers files after its glued short`() {
        assertEquals(listOf(Suggestion.Files("-O")), c.suggestionsFor("c -Ore"))
    }

    @Test
    fun `a file option offers files for its own word`() {
        assertEquals(listOf(Suggestion.Files("")), c.suggestionsFor("c --out "))
    }

    @Test
    fun `a provider calling completeFiles offers files for the whole word`() {
        assertEquals(listOf(Suggestion.Files("")), c.suggestionsFor("c "))
    }

    @Test
    fun `directive text beside other words is offered as the word every shell inserts`() {
        val mixed = cli("m") {
            argument("source").completeWith {
                candidate(COMPLETE_FILES)
                candidate("red")
            }
            action { Ok("") }
        }
        assertEquals(
            listOf(Suggestion.Word(COMPLETE_FILES), Suggestion.Word("red")),
            mixed.suggestionsFor("m "),
        )
    }
}
