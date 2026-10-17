package com.fromwau.klap.fixture.dd

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.dd.DdProjectionSupport.Cell
import com.fromwau.klap.fixture.dd.DdProjectionSupport.binds
import com.fromwau.klap.fixture.dd.DdProjectionSupport.checkAll
import com.fromwau.klap.fixture.dd.DdProjectionSupport.refused
import kotlin.test.Test

/*
 * Every expected value is from GNU coreutils 9.12 `dd`, run inside a throwaway directory holding a small file `in`:
 * each number list below ran under every key it is checked on (`dd if=in of=o count=0 KEY=VALUE`, with conv=notrunc
 * for seek= and oseek=), a refusal being dd's own "invalid number". A huge skip= or seek= (`skip=1E`) is read and then
 * fails at run time ("cannot skip"), as a missing file would, so it binds. dd's values are numbers, file names and
 * symbol lists; it has no double, boolean, choice, enum, duration, date, path or optional value, so the X rows of those
 * (X2 to X5, X8 to X11, X24 to X34, X36 to X49, X62 to X68, X70 to X86) have no dd cell.
 */

private val BYTE_KEYS: List<String> = OPERANDS.filter { it.kind == ValueKind.BYTES }.map { it.key }

private val COUNT_KEYS: List<String> = OPERANDS.filter { it.kind == ValueKind.COUNT }.map { it.key }

private val NUMBER_KEYS: List<String> = BYTE_KEYS + COUNT_KEYS

/** `key=value` binds for each of [keys] and [values]. */
private fun accepted(
    keys: List<String>,
    vararg values: String,
    because: String,
): List<Cell> = keys.flatMap { key -> values.map { binds("$key=$it", because = because) } }

/** `key=value` is refused for each of [keys] and [values]. */
private fun refusedEach(
    keys: List<String>,
    vararg values: String,
    because: String,
): List<Cell> = keys.flatMap { key -> values.map { refused("$key=$it", because = because) } }

/**
 * What the fixture makes of each typed dd value (X of the testing matrix): a number with dd's multiplicative suffixes
 * and `x` products, a file name taken as typed, the CONV, FLAG and LEVEL lists, and a value given twice.
 */
class DdConverterTest {

    private val parity = ParitySuite(ddCli())

    @Test
    fun `a number binds digits with a suffix from dd's table`() = parity.checkAll(
        // X1b, X21: `dd --help`: "c=1, w=2, b=512, kB=1000, K=1024, MB=1000*1000, M=1024*1024, xM=M ... KiB=K,
        // MiB=M", "If N ends in 'B', it counts bytes not blocks"; `010` is decimal ten.
        accepted(
            NUMBER_KEYS, "512", "1", "010", "4M", "1K", "1kB", "1KiB", "1MiB", "1b", "1c", "1w", "1B", "2x3", "2x3x4",
            "1MB", "1M",
            because = "dd reads it as a number",
        ),
    )

    @Test
    fun `a count or an offset may be zero`() = parity.checkAll(
        // X21: the info page: "count=0 copies zero blocks"; `dd count=0x10` warns "'0x' is a zero multiplier" and
        // runs; `dd skip=1E` and `skip=9223372036854775807` are read, then "cannot skip".
        accepted(
            COUNT_KEYS, "0", "0B", "2x0", "00x10", "0x10", "1E", "9223372036854775807", "1x2B", "2Bx1",
            because = "dd reads it as a count",
        ),
    )

    @Test
    fun `a number dd does not read is refused`() = parity.checkAll(
        // X1b, X14 to X21: "dd: invalid number" for each; dd has no exponent, no lower case m g t p e, no `_` or
        // other grouping, no sign but a leading +, and an `x` takes a number on each side.
        refusedEach(
            NUMBER_KEYS, "", "abc", "-5", "-0", "1e3", "5 ", "2x", "x2", "xK", "1kk", "1Kb", "1m", "1Ki", "1D", "1mB",
            "1Mb", "1g", "1W", "1C", "1BB", "1iB", "B", "_1", "1_000", "++5", "+", "+ 5", "1K2", "1xB", "=1", "1=2",
            "+K", " K", "1cD", "1KiD",
            because = "dd: invalid number",
        ),
    )

    @Test
    fun `a file name binds as typed`() = parity.checkAll(
        // X60, X69: `dd if==in` fails to open '=in', `dd of="a b"` writes "a b", `dd if=` fails to open ''.
        binds("if==in", because = "dd: failed to open '=in'"),
        binds("of= a ", because = "dd writes the file ' a '"),
        binds("if=", because = "dd: failed to open ''"),
        binds("of=a=b=c", because = "dd writes the file 'a=b=c'"),
    )

    @Test
    fun `every conv symbol binds alone and in a list`() = parity.checkAll(
        // X35: `dd conv=ucase,notrunc` runs; `dd conv=block,unblock` and `conv=ascii,block` too, as dd holds block and
        // unblock apart only once cbs= is on the line; a symbol given twice in one list runs.
        CONV_SYMBOLS.map { binds("conv=${it.name}", because = "dd --help: ${it.name}") } +
            listOf("ucase,notrunc", "block,unblock", "ucase,ucase", "sparse,swab", "fdatasync,fsync", "ascii,block")
                .map { binds("conv=$it", because = "dd conv=$it runs") },
    )

    @Test
    fun `a conv list dd cannot read is refused`() = parity.checkAll(
        // X35, X30, X4a: "dd: invalid conversion" for an empty element, another case, an abbreviation, a blank.
        refusedEach(
            listOf("conv"), "", "ucase,", ",ucase", "ucase,,notrunc", "NOTRUNC", "notr", "ucase notrunc", "=ucase",
            "bogus",
            because = "dd: invalid conversion",
        ),
    )

    @Test
    fun `every flag binds on its own side and in a list`() = parity.checkAll(
        // X35: `dd iflag=append` and every other FLAG symbol run as input flags, all but fullblock as output flags.
        IFLAG_SYMBOLS.map { binds("iflag=${it.name}", because = "dd --help: ${it.name}") } +
            OFLAG_SYMBOLS.map { binds("oflag=${it.name}", because = "dd --help: ${it.name}") } +
            binds("iflag=direct,nocache", because = "dd iflag=direct,nocache runs") +
            binds("iflag=fullblock,append", because = "dd iflag=fullblock,append runs"),
    )

    @Test
    fun `a flag dd cannot read is refused`() = parity.checkAll(
        // X35: "dd: invalid input flag" (and output flag) for each; nolinks, cio, binary and text are on the info page
        // but not supported here.
        refusedEach(
            listOf("iflag", "oflag"), "", "direct,", "direct,,sync", "APPEND", "rsync",
            *UNSUPPORTED_FLAGS.toTypedArray(),
            because = "dd: invalid input flag (invalid output flag for oflag=)",
        ),
    )

    @Test
    fun `a status level binds and a word that is none is refused`() = parity.checkAll(
        // X4a: `dd status=none`, `noxfer` and `progress` run; "dd: invalid status level" for the rest.
        STATUS_LEVELS.map { binds("status=${it.name}", because = "dd --help: ${it.name}") } +
            refusedEach(
                listOf("status"), "", "pro", "NONE", "bogus", "none,", ",none", "progress,bogus",
                because = "dd: invalid status level",
            ),
    )

    @Test
    fun `an invalid earlier value refuses the line`() = parity.checkAll(
        // X55: `dd bs=abc bs=1`: "invalid number: 'abc'"; `dd status=bogus status=noxfer`: "invalid status level".
        refused("bs=abc", "bs=1", because = "dd: invalid number: 'abc'"),
        refused("status=bogus", "status=noxfer", because = "dd: invalid status level: 'bogus'"),
        refused("count=1", "count=x", because = "dd: invalid number: 'x'"),
    )

    @Test
    fun `a value holding = is checked after the first`() = parity.checkAll(
        // X58, X69: `dd count==1`: "invalid number: '=1'"; `dd conv==ucase`: "invalid conversion: '=ucase'".
        refused("count==1", because = "dd: invalid number: '=1'"),
        refused("count=1=2", because = "dd: invalid number: '1=2'"),
        refused("conv==ucase", because = "dd: invalid conversion: '=ucase'"),
    )

    // X21: dd reads its numbers with xstrtoumax: a lower case k, `KB`, `kiB`, `KD`, and a `B` after c, b or w are
    // suffixes too (each `dd count=1k` and the rest ran on every key); `dd count=1MD` and `1GD` run, a `D` after any
    // multiplier being decimal, while `1D`, `1cD` and `1KiD` are "invalid number".
    @Test
    fun `a number takes every suffix spelling dd reads`() = parity.checkAll(
        accepted(
            NUMBER_KEYS, "1k", "1KB", "1kiB", "1KD", "1kD", "1cB", "1bB", "1wB", "1MD", "1GD",
            because = "dd reads it",
        ),
    )

    // X21: `dd bs=K`, `bs=c`, `bs=Kx2` and `bs=2xK` run: a suffix with no digits before it counts one of its unit.
    @Test
    fun `a suffix alone counts one`() =
        parity.checkAll(accepted(NUMBER_KEYS, "K", "c", "Kx2", "2xK", because = "dd reads it as one unit"))

    // X15, X18: `dd count=' 5'`, a tab, a newline, `+5` and `' +5'` run, as strtoumax skips leading blanks and takes a
    // plus sign, and so does each factor of a product (`count=2x+3`, `count='2x 3'`); `5 `, `+ 5`, `+K` and `' K'` are
    // refused.
    @Test
    fun `a number may open with blanks and a plus sign`() = parity.checkAll(
        accepted(NUMBER_KEYS, " 5", "\t5", "+5", " +5", "\n5", because = "dd reads it as 5") +
            accepted(NUMBER_KEYS, "2x+3", "2x 3", because = "dd reads it as 6"),
    )

    // X21: the info page: "Block sizes (i.e., specified by BYTES strings) must be nonzero"; `dd bs=0`, `bs=0B`,
    // `bs=2x0`, `bs=00x10` and `bs=0x10`: "invalid number".
    @Test
    fun `a block size of zero is refused`() = parity.checkAll(
        refusedEach(BYTE_KEYS, "0", "0B", "2x0", "1x0", "00x10", "0x10", because = "dd: invalid number: '0'"),
    )

    // X17, X19: `dd count=1Q`, `1R`, `1Y`, `1Z`, `15E`, `9223372036854775808` and `99999999999999999999`: "invalid
    // number: ... Value too large for defined data type"; dd's numbers stop at 2^63 - 1.
    @Test
    fun `a number too large is refused`() = parity.checkAll(
        refusedEach(
            NUMBER_KEYS, "1Q", "1R", "1Y", "1Z", "15E", "9223372036854775808", "99999999999999999999",
            because = "dd: invalid number: Value too large for defined data type",
        ),
    )

    // X14: `dd count=١٢` and `count=１２`: "invalid number"; dd reads ASCII digits only, while Kotlin's isDigit takes
    // every Unicode digit.
    @Test
    fun `a number in other digits is refused`() =
        parity.checkAll(refusedEach(NUMBER_KEYS, "١٢", "１２", because = "dd: invalid number"))

    // X35: `dd conv=ascii,ebcdic`, `ascii,ibm`, `ebcdic,ibm`: "cannot combine any two of {ascii,ebcdic,ibm}"; `dd
    // conv=lcase,ucase`: "cannot combine lcase and ucase"; `dd conv=excl,nocreat`: "cannot combine excl and
    // nocreat"; dd ORs every conv= on the line first, so `dd conv=ucase conv=lcase` is refused the same way.
    @Test
    fun `conv symbols dd cannot combine are refused`() = parity.checkAll(
        refusedEach(
            listOf("conv"), "ascii,ebcdic", "ascii,ibm", "ebcdic,ibm", "lcase,ucase", "excl,nocreat", "ucase,lcase",
            because = "dd: cannot combine",
        ) + listOf(
            refused("conv=ucase", "conv=lcase", because = "dd: cannot combine lcase and ucase"),
            refused("conv=excl", "conv=nocreat", because = "dd: cannot combine excl and nocreat"),
            refused("conv=ascii", "cbs=4", "conv=ebcdic", because = "dd: cannot combine any two of {ascii,ebcdic,ibm}"),
        ),
    )

    @Test
    fun `once cbs is given block and unblock are refused together`() = parity.checkAll(
        // X35: with cbs= dd blocks or unblocks, ascii counting as unblock and ebcdic and ibm as block: `dd
        // conv=block,unblock cbs=4`, `conv=ascii,block cbs=4`, `conv=ebcdic,unblock cbs=4`, `conv=ibm,unblock cbs=4`
        // and `dd conv=ebcdic conv=unblock cbs=4`: "cannot combine block and unblock"; `dd conv=ebcdic,block cbs=4`,
        // `conv=ascii,unblock cbs=4` and `conv=ebcdic,unblock` run.
        refusedEach(
            listOf("conv"), "block,unblock", "ascii,block", "ebcdic,unblock", "ibm,unblock",
            because = "dd: cannot combine block and unblock",
        ).map { it.copy(argv = it.argv + "cbs=4") } + listOf(
            refused("conv=ebcdic", "conv=unblock", "cbs=4", because = "dd: cannot combine block and unblock"),
            refused("cbs=4", "conv=unblock", "conv=ibm", because = "dd: cannot combine block and unblock"),
            binds("conv=ebcdic,block", "cbs=4", because = "dd conv=ebcdic,block cbs=4 runs"),
            binds("conv=ascii,unblock", "cbs=4", because = "dd conv=ascii,unblock cbs=4 runs"),
            binds("conv=ebcdic,unblock", because = "dd conv=ebcdic,unblock runs"),
        ),
    )

    // X35: `dd oflag=fullblock` and `oflag=append,fullblock`: "invalid output flag: 'fullblock'".
    @Test
    fun `fullblock is refused as an output flag`() = parity.checkAll(
        refusedEach(listOf("oflag"), "fullblock", "append,fullblock", because = "dd: invalid output flag: 'fullblock'"),
    )

    // X35, G35: `dd iflag=count_bytes`, `skip_bytes` and `seek_bytes` run on both sides, without a warning.
    @Test
    fun `the undocumented byte flags are accepted`() = parity.checkAll(
        accepted(listOf("iflag", "oflag"), *UNDOCUMENTED_FLAGS.toTypedArray(), because = "dd runs it"),
    )

    // X35: `dd status=none,noxfer`, `status=noxfer,none` and `status=none,progress` run, the last level winning;
    // `status=none,` and `status=progress,bogus` are refused.
    @Test
    fun `a status list is accepted`() = parity.checkAll(
        accepted(listOf("status"), "none,noxfer", "noxfer,none", "none,progress", because = "dd runs it"),
    )
}
