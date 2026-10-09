package com.fromwau.klap.fixture.pacman

import kotlin.test.Test

/**
 * What Tab offers on a pacman line, checked against what real pacman 7.1.0 would accept there: the exact
 * suggestions with their descriptions, the line once one is taken and the line after one Tab. The cells come
 * from every input under every operation and with none, with the cursor on no word, at a word's start, inside a
 * word and at its end, at the end of the line and with words after it. The groups klap does not meet yet are
 * [PacmanCompletionTargetsTest].
 */
class PacmanCompletionMatrixTest {

    private val pacman = pacmanCli().cli

    private fun check(cells: List<CompletionCell>) = checkCells(cells) { pacman.assertPacman(it) }

    private val everywhere = NO_OPERATION + ANY_OPERATION

    @Test
    fun `no word offers nothing since targets have no candidates`() = check(cells("no word", everywhere, NOTHING))

    @Test
    fun `a word's start is that word typed as far as nothing with its rest not sent`() =
        check(cells("word start", everywhere, NOTHING))

    @Test
    fun `a long name's start offers what no word offers`() = check(
        cells(
            "long word start", everywhere, OPERATION, OPTION, BUILT_IN, SAME_WORDING, OTHER_WORDING, OFF_PAGE,
            SAME_OPERATION, SHARED_NAME, OTHER_OPERATION,
        ),
    )

    @Test
    fun `no name is offered after the end of options`() = check(cells("end of options", everywhere, NOTHING))

    @Test
    fun `a long name completes with no operation on the line`() = check(
        cells("long end", NO_OPERATION, OPERATION, OPTION, BUILT_IN) +
            cells("long inside", NO_OPERATION, OPERATION, OPTION, BUILT_IN),
    )

    @Test
    fun `an option on the operation's page completes where the fixture uses the page's words`() = check(
        cells("long end", ANY_OPERATION, SAME_WORDING) +
            cells("long inside", ANY_OPERATION, SAME_WORDING) +
            cells("long before the operation", ANY_OPERATION, SAME_WORDING),
    )

    @Test
    fun `another operation's name completes to nothing under an operation`() = check(
        cells("long end", ANY_OPERATION, OTHER_OPERATION) + cells("long inside", ANY_OPERATION, OTHER_OPERATION),
    )

    @Test
    fun `help and version complete under any operation`() = check(
        cells("long end", ANY_OPERATION, BUILT_IN) +
            cells("long inside", ANY_OPERATION, BUILT_IN) +
            cells("long before the operation", ANY_OPERATION, BUILT_IN),
    )

    @Test
    fun `a single letter continues with every letter when no operation is given`() =
        check(cells("short end", NO_OPERATION, OPTION, VERSION_LETTER))

    @Test
    fun `a letter taking a value ends its cluster`() = check(
        cells("short end", ANY_OPERATION, VALUE_LETTER) +
            cells("cluster end", ANY_OPERATION, VALUE_LETTER) +
            cells("cluster inside", ANY_OPERATION, VALUE_LETTER),
    )

    @Test
    fun `a path value completes through the shell in every form`() = check(
        listOf(
            "value no word", "value word start", "value spaced", "value attached", "value abbreviated",
            "value short", "value cluster",
        ).flatMap { cells(it, everywhere, PATH_VALUE) },
    )

    @Test
    fun `a choice value completes with the option's description when attached`() = check(
        listOf("value no word", "value word start", "value spaced", "value attached", "value abbreviated")
            .flatMap { cells(it, everywhere, CHOICE) },
    )

    @Test
    fun `a free text value has no candidates`() = check(
        listOf("value no word", "value word start", "value spaced", "value attached", "value abbreviated")
            .flatMap { cells(it, everywhere, TEXT_VALUE) },
    )

    @Test
    fun `an optional value completes only attached`() = check(
        listOf("value no word", "value word start", "value spaced", "value attached", "value abbreviated")
            .flatMap { cells(it, everywhere, OPTIONAL) },
    )

    @Test
    fun `a value of another operation's option has no candidates`() = check(
        listOf("value no word", "value word start", "value spaced", "value attached", "value abbreviated")
            .flatMap { cells(it, ANY_OPERATION, OFF_PAGE) },
    )
}
