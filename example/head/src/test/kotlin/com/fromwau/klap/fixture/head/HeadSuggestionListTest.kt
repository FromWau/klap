package com.fromwau.klap.fixture.head

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.head.HeadLineContext.FILES
import com.fromwau.klap.fixture.head.HeadLineContext.assertOffersAll
import com.fromwau.klap.fixture.head.HeadLineContext.assertOffersNone
import com.fromwau.klap.fixture.head.HeadLineContext.at
import com.fromwau.klap.fixture.head.HeadLineContext.checkContext
import com.fromwau.klap.fixture.head.HeadLineContext.checkContextValues
import com.fromwau.klap.fixture.head.HeadLineContext.name
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * head declares no subcommand, no hidden input, no negation, no global, no choice, no provider and no exclusive set,
 * so the G rows of values and providers (G2's value half, G7, G13, G22, G23, G25, G26, G41 to G43, G46, G74, G75,
 * G76, G78 to G86, G91 to G95), of commands and aliases (G9, G19, G20, G33, G34, G36 to G40, G70, G71), of hidden
 * inputs (G8, G55 to G58), of negations (G10, G44, G59 to G63), of a file-valued option (G72, G73's path half) and of
 * exclusive sets (G49, G98, G100) have no head cell; G96 and G97 are shell presentation, left to the live shell
 * tests, and G48 is help-page layout. head has no completion protocol (`head __complete f`: "cannot open
 * '__complete'"), so E19, the wire form a completion script reads, has none either. G31 is pinned in
 * HeadCompletionTest beside the obsolete count's cells.
 */

/** A Tab press on [typed] (`|` at the cursor), the suggestion [taken] there, and what the line then binds. */
private data class Taken(
    val typed: String,
    val taken: String,
    val expected: HeadInputs,
)

/**
 * The suggestion list Tab gives on a head line and what every completion cell asserts about it (tables G and E of
 * the testing matrix): which names are offered and which not, their order, their descriptions, and the line once a
 * suggestion is taken, written out. Every expectation is what real head 9.12 accepts at the cursor, in the words of
 * `head --help`; a list cell compares whole suggestions sorted by value, since the shell sorts, and the order klap
 * hands over is pinned on its own.
 */
class HeadSuggestionListTest {

    private val head = headCli().cli

    private val parity = ParitySuite(headCli())

    @Test
    fun `a name head does not have offers nothing`() = head.checkContext(
        // G15, E12: `head --zzz f`: "unrecognized option '--zzz'".
        at("G15 E12", "head --zzz|"),
        // G54: `head --print f`: unrecognized, though -n's help says "print the first NUM lines".
        at("G54", "head --print|"),
        // G77: `head --QUIET f`: head's names are matched in their case.
        at("G77", "head --QU|"),
        // G65: `head --help-all`: "unrecognized option '--help-all'".
        at("G11 G65", "head --help-|"),
    )

    @Test
    fun `a value-taking long is offered once and bare`() {
        // G29: `head --help`: `--lines=[-]NUM`; Tab offers the name, and the NUM is the user's to type.
        head.checkContextValues(at("G29", "head --li|", name("--lines")), at("G29", "head --by|", name("--bytes")))
        head.assertOffersNone("G29", "head --li|", listOf("--lines=", "--lines "))
    }

    @Test
    fun `a single dash offers both spellings and two dashes only the longs`() {
        // G4: `head --help`: `-c, --bytes`, `-q, --quiet, --silent` and the rest; a short is never a `--` word.
        val single = head.suggestionsFor("head -").map { it.value }
        val double = head.suggestionsFor("head --").map { it.value }
        assertEquals(single.filter { it.startsWith("--") }, double, "G4 `head --|` against the longs of `head -|`")
        checkCells(INPUTS) { input ->
            val wanted = input.longs + "-${input.short}"
            assertTrue(single.containsAll(wanted), "G4 `head -|`: must offer $wanted, offered $single")
        }
    }

    @Test
    fun `names come grouped by kind and each input's spellings together`() {
        val names = head.suggestionsFor("head -").map { it.value }
        // G16, G17, G28: klap lists every option before every flag, each input's longs before its short; head has no
        // order of its own, and the shell sorts the list again.
        val options = listOf("--lines", "-n", "--bytes", "-c")
        val flags = listOf("--quiet", "--silent", "-q", "--verbose", "-v", "--zero-terminated", "-z")
        assertTrue(
            options.maxOf(names::indexOf) < flags.minOf(names::indexOf),
            "G16 `head -|`: every option name before every flag name, got $names",
        )
        for (spellings in listOf(listOf("--quiet", "--silent", "-q"), listOf("--lines", "-n"))) {
            val at = names.indexOf(spellings.first())
            val got = names.subList(at, at + spellings.size)
            assertEquals(spellings, got, "G17 G28 `head -|`: the longs, then the short")
        }
    }

    @Test
    fun `no suggestion is offered twice`() {
        val lines = listOf("head -", "head --", "head -q", "head -qv", "head --v", "head --lines=", "head -n ", "head ")
        checkCells(lines) { line ->
            val values = head.suggestionsFor(line).map { it.value }
            assertEquals(values.distinct(), values, "G3 G27 `$line|`")
        }
    }

    @Test
    fun `a short is described alike alone and in a cluster`() {
        // G5, G32: a cluster continuation carries the added letter's help.
        val alone = head.suggestionsFor("head -").filter { it.value.length == 2 }.associate { it.value[1] to it }
        val continued = head.suggestionsFor("head -z").associate { it.value.last() to it }
        checkCells((alone.keys - 'z').toList()) { letter ->
            assertEquals(
                alone.getValue(letter).description,
                continued[letter]?.description,
                "G32 `head -|` -$letter against `head -z|` -z$letter",
            )
        }
    }

    @Test
    fun `an operand slot is the shell's file completion alone`() = head.checkContext(
        // G12, E11: every operand is a file, and the directive is the whole answer.
        at("G12 E1", "head |", FILES),
        at("G12 E1", "head | a b", FILES),
        at("G12 E1", "head |--quiet", FILES),
        at("G12 G83 E1", "head -- -|", FILES),
        at("G12", "head -q a|", FILES),
    )

    @Test
    fun `a finished word asked again offers itself`() {
        head.checkContext(
            at("E17", "head --quiet|", name("--quiet")),
            at("E17", "head --zero-terminated|", name("--zero-terminated")),
        )
        // E17 cluster half: `head -q|` offers continuations only, never `-q` itself nor `-qq`.
        head.assertOffersNone("E17 G99", "head -q|", listOf("-q", "-qq"))
    }

    @Test
    fun `a line once a suggestion is taken binds as head reads it`() {
        // E15: each line holds a suggestion Tab offered for the word under the cursor; each ran under real head in a
        // throwaway directory and printed as the record says.
        val cells = listOf(
            Taken("head --qu| a b", "--quiet", A_AND_B.copy(headers = Headers.Never)),
            Taken("head --verb| f", "--verbose", F.copy(headers = Headers.Always)),
            Taken("head --li| 5 f", "--lines", F.lines("5")),
            Taken("head --by| 5 f", "--bytes", F.bytes("5")),
            Taken("head -q| a b", "-qz", A_AND_B.copy(headers = Headers.Never, zeroTerminated = true)),
            Taken("head -z| 5 f", "-zn", F.lines("5").copy(zeroTerminated = true)),
        )
        checkCells(cells) { cell ->
            val offered = head.suggestionsFor(cell.typed.replace("|", ""), cell.typed.indexOf('|')).map { it.value }
            assertTrue(cell.taken in offered, "E15 `${cell.typed}`: must offer ${cell.taken}, offered $offered")
            val before = cell.typed.substringBefore('|').split(' ').drop(1).dropLast(1)
            val line = before + cell.taken + cell.typed.substringAfter('|').split(' ').filter { it.isNotEmpty() }
            parity.binds(*line.toTypedArray(), expected = cell.expected, because = "E15 `${cell.typed}`")
        }
    }

    @Test
    fun `a name taken and then the next word is its NUM or a file`() {
        // E16: `head --li` offers --lines; on `head --lines ` the NUM is the user's; `head --qu` offers --quiet, and on
        // `head --quiet ` the operand is the shell's to complete.
        assertTrue("--lines" in head.suggestionsFor("head --li").map { it.value }, "E16 `head --li|` offers --lines")
        assertEquals(emptyList(), head.suggestionsFor("head --lines "), "E16 `head --lines |`")
        assertEquals(listOf(name("--quiet")), head.suggestionsFor("head --qu"), "E16 `head --qu|`")
        assertEquals(listOf(FILES), head.suggestionsFor("head --quiet "), "E16 `head --quiet |`")
    }

    @Test
    fun `a cluster continuation taken continues with the letters left`() {
        // E16: `head -q` offers -qz; `head -qz` continues without -q or -z again.
        assertTrue("-qz" in head.suggestionsFor("head -q").map { it.value }, "E16 `head -q|` offers -qz")
        val next = head.suggestionsFor("head -qz").map { it.value }
        assertTrue("-qzv" in next && "-qzn" in next, "E16 `head -qz|`: must continue, offered $next")
        head.assertOffersNone("E16 E22", "head -qz|", listOf("-qzq", "-qzz", "-qz"))
    }

    @Test
    fun `a name's description is the text its help row shows`() {
        // E20: every description Tab gives is the text of the fixture's own help row.
        val page = parity.helpPage("--help", because = "head --help")
        val spaces = Regex(" +")
        val flat = page.lines().joinToString(" ") { it.trim() }.replace(spaces, " ")
        checkCells(head.suggestionsFor("head -")) { offered ->
            val text = offered.description.orEmpty().replace(spaces, " ")
            assertTrue(text.isNotEmpty() && text in flat, "E20 `head -|` ${offered.value}: \"$text\" is not on --help")
        }
    }

    @Test
    fun `a description carries none of the help page's annotations`() {
        val annotations = listOf(
            "(default:", "(bare:", "(optional)", "(last of", "(one of:", "(required)", "(repeatable)", "(absent when",
            "(optional when",
        )
        checkCells(head.suggestionsFor("head -") + head.suggestionsFor("head -q")) {
            val text = it.description.orEmpty()
            assertTrue(annotations.none { note -> note in text }, "G47 ${it.value}: \"$text\" carries an annotation")
        }
    }

    @Test
    fun `asking twice gives the same answer`() {
        val lines = listOf("head -", "head --lines=", "head -q", "head ", "head --v", "head -5")
        checkCells(lines) { line ->
            assertEquals(head.suggestionsFor(line), head.suggestionsFor(line), "E24 `$line|`")
        }
    }

    @Test
    fun `klap's help short is not offered`() {
        // G64: `head -h f`: "invalid option -- 'h'"; head's --help has no short.
        head.assertOffersNone("G64", "head -|", listOf("-h"))
        head.assertOffersNone("G64", "head -q|", listOf("-qh"))
    }

    @Test
    fun `--version is offered alone for its prefix`() {
        // G66: `head --vers` prints the banner.
        assertEquals(listOf("--version"), head.suggestionsFor("head --vers").map { it.value }, "G66 `head --vers|`")
    }

    @Test
    fun `an input already given is offered again as head takes it again`() {
        // G99: `head -q -q a b` and `head -n 1 -n 2 f` run.
        head.assertOffersAll("G99", "head -q -|", listOf("-q", "--quiet"))
        head.assertOffersAll("G99", "head -n 1 -|", listOf("-n", "--lines"))
    }

    @Test
    fun `nothing follows a value-taking short at the end of a cluster`() = head.checkContext(
        // G89: `head -qn 5 a b` takes the next word as the NUM: the word ends with -n.
        at("G89", "head -qn|"),
        at("G89", "head -vc|"),
        // G90: `head --v f`: "option '--v' is ambiguous".
        at("G90", "head --v |"),
    )

    @Test
    fun `every long head documents is offered after two dashes`() {
        // G101: `head --help` lists these longs; `---presume-input-pipe` is left out of it, and of Tab.
        head.assertOffersAll("G101", "head --|", DOCUMENTED_LONGS)
        head.assertOffersAll("G101", "head -q|", listOf("-qc", "-qn", "-qv", "-qz"))
        head.assertOffersNone("G35", "head --|", listOf("---presume-input-pipe"))
    }

    // G1a, G1b, G4, G6a, G45, E5, E6: `head --help` lists every name with its wording; `head --json f`:
    // "unrecognized option '--json'".
    @Test
    fun `a dash offers head's names in head's words and nothing else`() = head.checkContext(
        at("G1a G1b G4 G6a G45 E5 E6", "head -|", *POOL.toTypedArray()),
        at("G1a G1b G4 G6a G45 E5 E6", "head --|", *POOL.filter { it.value.startsWith("--") }.toTypedArray()),
        at("G1a G4 E21", "head -| a b", *POOL.toTypedArray()),
    )

    // G6a: `head --help`: "-n, --lines=[-]NUM print the first NUM lines instead of the first 10; with the leading
    // '-', print all but the last NUM lines of each file", and the same for -c with bytes.
    @Test
    fun `-n and -c are described in head's words`() = head.checkContext(
        at("G6a", "head --li|", name("--lines")),
        at("G6a", "head --by|", name("--bytes")),
        at("G5 G6a", "head -n|", name("-n")),
    )

    // G6b, G45: `head --help`: "--help display this help and exit", "--version output version information and exit".
    @Test
    fun `the built-ins are described in head's words`() = head.checkContext(
        at("G6b G45", "head --he|", name("--help")),
        at("G6b G45 G66", "head --vers|", name("--version")),
    )

    // G11, G67, E22: `head --json f`, `head --color f`, `head --completion bash`, `head --docs man`: "unrecognized
    // option" each.
    @Test
    fun `a built-in head does not have is not offered`() {
        head.checkContext(at("G11 G67", "head --js|"), at("G11 G67", "head --comp|"), at("G11 G67", "head --do|"))
        head.assertOffersNone("G11 G67 E22", "head -|", KLAP_ONLY_LONGS)
    }

    // G18: `head --help` lists --help and --version after every other name.
    @Test
    fun `the built-ins come last and are only head's`() {
        val names = head.suggestionsFor("head -").map { it.value }
        assertEquals(listOf("--help", "--version"), names.takeLast(2), "G18 `head -|`: the built-ins come last")
    }

    // `head __complete --cursor 1 -- -`: "unrecognized option '--cursor'"; head has no completion protocol.
    @Test
    fun `a completion script's call reaches head as its own words`() {
        parity.rejects("__complete", "--cursor", "1", "--", "-", because = "real head: unrecognized option '--cursor'")
    }
}
