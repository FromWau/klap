package com.fromwau.klap.fixture.head

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.head.HeadProjectionSupport.Cell
import com.fromwau.klap.fixture.head.HeadProjectionSupport.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * Every expected value is from GNU head 9.12, run inside a throwaway directory on a twenty-line file f: whether a NUM
 * took is read off how much of f head prints, and a refusal is head's own "invalid number of lines" (or bytes). head
 * reads a NUM with strtoumax after taking off one leading '-', so leading blanks and a '+' are skipped, the digits are
 * decimal, and a multiplier from `b k K m M G T P E Z Y R Q` may follow, the upper-case ones and k and m also as `xB`
 * or `xiB`; a multiplier alone counts one of it. The record keeps the NUM as typed, so a cell asserts which words bind
 * rather than the number they mean. head has no double, boolean, choice, date, path or custom value, so the X rows of
 * those (X2 to X13, X24 to X54, X62 to X86) have no head cell.
 */

private fun numbers(
    words: List<String>,
    accepted: Boolean,
): List<Cell> = words.flatMap { word ->
    val lines = if (accepted) Outcome.Binds(F.lines(word)) else Outcome.Rejects()
    val bytes = if (accepted) Outcome.Binds(F.bytes(word)) else Outcome.Rejects()
    val shown = if (accepted) "`head -n '$word' f` runs" else "head: invalid number of lines: '$word'"
    listOf(
        Cell("value", "a NUM in its own word", listOf("-n", word, "f"), lines, shown),
        Cell("value", "a NUM attached", listOf("--bytes=$word", "f"), bytes, shown.replace("lines", "bytes")),
    )
}

/** NUMs `head -n` and `head -c` take, each checked on f. */
private val ACCEPTED: List<String> = listOf(
    "5", "0", "00", "010", "-5", "-0", "99999999999999999999", "-99999999999999999999",
    "1b", "1k", "1K", "1kB", "1KB", "1KiB", "1kiB", "1M", "1MB", "1MiB", "1G", "1GB", "1T", "1P", "1E", "1Z", "1Y",
    "1R", "1Q", "1QiB", "1RB", "-1K", "12345678901234567890123",
)

/**
 * NUMs head refuses: hex, exponents, fractions, groupings, doubled signs, bad suffixes, blanks anywhere but leading,
 * digits other than ASCII.
 */
private val REFUSED: List<String> = listOf(
    "", "-", "--", "+", " ", "abc", "0x10", "0x", "0b1", "1e", "1e3", "1.5", "1,000", "1Kb", "1kb", "1Mib", "1B",
    "1bB", "bB", "1Ki", "1g", "1x", "5x", "1w", "1c", "1kk", "1KK", "2x2", "iB", "B", "--5", "+-5", "++5", "+ 5",
    "-- 5", "5 ", " -5", "1 K", "١٢", "٥", "１２",
)

/** NUMs with a '+' or leading blanks, which head's strtoumax skips once the leading '-' is taken off. */
private val SIGNED_OR_BLANK: List<String> =
    listOf("+5", "-+5", "+1K", " 5", "\t5", "\n5", "\u000B5", "\u000C5", "\r5", " +5", "- 5")

private val LOWER_M: List<String> = listOf("1m", "1mB", "1miB")

private val MULTIPLIER_ALONE: List<String> = listOf("K", "b", "M", "kB", "KiB")

/**
 * What the fixture makes of each typed head NUM (X of the testing matrix): the NUMs of `-n` and `-c` as head's
 * strtoumax reads them, the dash-led forms, the repeats of a single value, and the obsolete count's digits and unit
 * letters.
 */
class HeadConverterTest {

    private val parity = ParitySuite(headCli())

    @Test
    fun `-n and -c bind every NUM head takes`() = parity.checkAll(numbers(ACCEPTED, accepted = true))

    @Test
    fun `-n and -c refuse what is no NUM`() = parity.checkAll(numbers(REFUSED, accepted = false))

    @Test
    fun `a dash-led NUM is the option's value`() = parity.checkAll(
        // X57: `head -n -5 f` and `head --lines=-5 f` print all but five lines; `head -n -- f`: "invalid number of
        // lines: '-'" (getopt hands -- to -n); `head -n - f`: "invalid number of lines: ''".
        listOf(
            Cell("value", "dash-led", listOf("-n", "-5", "f"), Outcome.Binds(F.lines("-5")), "all but five"),
            Cell("value", "dash-led", listOf("-n-5", "f"), Outcome.Binds(F.lines("-5")), "all but five"),
            Cell("value", "dash-led", listOf("--lines", "-5", "f"), Outcome.Binds(F.lines("-5")), "all but 5"),
            Cell("value", "dash-led", listOf("-c", "-5", "f"), Outcome.Binds(F.bytes("-5")), "all but 5 bytes"),
            Cell("value", "dash-led", listOf("-n", "--", "f"), Outcome.Rejects(), "head: invalid number of lines: '-'"),
            Cell("value", "dash-led", listOf("-n", "-", "f"), Outcome.Rejects(), "head: invalid number of lines: ''"),
            Cell("value", "dash-led", listOf("-n", "-q", "f"), Outcome.Rejects(), "head: invalid number of lines: 'q'"),
        ),
    )

    @Test
    fun `a NUM given twice is checked each time and the later binds`() = parity.checkAll(
        // X55, X56: `head -n abc -n 5 f` and `head -n 5 -n abc f`: "invalid number of lines: 'abc'"; `head -n 1 -n 2 f`
        // prints two lines.
        listOf(
            Cell("value", "repeat", listOf("-n", "abc", "-n", "5", "f"), Outcome.Rejects(), "invalid number: 'abc'"),
            Cell("value", "repeat", listOf("-n", "5", "-n", "abc", "f"), Outcome.Rejects(), "invalid number: 'abc'"),
            Cell("value", "repeat", listOf("-n", "1", "-n", "2", "f"), Outcome.Binds(F.lines("2")), "two lines"),
            Cell("value", "repeat", listOf("-c", "1", "--bytes=2", "f"), Outcome.Binds(F.bytes("2")), "2 bytes"),
        ),
    )

    @Test
    fun `the obsolete count binds its digits as typed`() = parity.checkAll(
        // X19, X23: `head -007 f` prints seven lines, `head -0 f` none, and `head -99999999999999999999 f` all of f.
        listOf(
            Cell("value", "count", listOf("-007", "f"), Outcome.Binds(F.lines("007")), "seven lines"),
            Cell("value", "count", listOf("-0", "f"), Outcome.Binds(F.lines("0")), "nothing"),
            Cell(
                "value",
                "count",
                listOf("-99999999999999999999", "f"),
                Outcome.Binds(F.lines("99999999999999999999")),
                "all of f",
            ),
        ),
    )

    // X15, X18: `head -n +5 f` prints five lines, `head -n -+5 f` all but five, `head -n ' 5' f` and `head -n '\t5' f`
    // five, `head -n '- 5' f` all but five: strtoumax skips leading blanks and takes a '+'.
    @Test
    fun `a NUM with a plus sign or leading blanks is taken`() =
        parity.checkAll(numbers(SIGNED_OR_BLANK, accepted = true))

    // X21: `head -n 1m f`, `head -n 1mB f` and `head -n 1miB f` print all of f; `head --help`'s list names M only, but
    // head's suffixes include a lower-case m as they do k.
    @Test
    fun `a NUM takes a lower-case m multiplier`() = parity.checkAll(numbers(LOWER_M, accepted = true))

    // X21: `head -n K f`, `head -n b f`, `head -n M f`, `head -n kB f` and `head -n KiB f` print all of f: a multiplier
    // with no digits counts one of it. `head -n B f` and `head -n iB f` are refused.
    @Test
    fun `a multiplier alone is a NUM`() = parity.checkAll(numbers(MULTIPLIER_ALONE, accepted = true))

    // `info coreutils 'head invocation'`: `-[NUM][bkm][cqv]`. On `seq 5000`, `head -3b f` prints 1536 bytes, `-3k`
    // 3072, `-3c` 3; `-3l` three lines, `-3bl` 1536 lines and `-3kl` 3072; `-3lb` 1536 bytes, `-3bc` three bytes and
    // `-3cb` 1536, `-3bk` 3072 bytes and `-3kb` 1536; `-3bcl` three lines; `-3lc` three bytes, `-3cl` three lines.
    @Test
    fun `the obsolete count's letters set its unit and multiplier the last one winning`() {
        val read = mapOf(
            "007" to Count.Lines("007"),
            "3c" to Count.Bytes("3"),
            "3b" to Count.Bytes("3b"),
            "3k" to Count.Bytes("3k"),
            "3m" to Count.Bytes("3m"),
            "3l" to Count.Lines("3"),
            "3bl" to Count.Lines("3b"),
            "3kl" to Count.Lines("3k"),
            "3lb" to Count.Bytes("3b"),
            "3bc" to Count.Bytes("3"),
            "3cb" to Count.Bytes("3b"),
            "3bk" to Count.Bytes("3k"),
            "3kb" to Count.Bytes("3b"),
            "3bcl" to Count.Lines("3"),
            "3lc" to Count.Bytes("3"),
            "3cl" to Count.Lines("3"),
        )
        assertEquals(read, read.mapValues { (typed, _) -> obsoleteCount(typed) })
    }
}
