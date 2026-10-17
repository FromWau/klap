package com.fromwau.klap.fixture.ls

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.ls.LsProjectionSupport.Cell
import com.fromwau.klap.fixture.ls.LsProjectionSupport.checkAll
import kotlin.test.Test

/*
 * Every expected value is from GNU ls 9.12, run inside a throwaway directory with COLUMNS and TABSIZE unset: whether a
 * width took is read off `ls -x -w N` over four files of three letters, which lists four per line from 18 columns and
 * three below (so `-w 021` listing three shows octal 17, and `-w 21` listing four decimal 21). A refusal is ls's own
 * "invalid line width" or "invalid tab size". ls has no double, boolean, date, path or custom value, so the X rows of
 * those (X2, X3, X8 to X13, X24 to X28, X44 to X49, X62 to X86) have no ls cell; its choices are the projection
 * tests' `ATTACHED`, `CHOICE_*` and `SYNONYM*` cells.
 */

private fun bound(
    vararg argv: String,
    because: String,
    expected: LsInputs,
) = Cell("value", "a converted value", argv.toList(), Outcome.Binds(expected), because)

private fun refused(
    vararg argv: String,
    because: String,
) = Cell("value", "a converted value", argv.toList(), Outcome.Rejects, because)

private val A: LsInputs = NOTHING_BOUND.copy(files = listOf("a"))

private fun width(
    typed: String,
    cols: Long,
) = bound(
    "-w", typed, "a",
    because = "`ls -x -w '$typed'` lists as $cols columns",
    expected = A.copy(width = cols.toULong()),
)

private fun tabs(
    typed: String,
    cols: Long,
) = bound("-T", typed, "a", because = "`ls -T '$typed' a` lists a", expected = A.copy(tabSize = cols.toULong()))

/**
 * What the fixture makes of each typed ls value (X of the testing matrix): the COLS of `-w` and `-T` as ls's strtoumax
 * reads them, the patterns of `-I` and `--hide` bound verbatim and in line order, the optional WHEN values left bare
 * or given twice, and the repeats of a single value.
 */
class LsConverterTest {

    private val parity = ParitySuite(lsCli())

    @Test
    fun `-w binds a decimal width in every spelling`() = parity.checkAll(
        // X1b, X17, X56: `-w 0` means no limit; `-w17`, `--width=17`, `--wid=17` and `-xw17` list three per line.
        listOf(
            width("0", 0), width("1", 1), width("80", 80), width("+5", 5), width("00", 0),
            width("2147483647", Int.MAX_VALUE.toLong()),
            // X15: `ls -x -w ' 17'` and `-w '\t17'` list three per line, `-w ' 21'` four: leading blanks are skipped.
            width(" 17", 17), width("\t17", 17), width(" 21", 21),
            bound("-w17", "a", because = "three per line", expected = A.copy(width = 17u)),
            bound("--width=17", "a", because = "three per line", expected = A.copy(width = 17u)),
            bound("--width", "17", "a", because = "three per line", expected = A.copy(width = 17u)),
            bound("--wid=17", "a", because = "three per line", expected = A.copy(width = 17u)),
            bound("-w", "17", "-w", "18", "a", because = "four per line", expected = A.copy(width = 18u)),
            bound("-w", "18", "--width=17", "a", because = "three per line", expected = A.copy(width = 17u)),
        ),
    )

    @Test
    fun `-w refuses what is no width`() = parity.checkAll(
        // X1b, X14, X15, X18, X59: "invalid line width" for each, Arabic-Indic and fullwidth digits too; `-w=17` hands
        // -w the word `=17`.
        listOf("", "80 ", "1e3", "abc", "5k", "+", "-", "--", "--5", "=17", "١٢", "１２").map {
            refused("-w", it, "a", because = "ls: invalid line width: '$it'")
        } + refused("-w=17", "a", because = "ls: invalid line width: '=17'") +
            refused("--width=", "a", because = "ls: invalid line width: ''"),
    )

    @Test
    fun `-T binds a decimal tab size`() = parity.checkAll(
        // X1b, X15: `ls -T 0 a`, `-T 1`, `-T 8`, `-T +5`, `-T 00`, `-T ' 8'` and `-T '\t8'` list a.
        listOf(
            tabs("0", 0), tabs("1", 1), tabs("8", 8), tabs("+5", 5), tabs("00", 0),
            tabs("2147483647", Int.MAX_VALUE.toLong()),
            tabs(" 8", 8), tabs("\t8", 8),
        ),
    )

    @Test
    fun `-T refuses what is no tab size`() = parity.checkAll(
        // X1b, X14, X19: "invalid tab size" for each, and `-T 9223372036854775808` and `-T 18446744073709551615` "Value
        // too large for defined data type".
        listOf("", "8 ", "abc", "--", "9223372036854775808", "18446744073709551615", "١٢", "１２").map {
            refused("-T", it, "a", because = "ls: invalid tab size: '$it'")
        } + refused("--tabsize=", "a", because = "ls: invalid tab size: ''"),
    )

    @Test
    fun `an invalid earlier value refuses the line`() = parity.checkAll(
        // X55: `ls -x -w abc -w 18`: "invalid line width: 'abc'"; `ls --color=bogus --color=always`, `ls
        // --sort=bogus --sort=size` and `ls --classify=bogus --classify`: "invalid argument 'bogus'".
        listOf(
            refused("-w", "abc", "-w", "18", "a", because = "ls: invalid line width: 'abc'"),
            refused("-T", "abc", "-T", "8", "a", because = "ls: invalid tab size: 'abc'"),
            refused("--color=bogus", "--color=always", "a", because = "ls: invalid argument 'bogus' for '--color'"),
            refused("--sort=bogus", "--sort=size", "a", because = "ls: invalid argument 'bogus' for '--sort'"),
            refused("--format=bogus", "--format=long", "a", because = "ls: invalid argument 'bogus' for '--format'"),
            refused("--classify=bogus", "--classify", "a", because = "ls: invalid argument 'bogus' for '--classify'"),
        ),
    )

    @Test
    fun `a pattern binds verbatim in line order`() = parity.checkAll(
        // X52, X54, X60, X61: `ls -m -I c* -I a*` hides both, `ls -m -I a.y -Ib.z --ignore c.x --ignore=sub` hides
        // all four; ls hands each word to fnmatch as typed.
        listOf(
            bound(
                "-I", "*.o", "-I", "*.o", "a",
                because = "both kept",
                expected = A.copy(ignore = listOf("*.o", "*.o")),
            ),
            bound("-I", "a", "-Ib", "--ignore", "c", "--ignore=d", because = "in order", expected =
                NOTHING_BOUND.copy(ignore = listOf("a", "b", "c", "d"))),
            bound("-I", "[a-", "a", because = "fnmatch reads it", expected = A.copy(ignore = listOf("[a-"))),
            bound("--hide", " x ", "a", because = "verbatim", expected = A.copy(hide = listOf(" x "))),
            bound("--hide=ü*", "a", because = "verbatim", expected = A.copy(hide = listOf("ü*"))),
            bound("-I", "", "a", because = "an empty pattern", expected = A.copy(ignore = listOf(""))),
        ),
    )

    @Test
    fun `an optional WHEN reads its value attached and its bare form as always`() = parity.checkAll(
        // X5a, X38, X40, X42, X43: `ls --color= sub` is "ambiguous argument ''"; `ls --col sub` and `ls --colo=al
        // sub` colour; `ls --color -- a.y` lists a.y and `ls --color -` is "cannot access '-'"; `ls --color=never
        // --color sub` colours.
        listOf(
            refused("--color=", "a", because = "ls: ambiguous argument '' for '--color'"),
            bound("--col", "a", because = "coloured", expected = A.copy(color = When.Always)),
            bound("--colo=al", "a", because = "coloured", expected = A.copy(color = When.Always)),
            bound("--color", "--", "a", because = "lists a", expected = A.copy(color = When.Always)),
            bound(
                "--color", "-",
                because = "cannot access '-'",
                expected = NOTHING_BOUND.copy(color = When.Always, files = listOf("-")),
            ),
            bound("--color=never", "--color", "a", because = "coloured", expected = A.copy(color = When.Always)),
            bound("--cl", "a", because = "sub/", expected = A.copy(indicator = IndicatorStyle.Classify)),
            bound("--classify=n", "a", because = "sub", expected = A),
        ),
    )

    @Test
    fun `-w and -T read octal and hex`() = parity.checkAll(
        // X16: `ls -x -w 021`, `-w 0021`, `-w 0x11`, `-w 0X11` and `-w +0x11` list three per line, where `-w 21` lists
        // four: octal after a 0, hex after 0x. `-w 010` and `-w 0x50` are accepted and `-w 080` refused, the same base
        // 0 (8 and 80 not seen in the layout); `-T 010`, `-T 0x10` and `-T 0X10` are accepted and `-T 080` refused (the
        // tab sizes not seen).
        listOf(
            width("021", 17), width("0021", 17), width("0x11", 17), width("0X11", 17), width("+0x11", 17),
            width("010", 8), width("0x50", 80), tabs("010", 8), tabs("0x10", 16), tabs("0X10", 16),
        ) + listOf(
            refused("-w", "080", "a", because = "ls: invalid line width: '080'"),
            refused("-T", "080", "a", because = "ls: invalid tab size: '080'"),
        ),
    )

    @Test
    fun `a width past the largest number is accepted`() = parity.checkAll(
        // X19: `ls -w 2147483648 a`, `-w 18446744073709551615`, `-w 99999999999999999999` and `-w
        // 18446744073709551616` list a, the record reading a width past 2^64-1 as the largest; `ls -T 2147483648 a` and
        // `-T 9223372036854775807` list a.
        listOf(
            width("2147483648", 2147483648),
            bound(
                "-w", "18446744073709551615", "a",
                because = "lists a",
                expected = A.copy(width = ULong.MAX_VALUE),
            ),
            bound(
                "-w", "99999999999999999999", "a",
                because = "lists a",
                expected = A.copy(width = ULong.MAX_VALUE),
            ),
            bound(
                "-w", "18446744073709551616", "a",
                because = "lists a",
                expected = A.copy(width = ULong.MAX_VALUE),
            ),
            tabs("2147483648", 2147483648),
            tabs("9223372036854775807", Long.MAX_VALUE),
        ),
    )

    @Test
    fun `-w and -T refuse a negative number`() = parity.checkAll(
        // X18: `ls -w -1 a`: "invalid line width: '-1'", `-w -0`: "invalid line width: '-0'", `-T -1`: "invalid tab
        // size: '-1'".
        listOf(
            refused("-w", "-1", "a", because = "ls: invalid line width: '-1'"),
            refused("-w", "-5", "a", because = "ls: invalid line width: '-5'"),
            refused("-w", "-0", "a", because = "ls: invalid line width: '-0'"),
            refused("-T", "-0", "a", because = "ls: invalid tab size: '-0'"),
            refused("-T", "-1", "a", because = "ls: invalid tab size: '-1'"),
        ),
    )
}
