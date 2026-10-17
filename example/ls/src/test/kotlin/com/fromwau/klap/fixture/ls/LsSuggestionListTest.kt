package com.fromwau.klap.fixture.ls

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.ls.LsLineContext.FILES
import com.fromwau.klap.fixture.ls.LsLineContext.assertOffersAll
import com.fromwau.klap.fixture.ls.LsLineContext.assertOffersNone
import com.fromwau.klap.fixture.ls.LsLineContext.at
import com.fromwau.klap.fixture.ls.LsLineContext.attached
import com.fromwau.klap.fixture.ls.LsLineContext.checkContext
import com.fromwau.klap.fixture.ls.LsLineContext.checkContextValues
import com.fromwau.klap.fixture.ls.LsLineContext.name
import com.fromwau.klap.fixture.ls.LsLineContext.spaced
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * ls declares no subcommand, no hidden input, no negation, no global, no number option and no exclusive set, and its
 * one provider (--time-style's words) cannot fail, so the G rows of provider failures (G23, G25, G26, G43, G74, G75,
 * G81, G86, G91 to G95), of commands and aliases (G9, G19, G20, G33 to G40, G70, G71), of hidden inputs (G8, G55 to
 * G58), of negations (G10, G44, G59 to G63), of several longs on one input (G28), of digit shorts (G31), of a
 * file-valued option (G72, G73's path half) and of exclusive sets (G49, G98, G100) have no ls cell; G96 and G97 are
 * shell presentation, left to the live shell tests, and G48 is help-page layout. ls has no completion protocol
 * (`ls __complete a`: "cannot access '__complete'"), so E19, the wire form a completion script reads, has none either.
 */

/** A Tab press on [typed] (`|` at the cursor), the suggestion [taken] there, and what the line then binds. */
private data class Taken(
    val typed: String,
    val taken: String,
    val expected: LsInputs,
)

/**
 * The suggestion list Tab gives on an ls line and what every completion cell asserts about it (tables G and E of the
 * testing matrix): which names are offered and which not, their order, their descriptions, and the line once a
 * suggestion is taken, written out. Every expectation is what real ls 9.12 accepts at the cursor, in the words of `ls
 * --help`; a list cell compares whole suggestions sorted by value, since the shell sorts, and the order klap hands over
 * is pinned on its own.
 */
class LsSuggestionListTest {

    private val ls = lsCli().cli

    private val parity = ParitySuite(lsCli())

    @Test
    fun `a name ls does not have offers nothing`() = ls.checkContext(
        // G15, E12: `ls --zzz a`: "unrecognized option '--zzz'".
        at("G15 E12", "ls --zzz|"),
        // G54: `ls --entries a`: unrecognized, though -a's help says "do not ignore entries".
        at("G54", "ls --entries|"),
        // G77: `ls --RECURSIVE a`: ls's names are matched in their case.
        at("G77", "ls --REC|"),
        // G65: `ls --help-all`: "unrecognized option '--help-all'".
        at("G11 G65", "ls --help-|"),
        // `ls --colour a`: "unrecognized option '--colour'".
        at("G15", "ls --colou|"),
    )

    @Test
    fun `an optional-value long is offered once and bare`() {
        // G29, G30: `ls --help`: `--color[=WHEN]`, `--format=WORD`; Tab offers the name, the values only after the `=`.
        ls.checkContextValues(at("G30", "ls --col|", name("--color")), at("G29", "ls --form|", name("--format")))
        ls.assertOffersNone("G30", "ls --colo|", listOf("--color=", "--color "))
        ls.assertOffersNone("G29", "ls --form|", listOf("--format=", "--format "))
    }

    @Test
    fun `a single dash offers both spellings and two dashes only the longs`() {
        // G4: `ls --help`: `-a, --all` and the rest; a short is never a `--` word.
        val single = ls.suggestionsFor("ls -").map { it.value }
        val double = ls.suggestionsFor("ls --").map { it.value }
        assertEquals(single.filter { it.startsWith("--") }, double, "G4 `ls --|` against the longs of `ls -|`")
        val bothSpellings = ALL_INPUTS.filter { it.long != null && it.shorts.isNotEmpty() }
        checkCells(bothSpellings) { input ->
            val wanted = listOf(input.long!!) + input.shorts.map { "-$it" }
            assertTrue(single.containsAll(wanted), "G4 `ls -|`: must offer $wanted, offered $single")
        }
    }

    @Test
    fun `names come grouped by kind and each input's spellings together`() {
        val names = ls.suggestionsFor("ls -").map { it.value }
        // G16, G17: klap lists every option before every flag, each input's long before its short; the shell sorts
        // the list again, so ls has no order of its own.
        val options = listOf("--ignore", "-I", "--hide", "--format", "--sort", "--width", "-w", "--tabsize", "-T")
        val flags = listOf("--all", "-a", "--almost-all", "-A", "-l", "--reverse", "-r", "--context", "-Z")
        assertTrue(
            options.maxOf(names::indexOf) < flags.minOf(names::indexOf),
            "G16 `ls -|`: every option name before every flag name, got $names",
        )
        for (pair in listOf(listOf("--all", "-a"), listOf("--width", "-w"), listOf("--human-readable", "-h"))) {
            val at = names.indexOf(pair.first())
            assertEquals(pair, names.subList(at, at + pair.size), "G17 `ls -|`: the long, then its short")
        }
    }

    @Test
    fun `--sort's values come in ls's order`() {
        // G2: `ls --sort=bogus`: "Valid arguments are: 'none', 'size', 'time', 'version', 'extension', 'name',
        // 'width'".
        assertEquals(
            SORT_GROUPS.flatten().map { "--sort=$it" },
            ls.suggestionsFor("ls --sort=").map { it.value },
            "G2 `ls --sort=|`",
        )
    }

    @Test
    fun `no suggestion is offered twice`() {
        val lines = listOf("ls -", "ls --", "ls -l", "ls -la", "ls --s", "ls --format=", "ls --color=", "ls --format ")
        checkCells(lines) { line ->
            val values = ls.suggestionsFor(line).map { it.value }
            assertEquals(values.distinct(), values, "G3 G27 `$line|`")
        }
    }

    @Test
    fun `a short is described alike alone and in a cluster`() {
        // G5, G32: a cluster continuation carries the added letter's help.
        val alone = ls.suggestionsFor("ls -").filter { it.value.length == 2 }.associate { it.value[1] to it }
        val continued = ls.suggestionsFor("ls -r").associate { it.value.last() to it }
        checkCells((alone.keys - 'r').toList()) { letter ->
            assertEquals(
                alone.getValue(letter).description,
                continued[letter]?.description,
                "G32 `ls -|` -$letter against `ls -r|` -r$letter",
            )
        }
    }

    @Test
    fun `an attached value is filtered on the part after the equals sign`() = ls.checkContextValues(
        // G14, G80: `ls --format=c a` lists with commas, `ls --sort=v a` by version.
        at("G14 G80 E9", "ls --format=c|", *attached("--format", "c")),
        at("G80", "ls --sort=v|x", *attached("--sort", "v")),
        at("G80 E21", "ls --format=s| a b", *attached("--format", "s")),
        // G84: `ls --format=long= a`: "invalid argument 'long='".
        at("G84", "ls --format=long=|"),
        at("G84", "ls --sort=size=|"),
    )

    @Test
    fun `a spaced value carries no description`() = ls.checkContext(
        // G7, G41: the spaced form of a value has no description of its own.
        at("G7 G41", "ls --format |", *spaced("--format")),
        at("G7 G41", "ls --sort |", *spaced("--sort")),
        at("G7 G41", "ls --fo c|", *spaced("--format", "c")),
    )

    @Test
    fun `an operand slot is the shell's file completion alone`() = ls.checkContext(
        // G12, E11: every operand is a file, and the directive is the whole answer.
        at("G12 E1", "ls |", FILES),
        at("G12 E1", "ls | a b", FILES),
        at("G12 E1", "ls |--all", FILES),
        at("G12 G83 E1", "ls -- -|", FILES),
        at("G12", "ls -l a|", FILES),
    )

    @Test
    fun `a value in capitals is not offered`() = ls.checkContext(
        // G13, G76, E13, E22: `ls --sort=SIZE` and `ls --format=LONG`: "invalid argument": ls matches in their case.
        at("G13 G76 E13 E22", "ls --sort=S|"),
        at("G13 G76 E13 E22", "ls --format=L|"),
        at("G13 G76 E22", "ls --color=N|"),
    )

    @Test
    fun `a finished word asked again offers itself`() {
        ls.checkContext(
            at("E17", "ls --recursive|", name("--recursive")),
            at("E17", "ls --almost-all|", name("--almost-all")),
        )
        ls.checkContextValues(at("E17", "ls --format=long|", *attached("--format", "long")))
        // E17 cluster half: `ls -l|` offers continuations only, never `-l` itself nor `-ll`.
        ls.assertOffersNone("E17 G99", "ls -l|", listOf("-l", "-ll"))
    }

    @Test
    fun `a line once a suggestion is taken binds as ls reads it`() {
        // E15: each line holds a suggestion Tab offered for the word under the cursor; each ran under real ls in a
        // throwaway directory and listed its operands.
        val cells = listOf(
            Taken("ls --rec| a b", "--recursive", A_AND_B.copy(recursive = true)),
            Taken("ls --alm| a b", "--almost-all", A_AND_B.copy(hidden = Hidden.AlmostAll)),
            Taken("ls -l| a b", "-lr", A_AND_B.copy(layout = Layout.Long, reverse = true)),
            Taken("ls --format=c| a b", "--format=commas", A_AND_B.copy(layout = Layout.Commas)),
            Taken("ls --sort t| a b", "time", A_AND_B.copy(sort = Sort.Time)),
            Taken("ls --color=n| a b", "--color=never", A_AND_B.copy(color = When.Never)),
        )
        checkCells(cells) { cell ->
            val offered = ls.suggestionsFor(cell.typed.replace("|", ""), cell.typed.indexOf('|')).map { it.value }
            assertTrue(cell.taken in offered, "E15 `${cell.typed}`: must offer ${cell.taken}, offered $offered")
            val head = cell.typed.substringBefore('|').split(' ').drop(1).dropLast(1)
            val line = head + cell.taken + cell.typed.substringAfter('|').split(' ').filter { it.isNotEmpty() }
            parity.binds(*line.toTypedArray(), expected = cell.expected, because = "E15 `${cell.typed}`")
        }
    }

    @Test
    fun `a name taken and then the next word is its value or a file`() {
        // E16: `ls --form` offers --format; on `ls --format ` the value's words follow; `ls --rec` offers --recursive,
        // and on `ls --recursive ` the operand is the shell's to complete.
        assertTrue("--format" in ls.suggestionsFor("ls --form").map { it.value }, "E16 `ls --form|` offers --format")
        assertEquals(
            spaced("--format").sortedBy { it.value },
            ls.suggestionsFor("ls --format ").sortedBy { it.value },
            "E16 `ls --format |`",
        )
        assertEquals(listOf(name("--recursive")), ls.suggestionsFor("ls --rec"), "E16 `ls --rec|`")
        assertEquals(listOf(FILES), ls.suggestionsFor("ls --recursive "), "E16 `ls --recursive |`")
    }

    @Test
    fun `a cluster continuation taken continues with the letters left`() {
        // E16: `ls -l` offers -la; `ls -la` continues without -l or -a again.
        assertTrue("-la" in ls.suggestionsFor("ls -l").map { it.value }, "E16 `ls -l|` offers -la")
        val next = ls.suggestionsFor("ls -la").map { it.value }
        assertTrue("-lar" in next && "-lah" in next, "E16 `ls -la|`: must continue, offered $next")
        ls.assertOffersNone("E16 E22", "ls -la|", listOf("-lal", "-laa", "-la"))
    }

    @Test
    fun `a name's description is the text its help row shows`() {
        // E20: every description Tab gives is the text of the fixture's own help row.
        val page = parity.helpPage("--help", because = "ls --help")
        val spaces = Regex(" +")
        val flat = page.lines().joinToString(" ") { it.trim() }.replace(spaces, " ")
        checkCells(ls.suggestionsFor("ls -")) { offered ->
            val text = offered.description.orEmpty().replace(spaces, " ")
            assertTrue(text.isNotEmpty() && text in flat, "E20 `ls -|` ${offered.value}: \"$text\" is not on --help")
        }
    }

    @Test
    fun `a description carries none of the help page's annotations`() {
        val annotations = listOf(
            "(default:", "(bare:", "(optional)", "(last of", "(one of:", "(required)", "(repeatable)", "(absent when",
            "(optional when",
        )
        checkCells(ls.suggestionsFor("ls -") + ls.suggestionsFor("ls --color=") + ls.suggestionsFor("ls --sort=")) {
            val text = it.description.orEmpty()
            assertTrue(annotations.none { note -> note in text }, "G47 ${it.value}: \"$text\" carries an annotation")
        }
    }

    @Test
    fun `asking twice gives the same answer`() {
        val lines = listOf("ls -", "ls --format=", "ls -l", "ls ", "ls --s")
        checkCells(lines) { line -> assertEquals(ls.suggestionsFor(line), ls.suggestionsFor(line), "E24 `$line|`") }
    }

    @Test
    fun `-h is ls's own and is offered once`() {
        // G24, G64, K61: `ls -h` is --human-readable; ls has no help short.
        val offered = ls.suggestionsFor("ls -").filter { it.value == "-h" }
        assertEquals(1, offered.size, "G24 `ls -|`: -h once, offered $offered")
        assertEquals(ls.suggestionsFor("ls --human-r").single().description, offered.single().description, "G24 -h")
    }

    @Test
    fun `--version is offered alone for its prefix`() {
        // G66: `ls --vers` prints the banner.
        assertEquals(listOf("--version"), ls.suggestionsFor("ls --vers").map { it.value }, "G66 `ls --vers|`")
    }

    @Test
    fun `an input already given is offered again as ls takes it again`() {
        // G99: `ls -r -r a` and `ls --reverse --reverse a` run.
        ls.assertOffersAll("G99", "ls -r -|", listOf("-r", "--reverse"))
        ls.assertOffersAll("G99", "ls -I a -|", listOf("-I", "--ignore"))
    }

    @Test
    fun `nothing follows a value-taking short at the end of a cluster`() = ls.checkContext(
        // G89: `ls -lw` takes the next word as its width: the word ends with -w.
        at("G89", "ls -lw|"),
        at("G89", "ls -rI|"),
        // G90: `ls --s a`: "option '--s' is ambiguous".
        at("G90", "ls --s |"),
    )

    @Test
    fun `a dash offers ls's names in ls's words and nothing else`() {
        // G1a, G1b, G4, G6a, G45, E5, E6: `ls --help` lists every name with its wording; `ls --json a`:
        // "unrecognized option".
        ls.checkContext(
            at("G1a G1b G4 G6a G45 E5 E6", "ls -|", *POOL.toTypedArray()),
            at("G1a G1b G4 G6a G45 E5 E6", "ls --|", *POOL.filter { it.value.startsWith("--") }.toTypedArray()),
            at("G1a G4 E21", "ls -| a b", *POOL.toTypedArray()),
        )
    }

    @Test
    fun `every long ls has is offered after two dashes`() {
        // G101: `ls --=x` lists the 44 longs of LS_LONGS as its possibilities.
        ls.assertOffersAll("G101", "ls --|", LS_LONGS)
        ls.assertOffersAll("G101", "ls -l|", ALL_INPUTS.flatMap { it.shorts }.filter { it != 'l' }.map { "-l$it" })
    }

    @Test
    fun `--format's values come in ls's order`() {
        // G2, G22: `ls --format=bogus`: "Valid arguments are: 'verbose', 'long' / 'commas' / 'horizontal', 'across' /
        // 'vertical' / 'single-column'".
        assertEquals(
            FORMAT_GROUPS.flatten().map { "--format=$it" },
            ls.suggestionsFor("ls --format=").map { it.value },
            "G2 `ls --format=|`",
        )
    }

    @Test
    fun `--color's values come in ls's order`() {
        // G2, G22: `ls --color=bogus`: "Valid arguments are: 'always', 'yes', 'force' / 'never', 'no', 'none' / 'auto',
        // 'tty', 'if-tty'".
        assertEquals(
            WHEN_GROUPS.flatten().map { "--color=$it" },
            ls.suggestionsFor("ls --color=").map { it.value },
            "G2 `ls --color=|`",
        )
    }

    @Test
    fun `the built-ins are described in ls's words`() = ls.checkContext(
        // G6b, G45: `ls --help`: "--help display this help and exit", "--version output version information and exit".
        at("G6b G45", "ls --he|", name("--help")),
        at("G6b G45 G66", "ls --vers|", name("--version")),
    )

    @Test
    fun `a built-in ls does not have is not offered`() {
        // G11, G67: `ls --json a`, `ls --completion bash`, `ls --docs a`: "unrecognized option" each.
        ls.checkContext(at("G11 G67", "ls --js|"), at("G11 G67", "ls --comp|"), at("G11 G67", "ls --do|"))
        ls.assertOffersNone("G11 G67 E22", "ls -|", KLAP_ONLY_LONGS)
    }

    @Test
    fun `the built-ins come last and are only ls's`() {
        // G18: `ls --help` lists --help and --version after every other name.
        val names = ls.suggestionsFor("ls -").map { it.value }
        assertEquals(listOf("--help", "--version"), names.takeLast(2), "G18 `ls -|`: the built-ins come last")
    }

    @Test
    fun `a completion script's call reaches ls as its own words`() {
        // ls has no completion protocol: `ls __complete --cursor 1 -- -` is "unrecognized option '--cursor'".
        parity.rejects("__complete", "--cursor", "1", "--", "-", because = "real ls: unrecognized option '--cursor'")
    }

    @Test
    fun `an attached value is described by its option's help`() = ls.checkContext(
        // G5, G6a: `ls --help`: --format "across,horizontal (-x), ...", --color "color the output WHEN; more info
        // below".
        at("G5 G6a", "ls --format=c|", *attached("--format", "c")),
        at("G5 G6a", "ls --color=al|", *attached("--color", "al")),
    )
}
