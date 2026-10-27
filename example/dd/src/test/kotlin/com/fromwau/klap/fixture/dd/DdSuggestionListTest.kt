package com.fromwau.klap.fixture.dd

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.dd.DdLineContext.NOTHING
import com.fromwau.klap.fixture.dd.DdLineContext.assertOffersNone
import com.fromwau.klap.fixture.dd.DdLineContext.assertOffersValues
import com.fromwau.klap.fixture.dd.DdLineContext.at
import com.fromwau.klap.fixture.dd.DdLineContext.checkContext
import com.fromwau.klap.fixture.dd.DdLineContext.checkContextValues
import com.fromwau.klap.fixture.dd.DdLineContext.filesOf
import com.fromwau.klap.fixture.dd.DdLineContext.offeredOn
import com.fromwau.klap.fixture.dd.DdLineContext.valuesAfter
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * dd declares no subcommand, alias, hidden input, negation, global, choice, enum or exclusive set, so the G rows of
 * those (G8 to G10, G19 to G22, G24 to G34, G36 to G42, G44, G46, G48, G49, G55 to G63, G70, G71, G73, G76, G78, G79,
 * G85, G88 to G90, G98 to G100) have no dd cell; its provider cannot fail and offers no value with a blank, a tab or a
 * special character, so G86, G91 to G95 and G52 have none either; G96 and G97 are shell presentation, left to the live
 * shell tests. dd has no completion protocol (`dd __complete if=`: "unrecognized operand '__complete'"), so E19, the
 * wire form a completion script reads, has none.
 */

/** A Tab press on [typed] (`|` at the cursor), the suggestion [taken] there, and the line written out once taken. */
private data class Taken(
    val typed: String,
    val taken: String,
    val line: List<String>,
)

/**
 * The suggestion list Tab gives on a dd line and what every completion cell asserts about it (tables G and E of the
 * testing matrix): which words are offered and which not, their order, their descriptions, and the line once a
 * suggestion is taken, written out. Every expectation is what real dd 9.12 accepts at the cursor, in the words of `dd
 * --help`; a list cell compares whole suggestions sorted by value, since the shell sorts, and the order klap hands
 * over is pinned on its own.
 */
class DdSuggestionListTest {

    private val dd = ddCli().cli

    private val parity = ParitySuite(ddCli())

    @Test
    fun `a word dd has no key or option for offers nothing`() = dd.checkContext(
        // G15, E12: `dd zzz=1`: "unrecognized operand"; `dd --zzz`: "unrecognized option".
        at("G15 E12", "dd zzz|", NOTHING),
        at("G15 E12", "dd --zzz|", NOTHING),
        // G54: `dd copy=1` is unrecognized, though count='s row says "copy only N input blocks".
        at("G54", "dd copy|", NOTHING),
        at("G54", "dd standard|", NOTHING),
        // G77: `dd IF=in` and `dd --HELP` are refused: dd's keys and names are matched in their case.
        at("G77", "dd IF|", NOTHING),
        at("G77", "dd --HE|", NOTHING),
        at("G11 G65", "dd --help-|", NOTHING),
    )

    @Test
    fun `the file directive is the whole answer after a file key`() = dd.checkContext(
        // G12, G72, G74, E11: the head is the key and its `=`; nothing beside the directive.
        at("G72 G74", "dd if=|", filesOf("if")),
        at("G72 G74", "dd of=|", filesOf("of")),
        at("G72 G75", "dd if=no/such/pre|", filesOf("if")),
        at("G72 G84", "dd of=a=b|", filesOf("of")),
    )

    @Test
    fun `a key is described by its own row`() = dd.checkContext(
        // G5, G6a, G43: `dd --help`: "conv=CONVS convert the file as per the comma separated symbol list", "if=FILE
        // read from FILE instead of standard input", and the rest.
        at("G43 G6a", "dd conv|", keysStarting("conv")),
        at("G43 G6a", "dd if|", keysStarting("if")),
        at("G43 G6a", "dd of|", keysStarting("of")),
        at("G43 G6a", "dd ib|", keysStarting("ib")),
        at("G43 G6a", "dd ob|", keysStarting("ob")),
        at("G43 G6a", "dd cb|", keysStarting("cb")),
        at("G43 G6a", "dd cou|", keysStarting("cou")),
    )

    @Test
    fun `no suggestion is offered twice`() {
        val lines = listOf("dd ", "dd i", "dd conv=", "dd iflag=", "dd -", "dd --", "dd if=in ", "dd status=")
        checkCells(lines) { line ->
            val values = dd.suggestionsFor(line).map { it.value }
            assertEquals(values.distinct(), values, "G3 `$line|`")
        }
    }

    @Test
    fun `keys and symbols come in dd's help order`() {
        // G2, G23: klap keeps the provider's order; `dd --help` lists the keys and each symbol table in this order.
        val keys = dd.suggestionsFor("dd ").map { it.value }.filter { it in DOCUMENTED_KEYS.map { key -> key.value } }
        assertEquals(DOCUMENTED_KEYS.map { it.value }, keys, "G23 `dd |`: the keys with a row, in dd --help order")
        val conv = dd.suggestionsFor("dd conv=").map { it.value }
        assertEquals(CONV_SYMBOLS.map { "conv=${it.name}" }, conv, "G23 `dd conv=|`")
        val iflag = dd.suggestionsFor("dd iflag=").map { it.value }
        assertEquals(IFLAG_SYMBOLS.map { "iflag=${it.name}" }, iflag, "G23 `dd iflag=|`")
        val status = dd.suggestionsFor("dd status=").map { it.value }
        assertEquals(STATUS_LEVELS.map { "status=${it.name}" }, status, "G23 `dd status=|`")
    }

    @Test
    fun `--help comes before --version`() {
        // G18: `dd --help` lists --help, then --version.
        val names = dd.suggestionsFor("dd --").map { it.value }
        assertTrue(names.indexOf("--help") in 0 until names.indexOf("--version"), "G18 `dd --|`: $names")
    }

    @Test
    fun `a description carries none of the help page's annotations`() {
        // G47: `(default: 512)` is dd's own wording, in bs='s and ibs='s rows, so it is no annotation of klap's.
        val annotations = listOf(
            "(bare:", "(optional)", "(last of", "(one of:", "(required)", "(repeatable)", "(absent when",
            "(optional when",
        )
        checkCells(dd.suggestionsFor("dd ") + dd.suggestionsFor("dd -")) {
            val text = it.description.orEmpty()
            assertTrue(annotations.none { note -> note in text }, "G47 ${it.value}: \"$text\" carries an annotation")
        }
    }

    @Test
    fun `klap's help short and --help-all are not offered`() {
        // G64, G65: `dd -h`: "invalid option -- 'h'"; `dd --help-all`: "unrecognized option".
        dd.assertOffersNone("G64", "dd -|", listOf("-h"))
        dd.assertOffersNone("G65", "dd --|", listOf("--help-all"))
    }

    @Test
    fun `--help and --version are offered for their prefixes`() = dd.checkContextValues(
        // G66: `dd --vers` prints the version, `dd --he` the usage.
        at("G66", "dd --vers|", DD_OPTIONS.takeLast(1)),
        at("G6b", "dd --he|", DD_OPTIONS.take(1)),
    )

    @Test
    fun `a finished word asked again offers itself`() = dd.checkContextValues(
        // E17: an attached value offers itself, a key word with no `=` the keys it starts.
        at("E17", "dd conv=ucase|", listOf(Suggestion("conv=ucase"))),
        at("E17", "dd status=none|", listOf(Suggestion("status=none"))),
        at("E17", "dd --version|", DD_OPTIONS.takeLast(1)),
        at("E17", "dd count|", keysStarting("count")),
    )

    @Test
    fun `a line once a suggestion is taken binds as dd reads it`() {
        // E15: each line holds a suggestion Tab offered for the word under the cursor, with the value then typed; each
        // ran under real dd in a throwaway directory.
        val cells = listOf(
            Taken("dd conv=uc|", "conv=ucase", listOf("conv=ucase")),
            Taken("dd status=pro| if=in", "status=progress", listOf("status=progress", "if=in")),
            Taken("dd if=in cou|", "count=", listOf("if=in", "count=1")),
            Taken("dd iflag=full|", "iflag=fullblock", listOf("iflag=fullblock")),
            Taken("dd of|", "of=", listOf("of=out")),
        )
        checkCells(cells) { cell ->
            val offered = dd.offeredOn(cell.typed).map { it.value }
            assertTrue(cell.taken in offered, "E15 `${cell.typed}`: must offer ${cell.taken}, offered $offered")
            val argv = cell.line.toTypedArray()
            parity.binds(*argv, expected = bound(*argv), because = "E15 `${cell.typed}`")
        }
    }

    @Test
    fun `a key taken and then its value completes from the key`() {
        // E16: `dd i` offers if=, then `dd if=` hands the path to the shell; `dd con` offers conv=, then `dd conv=` its
        // symbols; `dd conv=uc` offers conv=ucase, then the next word is an operand again.
        dd.assertOffersValues("E16", "dd i|", listOf("if="))
        dd.checkContext(at("E16", "dd if=|", filesOf("if")))
        dd.assertOffersValues("E16", "dd con|", listOf("conv="))
        dd.checkContextValues(at("E16", "dd conv=|", valuesAfter("conv=", CONV_SYMBOLS)))
        dd.assertOffersValues("E16", "dd conv=ucase |", DOCUMENTED_KEYS.map { it.value })
    }

    @Test
    fun `asking twice gives the same answer`() {
        val lines = listOf("dd ", "dd i", "dd conv=", "dd if=", "dd -", "dd --he", "dd status=n")
        checkCells(lines) { line -> assertEquals(dd.suggestionsFor(line), dd.suggestionsFor(line), "E24 `$line|`") }
    }

    // G11, G67, E22: `dd --json if=in`, `dd --color if=in`, `dd --completion bash` and `dd --docs markdown`:
    // "unrecognized option" each.
    @Test
    fun `a built-in dd does not have is not offered`() {
        dd.assertOffersNone("G11 G67 E22", "dd -|", KLAP_ONLY_LONGS)
        dd.assertOffersNone("G11 G67 E22", "dd --|", KLAP_ONLY_LONGS)
    }

    // G1a, G1b, G4, G6a, G6b, G45, G101, E5, E6: `dd --help` lists every key with its row and `--help`, `--version`
    // with theirs; `iseek=` and `oseek=` are named in skip='s and seek='s rows.
    @Test
    fun `Tab offers dd's words in dd's words and nothing else`() = dd.checkContext(
        at("G1a G1b G6a G101 E5 E6", "dd |", KEYS),
        at("G1a G4 G6b G45 E5 E6", "dd -|", DD_OPTIONS),
        at("G1a G4 G6b G45 E5 E6", "dd --|", DD_OPTIONS),
    )

    // E20: the fixture's help page shows each operand's row, and Tab describes the key with the same words: `dd
    // --help`: "status=LEVEL The LEVEL of information to print to standard error; 'none' suppresses ...", "bs=BYTES
    // ... (default: 512); overrides ibs and obs", "seek=N (or oseek=N) skip N obs sized output blocks".
    @Test
    fun `a key's description is the text its help row shows`() {
        val page = parity.helpPage("--help", because = "dd --help")
        val spaces = Regex("\\s+")
        val flat = page.replace(spaces, " ")
        checkCells(KEYS) { key ->
            assertTrue(key.description!!.replace(spaces, " ") in flat, "E20 ${key.value}: \"${key.description}\"")
            val offered = dd.offeredOn("dd ${key.value.dropLast(1)}|").firstOrNull { it.value == key.value }
            assertEquals(key.description, offered?.description, "E20 `dd ${key.value.dropLast(1)}|`")
        }
    }
}
