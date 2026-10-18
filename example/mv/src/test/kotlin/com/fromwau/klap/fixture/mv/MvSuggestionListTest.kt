package com.fromwau.klap.fixture.mv

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.mv.MvLineContext.Cell
import com.fromwau.klap.fixture.mv.MvLineContext.FILES
import com.fromwau.klap.fixture.mv.MvLineContext.assertCell
import com.fromwau.klap.fixture.mv.MvLineContext.assertOffersNone
import com.fromwau.klap.fixture.mv.MvLineContext.assertValues
import com.fromwau.klap.fixture.mv.MvLineContext.at
import com.fromwau.klap.fixture.mv.MvLineContext.checkContext
import com.fromwau.klap.fixture.mv.MvLineContext.mvHelp
import com.fromwau.klap.fixture.mv.MvLineContext.name
import com.fromwau.klap.io.CliPath
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val KLAP_BUILT_INS = listOf("-h", "--help-all", "--json", "--color", "--completion", "--docs")

/** [value] attached to [long], described by mv's help for [long]. */
private fun attached(
    long: String,
    value: String,
): Suggestion = Suggestion("$long=$value", mvHelp(long))

private fun backupControls(vararg values: String): Array<Suggestion> =
    values.map { attached("--backup", it) }.toTypedArray()

private fun spellings(input: Input): List<String> = listOfNotNull(input.long) + input.shorts.map { "-$it" }

/** Every name mv accepts after a dash, in mv's words: each input's long and shorts, then `--help` and `--version`. */
private fun pool(longsOnly: Boolean): Array<Suggestion> = (INPUTS.flatMap(::spellings) + "--help" + "--version")
    .filter { !longsOnly || it.startsWith("--") }
    .map(::name)
    .toTypedArray()

/** What mv continues [cluster] with: each of its letters not yet in it, in mv's words. */
private fun continuations(cluster: String): Array<Suggestion> = INPUTS
    .flatMap { it.shorts }
    .filter { it !in cluster.drop(1) }
    .map { Suggestion(cluster + it, inputFor(it)!!.help) }
    .toTypedArray()

/** The cell for [typed], with [rest] of its word after the cursor and [after] past it: every long of mv it starts. */
private fun nameCell(
    typed: String,
    rest: String = "",
    after: String = "",
): Cell {
    val offers = MV_LONGS.filter { it.startsWith(typed) }.map(::name)
    val rows = "G1a G6a G14 E1 E5 E6" + if (after.isEmpty()) "" else " E21"
    return Cell(rows, "mv $typed|$rest$after", offers)
}

/** For every long of mv: the finished word (E17), the word with its last letter after the cursor, and words after. */
private val NAME_CELLS: List<Cell> by lazy {
    MV_LONGS.flatMap { long ->
        listOf(
            nameCell(long),
            nameCell(long.dropLast(1), rest = long.takeLast(1)),
            nameCell(long, after = " a b"),
        )
    }
}

private val Cell.worded: Boolean get() = offers.any { it.description in WORDED_HELP }

private val Cell.builtinPrefix: Boolean
    get() {
        val typed = line.substring(line.lastIndexOf(' ', cursor - 1) + 1, cursor)
        return KLAP_ONLY_LONGS.any { it.startsWith(typed) }
    }

/** The cluster cells of G5 and G21: every letter mv continues a cluster with, in mv's words. */
private val CLUSTER_CELLS: List<Cell> = listOf(
    // fish's own `complete -C "mv -v"` lists -vb to -vZ; the descriptions are mv --help's.
    at("G5 G21 G32 G6a", "mv -v|", *continuations("-v")),
    at("G5 G21 G32 G6a", "mv -f|", *continuations("-f")),
    at("G5 G21 G32 G6a", "mv -fv|", *continuations("-fv")),
    at("G5 G6a E21", "mv -b| a b", *continuations("-b")),
)

/**
 * The suggestion list Tab gives on an mv line and what every completion cell asserts about it (tables G and E of the
 * testing matrix): which names are offered and which not, their order, their descriptions, and the line once a
 * suggestion is taken, written out. Every expectation is what real mv 9.12 accepts at the cursor, in the words of
 * `mv --help`; a list cell compares whole suggestions sorted by value, since the shell sorts, and the order klap hands
 * over is pinned on its own. A cell whose list holds one of [WORDED_HELP] is checked by its values in one test, and
 * whole in the one on mv's words.
 */
class MvSuggestionListTest {

    private val mv = mvCli().cli

    private val parity = ParitySuite(mvCli())

    @Test
    fun `a long name completes to every long of mv it starts in mv's words`() =
        checkCells(NAME_CELLS.filterNot { it.worded || it.builtinPrefix }) { mv.assertCell(it) }

    @Test
    fun `a long name completes to every long of mv it starts`() =
        checkCells(NAME_CELLS.filter { it.worded && !it.builtinPrefix }) {
            mv.assertValues("$it", it.line, it.cursor, it.offers)
        }

    @Test
    fun `a name mv does not have offers nothing`() = mv.checkContext(
        // `mv --zzz a b`: "unrecognized option '--zzz'".
        at("G15 E12", "mv --zzz|"),
        // `mv --make a b`: unrecognized, though --backup's help says "make a backup".
        at("G54", "mv --make|"),
        // `mv --VERBOSE a b`: mv's names are matched in their case.
        at("G77", "mv --VERB|"),
        // `mv --help-all a b`: "unrecognized option '--help-all'".
        at("G11 G65", "mv --help-|"),
        // `mv -h a b` and `mv -vh a b`: "invalid option -- 'h'".
        at("G64", "mv -h|"),
        at("G64", "mv -vh|"),
    )

    @Test
    fun `a value-taking long is offered bare and described by its own help`() = mv.checkContext(
        // `mv --help`: `--suffix=SUFFIX`, `--target-directory=DIRECTORY` and `--backup[=CONTROL]`.
        at("G29", "mv --suf|", name("--suffix")),
        at("G29", "mv --targ|", name("--target-directory")),
        at("G30", "mv --backu|", name("--backup")),
        at("G29 E21", "mv --suf| .bak a b", name("--suffix")),
    )

    @Test
    fun `a single dash offers both spellings and two dashes only the longs`() {
        // `mv --help`: `-v, --verbose` and the rest; a short is never a `--` word.
        val single = mv.suggestionsFor("mv -").map { it.value }
        val double = mv.suggestionsFor("mv --").map { it.value }
        assertEquals(single.filter { it.startsWith("--") }, double, "G4 `mv --|` against the longs of `mv -|`")
        val bothSpellings = INPUTS.filter { it.long != null && it.shorts.isNotEmpty() }
        checkCells(bothSpellings) { input ->
            val wanted = listOf(input.long!!) + input.shorts.map { "-$it" }
            assertTrue(single.containsAll(wanted), "G4 `mv -|`: must offer $wanted, offered $single")
        }
    }

    @Test
    fun `names come grouped by kind and each input's spellings together`() {
        val names = mv.suggestionsFor("mv -").map { it.value }
        val valueNames = INPUTS.filter { it.kind is Kind.Value }.flatMap(::spellings).filter { it in names }
        val flagNames = INPUTS.filter { it.kind is Kind.Flag }.flatMap(::spellings).filter { it in names }
        // klap lists every option before every flag; the shell sorts the list again, so mv has no order of its own.
        assertTrue(
            valueNames.maxOf(names::indexOf) < flagNames.minOf(names::indexOf),
            "G16 `mv -|`: every option name before every flag name, got $names",
        )
        val force = listOf("--force", "-f")
        val at = names.indexOf(force.first())
        assertEquals(force, names.subList(at, at + force.size), "G17 `mv -|`: the long, then its short")
        val continued = mv.suggestionsFor("mv -v").map { it.value }
        assertEquals(listOf("-vt", "-vS"), continued.take(2), "G21 `mv -v|`: value letters first")
    }

    @Test
    fun `choices come in the order mv documents them`() {
        // `mv --help`: "none, off / numbered, t / existing, nil / simple, never" and UPDATE={all,none,none-fail,older}.
        assertEquals(
            BACKUP_CONTROLS.map { attached("--backup", it) },
            mv.suggestionsFor("mv --backup="),
            "G2 `mv --backup=|`",
        )
        assertEquals(
            UPDATE_MODES.map { "--update=$it" },
            mv.suggestionsFor("mv --update=").map { it.value },
            "G2 `mv --update=|`",
        )
    }

    @Test
    fun `no suggestion is offered twice`() {
        val lines = listOf("mv -", "mv --", "mv -v", "mv -f", "mv --no", "mv --backup=", "mv --update=")
        checkCells(lines) { line ->
            val values = mv.suggestionsFor(line).map { it.value }
            assertEquals(values.distinct(), values, "G3 G27 `$line|`")
        }
    }

    @Test
    fun `a cluster continues with mv's letters`() =
        checkCells(CLUSTER_CELLS) { mv.assertValues("$it", it.line, it.cursor, it.offers) }

    @Test
    fun `a cluster holding all but a few letters continues with those alone`() = mv.checkContext(
        // `getopt -o bfint:uvS:TZ -- -fniuvZb` and the same with T, S.x and td read every letter: -fniuvZ leaves -b,
        // -S, -t and -T, -i being the last of -f, -n and -i (G88). -Z keeps this line from a real mv run.
        at("G88", "mv -fniuvZ|", *continuations("-fniuvZ")),
    )

    @Test
    fun `a short is described alike alone and in a cluster`() {
        val alone = mv.suggestionsFor("mv -").filter { it.value.length == 2 }.associate { it.value[1] to it }
        val continued = mv.suggestionsFor("mv -v").associate { it.value.last() to it }
        checkCells((alone.keys - 'v').toList()) { letter ->
            assertEquals(
                alone.getValue(letter).description,
                continued[letter]?.description,
                "G32 `mv -|` -$letter against `mv -v|` -v$letter",
            )
        }
    }

    @Test
    fun `an attached backup control is filtered on the part after the equals sign`() = mv.checkContext(
        // `mv --backup=nu a b` made b.~1~; `mv --backup=n a b` is "ambiguous argument 'n' for 'backup type'", so Tab
        // offers every control starting n.
        at("G14 G80 E10", "mv --backup=n|", *backupControls("none", "numbered", "nil", "never")),
        at("G80 E9", "mv --backup=nu|", attached("--backup", "numbered")),
        at("G80", "mv --backup=nu|x", attached("--backup", "numbered")),
        at("G80 E21", "mv --backup=nu| a b", attached("--backup", "numbered")),
        // `mv --backup=numbered= a b`: "invalid argument 'numbered=' for 'backup type'"; `--update=none=` the same.
        at("G84", "mv --backup=numbered=|"),
        at("G84", "mv --update=none=|"),
    )

    @Test
    fun `a directory value is the shell's file completion alone`() = mv.checkContext(
        // `mv --t d a` and `mv -td a` move into d; `mv -t -x a`: "target directory '-x'", so -x is the value.
        at("G12 G72 E11", "mv --target-directory=|", filesAfter("--target-directory=")),
        at("G72", "mv --targ=|", filesAfter("--targ=")),
        at("G72", "mv -td|", filesAfter("-t")),
        at("G72 G85", "mv -vtd|", filesAfter("-vt")),
        at("G72 G85", "mv -vtdi|", filesAfter("-vt")),
        at("G72", "mv -t |", filesAfter()),
        at("G82", "mv -t -|", filesAfter()),
        at("G75 G84", "mv --target-directory=a=b|", filesAfter("--target-directory=")),
        at("G72 E21", "mv -td| a", filesAfter("-t")),
        // A glued -t with nothing after it is still the name word: `mv -t|` is the option.
        at("G73", "mv -t|", name("-t")),
    )

    @Test
    fun `a value-taking short closing a cluster leaves nothing to offer`() = mv.checkContext(
        // `mv -bS.z a b` takes .z as the suffix, `mv -vt d a` takes d as the directory: the value is the next word.
        at("G89", "mv -fS|"),
        at("G89", "mv -ft|"),
        // `mv -Svb a b` made the backup `bvb`: a glued free text has no candidates.
        at("G85", "mv -vSfo|"),
        at("G82", "mv -S -|"),
    )

    @Test
    fun `a choice in capitals is not offered`() = mv.checkContext(
        // `mv --backup=NU a b` and `mv --update=OLDER a b`: "invalid argument": mv matches its values in their case.
        at("G13 G76 E13 E22", "mv --backup=NU|"),
        at("G13 G76 E13 E22", "mv --update=OL|"),
    )

    @Test
    fun `a cursor on no word or at a word's start is an operand`() = mv.checkContext(
        at("E1", "mv | a b", filesAfter()),
        at("E1", "mv |--verbose", filesAfter()),
        at("E1 G83", "mv -- -|", filesAfter()),
    )

    @Test
    fun `a finished word asked again offers itself`() {
        mv.checkContext(
            at("E17", "mv --verbose|", name("--verbose")),
            at("E17", "mv --backup=numbered|", attached("--backup", "numbered")),
        )
        // E17 cluster half: `mv -v|` offers continuations only, never `-v` itself.
        mv.assertOffersNone("E17 G99", "mv -v|", listOf("-v", "-vv"))
    }

    @Test
    fun `a line once a suggestion is taken binds as mv reads it`() {
        // Each line holds a suggestion Tab offered for the word under the cursor; each ran under real mv in a
        // throwaway directory and exited 0, or failed only on moving.
        val cells = listOf(
            Triple("mv --backup=nu| a b", "mv --backup=numbered a b", A_TO_B.copy(backup = BackupControl.Numbered)),
            Triple("mv --verb| a b", "mv --verbose a b", A_TO_B.copy(verbose = true)),
            Triple("mv --exch| a b", "mv --exchange a b", A_TO_B.copy(exchange = true)),
            Triple("mv --st| a b", "mv --strip-trailing-slashes a b", A_TO_B.copy(stripTrailingSlashes = true)),
            Triple("mv -v| a b", "mv -vf a b", A_TO_B.copy(verbose = true, force = true)),
            Triple("mv --no-cop| a b", "mv --no-copy a b", A_TO_B.copy(noCopy = true)),
            Triple(
                "mv --targ| d a",
                "mv --target-directory d a",
                NOTHING_BOUND.copy(targetDirectory = CliPath("d"), sources = listOf(CliPath("a"))),
            ),
        )
        checkCells(cells) { (typed, accepted, expected) ->
            val taken = accepted.split(' ')[1]
            val offered = mv.suggestionsFor(typed.replace("|", ""), typed.indexOf('|')).map { it.value }
            assertTrue(taken in offered, "E15 `$typed`: must offer $taken, offered $offered")
            parity.binds(*accepted.split(' ').drop(1).toTypedArray(), expected = expected, because = "E15 `$typed`")
        }
    }

    @Test
    fun `a name taken and then its value bind the value`() {
        // E16: `mv --targ` offers --target-directory; on `mv --target-directory ` the directory is the shell's to
        // complete, and `mv --target-directory d a` moved a into d.
        assertEquals(listOf(name("--target-directory")), mv.suggestionsFor("mv --targ"), "E16 `mv --targ|`")
        assertEquals(listOf(FILES), mv.suggestionsFor("mv --target-directory "), "E16 `mv --target-directory |`")
        parity.binds(
            "--target-directory", "d", "a",
            expected = NOTHING_BOUND.copy(targetDirectory = CliPath("d"), sources = listOf(CliPath("a"))),
            because = "E16 `mv --target-directory d a`",
        )
    }

    @Test
    fun `a cluster continuation taken continues with the letters left`() {
        // E16: `mv -v` offers -vf; `mv -vf` continues without -v or -f again, and with no -h.
        assertTrue("-vf" in mv.suggestionsFor("mv -v").map { it.value }, "E16 `mv -v|` offers -vf")
        val next = mv.suggestionsFor("mv -vf").map { it.value }
        assertTrue("-vfb" in next && "-vfS" in next, "E16 `mv -vf|`: must continue, offered $next")
        mv.assertOffersNone("E16 E22", "mv -vf|", listOf("-vfv", "-vff", "-vfh", "-vf"))
    }

    @Test
    fun `a name's description is the text its help row shows`() {
        val page = parity.helpPage("--help", because = "mv --help")
        val spaces = Regex(" +")
        val flat = page.lines().joinToString(" ") { it.trim() }.replace(spaces, " ")
        checkCells(mv.suggestionsFor("mv -")) { offered ->
            // Spaces collapsed as on the page, since mv's own words put two after a full stop.
            val text = offered.description.orEmpty().replace(spaces, " ")
            assertTrue(text.isNotEmpty() && text in flat, "E20 `mv -|` ${offered.value}: \"$text\" is not on --help")
        }
    }

    @Test
    fun `a description carries none of the help page's annotations`() {
        val annotations = listOf(
            "(default:", "(bare:", "(optional)", "(last of", "(one of", "(required)", "(repeatable)", "(absent when",
        )
        checkCells(mv.suggestionsFor("mv -") + mv.suggestionsFor("mv --backup=")) { offered ->
            val text = offered.description.orEmpty()
            assertTrue(annotations.none { it in text }, "G47 ${offered.value}: \"$text\" carries an annotation")
        }
    }

    @Test
    fun `asking twice gives the same answer`() {
        val lines = listOf("mv -", "mv --backup=", "mv --update=", "mv -v", "mv -td")
        checkCells(lines) { line ->
            assertEquals(mv.suggestionsFor(line), mv.suggestionsFor(line), "E24 `$line|`")
        }
    }

    @Test
    fun `the names starting with no- are mv's three`() {
        // `mv --help`: the only `--no-` names are --no-clobber, --no-copy and --no-target-directory; mv negates none.
        val noNames = listOf("--no-clobber", "--no-copy", "--no-target-directory")
        assertEquals(noNames, mv.suggestionsFor("mv --no-").map { it.value }.sorted(), "G10 G59 `mv --no-|`")
    }

    @Test
    fun `an input already given is offered again as mv takes it again`() {
        // `mv --verbose --verbose a b` and `mv -vv a b` run.
        val offered = mv.suggestionsFor("mv -v -").map { it.value }
        assertTrue("-v" in offered && "--verbose" in offered, "G99 `mv -v -|`: must offer -v again, offered $offered")
    }

    @Test
    fun `a value after a refused option is none of klap's or the option's own`() {
        // `mv --color a b`, `mv --completion bash a b`, `mv --json a b`, `mv --docs a b`: "unrecognized option"; `mv
        // --s a b`: "option '--s' is ambiguous; possibilities: '--strip-trailing-slashes' '--suffix'".
        val lines = listOf("mv --color |", "mv --completion |", "mv --json |", "mv --docs |", "mv --s |")
        checkCells(lines) { typed ->
            val offered = mv.suggestionsFor(typed.replace("|", ""), typed.indexOf('|'))
            assertTrue(offered.all { it.value.startsWith(FILES.value) }, "G68 G90 `$typed`: offers $offered")
        }
    }

    @Test
    fun `--version is offered alone for its prefix`() {
        // `mv --vers` prints the banner.
        assertEquals(listOf("--version"), mv.suggestionsFor("mv --vers").map { it.value }, "G66 `mv --vers|`")
        mv.assertOffersNone("G64 E22", "mv -v|", listOf("-vh"))
    }

    @Test
    fun `a dash offers mv's names in mv's words and nothing else`() = mv.checkContext(
        // `mv --help` lists every name with its wording; `mv --json a b`: "unrecognized option".
        at("G1a G1b G4 G6a G45 E5 E6", "mv -|", *pool(longsOnly = false)),
        at("G1a G1b G4 G6a G45 E5 E6", "mv --|", *pool(longsOnly = true)),
        at("G1a G4 E21", "mv -| a b", *pool(longsOnly = false)),
        at("G1a G4 E21", "mv --| a b", *pool(longsOnly = true)),
    )

    @Test
    fun `a long name is described in mv's words`() =
        // `mv --help`: "explain how a file is copied.  Implies -v", "(deprecated) silently skip existing files.  See
        // also --update", "control which existing files are updated; UPDATE={all,none,none-fail,older(default)}", "set
        // SELinux security context of destination file to default type", "display this help and exit", "output version
        // information and exit".
        checkCells(NAME_CELLS.filter { it.worded && !it.builtinPrefix }) { mv.assertCell(it) }

    @Test
    fun `a cluster continues with mv's letters in mv's words`() = checkCells(CLUSTER_CELLS) { mv.assertCell(it) }

    @Test
    fun `a built-in mv does not have is not offered`() {
        // `mv --json`, `mv --color=always`, `mv --completion bash`, `mv --docs`: "unrecognized option" each.
        mv.checkContext(
            at("G11 G67", "mv --js|"),
            at("G11", "mv --col|"),
            at("G11 G67", "mv --comp|"),
            at("G11 G67", "mv --do|"),
        )
        mv.assertOffersNone("G11 G64 G65 G67 E22", "mv -|", KLAP_BUILT_INS)
    }

    @Test
    fun `the built-ins come last and are only mv's`() {
        // `mv --help` lists --help and --version after every other name.
        val names = mv.suggestionsFor("mv -").map { it.value }
        assertEquals(listOf("--help", "--version"), names.takeLast(2), "G18 `mv -|`: the built-ins come last")
    }

    @Test
    fun `a completion script's call reaches mv as its own words`() {
        // mv has no completion protocol: `mv __complete --cursor 1 -- -` is "unrecognized option '--cursor'".
        parity.rejects("__complete", "--cursor", "1", "--", "-", because = "real mv: unrecognized option '--cursor'")
    }
}
