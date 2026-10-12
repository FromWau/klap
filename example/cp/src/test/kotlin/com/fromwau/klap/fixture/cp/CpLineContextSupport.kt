package com.fromwau.klap.fixture.cp

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * One Tab press on a cp line, typed with `|` at the cursor, what real cp 9.12 would accept there and the matrix rows
 * it covers, and the checks every cp completion test runs on it. Every name is described in cp's own `cp --help`
 * words, taken from the table in CpModel.kt. The evidence is a run of real cp in a throwaway directory unless a
 * comment says the help page.
 */

/** The cells, matchers and checks of the cp completion tests. */
internal object CpLineContext {

    /** One Tab press on [typed] (`|` at the cursor), what cp would accept there, and the [rows] it covers. */
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

    /** [spelled] (`--verbose`, `-v`, `--help`) as `cp --help` describes it. */
    fun name(spelled: String): Suggestion = Suggestion(spelled, cpHelp(spelled))

    /** The words `cp --help` gives [spelled], a long or a one-letter short. */
    fun cpHelp(spelled: String): String = when {
        spelled == "--help" -> HELP_HELP
        spelled == "--version" -> VERSION_HELP
        spelled.startsWith("--") -> input(spelled).help
        else -> inputFor(spelled[1])!!.help
    }

    fun Cli.assertCell(cell: Cell) = assertTab("$cell", cell.line, cell.cursor, cell.offers)

    fun Cli.checkContext(vararg cells: Cell) = checkCells(cells.toList()) { assertCell(it) }

    /** Tab at [cursor] on [line] offers exactly [offers] once each. */
    fun Cli.assertTab(
        label: String,
        line: String,
        cursor: Int,
        offers: Collection<Suggestion>,
    ) {
        val actual = suggestionsFor(line, cursor)
        assertEquals(offers.sortedBy { it.value }, actual.sortedBy { it.value }, "$label: offered (cp's set first)")
        assertEquals(actual.size, actual.toSet().size, "$label: a suggestion is offered twice: $actual")
    }

    /** Tab on [typed] offers none of [refused], each a word cp refuses at that point. */
    fun Cli.assertOffersNone(
        rows: String,
        typed: String,
        refused: List<String>,
    ) {
        val offered = suggestionsFor(typed.replace("|", ""), typed.indexOf('|')).map { it.value }
        val wrong = refused.filter { it in offered }
        assertTrue(wrong.isEmpty(), "$rows `$typed`: offers $wrong, which cp refuses there")
    }

    /** Tab on [typed] offers every one of [wanted], each a word cp accepts at that point. */
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
