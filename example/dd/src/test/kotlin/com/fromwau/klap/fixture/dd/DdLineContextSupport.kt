package com.fromwau.klap.fixture.dd

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * One Tab press on a dd line, typed with `|` at the cursor, what real dd 9.12 would accept there and the matrix rows it
 * covers, and the checks every dd completion test runs on it. Every key and symbol is described in dd's own `dd
 * --help` words, taken from the table in DdModel.kt.
 */

/** The cells, matchers and checks of the dd completion tests. */
internal object DdLineContext {

    /** One Tab press on [typed] (`|` at the cursor), what dd would accept there, and the [rows] it covers. */
    data class Cell(
        val rows: String,
        val typed: String,
        val offers: List<Suggestion>,
    ) {
        init {
            require(typed.count { it == '|' } == 1) { "one | marks the cursor in `$typed`" }
        }

        val line: String = typed.replace("|", "")
        val cursor: Int = typed.indexOf('|')

        override fun toString(): String = "$rows `$typed`"
    }

    val NOTHING: List<Suggestion> = emptyList()

    fun at(
        rows: String,
        typed: String,
        offers: List<Suggestion>,
    ): Cell = Cell(rows, typed, offers)

    /** The file directive for the value of [key]: the shell completes the path after `key=`. */
    fun filesOf(key: String): List<Suggestion> = listOf(filesAfter("$key="))

    /** `key=sym` for every symbol of [symbols] after [prefix] (`conv=` or `conv=ucase,`), each in dd's words. */
    fun valuesAfter(
        prefix: String,
        symbols: List<Symbol>,
    ): List<Suggestion> = symbols.map { Suggestion(prefix + it.name, it.help) }

    fun Cli.assertCell(cell: Cell) = assertTab("$cell", cell.line, cell.cursor, cell.offers)

    fun Cli.checkContext(vararg cells: Cell) = checkCells(cells.toList()) { assertCell(it) }

    fun Cli.checkContext(cells: List<Cell>) = checkCells(cells) { assertCell(it) }

    /** Checks only the values offered, where a cell's claim is the words and not their descriptions. */
    fun Cli.checkContextValues(vararg cells: Cell) = checkContextValues(cells.toList())

    fun Cli.checkContextValues(cells: List<Cell>) =
        checkCells(cells) { assertValues("$it", it.line, it.cursor, it.offers) }

    /** Tab at [cursor] on [line] offers exactly [offers] once each, sorted as the shell sorts them. */
    fun Cli.assertTab(
        label: String,
        line: String,
        cursor: Int,
        offers: Collection<Suggestion>,
    ) {
        val actual = suggestionsFor(line, cursor)
        assertEquals(offers.sortedBy { it.value }, actual.sortedBy { it.value }, "$label: offered (dd's set first)")
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
        assertEquals(offers.map { it.value }.sorted(), actual.sorted(), "$label: values offered (dd's set first)")
    }

    /** What Tab offers on [typed], `|` at the cursor. */
    fun Cli.offeredOn(typed: String): List<Suggestion> = suggestionsFor(typed.replace("|", ""), typed.indexOf('|'))

    /** Tab on [typed] offers none of [refused], each a word dd refuses at that point. */
    fun Cli.assertOffersNone(
        rows: String,
        typed: String,
        refused: List<String>,
    ) {
        val offered = offeredOn(typed).map { it.value }
        val wrong = refused.filter { it in offered }
        assertTrue(wrong.isEmpty(), "$rows `$typed`: offers $wrong, which dd refuses there")
    }

    /** Tab on [typed] offers every value of [wanted], whatever it describes them with. */
    fun Cli.assertOffersValues(
        rows: String,
        typed: String,
        wanted: List<String>,
    ) {
        val offered = offeredOn(typed).map { it.value }
        val missing = wanted.filterNot { it in offered }
        assertTrue(missing.isEmpty(), "$rows `$typed`: must offer $missing, offered $offered")
    }
}
