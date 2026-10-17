package com.fromwau.klap.fixture.ls

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * One Tab press on an ls line, typed with `|` at the cursor, what real ls 9.12 would accept there and the matrix rows
 * it covers, and the checks every ls completion test runs on it. Every name is described in ls's own `ls --help`
 * words, taken from the table in LsModel.kt. The evidence is a run of real ls in a throwaway directory unless a
 * comment says the help page or getopt.
 */

/** The cells, matchers and checks of the ls completion tests. */
internal object LsLineContext {

    /** One Tab press on [typed] (`|` at the cursor), what ls would accept there, and the [rows] it covers. */
    data class Cell(
        val rows: String,
        val typed: String,
        val offers: List<Suggestion>,
    ) {
        val line: String = typed.replace("|", "")
        val cursor: Int = typed.indexOf('|')

        override fun toString(): String = "$rows `$typed`"
    }

    val FILES: Suggestion = filesAfter()

    fun at(
        rows: String,
        typed: String,
        vararg offers: Suggestion,
    ): Cell = Cell(rows, typed, offers.toList())

    /** [spelled] (`--all`, `-a`, `--help`) as `ls --help` describes it. */
    fun name(spelled: String): Suggestion = Suggestion(spelled, lsHelp(spelled))

    /** The words `ls --help` gives [spelled], a long or a one-letter short. */
    fun lsHelp(spelled: String): String = when {
        spelled == "--help" -> HELP_HELP
        spelled == "--version" -> VERSION_HELP
        spelled.startsWith("--") -> input(spelled).help
        else -> inputFor(spelled[1])!!.help
    }

    /** Every word of the value of [long] starting with [typed], attached to [spelled], described by its help. */
    fun attached(
        long: String,
        typed: String = "",
        spelled: String = long,
    ): Array<Suggestion> = (input(long).kind as Kind.Value)
        .words!!
        .filter { it.startsWith(typed) }
        .map { Suggestion("$spelled=$it", input(long).help) }
        .toTypedArray()

    /** Every word of the value of [long] starting with [typed], in its own word: no description. */
    fun spaced(
        long: String,
        typed: String = "",
    ): Array<Suggestion> = (input(long).kind as Kind.Value)
        .words!!
        .filter { it.startsWith(typed) }
        .map { Suggestion(it) }
        .toTypedArray()

    fun Cli.assertCell(cell: Cell) = assertTab("$cell", cell.line, cell.cursor, cell.offers)

    fun Cli.checkContext(vararg cells: Cell) = checkCells(cells.toList()) { assertCell(it) }

    /** Checks only the values offered, where a cell's claim is the names and not their descriptions. */
    fun Cli.checkContextValues(vararg cells: Cell) =
        checkCells(cells.toList()) { assertValues("$it", it.line, it.cursor, it.offers) }

    /** Tab at [cursor] on [line] offers exactly [offers] once each. */
    fun Cli.assertTab(
        label: String,
        line: String,
        cursor: Int,
        offers: Collection<Suggestion>,
    ) {
        val actual = suggestionsFor(line, cursor)
        assertEquals(offers.sortedBy { it.value }, actual.sortedBy { it.value }, "$label: offered (ls's set first)")
        assertEquals(actual.size, actual.toSet().size, "$label: a suggestion is offered twice: $actual")
    }

    /** Tab at [cursor] on [line] offers exactly the values of [offers], whatever it describes them with. */
    fun Cli.assertValues(
        label: String,
        line: String,
        cursor: Int,
        offers: Collection<Suggestion>,
    ) {
        val actual = suggestionsFor(line, cursor).map { it.value }
        assertEquals(offers.map { it.value }.sorted(), actual.sorted(), "$label: values offered (ls's set first)")
    }

    /** Tab on [typed] offers none of [refused], each a word ls refuses at that point. */
    fun Cli.assertOffersNone(
        rows: String,
        typed: String,
        refused: List<String>,
    ) {
        val offered = suggestionsFor(typed.replace("|", ""), typed.indexOf('|')).map { it.value }
        val wrong = refused.filter { it in offered }
        assertTrue(wrong.isEmpty(), "$rows `$typed`: offers $wrong, which ls refuses there")
    }

    /** Tab on [typed] offers every one of [wanted], each a word ls accepts at that point. */
    fun Cli.assertOffersAll(
        rows: String,
        typed: String,
        wanted: List<String>,
    ) {
        val offered = suggestionsFor(typed.replace("|", ""), typed.indexOf('|')).map { it.value }
        val missing = wanted.filterNot { it in offered }
        assertTrue(missing.isEmpty(), "$rows `$typed`: must offer $missing, offered $offered")
    }
}
