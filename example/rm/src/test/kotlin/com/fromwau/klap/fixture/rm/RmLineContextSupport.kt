package com.fromwau.klap.fixture.rm

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * One Tab press on an rm line, typed with `|` at the cursor, what real rm 9.12 would accept there and the matrix rows
 * it covers, and the checks every rm completion test runs on it. Every name is described in rm's own `rm --help`
 * words, taken from the table in RmModel.kt. The evidence is a run of real rm in a throwaway directory unless a
 * comment says the help page or getopt.
 */

/** The cells, matchers and checks of the rm completion tests. */
internal object RmLineContext {

    /** One Tab press on [typed] (`|` at the cursor), what rm would accept there, and the [rows] it covers. */
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

    /** [spelled] (`--verbose`, `-v`, `--help`) as `rm --help` describes it. */
    fun name(spelled: String): Suggestion = Suggestion(spelled, rmHelp(spelled))

    /** The words `rm --help` gives [spelled], a long or a one-letter short. */
    fun rmHelp(spelled: String): String = when {
        spelled == "--help" -> HELP_HELP
        spelled == "--version" -> VERSION_HELP
        spelled.startsWith("--") -> input(spelled).help
        else -> inputFor(spelled[1])!!.help
    }

    /** [value] attached to [spelled], the way the long [long] is typed on the line, described by rm's help for it. */
    fun attached(
        spelled: String,
        value: String,
        long: String = spelled,
    ): Suggestion = Suggestion("$spelled=$value", input(long).help)

    /** Every value of [WHEN_WORDS] starting with [typed], attached to [spelled] as `--interactive` is typed. */
    fun whenValues(
        typed: String,
        spelled: String = "--interactive",
    ): Array<Suggestion> = WHEN_WORDS
        .filter { it.startsWith(typed) }
        .map { attached(spelled, it, "--interactive") }
        .toTypedArray()

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
        assertEquals(offers.sortedBy { it.value }, actual.sortedBy { it.value }, "$label: offered (rm's set first)")
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
        assertEquals(offers.map { it.value }.sorted(), actual.sorted(), "$label: values offered (rm's set first)")
    }

    /** Tab on [typed] offers none of [refused], each a word rm refuses at that point. */
    fun Cli.assertOffersNone(
        rows: String,
        typed: String,
        refused: List<String>,
    ) {
        val offered = suggestionsFor(typed.replace("|", ""), typed.indexOf('|')).map { it.value }
        val wrong = refused.filter { it in offered }
        assertTrue(wrong.isEmpty(), "$rows `$typed`: offers $wrong, which rm refuses there")
    }

    /** Tab on [typed] offers every one of [wanted], each a word rm accepts at that point. */
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
