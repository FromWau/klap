package com.fromwau.klap.fixture.dd

import com.fromwau.klap.fixture.dd.DdLineContext.NOTHING
import com.fromwau.klap.fixture.dd.DdLineContext.assertOffersNone
import com.fromwau.klap.fixture.dd.DdLineContext.at
import com.fromwau.klap.fixture.dd.DdLineContext.checkContext
import com.fromwau.klap.fixture.dd.DdLineContext.checkContextValues
import com.fromwau.klap.fixture.dd.DdLineContext.filesOf
import com.fromwau.klap.fixture.dd.DdLineContext.valuesAfter
import com.fromwau.klap.testing.Suggestion
import kotlin.test.Test

/*
 * Every expectation is what real dd 9.12 accepts at the cursor, run inside a throwaway directory unless a comment says
 * `dd --help`. A cell names its V rows (and the K, G or L rows it meets) and is typed with `|` at the cursor. dd's one
 * provider is the operand's: it hands a file key's path to the shell (V10b, V34), lists a symbol key's symbols, and
 * offers nothing for a number (V1a). dd has no option value, choice, enum, `.file()` input, hidden input or global,
 * and its provider reads no other input and cannot fail, so the V rows of those (V2, V3, V5, V7, V8, V9a to V9c, V11 to
 * V33, V36 to V49, V51 to V58, V62 to V80, V82 to V100) have no dd cell; V35 has none either, as the provider's
 * prefix is always the start of the word.
 */

/** The value of [key] after [given] symbols and a comma, each symbol dd still takes there, values only. */
private fun continuing(
    key: String,
    vararg given: String,
    typed: String = "",
    cbs: Boolean = false,
): List<Suggestion> {
    val prefix = "$key=" + given.joinToString(",") + ","
    return valuesAfter(prefix, symbolsAfter(key, given.toList(), cbs).startingWith(typed))
}

/**
 * Where the candidates for a dd value come from (V of the testing matrix): the shell's file completion after `if=` and
 * `of=`, the symbol tables after `conv=`, `iflag=`, `oflag=` and `status=`, nothing after a number key, and the keys
 * themselves before the `=`, each filtered on the whole word typed.
 */
class DdValueCandidatesTest {

    private val dd = ddCli().cli

    @Test
    fun `a file key hands the shell the path after its key alone`() = dd.checkContext(
        // V10b, V34, G74: `dd if=in of=o` copies in to o; the directive is the whole answer.
        at("V10b V34", "dd if=in|", filesOf("if")),
        at("V10b G74", "dd of=|", filesOf("of")),
        // G84: `dd if==in`: "failed to open '=in'"; the path is everything after the first `=`.
        at("V10b G84", "dd if=a=b|", filesOf("if")),
        // G75: the directive is offered whatever is typed after the head.
        at("V10b G75", "dd if=no/such/pre|", filesOf("if")),
        at("V10b V59 inside", "dd of=o|ut", filesOf("of")),
    )

    @Test
    fun `a symbol key offers every symbol of its table`() = dd.checkContextValues(
        // V34: `dd conv=ascii` and every other CONV symbol run, as do `dd iflag=append` and the other FLAG symbols, and
        // `dd status=none`, `noxfer` and `progress`.
        at("V34", "dd conv=|", valuesAfter("conv=", CONV_SYMBOLS)),
        at("V34", "dd iflag=|", valuesAfter("iflag=", IFLAG_SYMBOLS)),
        at("V34", "dd status=|", valuesAfter("status=", STATUS_LEVELS)),
        at("V34 L2", "dd conv=| if=in", valuesAfter("conv=", CONV_SYMBOLS)),
    )

    @Test
    fun `the provider filters on the whole word typed key included`() = dd.checkContext(
        // V50, V4a, G14: `dd status=pro` is "invalid status level", and `dd pro` "unrecognized operand 'pro'": no key
        // starts with a symbol's name.
        at("V50", "dd pro|", NOTHING),
        at("V50", "dd none|", NOTHING),
        at("V50", "dd ucase|", NOTHING),
        at("V50", "dd conv|", keysStarting("conv")),
    )

    @Test
    fun `a value no symbol starts offers nothing`() = dd.checkContext(
        // V34, G13, G77: `dd conv=NOTRUNC`: "invalid conversion: 'NOTRUNC'"; `dd zz=1`: "unrecognized operand".
        at("V34 G13", "dd conv=NOT|", NOTHING),
        at("V34", "dd iflag=zz|", NOTHING),
        at("V34", "dd zz=|", NOTHING),
        at("V34 G77", "dd Conv=|", NOTHING),
    )

    @Test
    fun `flags dd keeps out of its help are not offered`() {
        // G35: `dd iflag=count_bytes`, `skip_bytes` and `seek_bytes` run, though `dd --help` lists none of them.
        dd.assertOffersNone("G35", "dd iflag=|", UNDOCUMENTED_FLAGS.map { "iflag=$it" })
        dd.assertOffersNone("G35", "dd oflag=s|", UNDOCUMENTED_FLAGS.map { "oflag=$it" })
        // `dd iflag=nolinks`: "invalid input flag: 'nolinks'", and the same for cio, binary and text.
        dd.assertOffersNone("G1a", "dd iflag=|", UNSUPPORTED_FLAGS.map { "iflag=$it" })
        dd.assertOffersNone("G1a", "dd oflag=|", UNSUPPORTED_FLAGS.map { "oflag=$it" })
    }

    @Test
    fun `the word under the cursor is handed over as far as it is typed`() = dd.checkContextValues(
        // V59, L26: inside a word the rest of it is not sent, so `dd conv=no|tr` completes `conv=no`.
        at("V59 inside", "dd conv=no|tr", valuesAfter("conv=", CONV_SYMBOLS.startingWith("no"))),
        at("V59 inside", "dd status=|xx", valuesAfter("status=", STATUS_LEVELS)),
    )

    // `dd oflag=fullblock`: "invalid output flag: 'fullblock'"; `dd --help`: "fullblock accumulate full blocks of input
    // (iflag only)".
    @Test
    fun `oflag offers every flag but fullblock`() = dd.checkContextValues(
        at("V34 G1a", "dd oflag=|", valuesAfter("oflag=", OFLAG_SYMBOLS)),
        at("V34 G1a", "dd oflag=f|", NOTHING),
        at("V34 G1a", "dd oflag=full|", NOTHING),
    )

    // `dd --help`, "Each CONV symbol may be: ascii from EBCDIC to ASCII ...", "Each FLAG symbol may be: append append
    // mode ...", and status='s row: "'none' suppresses everything but error messages ...".
    @Test
    fun `every symbol is described in dd's words`() = dd.checkContext(
        at("V6a G6a", "dd conv=|", valuesAfter("conv=", CONV_SYMBOLS)),
        at("V6a G6a", "dd iflag=|", valuesAfter("iflag=", IFLAG_SYMBOLS)),
        at("V6a G6a", "dd oflag=|", valuesAfter("oflag=", OFLAG_SYMBOLS)),
        at("V6a G6a", "dd status=|", valuesAfter("status=", STATUS_LEVELS)),
        at("V6a G6a", "dd conv=no|", valuesAfter("conv=", CONV_SYMBOLS.startingWith("no"))),
    )

    // X35: `dd conv=ucase,notrunc`, `dd iflag=direct,nocache` and `dd status=none,progress` run: each of these keys
    // takes a comma separated list. `dd conv=ucase,lcase`: "cannot combine lcase and ucase", `dd conv=ascii,ibm`:
    // "cannot combine any two of {ascii,ebcdic,ibm}", `dd conv=excl,nocreat`: "cannot combine excl and nocreat".
    @Test
    fun `a comma continues the list with every symbol dd still takes`() = dd.checkContextValues(
        at("V34 X35", "dd conv=ucase,|", continuing("conv", "ucase")),
        at("V34 X35", "dd conv=ucase,no|", continuing("conv", "ucase", typed = "no")),
        at("V34 X35", "dd conv=ucase,l|", NOTHING),
        at("V34 X35", "dd conv=ascii,|", continuing("conv", "ascii")),
        at("V34 X35", "dd conv=notrunc,ascii,|", continuing("conv", "notrunc", "ascii")),
        at("V34 X35", "dd conv=excl,noc|", NOTHING),
        at("V34 X35 inside", "dd conv=ucase,n|xx", continuing("conv", "ucase", typed = "n")),
        at("V34 X35", "dd iflag=direct,|", continuing("iflag", "direct")),
        at("V34 X35", "dd oflag=append,|", continuing("oflag", "append")),
        at("V34 X35", "dd oflag=append,f|", NOTHING),
        at("V34 X35", "dd status=none,|", continuing("status", "none")),
    )

    @Test
    fun `once cbs is on the line conv offers no symbol that blocks beside one that unblocks`() = dd.checkContextValues(
        // X35: `dd conv=block,unblock cbs=4`, `conv=ascii,block cbs=4` and `conv=ebcdic,unblock cbs=4`: "cannot combine
        // block and unblock"; `dd conv=block,unblock` runs.
        at("V34 X35", "dd cbs=4 conv=block,|", continuing("conv", "block", cbs = true)),
        at("V34 X35 L3a", "dd conv=ascii,| cbs=4", continuing("conv", "ascii", cbs = true)),
        at(
            "V34 X35 S21",
            "dd cbs=4 conv=ebcdic conv=|",
            valuesAfter("conv=", symbolsAfter("conv", listOf("ebcdic"), cbs = true)),
        ),
        at("V34 X35", "dd conv=block,|", continuing("conv", "block")),
    )
}
