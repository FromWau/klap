package com.fromwau.klap.fixture.cp

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import kotlin.test.Test

/**
 * Every input of real cp 9.12 in every spelling, as real cp reads it: each cell here is one klap and the fixture
 * already get right. The cells where they do not are the targets in [CpTargetsTest].
 */
class CpProjectionMatrixTest {

    private val parity = ParitySuite(cpCli())

    private fun check(cells: List<ParseCell>) = checkCells(cells - TARGETED_CELLS) { parity.check(it) }

    private val flags = INPUTS.filter { it.kind == Kind.Flag }.map { it.name }

    private val values = INPUTS.filter { it.kind is Kind.Value }.map { it.name }

    @Test
    fun `every flag binds by its long name its abbreviation and each short alone doubled and clustered`() =
        check(cells(flags, LONG, LONG_REPEATED, ABBREVIATED, SHORT, SHORT_REPEATED, CLUSTER))

    @Test
    fun `a flag refuses a value attached to its long name`() = check(cells(flags, LONG_ATTACHED))

    @Test
    fun `every value binds attached spaced glued and at the end of a cluster`() =
        check(cells(values, ATTACHED, SPACED, GLUED, CLUSTER_VALUE, ABBREVIATED))

    @Test
    fun `a required value is refused when the line ends before it`() = check(cells(values, MISSING))

    @Test
    fun `an optional value left out binds its default and leaves the next word an operand`() =
        check(cells(values, BARE, BARE_THEN_WORD, BARE_AFTER_VALUE))

    @Test
    fun `a choice binds every value and its unique prefix and refuses the rest`() = check(
        cells(values, CHOICE_ABBREVIATED, CHOICE_AMBIGUOUS, CHOICE_CAPITALS, CHOICE_UNKNOWN, EMPTY),
    )

    @Test
    fun `an attribute list binds several names`() = check(cells(values, LIST))

    @Test
    fun `a value given twice binds the later one`() =
        check(cells(listOf("--backup", "--reflink", "--sparse", "--suffix", "--update", "--context"), REPEATED))

    @Test
    fun `the operands bind as each of cp's three usage shapes reads them`() {
        checkCells(OPERAND_CELLS) { (argv, expected) ->
            val words = argv.toTypedArray()
            if (expected == null) {
                parity.rejects(argv = words, because = "real cp: missing file operand")
            } else {
                parity.binds(argv = words, expected = expected)
            }
        }
    }

    @Test
    fun `a choice value in capitals is refused`() {
        // `cp --update=ALL`, `--backup=NONE`, `--reflink=AUTO`, `--sparse=AUTO`: invalid argument.
        check(CHOICE_CAPITAL_CELLS)
    }
}
