package com.fromwau.klap.fixture.git

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * One Tab press on a git line, typed with `|` at the cursor, and what real git 2.56.0 would accept there. The
 * evidence is a run of real git in a throwaway repository unless a comment says the man page or `-h`.
 */

/** The cell type and checks every git completion test runs. */
internal object GitTab {

    /** One Tab press on [typed] (`|` at the cursor), what git would accept there, and the matrix [rows] it covers. */
    data class Cell(
        val rows: String,
        val typed: String,
        val offers: List<Suggestion>,
    ) {
        val line: String = typed.replace("|", "")
        val cursor: Int = typed.indexOf('|')

        override fun toString(): String = "$rows `$typed`"
    }

    val NOTHING: List<Suggestion> = emptyList()

    fun at(
        rows: String,
        typed: String,
        offers: List<Suggestion>,
    ): Cell {
        require(typed.count { it == '|' } == 1) { "one | marks the cursor in `$typed`" }
        return Cell(rows, typed, offers)
    }

    fun at(
        rows: String,
        typed: String,
        vararg offers: Suggestion,
    ): Cell = at(rows, typed, offers.toList())

    fun files(head: String = ""): Suggestion = filesAfter(head)

    /** Tab on each cell offers exactly what git accepts there, each once, in any order. */
    fun Cli.checkTabs(vararg cells: Cell) = checkCells(cells.toList()) { cell ->
        val actual = suggestionsFor(cell.line, cell.cursor)
        assertEquals(cell.offers.sortedBy { it.value }, actual.sortedBy { it.value }, "$cell: offered")
        assertEquals(actual.size, actual.toSet().size, "$cell: a suggestion is offered twice: $actual")
    }

    /** Tab on [typed] offers none of [refused], each a word git refuses at that point. */
    fun Cli.offersNone(
        rows: String,
        typed: String,
        refused: List<String>,
    ) {
        val offered = suggestionsFor(typed.replace("|", ""), typed.indexOf('|')).map { it.value }
        val wrong = refused.filter { it in offered }
        assertTrue(wrong.isEmpty(), "$rows `$typed`: offers $wrong, which git refuses there")
    }

    /** Tab on [typed] offers every one of [wanted], each a word git accepts at that point. */
    fun Cli.offersAll(
        rows: String,
        typed: String,
        wanted: List<String>,
    ) {
        val offered = suggestionsFor(typed.replace("|", ""), typed.indexOf('|')).map { it.value }
        val missing = wanted.filterNot { it in offered }
        assertTrue(missing.isEmpty(), "$rows `$typed`: must offer $missing, offered $offered")
    }
}

/**
 * The root's spellings but `-p`, which add and log take too, plus `--directory` and `--config`, which git does not
 * have: git takes none of them after the subcommand.
 */
internal val ROOT_OPTION_NAMES: List<String> =
    listOf("-C", "--directory", "-c", "--config", "-P") + ROOT_LONGS.map { it.value }

/** klap's built-ins real git refuses after a subcommand: `git status --json` and the rest are "unknown option". */
internal val BUILTINS_GIT_REFUSES_BELOW_ROOT: List<String> = listOf("--json", "--color", "--version")
