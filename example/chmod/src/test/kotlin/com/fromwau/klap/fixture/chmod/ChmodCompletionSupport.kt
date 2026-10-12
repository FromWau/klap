package com.fromwau.klap.fixture.chmod

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.marked
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * One Tab press on a chmod line, typed with `|` at the cursor, what real chmod 9.12 would accept there and the matrix
 * rows it covers, and the checks every chmod completion test runs on it. Every name is described in chmod's own
 * `chmod --help` words, from the table in ChmodModel.kt. The evidence is a run of real chmod in a throwaway directory
 * unless a comment says the help page.
 */

/** The cells, matchers and checks of the chmod completion tests. */
internal object ChmodTab {

    /** One Tab press on [typed] (`|` at the cursor), what chmod would accept there, and the [rows] it covers. */
    data class Cell(
        val rows: String,
        val typed: String,
        val offers: List<Suggestion>,
    ) {
        val line: String = typed.replace("|", "")
        val cursor: Int = typed.indexOf('|')

        init {
            require(cursor >= 0 && typed.indexOf('|', cursor + 1) < 0) { "one | marks the cursor in `$typed`" }
        }

        override fun toString(): String = "$rows ${marked(line, cursor)}"
    }

    val FILES: Suggestion = filesAfter()

    fun at(
        rows: String,
        typed: String,
        vararg offers: Suggestion,
    ): Cell = Cell(rows, typed, offers.toList())

    fun at(
        rows: String,
        typed: String,
        offers: List<Suggestion>,
    ): Cell = Cell(rows, typed, offers)

    /** [spelled] (`--verbose`, `-v`, `--help`) as `chmod --help` describes it. */
    fun name(spelled: String): Suggestion = Suggestion(spelled, chmodHelp(spelled))

    /** The fixture's mode candidates that start with [typed], in its order, with no description. */
    fun modes(typed: String = ""): List<Suggestion> = MODE_CANDIDATES.filter { it.startsWith(typed) }.map(::Suggestion)

    /** Every name of real chmod starting with [typed], the longs only when [typed] starts with two dashes. */
    fun names(typed: String): List<Suggestion> {
        val spellings = INPUTS.flatMap { it.spellings } + listOf("--help", "--version")
        return spellings.filter { it.startsWith(typed) }.map(::name)
    }

    /**
     * What a cluster of chmod's flag letters [cluster] continues with: each letter not in it yet, described by that
     * letter's own help. chmod counts no letter, and a mode letter after a flag makes the word an invalid mode
     * (`chmod -Rw f`: "invalid mode: '-Rw'"), so only flag letters follow.
     */
    fun continuations(cluster: String): List<Suggestion> = INPUTS
        .mapNotNull { input -> input.short?.let { it to input } }
        .filter { (letter, _) -> letter !in cluster.drop(1) }
        .map { (letter, input) -> Suggestion(cluster + letter, input.help) }

    fun Cli.assertCell(cell: Cell) = assertTab("$cell", cell.line, cell.cursor, cell.offers)

    fun Cli.checkTab(cells: List<Cell>) = checkCells(cells) { assertCell(it) }

    fun Cli.checkTab(vararg cells: Cell) = checkTab(cells.toList())

    /** Tab at [cursor] on [line] offers exactly [offers] once each, compared sorted as the shell sorts them. */
    fun Cli.assertTab(
        label: String,
        line: String,
        cursor: Int,
        offers: Collection<Suggestion>,
    ) {
        val actual = suggestionsFor(line, cursor)
        assertEquals(offers.sortedBy { it.value }, actual.sortedBy { it.value }, "$label: offered (chmod's set first)")
        assertEquals(actual.size, actual.toSet().size, "$label: a suggestion is offered twice: $actual")
    }

    /** Tab on [typed] offers none of [refused], each a word chmod refuses at that point. */
    fun Cli.assertOffersNone(
        rows: String,
        typed: String,
        refused: List<String>,
    ) {
        val offered = suggestionsFor(typed.replace("|", ""), typed.indexOf('|')).map { it.value }
        val wrong = refused.filter { it in offered }
        assertTrue(wrong.isEmpty(), "$rows `$typed`: offers $wrong, which chmod refuses there")
    }

    /** Tab on [typed] offers every one of [wanted], each with its description, among whatever else it offers. */
    fun Cli.assertOffersAll(
        rows: String,
        typed: String,
        wanted: List<Suggestion>,
    ) {
        val offered = suggestionsFor(typed.replace("|", ""), typed.indexOf('|'))
        val missing = wanted.filterNot { it in offered }
        assertTrue(missing.isEmpty(), "$rows `$typed`: must offer $missing, offered $offered")
    }

    /** Tab on [typed] offers every one of [wanted] as a value, among whatever else it offers. */
    fun Cli.assertOffersValues(
        rows: String,
        typed: String,
        wanted: List<String>,
    ) {
        val offered = suggestionsFor(typed.replace("|", ""), typed.indexOf('|')).map { it.value }
        val missing = wanted.filterNot { it in offered }
        assertTrue(missing.isEmpty(), "$rows `$typed`: must offer $missing, offered $offered")
    }
}
