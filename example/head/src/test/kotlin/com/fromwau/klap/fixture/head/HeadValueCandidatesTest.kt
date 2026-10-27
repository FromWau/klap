package com.fromwau.klap.fixture.head

import com.fromwau.klap.fixture.head.HeadLineContext.FILES
import com.fromwau.klap.fixture.head.HeadLineContext.NOTHING
import com.fromwau.klap.fixture.head.HeadLineContext.at
import com.fromwau.klap.fixture.head.HeadLineContext.checkContext
import com.fromwau.klap.fixture.head.HeadLineContext.checkContextValues
import com.fromwau.klap.fixture.head.HeadLineContext.name
import kotlin.test.Test

/*
 * Every expectation is what real head 9.12 accepts at the cursor. A cell names its V rows (and the K, L or G rows it
 * meets) and is typed with `|` at the cursor. The evidence is a run of real head inside a throwaway directory unless a
 * comment says `head --help`. head's two values are NUMs, free text with no candidates (V1a), and its operands are
 * files (V3a, V24). It declares no choice, no file-valued option, no provider and no hidden input, so the V rows of
 * choices (V2, V12 to V21), of file values (V3b to V3g, V22, V23, V25 to V29), of providers (V4 to V11, V31 to V94)
 * and of hidden inputs (V30) have no head cell; V100 has none either, as head has no optional value.
 */

/**
 * Where the candidates for a head value come from (V of the testing matrix): nothing for a NUM in any form, the
 * shell's file completion for every operand, and the operand slot's source wherever no option took the word.
 */
class HeadValueCandidatesTest {

    private val head = headCli().cli

    @Test
    fun `every operand completes through the shell`() = head.checkContext(
        // V3a, V24: a file is any word, as many as given (`head a b` prints both).
        at("V3a V24", "head |", FILES),
        at("V24", "head a |", FILES),
        at("V24", "head a b |", FILES),
        at("V24", "head a b c|", FILES),
        at("V24 L2", "head a | b", FILES),
        at("V24 K79", "head -q |", FILES),
        at("V24 K79", "head -q a|", FILES),
        at("V24 S32", "head - |", FILES),
    )

    @Test
    fun `a NUM offers nothing in every form`() = head.checkContext(
        // V1a, V59: `head -n 5 f`, `head --lines=5 f`, `head -n5 f` and `head -c -5 f` take any NUM; Tab has nothing to
        // offer for it.
        at("V1a", "head -n |", *NOTHING),
        at("V1a", "head -n 1|", *NOTHING),
        at("V1a", "head -n -|", *NOTHING),
        at("V1a", "head -c |", *NOTHING),
        at("V1a", "head --lines=|", *NOTHING),
        at("V1a", "head --bytes=1|", *NOTHING),
        at("V1a", "head --bytes |", *NOTHING),
        at("V1a", "head -n1|", *NOTHING),
        at("V1a L2", "head -n | f", *NOTHING),
        // V97: `head -qn 5 a b` prints five lines of each, no headers: the spaced NUM after a cluster.
        at("V97", "head -qn |", *NOTHING),
        at("V97", "head -vc |", *NOTHING),
    )

    @Test
    fun `an abbreviated long reaches its NUM`() = head.checkContext(
        // V95, K33: `head --li 5 f`, `head --l=5 f` and `head --by 5 f` run.
        at("V95 K33", "head --li |", *NOTHING),
        at("V95", "head --l=|", *NOTHING),
        at("V95", "head --by 5|", *NOTHING),
    )

    @Test
    fun `an ambiguous abbreviation leaves the next word without candidates`() = head.checkContext(
        // V96, G90, K34: `head --v f`: "option '--v' is ambiguous; possibilities: '--verbose' '--version'".
        at("V96 G90", "head --v |", *NOTHING),
        at("V96 G90 L2", "head --ve | f", *NOTHING),
        at("V96 G90", "head --v=|", *NOTHING),
    )

    @Test
    fun `an unknown option claims no word so the next one is an operand`() = head.checkContext(
        // V96, K47: `head --zzz f` and `head --lines-of f`: "unrecognized option"; it claims no word.
        at("V96 K47", "head --zzz |", FILES),
        at("V96 K47 L2", "head --zzz | a b", FILES),
        at("V96 K47", "head --lines-of |", FILES),
    )

    @Test
    fun `a word an option took is no slot of its own`() = head.checkContext(
        // V98: `head -n --lines f`: "invalid number of lines: '-lines'": --lines is -n's NUM, so the cursor after it
        // is on an operand.
        at("V98", "head -n --lines |", FILES),
        at("V98", "head --bytes -q |", FILES),
        at("V98", "head -n -- |", FILES),
    )

    @Test
    fun `a NUM typed with dashes answers from the option`() = head.checkContext(
        // V63, K40: `head -n -5 f` prints all but the last five lines and `head -n --he f` is "invalid number of
        // lines"; the option's own source answers, never names.
        at("V63 K40", "head -n -|", *NOTHING),
        at("V63", "head -n --he|", *NOTHING),
        at("V63", "head --lines --|", *NOTHING),
        at("V63", "head -c -q|", *NOTHING),
    )

    @Test
    fun `after the end of options a dash-led word is an operand`() = head.checkContext(
        // V61: `head -- -x` prints the file -x.
        at("V61", "head -- -x|", FILES),
        at("V61", "head -- --lines=|", FILES),
        at("V61", "head -- -n|", FILES),
    )

    @Test
    fun `a glued short with nothing glued yet is a name`() = head.checkContextValues(
        // V99: `head -n` is "option requires an argument -- 'n'": `-n` is a name until a NUM is glued on.
        at("V99 K6", "head -n|", name("-n")),
        at("V99 K6 L2", "head -n| 5 f", name("-n")),
        at("V99 K6", "head -c|", name("-c")),
    )
}
