package com.fromwau.klap.fixture.cp

import com.fromwau.klap.fixture.checkCells
import kotlin.test.Test

/** The cells naming klap's `--help-all`, or its `-h` inside a cluster, neither of which klap completes. */
internal val HELP_IN_CLUSTER_AND_HELP_ALL: (CompletionCell) -> Boolean = { cell ->
    cell.line.split(' ').any { it == "-vh" || it.startsWith("--help-a") }
}

/** The attribute lists in capitals, which neither cp nor klap completes. */
internal val ATTRIBUTE_CAPITALS: (CompletionCell) -> Boolean = { cell ->
    cell.line.startsWith("cp --preserve=") || cell.line.startsWith("cp --no-preserve=")
}

/**
 * What Tab offers on a cp line, checked against what real cp 9.12 would accept there: the exact suggestions with
 * their descriptions, the line once one is taken and the line after one Tab. The cells come from every input of
 * cp, with the cursor on no word, at a word's start, inside a word and at its end, at the end of the line and with
 * words after it. The groups klap or the fixture does not meet yet are [CpCompletionTargetsTest].
 */
class CpCompletionMatrixTest {

    private val cp = cpCli().cli

    private fun check(cells: List<CompletionCell>) = checkCells(cells) { cp.assertCp(it) }

    @Test
    fun `an operand slot hands the word to the shell's file completion`() = check(
        cells("no word", OPERAND) + cells("word start", OPERAND) + cells("operand inside", OPERAND) +
            cells("operand end", OPERAND),
    )

    @Test
    fun `after the end of options every word is an operand`() = check(cells("end of options", OPERAND))

    @Test
    fun `a long name completes in cp's words where the fixture uses them`() = check(
        cells("long end", SAME_WORDING) + cells("long inside", SAME_WORDING) + cells("long abbreviated", SAME_WORDING),
    )

    @Test
    fun `a letter taking a value ends its cluster`() = check(
        cells("short end", VALUE_LETTER) + cells("cluster end", VALUE_LETTER) + cells("cluster inside", VALUE_LETTER),
    )

    @Test
    fun `klap's --help-all and -h inside a cluster are not offered`() =
        check(CELLS.filter(HELP_IN_CLUSTER_AND_HELP_ALL))

    @Test
    fun `a directory value completes through the shell in every form`() = check(
        listOf(
            "value no word", "value word start", "value spaced", "value attached", "value abbreviated",
            "value short", "value cluster",
        ).flatMap { cells(it, PATH_VALUE) },
    )

    @Test
    fun `a free text value has no candidates`() = check(
        listOf(
            "value no word", "value word start", "value spaced", "value attached", "value abbreviated",
            "value short", "value cluster",
        ).flatMap { cells(it, TEXT_VALUE) },
    )

    @Test
    fun `a required choice completes its values in its own word`() = check(
        listOf("value no word", "value word start", "value spaced", "value abbreviated").flatMap { cells(it, CHOICE) },
    )

    @Test
    fun `an optional value completes only attached and leaves a spaced word to the operands`() = check(
        listOf("value no word", "value word start", "value spaced", "value attached", "value abbreviated")
            .flatMap { cells(it, OPTIONAL) },
    )

    @Test
    fun `an attribute list in capitals has no candidates`() =
        check(cells("value capitals", CAPITALS).filter(ATTRIBUTE_CAPITALS))

    @Test
    fun `a choice value in capitals is not offered`() =
        check(cells("value capitals", CAPITALS).filterNot(ATTRIBUTE_CAPITALS))
}
