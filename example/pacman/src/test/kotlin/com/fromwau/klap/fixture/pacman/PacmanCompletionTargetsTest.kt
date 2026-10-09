package com.fromwau.klap.fixture.pacman

import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What Tab should offer on a pacman line where klap offers something else today: exactly what real pacman 7.1.0
 * would accept at the cursor, in its own `--help` wording. Each test is ignored with the reason
 * `TDD target: <what must change>`; removing the annotation shows it failing until klap or the fixture is
 * changed, and no other test pins today's suggestions for these cells, so a fix breaks nothing else.
 */
class PacmanCompletionTargetsTest {

    private val pacman = pacmanCli().cli

    private fun check(cells: List<CompletionCell>) = checkCells(cells) { pacman.assertPacman(it) }

    private val longKinds = listOf("long end", "long inside")
    private val letterKinds = listOf("short end", "cluster end", "cluster inside")

    @Ignore("TDD target: klap must not continue a cluster with another member of an exclusive set the cluster holds")
    @Test
    fun `a cluster holding an operation offers no other operation`() {
        // `pacman -QS pacman`: "error: only one operation may be used at a time". `pacman -Q -<TAB>` already
        // leaves -S out; the cluster under the cursor must count the same way.
        val offered = pacman.suggestionsFor("pacman -Q").map { it.value }
        val others = listOf("-QS", "-QR", "-QU", "-QD", "-QF", "-QT")
        assertTrue(others.none { it in offered }, "`pacman -Q|` offers another operation: $offered")
    }

    @Ignore("TDD target: klap must offer under an operation only the options that operation's page lists")
    @Test
    fun `an option of another operation is not offered`() {
        // `pacman -Q --needed pacman`: "error: invalid option '--needed'".
        assertEquals(emptyList<Suggestion>(), pacman.suggestionsFor("pacman -Q --ne"), "`pacman -Q --ne|`")
    }

    @Ignore("TDD target: klap must offer a counted letter again in its cluster where the operation's page counts it")
    @Test
    fun `a counted letter is offered again in its cluster`() {
        // The pages: -Q -ii -kk -tt, -S -cc -dd -gg -ii -uu -yy, -R -dd -ss, -U -dd, -D -kk, -F -yy.
        val cells = Op.entries.flatMap { op -> op.page.filter { op.counts(it) }.map { op to it } }
        checkCells(cells) { (op, entry) ->
            val cluster = "pacman -${op.short}${entry.short}"
            val again = Suggestion("${cluster.drop(7)}${entry.short}", entry.help)
            val offered = pacman.suggestionsFor(cluster)
            assertTrue(again in offered, "${marked(cluster, cluster.length)} must offer $again: $offered")
        }
    }

    @Ignore("TDD target: scope names by operation, word them by its page, drop --json --completion --docs")
    @Test
    fun `a dash offers the operation's page and nothing pacman refuses`() =
        check(cells("dash", NO_OPERATION + ANY_OPERATION, NOTHING))

    @Ignore("TDD target: klap must offer under an operation only the long names its page lists")
    @Test
    fun `a long of another operation completes to nothing`() =
        check(longKinds.flatMap { cells(it, ANY_OPERATION, OFF_PAGE) })

    @Ignore("TDD target: klap must describe an option by the page of the operation on the line")
    @Test
    fun `a long on the page is described in that page's words`() =
        check(longKinds.flatMap { cells(it, ANY_OPERATION, OTHER_WORDING) })

    @Ignore("TDD target: klap must not offer a requireExactlyOne member already given")
    @Test
    fun `the operation already given is not offered again`() =
        check(longKinds.flatMap { cells(it, ANY_OPERATION, SAME_OPERATION) })

    @Ignore("TDD target: klap must complete another operation's name to this operation's options only, in its words")
    @Test
    fun `another operation's name as typed completes to this operation's options`() =
        check(longKinds.flatMap { cells(it, ANY_OPERATION, SHARED_NAME) })

    @Ignore("TDD target: klap must read an operation typed after the cursor when it scopes the names it offers")
    @Test
    fun `a long name completes to what an operation after the cursor accepts`() = check(
        cells(
            "long before the operation", ANY_OPERATION, OFF_PAGE, OTHER_WORDING, SAME_OPERATION, SHARED_NAME,
            OTHER_OPERATION,
        ),
    )

    @Ignore("TDD target: klap must refuse a cluster letter the operation's page does not list")
    @Test
    fun `a letter of another operation offers nothing after it`() =
        check(letterKinds.flatMap { cells(it, ANY_OPERATION, OFF_PAGE) })

    @Ignore("TDD target: klap must resolve a letter by the operation's page")
    @Test
    fun `a contested letter continues by its meaning on the operation's page`() =
        check(letterKinds.flatMap { cells(it, ANY_OPERATION, CONTESTED) })

    @Ignore("TDD target: klap must continue a cluster with its operation's page letters only, in the page's words")
    @Test
    fun `a letter on the page continues with that page's letters`() =
        check(letterKinds.flatMap { cells(it, ANY_OPERATION, SAME_WORDING, OTHER_WORDING, VERSION_LETTER) })

    @Ignore("TDD target: klap must continue a cluster holding an operation with that operation's letters only")
    @Test
    fun `a letter of an operation continues with that operation's letters or with nothing`() =
        check(letterKinds.flatMap { cells(it, NO_OPERATION + ANY_OPERATION, OPERATION) })

    @Ignore("TDD target: klap must continue an operation's own cluster with its page's letters only")
    @Test
    fun `an operation's own cluster continues with that operation's letters`() =
        check(cells("operation cluster", ANY_OPERATION, OPERATION))

    @Ignore("TDD target: klap must read an operation typed after the cursor when it continues a cluster")
    @Test
    fun `a cluster continues with the letters of an operation after the cursor`() =
        check(cells("operation cluster after the cursor", ANY_OPERATION, OPERATION))

    @Ignore("TDD target: klap must match a choice value case-sensitively, as pacman's --color does")
    @Test
    fun `a color value in capitals is not offered`() =
        check(cells("value capitals", NO_OPERATION + ANY_OPERATION, CHOICE))
}
