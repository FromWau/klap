package com.fromwau.klap.fixture.find

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.find.FindCompletionSupport.CompletionCell
import com.fromwau.klap.fixture.find.FindCompletionSupport.PREDICATES
import com.fromwau.klap.fixture.find.FindCompletionSupport.assertFind
import com.fromwau.klap.fixture.marked
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.assertTrue

/*
 * Cells for the L and S tables of the testing matrix on a find line: one Tab press, typed with `|` at the cursor,
 * what real find (GNU findutils 4.11.0) would accept there through the fixture's respelling (F1: `-name` is
 * `--name`), and the matrix rows it covers. The evidence is a run of /usr/bin/find in a throwaway directory unless
 * a comment says the man page.
 */

/** The cells, matchers and checks of [FindLineContextTest]. */
internal object FindLineContext {

    /** One Tab press on [typed] (`|` at the cursor), what find would accept there, and the [rows] it covers. */
    data class Cell(
        val rows: String,
        val typed: String,
        val offers: List<Suggestion>,
    ) {
        val line: String = typed.replace("|", "")
        val cursor: Int = typed.indexOf('|')

        override fun toString(): String = "$rows ${marked(line, cursor)}"
    }

    /** `-O` with each level `man find` documents glued, as `find -O .` refuses a bare `-O`. */
    val LEVELS: List<String> = FindCompletionSupport.LEVELS.map { it.value }

    /** find's pre-path switches, which real find takes only before the first starting point and predicate. */
    val PRE_PATH: List<String> = listOf("-P", "-L", "-H", "-D") + LEVELS

    /** `--newer` and the twelve `--newerXY` forms it starts. */
    val NEWER: Array<Suggestion> = PREDICATES
        .filter { it.name.startsWith("--newer") }
        .map { name(it.name) }
        .toTypedArray()

    fun at(
        rows: String,
        typed: String,
        vararg offers: Suggestion,
    ): Cell = Cell(rows, typed, offers.toList())

    /** The predicate [spelled] (`--maxdepth`) with its help. */
    fun name(spelled: String): Suggestion = Suggestion(spelled, PREDICATES.single { it.name == spelled }.help)

    /** Every `--print` family name `--pr` completes to. */
    val PR: Array<Suggestion> = arrayOf(name("--print"), name("--print0"), name("--printf"), name("--prune"))

    /** Tab on [cell] offers exactly its set once each. */
    fun Cli.assertCell(cell: Cell) = assertFind(
        CompletionCell(cell.rows, cell.rows, cell.line, cell.cursor, cell.offers.toSet()),
    )

    fun Cli.checkContext(vararg cells: Cell) = checkCells(cells.toList()) { assertCell(it) }

    /** Tab on [typed] offers none of [refused], each a word find refuses at that point. */
    fun Cli.assertOffersNone(
        rows: String,
        typed: String,
        refused: List<String>,
    ) {
        val line = typed.replace("|", "")
        val cursor = typed.indexOf('|')
        val offered = suggestionsFor(line, cursor).map { it.value }
        val wrong = refused.filter { it in offered }
        assertTrue(wrong.isEmpty(), "$rows ${marked(line, cursor)}: offers $wrong, which find refuses there")
    }

    /** Tab on [typed] offers every one of [wanted], each a word find accepts at that point. */
    fun Cli.assertOffersAll(
        rows: String,
        typed: String,
        wanted: List<String>,
    ) {
        val line = typed.replace("|", "")
        val cursor = typed.indexOf('|')
        val offered = suggestionsFor(line, cursor).map { it.value }
        val missing = wanted.filterNot { it in offered }
        assertTrue(missing.isEmpty(), "$rows ${marked(line, cursor)}: must offer $missing, offered $offered")
    }
}
