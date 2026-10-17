package com.fromwau.klap.fixture.head

import com.fromwau.kern.result.Ok
import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.head.HeadProjectionSupport.ABBREVIATED
import com.fromwau.klap.fixture.head.HeadProjectionSupport.AFTER
import com.fromwau.klap.fixture.head.HeadProjectionSupport.AFTER_END
import com.fromwau.klap.fixture.head.HeadProjectionSupport.ALL
import com.fromwau.klap.fixture.head.HeadProjectionSupport.AMBIGUOUS_PREFIXES
import com.fromwau.klap.fixture.head.HeadProjectionSupport.ATTACHED
import com.fromwau.klap.fixture.head.HeadProjectionSupport.BETWEEN
import com.fromwau.klap.fixture.head.HeadProjectionSupport.CLUSTER
import com.fromwau.klap.fixture.head.HeadProjectionSupport.CLUSTER_GLUED
import com.fromwau.klap.fixture.head.HeadProjectionSupport.CLUSTER_SPACED
import com.fromwau.klap.fixture.head.HeadProjectionSupport.Cell
import com.fromwau.klap.fixture.head.HeadProjectionSupport.DASH_LED
import com.fromwau.klap.fixture.head.HeadProjectionSupport.GLUED
import com.fromwau.klap.fixture.head.HeadProjectionSupport.LONG
import com.fromwau.klap.fixture.head.HeadProjectionSupport.LONG_ATTACHED
import com.fromwau.klap.fixture.head.HeadProjectionSupport.LONG_REPEATED
import com.fromwau.klap.fixture.head.HeadProjectionSupport.MISSING_VALUE
import com.fromwau.klap.fixture.head.HeadProjectionSupport.REPEATED
import com.fromwau.klap.fixture.head.HeadProjectionSupport.SHORT
import com.fromwau.klap.fixture.head.HeadProjectionSupport.SHORT_EQUALS
import com.fromwau.klap.fixture.head.HeadProjectionSupport.SHORT_REPEATED
import com.fromwau.klap.fixture.head.HeadProjectionSupport.SHORT_SPACED
import com.fromwau.klap.fixture.head.HeadProjectionSupport.SPACED
import com.fromwau.klap.fixture.head.HeadProjectionSupport.checkAll
import com.fromwau.klap.fixture.head.HeadProjectionSupport.cellsIn
import com.fromwau.klap.parse
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Every expected value is from GNU coreutils 9.12 `head`, run inside a throwaway directory holding the files a, b
 * and f (and where a line names them, a file -1, a file -x and a file named `a b`): a `because` quotes what it
 * printed. How a line binds is read off what head prints: how many lines or bytes, whether headers show, which files.
 * The obsolete `-NUM` form is read from `info coreutils 'head invocation'` ("recognized only if it is specified
 * first") and checked the same way.
 */

private fun cell(
    argv: List<String>,
    outcome: Outcome,
    because: String,
) = Cell("line", "whole line", argv, outcome, because)

private fun binds(
    vararg argv: String,
    because: String,
    expected: HeadInputs,
) = cell(argv.toList(), Outcome.Binds(expected), because)

private fun refused(
    vararg argv: String,
    because: String,
    differs: String? = null,
) = cell(argv.toList(), Outcome.Rejects(differs), because)

private fun files(vararg names: String): HeadInputs = NOTHING_BOUND.copy(files = names.toList())

private val FLAG_KEYS: List<String> =
    INPUTS.filter { it.kind == Kind.Flag }.flatMap { it.longs + "-${it.short}" }

private val VALUE_KEYS: List<String> =
    INPUTS.filter { it.kind is Kind.Value }.flatMap { it.longs + "-${it.short}" }

/**
 * The record a head line binds in the fixture, or its refusal, as real head 9.12 reads the line (P of the testing
 * matrix): every input in every spelling and position, NUMs at the edges of their word, the obsolete count, repeats
 * and overrides, the operands, and help, version and klap's built-ins on a line.
 */
class HeadProjectionTest {

    private val head = headCli()

    private val parity = ParitySuite(head)

    @Test
    fun `every flag binds by each long name and its short alone doubled and clustered`() =
        parity.checkAll(HeadProjectionSupport.cells(FLAG_KEYS, LONG, LONG_REPEATED, SHORT, SHORT_REPEATED, CLUSTER))

    @Test
    fun `every long binds by its shortest abbreviation among head's longs`() =
        parity.checkAll(HeadProjectionSupport.cells(FLAG_KEYS + VALUE_KEYS, ABBREVIATED))

    @Test
    fun `a flag refuses a value attached to its long name`() =
        parity.checkAll(HeadProjectionSupport.cells(FLAG_KEYS, LONG_ATTACHED))

    @Test
    fun `every input binds the same record before between and after the operands`() =
        parity.checkAll(HeadProjectionSupport.cells(FLAG_KEYS + VALUE_KEYS, BETWEEN, AFTER))

    @Test
    fun `every spelling after the end of options is an operand`() = parity.checkAll(cellsIn(AFTER_END))

    @Test
    fun `-n and -c bind their NUM attached in its own word glued and after a cluster`() = parity.checkAll(
        HeadProjectionSupport.cells(
            VALUE_KEYS, ATTACHED, SPACED, GLUED, SHORT_SPACED, CLUSTER_GLUED, CLUSTER_SPACED, DASH_LED, SHORT_EQUALS,
        ),
    )

    @Test
    fun `a NUM left out refuses the line`() = parity.checkAll(HeadProjectionSupport.cells(VALUE_KEYS, MISSING_VALUE))

    @Test
    fun `a NUM given twice binds the later`() = parity.checkAll(HeadProjectionSupport.cells(VALUE_KEYS, REPEATED))

    @Test
    fun `every prefix head calls ambiguous is refused`() = parity.checkAll(AMBIGUOUS_PREFIXES)

    @Test
    fun `every generated cell is checked by one test`() {
        val forms = listOf(
            LONG, LONG_REPEATED, SHORT, SHORT_REPEATED, CLUSTER, ABBREVIATED, LONG_ATTACHED, BETWEEN, AFTER, AFTER_END,
            ATTACHED, SPACED, GLUED, SHORT_SPACED, CLUSTER_GLUED, CLUSTER_SPACED, DASH_LED, SHORT_EQUALS, MISSING_VALUE,
            REPEATED,
        )
        val unchecked = ALL.filter { it.form !in forms }
        assertTrue(unchecked.isEmpty(), "cells no test checks: $unchecked")
    }

    @Test
    fun `the last of -c and -n decides the unit`() = parity.checkAll(
        // P7d, P61, P74: `head -c 5 -n 3 f` prints three lines and `head -n 3 -c 5 f` five bytes; the loser is absent.
        listOf(
            binds("-c", "5", "-n", "3", "f", because = "three lines", expected = F.lines("3")),
            binds("-n", "3", "-c", "5", "f", because = "five bytes", expected = F.bytes("5")),
            binds("--bytes=5", "--lines=3", "f", because = "three lines", expected = F.lines("3")),
            binds("--lines=3", "--bytes=5", "f", because = "three lines", expected = F.bytes("5")),
            binds(
                "-qn3", "-c5", "a", "b",
                because = "five bytes each",
                expected = A_AND_B.bytes("5").copy(headers = Headers.Never),
            ),
        ),
    )

    @Test
    fun `the obsolete count is the line count when it comes first`() = parity.checkAll(
        // P58, P60, P61: `head -5 f` prints five lines; `head -5 -n 3 f` three; `head -3 -c 5 f` five bytes; a count of
        // any length reads, saturating past the largest (`head -99999999999999999999 f` prints all of f).
        listOf(
            binds("-5", "f", because = "five lines", expected = F.lines("5")),
            binds("-100", because = "a hundred lines of standard input", expected = NOTHING_BOUND.lines("100")),
            binds("-0", "f", because = "nothing", expected = F.lines("0")),
            binds("-0003", "f", because = "three lines", expected = F.lines("0003")),
            binds(
                "-99999999999999999999", "f",
                because = "all of f",
                expected = F.lines("99999999999999999999"),
            ),
            binds("-5", "-n", "3", "f", because = "three lines", expected = F.lines("3")),
            binds("-3", "-c", "5", "f", because = "five bytes", expected = F.bytes("5")),
            binds(
                "-3", "-v", "f",
                because = "a header, three lines",
                expected = F.lines("3").copy(headers = Headers.Always),
            ),
            binds("-5", "--", "-1", because = "five lines of the file -1", expected = files("-1").lines("5")),
            binds("-n5", "./-1", because = "five lines of the file -1", expected = files("./-1").lines("5")),
        ),
    )

    @Test
    fun `the obsolete count takes head's flag letters after its digits`() = parity.checkAll(
        // P58: `head -5v f` prints a header, `head -12v f` too, `head -3q a b` none and `head -3z nul` reads NUL lines.
        listOf(
            binds("-5v", "f", because = "a header, five lines", expected = F.lines("5").copy(headers = Headers.Always)),
            binds(
                "-12v", "f",
                because = "a header, twelve lines",
                expected = F.lines("12").copy(headers = Headers.Always),
            ),
            binds("-3q", "a", "b", because = "no headers", expected = A_AND_B.lines("3").copy(headers = Headers.Never)),
            binds("-3z", "f", because = "NUL lines", expected = F.lines("3").copy(zeroTerminated = true)),
        ),
    )

    @Test
    fun `an obsolete count with a letter head does not take there is refused`() = parity.checkAll(
        // `head -3K f`, `head -3x f` and `head -3n f`: "invalid trailing option -- K" (x, n).
        listOf(
            refused("-3K", "f", because = "head: invalid trailing option -- K"),
            refused("-5x", "f", because = "head: invalid trailing option -- x"),
            refused(
                "-3n", "f",
                because = "head: invalid trailing option -- n",
                differs = "klap reads -n and refuses f as its NUM",
            ),
        ),
    )

    @Test
    fun `operands are files in their own words`() = parity.checkAll(
        // P5d, P37, P65, P71: `head` reads standard input; `head - f` standard input, then f; `head -- -- f`: "cannot
        // open '--'"; `head ''`: "cannot open ''"; `head 'a b'` prints the file `a b`.
        listOf(
            binds(because = "standard input", expected = NOTHING_BOUND),
            binds("-", because = "standard input", expected = files("-")),
            binds("-", "f", because = "standard input, then f", expected = files("-", "f")),
            binds("--", "--", "f", because = "cannot open '--'", expected = files("--", "f")),
            binds("--", "-x", because = "the file -x", expected = files("-x")),
            binds("", because = "cannot open ''", expected = files("")),
            binds("a b", "~/x", because = "the file `a b`, then `~/x` as typed", expected = files("a b", "~/x")),
            binds("a", "-n", "3", because = "three lines of a", expected = files("a").lines("3")),
        ),
    )

    @Test
    fun `what head refuses gives no record`() = parity.checkAll(
        // P9a, P70: an unknown option, a missing or bad NUM, a short with no meaning.
        listOf(
            refused("--zzz", because = "head: unrecognized option '--zzz'"),
            refused("-Q", "f", because = "head: invalid option -- 'Q'"),
            refused("-h", "f", because = "head: invalid option -- 'h'"),
            refused("-x", "f", because = "head: invalid option -- 'x'"),
            refused("-n", "abc", "f", because = "head: invalid number of lines: 'abc'"),
            refused("--bytes=abc", "f", because = "head: invalid number of bytes: 'abc'"),
            refused("-nq", "f", because = "head: invalid number of lines: 'q'"),
            refused("--lines==3", "f", because = "head: invalid number of lines: '=3'"),
            refused("-q=x", "f", because = "head: invalid option -- '='"),
            refused("--help=x", because = "head: option '--help' doesn't allow an argument"),
            refused("--version=x", because = "head: option '--version' doesn't allow an argument"),
            refused("--=x", "f", because = "head: option '--=x' is ambiguous"),
            refused("--print", "f", because = "head: unrecognized option '--print'"),
            refused("--QUIET", "f", because = "head: unrecognized option '--QUIET'"),
            // `--c` and `--d` name no long of head's; klap reads them as its own built-ins.
            refused(
                "--c", "f",
                because = "head: unrecognized option '--c'",
                differs = "klap calls --c ambiguous between its --color and --completion",
            ),
            refused(
                "--d", "f",
                because = "head: unrecognized option '--d'",
                differs = "klap reads --d as its --docs and refuses f as a format",
            ),
        ),
    )

    @Test
    fun `--help and --version answer as head reads the line`() {
        // P30, P33, P34, P48: getopt acts on the first of them, and head checks a NUM as it reads it, so `head --help
        // --zzz` and `head --help -n abc` print the usage, `head -n abc --help` and `head --zzz --help` are refused.
        listOf(listOf("--help"), listOf("--h", "f"), listOf("--he", "f"), listOf("--hel", "f"), listOf("f", "--help"))
            .forEach { parity.showsHelp(*it.toTypedArray(), because = "head prints its usage") }
        parity.showsHelp("-n", "3", "--help", because = "head prints its usage")
        parity.showsHelp("--help", "--zzz", because = "head prints its usage")
        parity.showsHelp("--help", "-n", "abc", because = "head prints its usage")
        parity.showsVersion("--vers", "f", because = "head prints its version")
        parity.showsVersion("--version", "-n", "abc", because = "head prints its version")
        parity.rejects("-n", "abc", "--help", because = "head: invalid number of lines: 'abc'")
        parity.rejects("--zzz", "--help", because = "head: unrecognized option '--zzz'")
        parity.rejects("-n", "abc", "--version", because = "head: invalid number of lines: 'abc'")
        parity.rejects("--ver", "f", because = "head: option '--ver' is ambiguous; possibilities: '--verbose' ...")
    }

    @Test
    fun `a built in binds nothing because no command ran`() {
        // P2: help answers before any command is reached, so there is no binding to project.
        assertEquals(Ok(null), head.parse(listOf("--help")))
        assertEquals(Ok(null), head.parse(listOf("--version")))
    }

    // P7d, P74, P75: `head -v -q a b`, `head -vq a b` and `head --verbose --quiet a b` print no headers; `head -q -v a
    // b`, `head -qv a b` and `head --quiet --verbose a b` print them; the earlier one binds as absent.
    @Test
    fun `the last of -q and -v decides the header mode`() = parity.checkAll(
        listOf(
            binds("-vq", "a", "b", because = "no headers", expected = A_AND_B.copy(headers = Headers.Never)),
            binds("-qv", "a", "b", because = "headers", expected = A_AND_B.copy(headers = Headers.Always)),
            binds("-v", "-q", "a", "b", because = "no headers", expected = A_AND_B.copy(headers = Headers.Never)),
            binds("-q", "-v", "a", "b", because = "headers", expected = A_AND_B.copy(headers = Headers.Always)),
            binds(
                "--verbose", "--quiet", "a", "b",
                because = "no headers",
                expected = A_AND_B.copy(headers = Headers.Never),
            ),
            binds(
                "--quiet", "--verbose", "a", "b",
                because = "headers",
                expected = A_AND_B.copy(headers = Headers.Always),
            ),
            binds("--silent", "-v", "a", "b", because = "headers", expected = A_AND_B.copy(headers = Headers.Always)),
            binds(
                "-3vq", "a", "b",
                because = "no headers",
                expected = A_AND_B.lines("3").copy(headers = Headers.Never),
            ),
            binds("-3qv", "a", "b", because = "headers", expected = A_AND_B.lines("3").copy(headers = Headers.Always)),
        ),
    )

    // P31, P84: `head --json f`, `head --j f`, `head --color f`, `head --color=never f`, `head --completion bash` and
    // `head --docs man`: "unrecognized option".
    @Test
    fun `an option head does not have is refused`() = parity.checkAll(
        listOf(
            refused("--json", "f", because = "head: unrecognized option '--json'"),
            refused("--j", "f", because = "head: unrecognized option '--j'"),
            refused("--color", "f", because = "head: unrecognized option '--color'"),
            refused("--color=never", "f", because = "head: unrecognized option '--color=never'"),
            refused("--completion", "bash", because = "head: unrecognized option '--completion'"),
            refused("--docs", "man", because = "head: unrecognized option '--docs'"),
        ),
    )

    // P32: `head __complete f`: "cannot open '__complete'", then f.
    @Test
    fun `__complete is a file name`() = parity.checkAll(
        listOf(
            binds("__complete", "f", because = "cannot open '__complete'", expected = files("__complete", "f")),
        ),
    )

    // P5c, P6a: `head f` prints ten lines; the matrix asks the record to carry head's default rather than nothing.
    @Test
    fun `a line without -n or -c binds ten lines`() = parity.checkAll(
        listOf(
            binds("f", because = "ten lines", expected = F.lines("10")),
            binds(
                "-q", "a", "b",
                because = "ten lines each",
                expected = A_AND_B.lines("10").copy(headers = Headers.Never),
            ),
            binds("-c", "5", "f", because = "five bytes", expected = F.bytes("5")),
        ),
    )

    // P58: `info coreutils 'head invocation'`: the obsolete `-[NUM][bkm][cqv]`, where b, k and m are a size letter as
    // in -c and l means lines. `head -3c f` prints three bytes, `-3b` 1536 bytes, `-1k` 1024, `-3m` 3 MiB, `-3l` three
    // lines; head appends the size letter to the digits, so `-3bl` prints 1536 lines; `-3lc` and `-3kc` three bytes,
    // `-3cl` three lines; `head -5c -n 2 f` prints two lines.
    @Test
    fun `the obsolete count takes head's unit letters after its digits`() = parity.checkAll(
        listOf(
            binds("-3c", "f", because = "three bytes", expected = F.bytes("3")),
            binds("-3b", "f", because = "1536 bytes", expected = F.bytes("3b")),
            binds("-1k", "f", because = "1024 bytes", expected = F.bytes("1k")),
            binds("-3m", "f", because = "3 MiB", expected = F.bytes("3m")),
            binds("-3l", "f", because = "three lines", expected = F.lines("3")),
            binds("-3bl", "f", because = "1536 lines", expected = F.lines("3b")),
            binds("-3lc", "f", because = "three bytes", expected = F.bytes("3")),
            binds("-3kc", "f", because = "three bytes", expected = F.bytes("3")),
            binds("-3cl", "f", because = "three lines", expected = F.lines("3")),
            binds("-3cz", "f", because = "three bytes, NUL lines", expected = F.bytes("3").copy(zeroTerminated = true)),
            binds("-5c", "-n", "2", "f", because = "two lines", expected = F.lines("2")),
            binds(
                "-3c", "-v", "f",
                because = "a header, three bytes",
                expected = F.bytes("3").copy(headers = Headers.Always),
            ),
        ),
    )

    // P58, P59, P60, P61, P84: head reads a count only as its first word ("recognized only if it is specified first"),
    // and getopt refuses a digit anywhere else: `head -c 5 -3 f`, `head -n 3 -5 f`, `head -v -3 f`, `head f -3`, `head
    // -3 -3 f`, `head -5 -1`, `head -v12 f`, `head -q5 a b` and `head -z5 f` are "invalid trailing option -- 3" (5, 1).
    // The matrix's own P60 and P61 examples (`head -n 3 -5`, `head -c 5 -5`) are refused by real head.
    @Test
    fun `a count anywhere but first is refused`() = parity.checkAll(
        listOf(
            refused("-c", "5", "-3", "f", because = "head: invalid trailing option -- 3"),
            refused("-n", "3", "-5", "f", because = "head: invalid trailing option -- 5"),
            refused("-v", "-3", "f", because = "head: invalid trailing option -- 3"),
            refused("f", "-3", because = "head: invalid trailing option -- 3"),
            refused("-3", "-3", "f", because = "head: invalid trailing option -- 3"),
            refused("-5", "-1", because = "head: invalid trailing option -- 1"),
            refused("-v12", "f", because = "head: invalid trailing option -- 1"),
            refused("-q5", "a", "b", because = "head: invalid trailing option -- 5"),
            refused("-z5", "f", because = "head: invalid trailing option -- 5"),
        ),
    )

    // P58, P84: `head -3n5 f`, `head -5n 3 f` and `head -5vn3 f`: "invalid trailing option -- n"; only b c k l m q v z
    // follow the digits.
    @Test
    fun `an n after the obsolete count is refused`() = parity.checkAll(
        listOf(
            refused("-3n5", "f", because = "head: invalid trailing option -- n"),
            refused("-5n", "3", "f", because = "head: invalid trailing option -- n"),
            refused("-5vn3", "f", because = "head: invalid trailing option -- n"),
        ),
    )

    /** A record, not a target: klap spells a long without its leading dashes, so none can start with a third. */
}

/**
 * The cells of the P table the fixture or klap fails today, each a TDD target: the record real head 9.12 reads the
 * line into, or its refusal, with the evidence beside it; removing the `@Ignore` shows the failure its message states.
 * A test ignored as `Will not do` is a record instead: what head does where the fixture reads otherwise by decision.
 */
class HeadProjectionTargetsTest {

    private val parity = ParitySuite(headCli())

    private fun ParitySuite<HeadInputs>.checkAll(vararg cells: Cell) = checkAll(cells.toList())

    // `head --=x f` lists `---presume-input-pipe` among the possibilities; `head ---presume-input-pipe f`, `head ---p
    // f` and `head --- f` print f; rm's `---presume-input-tty` is the same kind of hook (todo 121).
    @Ignore(
        "Will not do (todo 145): klap refuses a long starting with '-', and head's ---presume-input-pipe is an " +
            "undocumented test hook",
    )
    @Test
    fun `---presume-input-pipe is accepted`() = parity.checkAll(
        binds("---presume-input-pipe", "f", because = "prints f", expected = F),
        binds("---p", "f", because = "prints f", expected = F),
        binds("---", "f", because = "prints f", expected = F),
        refused(
            "---presume-input-pipe=x", "f",
            because = "head: option '---presume-input-pipe' doesn't allow an argument",
        ),
    )
}
