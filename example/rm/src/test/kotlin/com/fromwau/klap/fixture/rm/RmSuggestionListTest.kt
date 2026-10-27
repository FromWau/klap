package com.fromwau.klap.fixture.rm

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.rm.RmLineContext.Cell
import com.fromwau.klap.fixture.rm.RmLineContext.FILES
import com.fromwau.klap.fixture.rm.RmLineContext.assertCell
import com.fromwau.klap.fixture.rm.RmLineContext.assertOffersNone
import com.fromwau.klap.fixture.rm.RmLineContext.assertValues
import com.fromwau.klap.fixture.rm.RmLineContext.at
import com.fromwau.klap.fixture.rm.RmLineContext.attached
import com.fromwau.klap.fixture.rm.RmLineContext.checkContext
import com.fromwau.klap.fixture.rm.RmLineContext.name
import com.fromwau.klap.fixture.rm.RmLineContext.whenValues
import com.fromwau.klap.io.CliPath
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * rm declares no provider, no subcommand, no hidden input, no group and no value-taking short, so the G rows of
 * providers and their failures (G25, G26, G43, G74, G75, G81, G86, G91 to G95), of commands and aliases (G9, G19, G20,
 * G33 to G40, G70, G71), of hidden inputs (G8, G55 to G58), of groups (G48), of glued and clustered short values (G42,
 * G73, G85, G89), of several longs on one input (G28, G60), of digit shorts (G31), of enums (G22, G79), of negative
 * shorts (G61) and of exclusive sets (G49, G63, G98, G100) have no rm cell; G96 and G97 are shell presentation, left to
 * the live shell tests.
 */

private val KLAP_BUILT_INS = listOf("-h", "--help-all", "--json", "--color", "--completion", "--docs")

private fun spellings(input: Input): List<String> = listOfNotNull(input.long) + input.shorts.map { "-$it" }

/** Every name rm accepts after a dash, in rm's words: each input's long and shorts, then `--help` and `--version`. */
private fun pool(longsOnly: Boolean): Array<Suggestion> = (INPUTS.flatMap(::spellings) + "--help" + "--version")
    .filter { !longsOnly || it.startsWith("--") }
    .map(::name)
    .toTypedArray()

/** What rm continues [cluster] with: each of its letters not yet in it, in rm's words. */
private fun continuations(cluster: String): Array<Suggestion> = INPUTS
    .flatMap { it.shorts }
    .filter { it !in cluster.drop(1) }
    .map { Suggestion(cluster + it, inputFor(it)!!.help) }
    .toTypedArray()

/** The cell for [typed], with [rest] of its word after the cursor and [after] past it: every long of rm it starts. */
private fun nameCell(
    typed: String,
    rest: String = "",
    after: String = "",
): Cell {
    val offers = RM_LONGS.filter { it.startsWith(typed) }.map(::name)
    val rows = "G1a G6a G14 E1 E5 E6" + if (after.isEmpty()) "" else " E21"
    return Cell(rows, "rm $typed|$rest$after", offers)
}

/** For every long of rm: the finished word (E17), the word with its last letter after the cursor, and words after. */
private val NAME_CELLS: List<Cell> by lazy {
    RM_LONGS.flatMap { long ->
        listOf(
            nameCell(long),
            nameCell(long.dropLast(1), rest = long.takeLast(1)),
            nameCell(long, after = " a b"),
        )
    }
}

private val WORDED_HELP: Set<String> by lazy { WORDED_INPUTS.map { input(it).help }.toSet() + HELP_HELP + VERSION_HELP }

private val Cell.worded: Boolean get() = offers.any { it.description in WORDED_HELP }

private val Cell.foreignPrefix: Boolean
    get() {
        val typed = line.substring(line.lastIndexOf(' ', cursor - 1) + 1, cursor)
        return (KLAP_ONLY_LONGS + INVENTED_LONG).any { it.startsWith(typed) }
    }

/** The cluster cells of G5 and G21: every letter rm continues a cluster with, in rm's words. */
private val CLUSTER_CELLS: List<Cell> = listOf(
    at("G5 G21 G32 G6a", "rm -v|", *continuations("-v")),
    at("G5 G21 G32 G6a", "rm -f|", *continuations("-f")),
    at("G5 G21 G32 G6a", "rm -rf|", *continuations("-rf")),
    at("G5 G6a E21", "rm -d| a b", *continuations("-d")),
)

/**
 * The suggestion list Tab gives on an rm line and what every completion cell asserts about it (tables G and E of the
 * testing matrix): which names are offered and which not, their order, their descriptions, and the line once a
 * suggestion is taken, written out. Every expectation is what real rm 9.12 accepts at the cursor, in the words of `rm
 * --help`; a list cell compares whole suggestions sorted by value, since the shell sorts, and the order klap hands over
 * is pinned on its own. A cell whose list holds one of the longer descriptions ([WORDED_INPUTS], --help and --version)
 * is checked whole by a test of its own.
 */
class RmSuggestionListTest {

    private val rm = rmCli().cli

    private val parity = ParitySuite(rmCli())

    @Test
    fun `a long name completes to every long of rm it starts in rm's words`() =
        checkCells(NAME_CELLS.filterNot { it.worded || it.foreignPrefix }) { rm.assertCell(it) }

    @Test
    fun `a long name completes to every long of rm it starts`() =
        checkCells(NAME_CELLS.filter { it.worded && !it.foreignPrefix }) {
            rm.assertValues("$it", it.line, it.cursor, it.offers)
        }

    @Test
    fun `a name rm does not have offers nothing`() = rm.checkContext(
        // `rm --zzz a`: "unrecognized option '--zzz'".
        at("G15 E12", "rm --zzz|"),
        // `rm --remove a`: unrecognized, though --dir's help says "remove empty directories".
        at("G54", "rm --remove|"),
        // `rm --VERBOSE a`: rm's names are matched in their case.
        at("G77", "rm --VERB|"),
        // `rm --help-all a`: "unrecognized option '--help-all'".
        at("G11 G65", "rm --help-|"),
        // `rm -h a` and `rm -vh a`: "invalid option -- 'h'".
        at("G64", "rm -h|"),
        at("G64", "rm -vh|"),
        // `rm ---presume-input-tty a` runs, but `rm --help` keeps it out of sight (G35).
        at("G35", "rm ---|"),
        at("G35", "rm ---pres|"),
    )

    @Test
    fun `an optional-value long is offered once and bare`() {
        // G30: `rm --help`: `--preserve-root[=all]`; Tab offers the name, the values only after the `=`.
        rm.assertValues("G30", "rm --preserve-r", "rm --preserve-r".length, listOf(name("--preserve-root")))
        rm.assertOffersNone("G30", "rm --interactiv|", listOf("--interactive=", "--interactive "))
    }

    @Test
    fun `a single dash offers both spellings and two dashes only the longs`() {
        // `rm --help`: `-f, --force` and the rest; a short is never a `--` word.
        val single = rm.suggestionsFor("rm -").map { it.value }
        val double = rm.suggestionsFor("rm --").map { it.value }
        assertEquals(single.filter { it.startsWith("--") }, double, "G4 `rm --|` against the longs of `rm -|`")
        val bothSpellings = INPUTS.filter { it.long != null && it.shorts.isNotEmpty() }
        checkCells(bothSpellings) { input ->
            val wanted = listOf(input.long!!) + input.shorts.map { "-$it" }
            assertTrue(single.containsAll(wanted), "G4 `rm -|`: must offer $wanted, offered $single")
        }
    }

    @Test
    fun `names come grouped by kind and each input's spellings together`() {
        val names = rm.suggestionsFor("rm -").map { it.value }
        // klap lists every option before every flag; the shell sorts the list again, so rm has no order of its own.
        val options = listOf("--interactive").filter { it in names }
        val flags = listOf("--force", "-f", "-i", "-I", "--one-file-system", "--dir", "--verbose")
            .filter { it in names }
        assertTrue(
            options.maxOf(names::indexOf) < flags.minOf(names::indexOf),
            "G16 `rm -|`: every option name before every flag name, got $names",
        )
        for (pair in listOf(listOf("--force", "-f"), listOf("--dir", "-d"), listOf("--verbose", "-v"))) {
            val at = names.indexOf(pair.first())
            assertEquals(pair, names.subList(at, at + pair.size), "G17 `rm -|`: the long, then its short")
        }
    }

    @Test
    fun `the values rm documents come in rm's order`() {
        // G2: `rm --help`: "prompt according to WHEN: never, once (-I), or always (-i)".
        val documented = listOf("never", "once", "always").map { "--interactive=$it" }
        val offered = rm.suggestionsFor("rm --interactive=").map { it.value }
        assertEquals(documented, offered.filter { it in documented }, "G2 `rm --interactive=|`")
    }

    @Test
    fun `no suggestion is offered twice`() {
        val lines = listOf("rm -", "rm --", "rm -v", "rm -r", "rm -rf", "rm --no", "rm --interactive=", "rm --r")
        checkCells(lines) { line ->
            val values = rm.suggestionsFor(line).map { it.value }
            assertEquals(values.distinct(), values, "G3 G27 `$line|`")
        }
    }

    @Test
    fun `a cluster continues with rm's letters`() =
        checkCells(CLUSTER_CELLS) { rm.assertValues("$it", it.line, it.cursor, it.offers) }

    @Test
    fun `a short is described alike alone and in a cluster`() {
        val alone = rm.suggestionsFor("rm -").filter { it.value.length == 2 }.associate { it.value[1] to it }
        val continued = rm.suggestionsFor("rm -v").associate { it.value.last() to it }
        checkCells((alone.keys - 'v').toList()) { letter ->
            assertEquals(
                alone.getValue(letter).description,
                continued[letter]?.description,
                "G32 `rm -|` -$letter against `rm -v|` -v$letter",
            )
        }
    }

    @Test
    fun `an attached value is filtered on the part after the equals sign`() = rm.checkContext(
        // G14, G80: `rm --interactive=al a` prompts, `rm --interactive=o a b c e` asks once.
        at("G14 G80 E9", "rm --interactive=al|", *whenValues("al")),
        at("G80", "rm --interactive=o|x", *whenValues("o")),
        at("G80 E21", "rm --interactive=a| a b", *whenValues("a")),
        // G84: `rm --interactive=nev= a`: "invalid argument 'nev='"; `rm --preserve-root=all= a` is "unrecognized".
        at("G84", "rm --interactive=never=|"),
        at("G84", "rm --preserve-root=all=|"),
    )

    @Test
    fun `an operand slot is the shell's file completion alone`() = rm.checkContext(
        // G12, G72, E11: every operand is a file, and the directive is the whole answer.
        at("G12 G72 E1", "rm |", FILES),
        at("G12 E1", "rm | a b", FILES),
        at("G12 E1", "rm |--verbose", FILES),
        at("G12 G83 E1", "rm -- -|", FILES),
        at("G12", "rm -f a|", FILES),
    )

    @Test
    fun `a value in capitals is not offered`() = rm.checkContext(
        // G13, G76, E13, E22: `rm --interactive=ALWAYS a`: "invalid argument": rm matches its values in their case.
        at("G13 G76 E13 E22", "rm --interactive=AL|"),
        at("G13 G76 E13 E22", "rm --interactive=N|"),
    )

    @Test
    fun `a finished word asked again offers itself`() {
        rm.checkContext(
            at("E17", "rm --verbose|", name("--verbose")),
            at("E17", "rm --interactive=never|", attached("--interactive", "never")),
        )
        // E17 cluster half: `rm -v|` offers continuations only, never `-v` itself.
        rm.assertOffersNone("E17 G99", "rm -v|", listOf("-v", "-vv"))
    }

    @Test
    fun `a line once a suggestion is taken binds as rm reads it`() {
        // E15: each line holds a suggestion Tab offered for the word under the cursor; each ran under real rm in a
        // throwaway directory and exited 0, or failed only on removing.
        val cells = listOf(
            Triple("rm --verb| a b", "rm --verbose a b", A_AND_B.copy(verbose = true)),
            Triple("rm --rec| a b", "rm --recursive a b", A_AND_B.copy(recursive = true)),
            Triple("rm --for| a b", "rm --force a b", A_AND_B.forcing()),
            Triple("rm -r| a b", "rm -rf a b", A_AND_B.forcing().copy(recursive = true)),
            Triple("rm -v| a b", "rm -vd a b", A_AND_B.copy(verbose = true, dir = true)),
            Triple("rm --di| a b", "rm --dir a b", A_AND_B.copy(dir = true)),
            Triple("rm --interactive=nev| a b", "rm --interactive=never a b", A_AND_B.promptingBy(Prompt.Never)),
        )
        checkCells(cells) { (typed, accepted, expected) ->
            val taken = accepted.split(' ')[1]
            val offered = rm.suggestionsFor(typed.replace("|", ""), typed.indexOf('|')).map { it.value }
            assertTrue(taken in offered, "E15 `$typed`: must offer $taken, offered $offered")
            parity.binds(*accepted.split(' ').drop(1).toTypedArray(), expected = expected, because = "E15 `$typed`")
        }
    }

    @Test
    fun `a name taken and then the next word is a file`() {
        // E16: `rm --verb` offers --verbose; on `rm --verbose ` the operand is the shell's to complete, and `rm
        // --verbose a` printed "removed 'a'".
        assertEquals(listOf(name("--verbose")), rm.suggestionsFor("rm --verb"), "E16 `rm --verb|`")
        assertEquals(listOf(FILES), rm.suggestionsFor("rm --verbose "), "E16 `rm --verbose |`")
        parity.binds("--verbose", "a", expected = NOTHING_BOUND.copy(verbose = true, files = listOf(CliPath("a"))))
    }

    @Test
    fun `a cluster continuation taken continues with the letters left`() {
        // E16: `rm -r` offers -rf; `rm -rf` continues without -r or -f again, and with no -h.
        assertTrue("-rf" in rm.suggestionsFor("rm -r").map { it.value }, "E16 `rm -r|` offers -rf")
        val next = rm.suggestionsFor("rm -rf").map { it.value }
        assertTrue("-rfv" in next && "-rfd" in next, "E16 `rm -rf|`: must continue, offered $next")
        rm.assertOffersNone("E16 E22", "rm -rf|", listOf("-rfr", "-rff", "-rfh", "-rf"))
    }

    @Test
    fun `a name's description is the text its help row shows`() {
        val page = parity.helpPage("--help", because = "rm --help")
        val spaces = Regex(" +")
        val flat = page.lines().joinToString(" ") { it.trim() }.replace(spaces, " ")
        checkCells(rm.suggestionsFor("rm -")) { offered ->
            val text = offered.description.orEmpty().replace(spaces, " ")
            assertTrue(text.isNotEmpty() && text in flat, "E20 `rm -|` ${offered.value}: \"$text\" is not on --help")
        }
    }

    @Test
    fun `a description carries none of the help page's annotations`() {
        val annotations = listOf(
            "(default:", "(bare:", "(optional)", "(last of", "(one of", "(required)", "(repeatable)", "(absent when",
            "(optional when",
        )
        checkCells(rm.suggestionsFor("rm -") + rm.suggestionsFor("rm --interactive=")) { offered ->
            val text = offered.description.orEmpty()
            assertTrue(annotations.none { it in text }, "G47 ${offered.value}: \"$text\" carries an annotation")
        }
    }

    @Test
    fun `asking twice gives the same answer`() {
        val lines = listOf("rm -", "rm --interactive=", "rm -v", "rm ", "rm --no")
        checkCells(lines) { line ->
            assertEquals(rm.suggestionsFor(line), rm.suggestionsFor(line), "E24 `$line|`")
        }
    }

    @Test
    fun `the one name starting with no- is --no-preserve-root`() {
        // G10, G59: `rm --help`: the only `--no-` name is --no-preserve-root, and rm negates nothing else.
        assertEquals(listOf("--no-preserve-root"), rm.suggestionsFor("rm --no-").map { it.value }, "G10 `rm --no-|`")
    }

    @Test
    fun `--no-preserve-root stays offered after --preserve-root`() {
        // G62: `rm --help` lists both; getopt reads `rm --preserve-root --no-preserve-root a` as the two longs. Not
        // run.
        assertEquals(
            listOf("--no-preserve-root"),
            rm.suggestionsFor("rm --preserve-root --no-p").map { it.value },
            "G62 `rm --preserve-root --no-p|`",
        )
    }

    @Test
    fun `an input already given is offered again as rm takes it again`() {
        // G99: `rm --verbose --verbose a` and `rm -vv a` run.
        val offered = rm.suggestionsFor("rm -v -").map { it.value }
        assertTrue("-v" in offered && "--verbose" in offered, "G99 `rm -v -|`: must offer -v again, offered $offered")
    }

    @Test
    fun `a value after a refused option is none of klap's or the option's own`() {
        // G68, G90: `rm --color a`, `rm --completion bash a`, `rm --json a`, `rm --docs a`: "unrecognized option"; `rm
        // --v a`: "option '--v' is ambiguous".
        val lines = listOf("rm --color |", "rm --completion |", "rm --json |", "rm --docs |", "rm --v |")
        checkCells(lines) { typed ->
            val offered = rm.suggestionsFor(typed.replace("|", ""), typed.indexOf('|'))
            assertTrue(offered.all { it == FILES }, "G68 G90 `$typed`: offers $offered")
        }
    }

    @Test
    fun `--version is offered alone for its prefix`() {
        // G66: `rm --vers` prints the banner.
        assertEquals(listOf("--version"), rm.suggestionsFor("rm --vers").map { it.value }, "G66 `rm --vers|`")
        rm.assertOffersNone("G64 E22", "rm -v|", listOf("-vh"))
    }

    @Test
    fun `a long name is described in rm's words`() =
        // G6a, G6b, G44, G45, G50: `rm --help`, each entry's lines joined: -I "prompt once before removing more than
        // three files, or when removing recursively; less intrusive than -i, while still giving protection against
        // most mistakes", --no-preserve-root "do not treat '/' specially", "display this help and exit", "output
        // version information and exit", and the rest as RmModel.kt holds them.
        checkCells(NAME_CELLS.filter { it.worded && !it.foreignPrefix }) { rm.assertCell(it) }

    @Test
    fun `a cluster continues with rm's letters in rm's words`() = checkCells(CLUSTER_CELLS) { rm.assertCell(it) }

    @Test
    fun `a dash offers rm's names in rm's words and nothing else`() = rm.checkContext(
        // G1a, G1b, G4, G6a, G45: `rm --help` lists every name with its wording; `rm --json a` and `rm
        // --interactive-once a`: "unrecognized option".
        at("G1a G1b G4 G6a G45 E5 E6", "rm -|", *pool(longsOnly = false)),
        at("G1a G1b G4 G6a G45 E5 E6", "rm --|", *pool(longsOnly = true)),
        at("G1a G4 E21", "rm -| a b", *pool(longsOnly = false)),
        at("G1a G4 E21", "rm --| a b", *pool(longsOnly = true)),
    )

    @Test
    fun `a built-in rm does not have is not offered`() {
        // G11, G67: `rm --json a`, `rm --color=always a`, `rm --completion bash a`, `rm --docs a`: "unrecognized
        // option" each.
        rm.checkContext(
            at("G11 G67", "rm --js|"),
            at("G11", "rm --col|"),
            at("G11 G67", "rm --comp|"),
            at("G11 G67", "rm --do|"),
        )
        rm.assertOffersNone("G11 G64 G65 G67 E22", "rm -|", KLAP_BUILT_INS)
    }

    @Test
    fun `the built-ins come last and are only rm's`() {
        // G18: `rm --help` lists --help and --version after every other name.
        val names = rm.suggestionsFor("rm -").map { it.value }
        assertEquals(listOf("--help", "--version"), names.takeLast(2), "G18 `rm -|`: the built-ins come last")
    }

    @Test
    fun `a name rm does not have is not offered for --interactive`() {
        // G1a, G101: `rm --interactive-once a`: "unrecognized option"; `rm --i a` and `rm --inter a` prompt.
        rm.checkContext(
            at("G1a G30", "rm --interac|", name("--interactive")),
            at("G1a", "rm --i|", name("--interactive")),
            at("G1a", "rm --interactive-|"),
        )
    }

    @Test
    fun `--preserve-root= offers all`() = rm.checkContext(
        // G1b, G101: `rm --preserve-root=all a` runs.
        at("G1b G101", "rm --preserve-root=|", attached("--preserve-root", "all")),
        at("G1b G101 E17", "rm --preserve-root=all|", attached("--preserve-root", "all")),
    )

    @Test
    fun `a completion script's call reaches rm as its own words`() {
        // rm has no completion protocol: `rm __complete --cursor 1 -- -` is "unrecognized option '--cursor'".
        parity.rejects("__complete", "--cursor", "1", "--", "-", because = "real rm: unrecognized option '--cursor'")
    }

    @Test
    fun `the values of --interactive come in rm's order`() {
        // G2, G14, G80: `rm --interactive=bogus a`: "Valid arguments are: 'never', 'no', 'none' / 'once' / 'always',
        // 'yes'"; `rm --interactive=n a` removes without asking.
        assertEquals(
            WHEN_WORDS.map { "--interactive=$it" },
            rm.suggestionsFor("rm --interactive=").map { it.value },
            "G2 `rm --interactive=|`",
        )
        rm.checkContext(at("G14 G80 E10", "rm --interactive=n|", *whenValues("n")))
    }
}
