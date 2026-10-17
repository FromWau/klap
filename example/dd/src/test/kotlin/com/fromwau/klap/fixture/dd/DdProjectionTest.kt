package com.fromwau.klap.fixture.dd

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.dd.DdProjectionSupport.ACCUMULATED
import com.fromwau.klap.fixture.dd.DdProjectionSupport.AFTER_END
import com.fromwau.klap.fixture.dd.DdProjectionSupport.ALL
import com.fromwau.klap.fixture.dd.DdProjectionSupport.ALONE
import com.fromwau.klap.fixture.dd.DdProjectionSupport.AMONG
import com.fromwau.klap.fixture.dd.DdProjectionSupport.EMPTY_VALUE
import com.fromwau.klap.fixture.dd.DdProjectionSupport.KEY_ALONE
import com.fromwau.klap.fixture.dd.DdProjectionSupport.KEY_CASE
import com.fromwau.klap.fixture.dd.DdProjectionSupport.KEY_PREFIX
import com.fromwau.klap.fixture.dd.DdProjectionSupport.KEY_SPACED
import com.fromwau.klap.fixture.dd.DdProjectionSupport.LAST_WINS
import com.fromwau.klap.fixture.dd.DdProjectionSupport.binds
import com.fromwau.klap.fixture.dd.DdProjectionSupport.cellsIn
import com.fromwau.klap.fixture.dd.DdProjectionSupport.checkAll
import com.fromwau.klap.fixture.dd.DdProjectionSupport.refused
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * Every expected value is from GNU coreutils 9.12 `dd`, run inside a throwaway directory holding a small file `in`,
 * every `if=` and `of=` a relative name there: a `because` quotes what it printed. dd reads its argv with getopt_long
 * over `--help` and `--version` alone (`getopt -o '' -l help,version -- --he --v` reads both), then every word left is
 * a `key=value` operand. Which help or version text klap prints is DdVersionTest's.
 */

/**
 * The record a dd line binds in the fixture, or its refusal, as real dd 9.12 reads the line (P of the testing matrix):
 * every operand in every form and position, its key spelled wrong, its value empty or repeated, `--`, dash-led words,
 * help and version on a line, and klap's built-ins, which dd has not.
 */
class DdProjectionTest {

    private val parity = ParitySuite(ddCli())

    @Test
    fun `every operand binds alone among others and after the end of options`() =
        parity.checkAll(cellsIn(ALONE, AMONG, AFTER_END))

    @Test
    fun `a key spelled other than dd spells it is refused`() =
        parity.checkAll(cellsIn(KEY_CASE, KEY_PREFIX, KEY_ALONE, KEY_SPACED))

    @Test
    fun `an empty value binds a file name and refuses the rest`() = parity.checkAll(cellsIn(EMPTY_VALUE))

    @Test
    fun `conv iflag and oflag given twice keep both lists`() = parity.checkAll(cellsIn(ACCUMULATED))

    @Test
    fun `every generated cell is checked by one test`() {
        val forms = listOf(
            ALONE, AMONG, AFTER_END, KEY_CASE, KEY_PREFIX, KEY_ALONE, KEY_SPACED, EMPTY_VALUE, ACCUMULATED, LAST_WINS,
        )
        assertEquals(emptyList(), ALL.filter { it.form !in forms }, "cells no test checks")
    }

    @Test
    fun `operands bind in line order whatever the order`() = parity.checkAll(
        // P62, P3: `dd if=in of=o bs=4M count=1 status=progress` and the same operands in another order copy alike.
        binds("if=in", "of=out", "bs=4M", "count=10", "status=progress", because = "dd --help"),
        binds("count=1", "if=in", because = "`dd count=1 if=in` copies one block of in"),
        binds("cbs=80", "ibs=1024", "obs=2048", "seek=1", "skip=2", "oseek=3", "iseek=4", because = "dd --help"),
        binds("conv=ucase,notrunc", "iflag=fullblock,nocache", "oflag=append", because = "dd --help"),
    )

    @Test
    fun `a line of no operands binds the defaults`() = parity.checkAll(
        // P37: `dd </dev/null` copies standard input to standard output; `dd --` the same.
        binds(because = "`dd` copies nothing from /dev/null", expected = NOTHING_BOUND),
        binds("--", because = "`dd --` runs", expected = NOTHING_BOUND),
    )

    @Test
    fun `the end of options marker is read once`() = parity.checkAll(
        // P65: `dd if=in -- of=o`, `dd if=in of=o --` run; `dd -- -- if=in`: "unrecognized operand '--'"; `dd --
        // --help` and `dd -- -x`: "unrecognized operand".
        binds("if=in", "--", "of=out", because = "`dd if=in -- of=o` copies", expected = bound("if=in", "of=out")),
        binds("if=in", "of=out", "--", because = "`dd if=in of=o --` copies", expected = bound("if=in", "of=out")),
        refused("--", "--", "if=in", because = "dd: unrecognized operand '--'"),
        refused("--", "--help", because = "dd: unrecognized operand '--help'"),
        refused("--", "-x", because = "dd: unrecognized operand '-x'"),
    )

    @Test
    fun `a value keeps its exact text`() = parity.checkAll(
        // P71, P42: `dd if==in` fails to open '=in', `dd of="a b"` writes the file "a b", `dd if=a,b` fails to open
        // 'a,b', `dd if=-` fails to open '-': each value is the file name as typed. `of=~/x` was not run, as no real
        // run names a path outside its directory; a `~` reaches dd only as typed, since the shell expands it alone.
        binds("if==in", because = "dd: failed to open '=in'"),
        binds("of=a b", because = "`dd if=in of='a b'` writes 'a b'"),
        binds("if=a,b", because = "dd: failed to open 'a,b'"),
        binds("if=-", because = "dd: failed to open '-'"),
        binds("of=~/x", because = "the value is the file name as typed"),
    )

    @Test
    fun `a word that is no operand is refused`() = parity.checkAll(
        // P9a, P59: dd takes no word without `=`, and none starting with `=` or a lone dash.
        refused("", because = "dd: unrecognized operand ''"),
        refused("=", because = "dd: unrecognized operand '='"),
        refused("=1", because = "dd: unrecognized operand '=1'"),
        refused("-", because = "dd: unrecognized operand '-'"),
        refused("pro", because = "dd: unrecognized operand 'pro'"),
        refused("bogus=1", because = "dd: unrecognized operand 'bogus=1'"),
        refused("if=in", "count", because = "dd: unrecognized operand 'count'"),
    )

    @Test
    fun `a dash-led word dd has no option for is refused`() = parity.checkAll(
        // P9a, P59: `dd -h`, `dd -V`, `dd -v`, `dd -x` and `dd -5`: "invalid option"; `dd --zzz`, `dd ---` and `dd
        // ---x`: "unrecognized option"; `dd --help-all`: "unrecognized option '--help-all'".
        refused("-h", "if=in", because = "dd: invalid option -- 'h'"),
        refused("-V", because = "dd: invalid option -- 'V'"),
        refused("-v", because = "dd: invalid option -- 'v'"),
        refused("-x", because = "dd: invalid option -- 'x'"),
        refused("-5", because = "dd: invalid option -- '5'"),
        refused("--zzz", because = "dd: unrecognized option '--zzz'"),
        refused("---", because = "dd: unrecognized option '---'"),
        refused("---x", because = "dd: unrecognized option '---x'"),
        refused("--help-all", "if=in", because = "dd: unrecognized option '--help-all'"),
    )

    @Test
    fun `--help and --version refuse a value`() = parity.checkAll(
        // P53: "option '--help' doesn't allow an argument", for `--he=x` too.
        refused("--help=x", because = "dd: option '--help' doesn't allow an argument"),
        refused("--he=x", because = "dd: option '--help' doesn't allow an argument"),
        refused("--version=x", because = "dd: option '--version' doesn't allow an argument"),
    )

    @Test
    fun `an abbreviation of no dd option is refused`() = parity.checkAll(
        // P46, P48: dd's getopt table holds --help and --version alone: `dd --=x` is "ambiguous; possibilities:
        // '--help' '--version'", and every other prefix unrecognized.
        refused("--=x", because = "dd: option '--=x' is ambiguous; possibilities: '--help' '--version'"),
        refused("--c", "if=in", because = "dd: unrecognized option '--c'"),
        refused("--d", "if=in", because = "dd: unrecognized option '--d'"),
    )

    // P33: getopt acts on the options in line order: `dd --help --version` prints the usage, `dd --help --bogus`
    // prints the usage, `dd --version --bogus` prints the version; `dd --bogus --help` is refused.
    @Test
    fun `the first built-in on the line answers`() {
        parity.showsHelp("--help", "--version", because = "dd --help --version prints the usage")
        parity.showsHelp("--help", "--bogus", because = "dd --help --bogus prints the usage")
        parity.showsVersion("--version", "--bogus", because = "dd --version --bogus prints the version")
        parity.rejects("--bogus", "--help", because = "dd: unrecognized option '--bogus'")
    }

    @Test
    fun `--help and --version answer wherever they stand`() {
        // P30, P34: `dd --h`, `--he`, `--hel`, `dd if=in --help` and `dd bogus=1 --help` print the usage, a bad
        // operand before it included; `dd --v`, `--ver`, `--vers` and `dd if=in of=o --version` print the version.
        val help = listOf(
            listOf("--help"), listOf("--h"), listOf("--he"), listOf("--hel"), listOf("--help", "if=in"),
            listOf("if=in", "--help"), listOf("bogus=1", "--help"), listOf("count=x", "--help"),
        )
        checkCells(help) { parity.showsHelp(*it.toTypedArray(), because = "dd prints its usage") }
        val version = listOf(
            listOf("--version"), listOf("--v"), listOf("--ve"), listOf("--ver"), listOf("--vers"),
            listOf("if=in", "of=out", "--version"), listOf("--version", "--help"),
        )
        checkCells(version) { parity.showsVersion(*it.toTypedArray(), because = "dd prints its version") }
    }

    // P72, X56: the info page: "If this operand is given multiple times, the last one takes precedence" (status=);
    // `dd status=none status=noxfer` prints the counts, `dd count=1 count=2` copies two blocks, `dd if=in if=in2`
    // reads in2.
    @Test
    fun `a single value given twice binds the later`() = parity.checkAll(cellsIn(LAST_WINS))

    // P4a: `dd --help`: "N and BYTES may be followed by the following multiplicative suffixes: ... K=1024", and bs=
    // "overrides ibs and obs"; dd reads `bs=1K` as 1024 bytes.
    @Test
    fun `a number operand binds the number dd reads`() = parity.accepts("bs=1K", because = "dd --help: K=1024") {
        assertEquals(NOTHING_BOUND.copy(ibs = 1024, obs = 1024), it, "dd reads bs=1K as 1024 bytes")
    }

    @Test
    fun `bs sets ibs and obs wherever it stands`() = parity.checkAll(
        // `dd if=in ibs=4 bs=10 count=1` and `dd if=in bs=10 ibs=4 count=1` copy 10 bytes; `bs=10 obs=4` as well.
        binds("ibs=4", "bs=10", because = "dd copies 10 bytes", expected = NOTHING_BOUND.copy(ibs = 10, obs = 10)),
        binds("bs=10", "ibs=4", because = "dd copies 10 bytes", expected = NOTHING_BOUND.copy(ibs = 10, obs = 10)),
        binds("bs=10", "obs=4", because = "dd copies 10 bytes", expected = NOTHING_BOUND.copy(ibs = 10, obs = 10)),
    )

    @Test
    fun `a count or offset ending in B or under a byte flag counts bytes`() = parity.checkAll(
        // `dd if=in ibs=10 count=3B` copies 3 bytes and `count=3B count=2` 20; `skip=2B` and
        // `iflag=skip_bytes skip=2` start at the third byte, `seek=2B` and `oflag=seek_bytes seek=2` write after two
        // NULs. A byte flag acts on its own side alone: `oflag=count_bytes count=3` copies 30 bytes, `iflag=seek_bytes
        // seek=2` writes after two obs blocks.
        binds("count=3B", because = "dd copies 3 bytes", expected = NOTHING_BOUND.copy(count = Amount.Bytes(3))),
        binds("count=3B", "count=2", because = "dd copies 2 blocks"),
        binds("skip=2B", because = "dd skips 2 bytes", expected = NOTHING_BOUND.copy(skip = Amount.Bytes(2))),
        binds("iseek=2B", because = "dd skips 2 bytes", expected = NOTHING_BOUND.copy(skip = Amount.Bytes(2))),
        binds("seek=2B", because = "dd seeks 2 bytes", expected = NOTHING_BOUND.copy(seek = Amount.Bytes(2))),
        binds(
            "iflag=count_bytes", "count=3",
            because = "dd copies 3 bytes",
            expected = NOTHING_BOUND.copy(count = Amount.Bytes(3)),
        ),
        binds(
            "count=3", "iflag=count_bytes",
            because = "dd copies 3 bytes",
            expected = NOTHING_BOUND.copy(count = Amount.Bytes(3)),
        ),
        binds(
            "iflag=skip_bytes", "skip=2",
            because = "dd skips 2 bytes",
            expected = NOTHING_BOUND.copy(skip = Amount.Bytes(2)),
        ),
        binds(
            "oflag=seek_bytes", "seek=2",
            because = "dd seeks 2 bytes",
            expected = NOTHING_BOUND.copy(seek = Amount.Bytes(2)),
        ),
        binds(
            "oflag=count_bytes", "count=3",
            because = "dd copies 3 blocks",
            expected = NOTHING_BOUND.copy(count = Amount.Blocks(3)),
        ),
        binds(
            "iflag=seek_bytes", "seek=2",
            because = "dd seeks 2 blocks",
            expected = NOTHING_BOUND.copy(seek = Amount.Blocks(2)),
        ),
    )

    // P31, P84: `dd --json if=in`, `dd --j if=in`, `dd --color=never if=in`, `dd --col if=in`, `dd --completion bash`
    // and `dd --docs markdown`: "unrecognized option"; `dd __complete if=`: "unrecognized operand '__complete'".
    @Test
    fun `an option dd does not have is refused`() = parity.checkAll(
        refused("--json", "if=in", because = "dd: unrecognized option '--json'"),
        refused("--j", "if=in", because = "dd: unrecognized option '--j'"),
        refused("--color=never", "if=in", because = "dd: unrecognized option '--color=never'"),
        refused("--color", "if=in", because = "dd: unrecognized option '--color'"),
        refused("--col", "if=in", because = "dd: unrecognized option '--col'"),
        refused("--completion", "bash", because = "dd: unrecognized option '--completion'"),
        refused("--docs", "markdown", because = "dd: unrecognized option '--docs'"),
    )

    // P32: `dd __complete if=`: "unrecognized operand '__complete'".
    @Test
    fun `__complete is refused as an operand`() =
        parity.checkAll(refused("__complete", "if=", because = "dd: unrecognized operand '__complete'"))
}
