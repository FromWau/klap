package com.fromwau.klap.fixture.dd

import com.fromwau.klap.fixture.dd.DdLineContext.assertOffersValues
import com.fromwau.klap.fixture.dd.DdLineContext.at
import com.fromwau.klap.fixture.dd.DdLineContext.checkContext
import com.fromwau.klap.fixture.dd.DdLineContext.checkContextValues
import com.fromwau.klap.fixture.dd.DdLineContext.filesOf
import com.fromwau.klap.fixture.dd.DdLineContext.valuesAfter
import kotlin.test.Test

/*
 * Every expectation is what real dd 9.12 accepts at the cursor, run inside a throwaway directory. dd's operands share
 * one slot in any order, so no word after the cursor moves it (L13), and a key given before is taken again, the
 * later winning (`dd count=1 count=2` copies two blocks), so nothing given rules a key out (S21). What does reach
 * across words is `conv=`: dd ORs every `conv=` on the line and refuses a pair it cannot combine (`dd conv=ucase
 * conv=lcase`: "cannot combine lcase and ucase"). dd has no flag, option value, exclusive set of inputs, absentWhen
 * trigger, subcommand or global, so the L and S rows of those (L5 to L12, L15 to L19, L22 to L25, S2 to S20, S22 to
 * S43, S46, S47) have no dd cell; L27 is fish's, left to the live shell tests.
 */

/**
 * What the rest of a dd line does to the slot under the cursor (L and S of the testing matrix): the operands before
 * and after it, `--`, klap's built-ins and malformed words, each leaving the key slot as it is.
 */
class DdLineContextTest {

    private val dd = ddCli().cli

    @Test
    fun `operands after the cursor leave the key slot as it is`() = dd.checkContext(
        // L13, L2b: `dd count=0 if=in of=o` and `dd if=in count=0 of=o` run alike.
        at("L13", "dd co| if=in of=out", keysStarting("co")),
        at("L13", "dd if=| of=out count=1", filesOf("if")),
        at("L13 inside", "dd co|nv=x if=in", keysStarting("co")),
        // L14: `dd if=in -- of=o` reads of=o after `--` as an operand still.
        at("L14", "dd co| -- of=out", keysStarting("co")),
        // L20, L21: what comes after the word does not change it, even a word dd refuses.
        at("L20", "dd co| --json", keysStarting("co")),
        at("L21", "dd co| bogus", keysStarting("co")),
        at("L21", "dd co| --zzz", keysStarting("co")),
        at("L21", "dd co| count", keysStarting("co")),
    )

    @Test
    fun `a key given before is offered again`() = dd.checkContext(
        // S21: `dd count=1 count=2` and `dd conv=ucase conv=notrunc` run; the later count wins, conv's add up.
        at("S21", "dd conv=ucase co|", keysStarting("co")),
        at("S21", "dd count=1 co|", keysStarting("co")),
        at("S21", "dd of=out of|", keysStarting("of")),
        at("S21", "dd if=in if|", keysStarting("if")),
    )

    @Test
    fun `every key is offered again after itself`() {
        // S21: `dd if=in if=in` and `dd status=none status=noxfer` run, the later winning.
        dd.assertOffersValues("S21", "dd if=in |", listOf("if="))
        dd.assertOffersValues("S21", "dd status=none st|", listOf("status="))
        dd.assertOffersValues("S21", "dd bs=1 |", listOf("bs=", "ibs=", "obs="))
    }

    @Test
    fun `flag lists add up across words with nothing ruled out`() = dd.checkContextValues(
        // `dd iflag=direct iflag=sync` and `dd oflag=append oflag=dsync` run.
        at("S21", "dd iflag=direct iflag=|", valuesAfter("iflag=", IFLAG_SYMBOLS)),
        at("S21 L3", "dd iflag=| iflag=direct", valuesAfter("iflag=", IFLAG_SYMBOLS)),
        at("S21", "dd status=none status=|", valuesAfter("status=", STATUS_LEVELS)),
    )

    @Test
    fun `a dash-led word before the cursor leaves the next word an operand`() = dd.checkContext(
        // S32, S45: `dd - count=1`: "unrecognized operand '-'"; `dd -x count=1`: "invalid option -- 'x'"; neither
        // claims the word after it.
        at("S32", "dd - co|", keysStarting("co")),
        at("S45", "dd -x co|", keysStarting("co")),
        at("S44", "dd --json if=|", filesOf("if")),
    )

    @Test
    fun `the rest of the word under the cursor is not sent`() = dd.checkContext(
        // L26: handed `co`, the `unt=1` after the cursor stays on the line.
        at("L26", "dd co|unt=1", keysStarting("co")),
        at("L26", "dd of|=out", keysStarting("of")),
    )

    @Test
    fun `a conv symbol given in another word is offered again`() = dd.checkContextValues(
        // S21: `dd conv=ucase conv=ucase` runs; L26: handed `conv=uc`, the `ase` after the cursor is not sent.
        at("S21", "dd conv=ucase conv=uc|", convAfter(listOf("ucase"), "uc")),
        at("L26", "dd conv=uc|ase", convAfter(emptyList(), "uc")),
    )

    // L3a, S21: `dd conv=ucase conv=lcase` and `dd conv=lcase conv=ucase`: "cannot combine lcase and ucase"; `dd
    // conv=ascii conv=ibm`: "cannot combine any two of {ascii,ebcdic,ibm}"; `dd conv=excl conv=nocreat`: "cannot
    // combine excl and nocreat".
    // E21: fish sends no word after the cursor, so there the L3a cells answer as `dd conv=|` does.
    @Test
    fun `conv offers no symbol another conv on the line rules out`() = dd.checkContextValues(
        at("S21", "dd conv=ucase conv=|", convAfter(listOf("ucase"))),
        at("S21", "dd conv=ucase conv=l|", emptyList()),
        at("L3a", "dd conv=| conv=ucase", convAfter(listOf("ucase"))),
        at("S21", "dd conv=ascii conv=|", convAfter(listOf("ascii"))),
        at("S21", "dd conv=excl conv=no|", convAfter(listOf("excl"), "no")),
        at("L3a", "dd conv=no| conv=nocreat", convAfter(listOf("nocreat"), "no")),
    )

    // S21: `dd if=in iseek=0 iseek=0` runs; every key is taken again, the later winning.
    @Test
    fun `every key dd takes is offered again after another`() = dd.checkContextValues(
        at("S21", "dd if=in i|", keysStarting("i")),
        at("S21", "dd seek=1 o|", keysStarting("o")),
        at("L26", "dd i|f=x", keysStarting("i")),
    )
}

/** `conv=sym` for each symbol starting with [typed] that dd still takes once [given] are on the line. */
private fun convAfter(
    given: List<String>,
    typed: String = "",
) = valuesAfter("conv=", symbolsAfter("conv", given).startingWith(typed))
