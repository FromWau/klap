package com.fromwau.klap.fixture.dd

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells

/*
 * The cells of the projection and converter tests, generated from the operands real dd 9.12 takes (`OPERANDS` in
 * DdModel.kt). Every expected outcome is how real dd reads the line, run inside a throwaway directory holding a small
 * file `in`, every `if=` and `of=` a relative name there. dd checks every operand before it opens a file, so a line it
 * refuses never reaches one; a line that names a file dd then fails to open (`dd if=`: "failed to open ''") is a line
 * it read, and binds.
 */

/** What real dd makes of one line. */
internal sealed interface Outcome {
    /** The line binds [record]. */
    data class Binds(val record: DdInputs) : Outcome

    /** dd refuses the line; [differs] names how klap's refusal differs, where both refuse for other reasons. */
    data class Rejects(val differs: String? = null) : Outcome
}

/** The projection cells of dd: one argv line each, the form under test and what real dd makes of it. */
internal object DdProjectionSupport {

    data class Cell(
        val form: String,
        val argv: List<String>,
        val outcome: Outcome,
        val because: String,
    ) {
        override fun toString(): String = "$form ${shown(argv)}"
    }

    fun ParitySuite<DdInputs>.check(cell: Cell) {
        val argv = cell.argv.toTypedArray()
        when (val outcome = cell.outcome) {
            is Outcome.Binds -> binds(argv = argv, expected = outcome.record, because = cell.because)
            is Outcome.Rejects -> rejects(argv = argv, because = cell.because, differs = outcome.differs)
        }
    }

    fun ParitySuite<DdInputs>.checkAll(cells: List<Cell>) = checkCells(cells) { check(it) }

    fun ParitySuite<DdInputs>.checkAll(vararg cells: Cell) = checkAll(cells.toList())

    fun shown(line: List<String>): String {
        val words = line.map { if (it.isEmpty() || it.any(Char::isWhitespace)) "\"$it\"" else it }
        return (listOf("dd") + words).joinToString(" ")
    }

    fun binds(
        vararg argv: String,
        because: String,
        expected: DdInputs = bound(*argv),
    ) = Cell("whole line", argv.toList(), Outcome.Binds(expected), because)

    fun refused(
        vararg argv: String,
        because: String,
        differs: String? = null,
    ) = Cell("whole line", argv.toList(), Outcome.Rejects(differs), because)

    // --- what a cell spells ---

    const val ALONE = "the operand alone"
    const val AMONG = "between other operands"
    const val AFTER_END = "after the end of options"
    const val KEY_CASE = "the key in capitals"
    const val KEY_PREFIX = "the key cut short"
    const val KEY_ALONE = "the key with no ="
    const val KEY_SPACED = "a blank inside the key"
    const val EMPTY_VALUE = "an empty value"
    const val ACCUMULATED = "a list key given twice"
    const val LAST_WINS = "a single value given twice"

    /** Every form of every operand real dd takes, each with what dd makes of it. */
    val ALL: List<Cell> by lazy { OPERANDS.flatMap(::cellsOf) }

    /** The cells in one of [forms]. */
    fun cellsIn(vararg forms: String): List<Cell> = ALL.filter { it.form in forms }

    private fun cellsOf(operand: Operand): List<Cell> = buildList {
        val word = "${operand.head}${operand.sample}"
        add(Cell(ALONE, listOf(word), Outcome.Binds(bound(word)), "dd --help: ${operand.head}"))
        add(
            Cell(
                AMONG,
                listOf("if=in", word, "of=out"),
                Outcome.Binds(bound("if=in", word, "of=out")),
                "`dd count=0 if=in of=o` and `dd if=in count=0 of=o` run alike",
            ),
        )
        add(Cell(AFTER_END, listOf("--", word), Outcome.Binds(bound(word)), "`dd -- if=in of=o` copies in to o"))
        add(
            Cell(
                KEY_CASE,
                listOf("${operand.key.uppercase()}=${operand.sample}"),
                Outcome.Rejects(),
                "dd: unrecognized operand 'IF=x'",
            ),
        )
        add(
            Cell(
                KEY_PREFIX,
                listOf("${operand.key.dropLast(1)}=${operand.sample}"),
                Outcome.Rejects(),
                "dd: unrecognized operand 'cou=1': dd takes no abbreviated key",
            ),
        )
        add(Cell(KEY_ALONE, listOf(operand.key), Outcome.Rejects(), "dd: unrecognized operand 'count'"))
        add(Cell(KEY_SPACED, listOf(" $word"), Outcome.Rejects(), "dd: unrecognized operand ' count=1'"))
        add(Cell(KEY_SPACED, listOf("${operand.key} =${operand.sample}"), Outcome.Rejects(), "'count =1'"))
        add(emptyValue(operand))
        add(repeated(operand))
    }

    private fun emptyValue(operand: Operand): Cell = when (operand.kind) {
        ValueKind.FILE -> Cell(
            EMPTY_VALUE,
            listOf(operand.head),
            Outcome.Binds(bound(operand.head)),
            "`dd if=` reads the line, then fails to open '' as it would any missing file",
        )
        ValueKind.BYTES, ValueKind.COUNT ->
            Cell(EMPTY_VALUE, listOf(operand.head), Outcome.Rejects(), "dd: invalid number: ''")
        ValueKind.SYMBOLS ->
            Cell(EMPTY_VALUE, listOf(operand.head), Outcome.Rejects(), "dd: invalid conversion: '' (and so on)")
    }

    /**
     * The info page: conv=, iflag= and oflag= given twice add up (`dd conv=ucase conv=lcase` is refused as the pair),
     * while for the rest "the last one takes precedence" (`dd status=none status=noxfer` prints the counts, `dd
     * count=1 count=2` copies two blocks).
     */
    private fun repeated(operand: Operand): Cell {
        val first = "${operand.head}${operand.sample}"
        val second = "${operand.head}${operand.second}"
        return if (operand.key in listOf("conv", "iflag", "oflag")) {
            Cell(ACCUMULATED, listOf(first, second), Outcome.Binds(bound(first, second)), "dd ORs every $first")
        } else {
            Cell(LAST_WINS, listOf(first, second), Outcome.Binds(bound(second)), "dd reads the later $second")
        }
    }
}
