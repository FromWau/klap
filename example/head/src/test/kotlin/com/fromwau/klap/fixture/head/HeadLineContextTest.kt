package com.fromwau.klap.fixture.head

import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.head.HeadLineContext.FILES
import com.fromwau.klap.fixture.head.HeadLineContext.NOTHING
import com.fromwau.klap.fixture.head.HeadLineContext.assertOffersAll
import com.fromwau.klap.fixture.head.HeadLineContext.at
import com.fromwau.klap.fixture.head.HeadLineContext.checkContext
import com.fromwau.klap.fixture.head.HeadLineContext.checkContextValues
import com.fromwau.klap.fixture.head.HeadLineContext.name
import kotlin.test.Test

/*
 * head reads no word after the cursor into what Tab offers: it has no option that excludes another, refuses no
 * repeat, declares no provider and no trigger, and ends options only at `--`. So the L and S rows of exclusive sets,
 * refused repeats, counted flags, triggers, providers, subcommands and globals (L3a, L3b, L3d, L5, L6, L8 to L12, L16
 * to L20, L23 to L25, S2, S5 to S10, S12 to S15, S17 to S19, S21, S23, S26 to S31, S34, S36 to S44, S46, S47) have no
 * head cell. L27 is fish's rule and belongs to the live shell tests. A cell whose claim is one name among the dash's
 * list asserts that name alone; HeadCompletionTest's `a dash offers exactly head's names` pins the whole list.
 */

/** A dash after [head] offers every one of [names]. */
private data class Offered(
    val rows: String,
    val typed: String,
    val names: List<String>,
)

/**
 * How the words around the cursor change what Tab offers on a head line (L and S of the testing matrix): the words
 * after the cursor, and what the line already holds, from the -c/-n and -q/-v pairs to the end of options. Every
 * expectation is what real head 9.12 accepts at the cursor.
 */
class HeadLineContextTest {

    private val head = headCli().cli

    @Test
    fun `words after the cursor that bear on nothing leave the answer as at the end of the line`() = head.checkContext(
        at("L1", "head --qu|", name("--quiet")),
        at("L1", "head -q |", FILES),
        at("L2a", "head --qu| -v", name("--quiet")),
        at("L2a", "head --qu| --lines=5", name("--quiet")),
        at("L2a", "head | -q", FILES),
        at("L2b", "head --qu| a b", name("--quiet")),
        at("L2b L3c L13", "head | b", FILES),
        at("L13", "head a | b c", FILES),
        // L21: `head --zzz f`: "unrecognized option"; `head f -n`: "option requires an argument -- 'n'"; `head f --v`:
        // "option '--v' is ambiguous".
        at("L21", "head --qu| --zzz a b", name("--quiet")),
        at("L21", "head | a --zzz", FILES),
        at("L21", "head --qu| -n", name("--quiet")),
        at("L21 L22", "head --qu| --v", name("--quiet")),
        at("L22", "head | --li 5", FILES),
    )

    @Test
    fun `the other of -c and -n after the cursor rules nothing out`() {
        // L7: `head -c 5 -n 3 f` prints three lines and `head -n 3 -c 5 f` five bytes: the later wins, in either order.
        head.checkContextValues(
            at("L7", "head --li| -c 5", name("--lines")),
            at("L7", "head --by| -n 3", name("--bytes")),
        )
        val lines = listOf(
            Offered("L7", "head -| -c 5", listOf("-n", "--lines", "-c", "--bytes")),
            Offered("L7", "head -| --lines=3", listOf("-c", "--bytes", "-n")),
            Offered("L7", "head -| -v", listOf("-q", "--quiet", "--silent", "-v")),
        )
        checkCells(lines) { head.assertOffersAll(it.rows, it.typed, it.names) }
    }

    @Test
    fun `words behind an end of options after the cursor are operands and never inputs`() = head.checkContext(
        // L14: `head f -- -v` prints f, then "cannot open '-v'": the -v is a file.
        at("L14", "head --qu| -- -v", name("--quiet")),
        at("L14", "head --verb| -- -q", name("--verbose")),
        at("L14", "head | -- -q", FILES),
        at("L14", "head -q | -- -x", FILES),
    )

    @Test
    fun `a dash-led word an option takes after the cursor is its NUM`() = head.checkContext(
        // L15: `head f -n -5` prints all but the last five lines of f: the -5 after -n is no option.
        at("L15", "head | -n -q", FILES),
        at("L15", "head --qu| -c -v", name("--quiet")),
    )

    @Test
    fun `the rest of the word under the cursor is not sent`() = head.checkContext(
        // L26: the completion contract, not a head run: the word is completed as far as it is typed.
        at("L26", "head --qu|iet", name("--quiet")),
        at("L26", "head --verb|ose a", name("--verbose")),
        at("L26", "head --ze|ro-terminated f", name("--zero-terminated")),
    )

    @Test
    fun `after an end of options every word is an operand whatever it is spelled as`() = head.checkContext(
        // L4a to L4c: `head -- -x` and `head -- --lines=5`: each word is a file.
        at("L4a", "head -- |", FILES),
        at("L4a", "head -- -|", FILES),
        at("L4b", "head -- --lines |", FILES),
        at("L4b", "head -- -n |", FILES),
        at("L4c", "head -- --lines=5|", FILES),
        at("L4c", "head -- -qv|", FILES),
    )

    @Test
    fun `an unknown long before the cursor claims no word`() = head.checkContext(
        // S45: `head --zzz f`: "unrecognized option '--zzz'"; its next word is no value.
        at("S45", "head --zzz |", FILES),
        at("S45", "head --zzz --qu|", name("--quiet")),
    )

    @Test
    fun `nothing completes right after an ambiguous long`() = head.checkContext(
        // S45, K34: `head --v f`: "option '--v' is ambiguous", so the word after it does not complete.
        at("S45", "head --v |", *NOTHING),
        at("S45", "head --v --qu|", *NOTHING),
    )

    @Test
    fun `an input already given stays offered as head takes it again`() {
        // S16, S20, S22: `head -q -q a b`, `head -n 1 -n 2 f` (prints two lines), `head -c 5 -n 3 f` and `head -q -v a
        // b` run, the last of each pair winning.
        val lines = listOf(
            Offered("S16", "head -q -|", listOf("-q", "--quiet", "--silent")),
            Offered("S16", "head -z -|", listOf("-z", "--zero-terminated")),
            Offered("S20", "head -n 1 -|", listOf("-n", "--lines", "-c")),
            Offered("S20", "head --bytes=1 -|", listOf("-c", "--bytes", "-n")),
            Offered("S22", "head -c 5 -|", listOf("-n", "--lines")),
            Offered("S22", "head -q -|", listOf("-v", "--verbose")),
            Offered("S22", "head -v -|", listOf("-q", "--quiet", "--silent")),
        )
        checkCells(lines) { head.assertOffersAll(it.rows, it.typed, it.names) }
        head.checkContextValues(
            at("S20", "head --lines=1 --li|", name("--lines")),
            at("S22", "head -v --qu|", name("--quiet")),
        )
    }

    @Test
    fun `the obsolete count leaves -n and -c offered`() {
        // S24, S25: `head -5 -n 3 f` prints three lines, `head -5 -c 3 f` three bytes and `head -5v -q a b` no headers:
        // the count is one more spelling of the line count, and a later word overrides it.
        val lines = listOf(
            Offered("S24", "head -5 -|", listOf("-n", "--lines", "-c", "--bytes", "-q")),
            Offered("S25", "head -5v -|", listOf("-n", "-c", "-q", "--quiet")),
            Offered("S24", "head -5 --|", listOf("--lines", "--bytes")),
        )
        checkCells(lines) { head.assertOffersAll(it.rows, it.typed, it.names) }
    }

    @Test
    fun `a lone dash is an operand`() {
        // S32: `head - f` reads standard input, then f.
        head.checkContext(at("S32", "head - |", FILES), at("S32", "head - fi|", FILES), at("S32", "head -q - |", FILES))
        head.assertOffersAll("S32", "head - -|", listOf("-q", "-n"))
    }

    @Test
    fun `a dash-led word an option took before the cursor is no input`() {
        // S11: `head -n -q f` refuses '-q' as a NUM ("invalid number of lines"), so -q was never given; S33: getopt
        // hands `--` to -n as its NUM too (head then refuses it as a number), so the words after it are options again.
        val lines = listOf(
            Offered("S11", "head -n -q -|", listOf("-q", "--quiet")),
            Offered("S33", "head -n -- -|", listOf("-q", "-v", "-n")),
            Offered("S33", "head --lines -- --|", listOf("--quiet")),
        )
        checkCells(lines) { head.assertOffersAll(it.rows, it.typed, it.names) }
        head.checkContext(at("S11", "head -n -q |", FILES), at("S33", "head -n -- |", FILES))
    }

    @Test
    fun `options go on after an operand`() {
        // S35: `head a -q b` and `head a b -v` take the options: head permutes.
        head.checkContext(at("S35", "head a --qu|", name("--quiet")), at("S35", "head a b -qv -- |", FILES))
        head.assertOffersAll("S35", "head a -|", listOf("-q", "-v", "-n", "--lines"))
    }
}
