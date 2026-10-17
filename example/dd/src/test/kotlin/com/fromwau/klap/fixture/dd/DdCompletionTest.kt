package com.fromwau.klap.fixture.dd

import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.dd.DdLineContext.NOTHING
import com.fromwau.klap.fixture.dd.DdLineContext.assertOffersValues
import com.fromwau.klap.fixture.dd.DdLineContext.at
import com.fromwau.klap.fixture.dd.DdLineContext.checkContext
import com.fromwau.klap.fixture.dd.DdLineContext.checkContextValues
import com.fromwau.klap.fixture.dd.DdLineContext.filesOf
import com.fromwau.klap.fixture.dd.DdLineContext.valuesAfter
import kotlin.test.Test

/*
 * Every expectation is what real dd 9.12 accepts at the cursor: an operand word is one of dd's keys followed by `=`
 * and a value, and the only dash-led words dd takes are `--help`, `--version` and their unique prefixes. The evidence
 * is a run of real dd inside a throwaway directory unless a comment says `dd --help` or getopt. dd has no short
 * option, no option taking a value, no subcommand, negation, global, hidden input, number option or dash-led operand,
 * so the K rows of those (K1, K3 to K11, K16, K17, K19 to K28, K30 to K33, K35 to K44, K48 to K54, K56, K57, K61 to
 * K81, K83 to K93) and every K7 row but K7s have no dd cell; its one slot is K82, the `key=value` operand, read
 * through K12's cursor columns.
 */

/** Words with no `=` yet, each with the keys real dd takes that start with it. */
private val KEY_PREFIXES: List<String> = listOf("c", "co", "con", "cou", "cb", "if", "ifl", "ib", "of", "ofl", "ob")

/** Words dd has no key for, typed as far as any key would be. */
private val NO_KEY: List<String> = listOf("x", "IF", "Conv", "bss", "iff", "=", "__c", "read")

/**
 * What Tab completes at each slot and cursor column of a dd line (K and K7 of the testing matrix, with their cursor
 * columns), checked against what real dd 9.12 would accept there. The `key=` words are dd's operands (K82, K12); a
 * dash starts one of dd's two options (K2, K13, K15). A cell names its K row; generated cells cover every key.
 */
class DdCompletionTest {

    private val dd = ddCli().cli

    // --- K82, K12: the key half of an operand ---

    @Test
    fun `a word with no = completes to the keys it starts`() = dd.checkContext(
        KEY_PREFIXES.flatMap { prefix ->
            listOf(
                at("K82", "dd $prefix|", keysStarting(prefix)),
                at("K82 words after", "dd $prefix| of=out", keysStarting(prefix)),
                at("K82 inside", "dd $prefix|zz=x", keysStarting(prefix)),
            )
        },
    )

    @Test
    fun `the keys of seek skip status and bs complete by their names`() = dd.checkContextValues(
        listOf("b", "bs", "s", "se", "sk", "st", "status").map { at("K82", "dd $it|", keysStarting(it)) },
    )

    @Test
    fun `a word no key starts offers nothing`() = dd.checkContext(NO_KEY.map { at("K82 G15", "dd $it|", NOTHING) })

    @Test
    fun `an empty operand word offers at least every key with a row in dd's help`() {
        val lines = listOf(
            "K12a no word" to "dd |",
            "K12a start" to "dd |conv=x",
            "K12a words after" to "dd | of=out",
            "K12b no word" to "dd if=in |",
            "K12b both sides" to "dd if=in | of=out",
            "K14a" to "dd -- |",
            "K47" to "dd --zzz |",
            "K59" to "dd --help |",
            "K60" to "dd --json |",
        )
        checkCells(lines) { (rows, typed) -> dd.assertOffersValues(rows, typed, DOCUMENTED_KEYS.map { it.value }) }
    }

    @Test
    fun `an operand after another or after an option completes the same way`() = dd.checkContext(
        // K12b, K79: dd takes its operands in any order (`dd count=0 if=in of=o` runs).
        at("K12b", "dd if=in co|", keysStarting("co")),
        at("K12b inside", "dd if=in co|un=x", keysStarting("co")),
        at("K12b both sides", "dd if=in co| of=out", keysStarting("co")),
        at("K14a", "dd -- co|", keysStarting("co")),
        at("K14a", "dd if=in -- co|", keysStarting("co")),
        // K47: `dd --zzz count=1`: "unrecognized option '--zzz'"; it claims no word.
        at("K47", "dd --zzz co|", keysStarting("co")),
        // K59: `dd --help count=x` prints the usage; the words after it are operands still.
        at("K59", "dd --help co|", keysStarting("co")),
        at("K59", "dd --version co|", keysStarting("co")),
        // K60: klap's built-ins are no word dd reads as a value.
        at("K60", "dd --json co|", keysStarting("co")),
        at("K60", "dd --color=never co|", keysStarting("co")),
    )

    @Test
    fun `the word after --completion or --docs is an operand`() = dd.checkContext(
        // K55: `dd --completion bash`: "unrecognized option '--completion'", which claims no word.
        at("K55", "dd --completion co|", keysStarting("co")),
        at("K55", "dd --completion ba|", NOTHING),
        at("K55", "dd --docs co|", keysStarting("co")),
    )

    // --- K82: the value half of an operand ---

    @Test
    fun `a file key hands the path after its = to the shell`() = dd.checkContext(
        // K82, G72: `dd if=in of=o` reads in and writes o; the path is the shell's to complete.
        at("K82 no word", "dd if=|", filesOf("if")),
        at("K82", "dd if=i|", filesOf("if")),
        at("K82 inside", "dd if=i|n", filesOf("if")),
        at("K82 words after", "dd if=| of=out", filesOf("if")),
        at("K82 no word", "dd of=|", filesOf("of")),
        at("K82", "dd of=ou|", filesOf("of")),
        at("K82 words after", "dd of=ou| if=in", filesOf("of")),
        at("K14a", "dd -- if=|", filesOf("if")),
        at("K12b", "dd if=in of=|", filesOf("of")),
    )

    @Test
    fun `a number key offers nothing after its =`() = dd.checkContext(
        // K82, V1a: `dd bs=4M count=1` and the rest take any dd number; Tab has nothing to offer for it.
        OPERANDS.filter { it.kind == ValueKind.BYTES || it.kind == ValueKind.COUNT }.flatMap {
            listOf(
                at("K82 no word", "dd ${it.head}|", NOTHING),
                at("K82", "dd ${it.head}${it.sample}|", NOTHING),
                at("K82 words after", "dd ${it.head}| if=in", NOTHING),
            )
        },
    )

    @Test
    fun `a symbol key completes the symbols its value starts`() = dd.checkContextValues(
        // K82, V34: `dd conv=nocreat`, `conv=notrunc` and `conv=noerror` run; `dd status=progress` prints progress.
        at("K82", "dd conv=no|", valuesAfter("conv=", CONV_SYMBOLS.startingWith("no"))),
        at("K82 inside", "dd conv=no|xx", valuesAfter("conv=", CONV_SYMBOLS.startingWith("no"))),
        at("K82", "dd iflag=no|", valuesAfter("iflag=", IFLAG_SYMBOLS.startingWith("no"))),
        at("K82", "dd status=pro|", valuesAfter("status=", STATUS_LEVELS.takeLast(1))),
        at("K82 words after", "dd status=n| if=in", valuesAfter("status=", STATUS_LEVELS.take(2))),
    )

    @Test
    fun `a value no symbol starts offers nothing`() = dd.checkContext(
        // K82, G13: `dd conv=NOTRUNC`: "invalid conversion"; `dd status=pro`: "invalid status level".
        at("K82 G13", "dd conv=NO|", NOTHING),
        at("K82", "dd conv=zz|", NOTHING),
        at("K82 G13", "dd status=P|", NOTHING),
        at("K82", "dd zz=|", NOTHING),
        at("K82", "dd IF=|", NOTHING),
    )

    // --- K2, K13, K15: names after a dash ---

    @Test
    fun `--help and --version complete by every prefix getopt takes`() = dd.checkContextValues(
        // K13a, K13d, K15a: `dd --h`, `dd --he` and `dd --hel` print the usage; `dd --v` and `dd --vers` the version.
        at("K13a K15a", "dd --h|", DD_OPTIONS.take(1)),
        at("K13a", "dd --he|", DD_OPTIONS.take(1)),
        at("K13a", "dd --help|", DD_OPTIONS.take(1)),
        at("K13a inside", "dd --h|elp", DD_OPTIONS.take(1)),
        at("K13a words after", "dd --he| if=in", DD_OPTIONS.take(1)),
        at("K13d K15a", "dd --v|", DD_OPTIONS.takeLast(1)),
        at("K13d", "dd --vers|", DD_OPTIONS.takeLast(1)),
        at("K13d inside", "dd --ve|rs", DD_OPTIONS.takeLast(1)),
    )

    @Test
    fun `a dash-led word dd has no option for offers nothing`() = dd.checkContext(
        // K13b, K29, K7s: `dd -h`, `dd -5` and `dd -xy`: "invalid option"; K13c: `dd --help-all`: "unrecognized
        // option"; K45: `dd --help=x`: "option '--help' doesn't allow an argument"; K46: `dd --zzz=x`.
        at("K13b", "dd -h|", NOTHING),
        at("K13b", "dd -V|", NOTHING),
        at("K29", "dd -5|", NOTHING),
        at("K7s", "dd -xy|", NOTHING),
        at("K13c", "dd --help-|", NOTHING),
        at("K2", "dd --zzz|", NOTHING),
        at("K2 words after", "dd --zzz| if=in", NOTHING),
        at("K45", "dd --help=|", NOTHING),
        at("K45", "dd --version=|", NOTHING),
        at("K46", "dd --zzz=|", NOTHING),
        at("K46", "dd --zzz=a|", NOTHING),
    )

    @Test
    fun `after the end of options a dash-led word offers nothing`() = dd.checkContext(
        // K14a: `dd -- --help` and `dd -- -x`: "unrecognized operand"; `dd -- -- if=in`: "unrecognized operand '--'".
        at("K14a", "dd -- -|", NOTHING),
        at("K14a", "dd -- --he|", NOTHING),
        at("K14a inside", "dd -- --|he", NOTHING),
        at("K14a a second --", "dd -- --|", NOTHING),
    )

    // `dd --help`: "seek=N (or oseek=N) skip N obs sized output blocks", "skip=N (or iseek=N) skip N ibs sized input
    // blocks"; `dd iseek=0 oseek=0 if=in of=o` runs.
    @Test
    fun `an empty operand word offers every key dd takes`() = dd.checkContextValues(
        at("K12a no word", "dd |", KEYS),
        at("K12a start", "dd |conv=x", KEYS),
        at("K12a words after", "dd | of=out", KEYS),
        at("K12b no word", "dd if=in |", KEYS),
        at("K12b both sides", "dd if=in | of=out", KEYS),
        at("K14a", "dd -- |", KEYS),
        at("K47", "dd --zzz |", KEYS),
        at("K59", "dd --help |", KEYS),
        at("K60", "dd --json |", KEYS),
        at("K82", "dd i|", keysStarting("i")),
        at("K82", "dd o|", keysStarting("o")),
        at("K82", "dd is|", keysStarting("is")),
        at("K82", "dd os|", keysStarting("os")),
        at("K82 inside", "dd is|ee=4", keysStarting("is")),
    )

    // `dd --help`, each row's lines joined: "bs=BYTES read and write up to BYTES bytes at a time (default: 512);
    // overrides ibs and obs", "seek=N (or oseek=N) skip N obs sized output blocks", "skip=N (or iseek=N) skip N ibs
    // sized input blocks", "status=LEVEL The LEVEL of information to print to standard error; 'none' suppresses ...".
    @Test
    fun `every key is described by its whole row in dd's help`() = dd.checkContext(
        listOf("b", "bs", "s", "se", "sk", "st").map { at("K82 G6a G50", "dd $it|", keysStarting(it)) } +
            at("K12a no word", "dd |", KEYS),
    )

    // `dd --help` lists `--help display this help and exit` and `--version output version information and exit`;
    // `dd --json`, `--color`, `--completion bash` and `--docs markdown`: "unrecognized option".
    @Test
    fun `a dash offers exactly dd's two options`() = dd.checkContextValues(
        at("K2 K3", "dd -|", DD_OPTIONS),
        at("K2", "dd --|", DD_OPTIONS),
        at("K2 inside the dashes", "dd -|-help", DD_OPTIONS),
        at("K2 words after", "dd --| if=in", DD_OPTIONS),
        at("K13e", "dd --j|", NOTHING),
        at("K13f", "dd --c|", NOTHING),
        at("K13f", "dd --col|", NOTHING),
        at("K13g", "dd --comp|", NOTHING),
        at("K13g", "dd --d|", NOTHING),
    )

    // `dd --help`: "--help display this help and exit", "--version output version information and exit".
    @Test
    fun `--help and --version are described in dd's words`() = dd.checkContext(
        at("K13a G45", "dd --he|", DD_OPTIONS.take(1)),
        at("K13d G45", "dd --vers|", DD_OPTIONS.takeLast(1)),
    )

    // K46, G68: `dd --color=never if=in`: "unrecognized option '--color=never'"; nothing completes after its `=`.
    @Test
    fun `--color= offers no values`() = dd.checkContext(
        at("K46 G68", "dd --color=|", NOTHING),
        at("K46 G68", "dd --color=al|", NOTHING),
    )

    // K58: `dd __complete if=`: "unrecognized operand '__complete'", an operand like any other, and so is the word
    // after it.
    @Test
    fun `the word after __complete is an operand`() = dd.checkContext(
        at("K58", "dd __complete co|", keysStarting("co")),
        at("K58", "dd __complete if=|", filesOf("if")),
    )
}
