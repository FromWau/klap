package com.fromwau.klap.fixture.find

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.find.FindCompletionSupport.FILES
import com.fromwau.klap.fixture.find.FindCompletionSupport.Input
import com.fromwau.klap.fixture.find.FindCompletionSupport.KLAP_LONGS
import com.fromwau.klap.fixture.find.FindCompletionSupport.LEVELS
import com.fromwau.klap.fixture.find.FindCompletionSupport.LONGS
import com.fromwau.klap.fixture.find.FindCompletionSupport.PREDICATES
import com.fromwau.klap.fixture.find.FindCompletionSupport.REGEX_TYPES
import com.fromwau.klap.fixture.find.FindCompletionSupport.SHORTS
import com.fromwau.klap.fixture.find.FindLineContext.assertOffersNone
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.acceptSuggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import com.fromwau.klap.testing.tab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The suggestion list Tab gives on a find line and what every completion cell asserts about it (tables G and E of
 * the testing matrix). Every expectation is what GNU findutils 4.11.0
 * `/usr/bin/find` accepts at the cursor, read through the fixture's respelling (F1: `-name` is `--name`); a name's
 * description is the fixture's own help, since `find --help` describes no predicate. A list cell compares whole
 * suggestions sorted by value, since the shell sorts, and the order klap hands over is pinned on its own.
 */
class FindSuggestionListTest {

    private val find = findCli().cli

    private val parity = ParitySuite(findCli())

    @Test
    fun `a long name completes to every long of find it starts`() =
        // `find --help` lists every predicate; each name here is one the fixture declares and find has, typed whole,
        // with its last letter after the cursor, and with a starting point after it.
        checkCells(NAME_CELLS) { find.assertList(it) }

    @Test
    fun `a name find does not have offers nothing`() = find.checkLists(
        // `find . -zzz`: "unknown predicate `-zzz'".
        at("G15 E12", "find --zzz|"),
        // No predicate starts with `-base`, though --name's help says "base name".
        at("G54", "find --base|"),
        // `find . -NAME a`: unknown predicate; find matches its names in their case.
        at("G77", "find --NAME|"),
        // `find --help-all`: "unknown predicate `--help-all'".
        at("G11 G65", "find --help-|"),
    )

    @Test
    fun `a value-taking long is offered bare and described by its own help`() = find.checkLists(
        // `find --help`: `-maxdepth LEVELS`, `-files0-from FILE`; the fixture declares both in its traversal group.
        at("G29 G48", "find --maxd|", named("--maxdepth")),
        at("G29 G48", "find --files0|", named("--files0-from")),
        at("G29 E21", "find . --maxd| 2", named("--maxdepth")),
    )

    @Test
    fun `names come grouped by kind and the built-ins last`() {
        val names = find.suggestionsFor("find -").map { it.value }
        val valueNames = DECLARED.filter { it.value != null }.map { it.name }.filter { it in names }
        val flagNames = DECLARED.filter { it.value == null }.map { it.name }.filter { it in names }
        // The fixture declares --maxdepth after -P and --empty before --print; klap still lists every option name
        // before every flag name. The shell sorts the list again, so find has no order of its own here.
        assertTrue(
            valueNames.maxOf(names::indexOf) < flagNames.minOf(names::indexOf),
            "G16 `find -|`: every option name before every flag name, got $names",
        )
        val lastApp = DECLARED.maxOf { names.indexOf(it.name) }
        assertTrue(lastApp < names.indexOf("--help"), "G18 `find -|`: --help after every app name, got $names")
        assertTrue(names.indexOf("--help") < names.indexOf("--version"), "G18 `find -|`: --help before --version")
    }

    @Test
    fun `a regex type is filtered on the part after the equals sign in find's order`() = find.checkLists(
        // `find . -regextype posix-egrep` and `posix-extended` walk; find lists posix-egrep before posix-extended.
        at("G2 G14 G80 E10", "find --regextype=posix-e|", *regexTypes("posix-egrep", "posix-extended")),
        at("G80 E9", "find --regextype=f|", *regexTypes("findutils-default")),
        at("G80", "find --regextype=f|x", *regexTypes("findutils-default")),
        at("G80 E21", "find --regextype=f| .", *regexTypes("findutils-default")),
        at("E17", "find --regextype=gnu-awk|", *regexTypes("gnu-awk")),
        // `find . -regextype posix-egrep=`: "Unknown regular expression type ‘posix-egrep=’".
        at("G84", "find --regextype=posix-egrep=|"),
    )

    @Test
    fun `a spaced regex type carries no description`() = find.checkLists(
        // `find . -regextype findutils-default` walks. The attached form is described by the option's help (G41).
        at("G41 G7", "find --regextype f|", Suggestion("findutils-default")),
        at("G41 G7 G2", "find --regextype posix-e|", Suggestion("posix-egrep"), Suggestion("posix-extended")),
        at("G41 G7 E21", "find --regextype f| .", Suggestion("findutils-default")),
    )

    @Test
    fun `a regex type in capitals is not offered`() = find.checkLists(
        // `find . -regextype POSIX-EGREP`: "Unknown regular expression type ‘POSIX-EGREP’"; find matches in case.
        at("G13 G76 E13 E22", "find --regextype=POS|"),
        at("G13 G76 E13 E22", "find --regextype POSIX|"),
        at("G13 G76 E13 E22", "find --regextype=Find|"),
    )

    @Test
    fun `a file value is the shell's file completion alone`() = find.checkLists(
        // `find . -newer a` walks; `find -files0-from -` reads the starting points from stdin, so `-` is a file.
        at("G12 G72 E11", "find --files0-from=|", filesAfter("--files0-from=")),
        at("G12 G72 E11", "find --files0-from |", filesAfter()),
        at("G72 G82", "find --files0-from -|", filesAfter()),
        at("G72", "find --newer=a|", filesAfter("--newer=")),
        at("G72", "find --newer |", filesAfter()),
        at("G72 G82", "find --newer -|", filesAfter()),
        at("G75", "find --newer=/no/such/pre|", filesAfter("--newer=")),
        at("G72 E21", "find --newer=| .", filesAfter("--newer=")),
    )

    @Test
    fun `a starting point slot is the shell's file completion`() = find.checkLists(
        // `find --help`: "[path...] [expression]"; `find .` and `find a` walk.
        at("E1", "find |", filesAfter()),
        at("E1", "find . |", filesAfter()),
        at("E1 E21", "find | --name a", filesAfter()),
        at("E1", "find |--name", filesAfter()),
    )

    @Test
    fun `a value slot without candidates offers nothing for a dash`() = find.checkLists(
        // `find . -name -foo` walks, -foo being the pattern; no regex type starts with a dash.
        at("G82", "find --name -|"),
        at("G82", "find --regextype -|"),
    )

    @Test
    fun `a glued short with nothing after it is its own name word`() = find.checkLists(
        // `find -D tree .` runs; the word typed so far is the option. `find -O|` offers its glued levels instead, as
        // `find -O .` is refused ([FindValueCandidatesTest]).
        at("G73 E17", "find -D|", named("-D")),
    )

    @Test
    fun `a word find refuses as a whole offers nothing`() = find.checkLists(
        // `find -Dtree .` and `find -LD .`: "unknown predicate"; find reads -D's value from the next word only.
        at("G85", "find -Dtr|"),
        at("G89", "find -LD|"),
    )

    @Test
    fun `an ambiguous long leaves nothing but the file completion`() {
        // `find . -re x`: "unknown predicate `-re'"; find matches every predicate whole.
        val offered = find.suggestionsFor("find --re ")
        assertTrue(offered.all { it.value.startsWith(FILES.value) }, "G90 `find --re |`: offers $offered")
    }

    @Test
    fun `tab puts in what the suggestions share and no further`() {
        val cells = listOf(
            // E9: one suggestion; one equal to the word leaves the line.
            "find --xd|" to "find --xdev",
            "find --xdev|" to "find --xdev",
            // E10: nothing shared past the typed word, and a shared prefix that is itself a name.
            "find --pr|" to "find --pr",
            "find --prin|" to "find --print",
            // E8 inside a word: the typed part is replaced and the rest of the word stays, as bash does; zsh, fish
            // and PowerShell replace the whole word (E21).
            "find --xd|x" to "find --xdevx",
            "find . --xd| --print" to "find . --xdev --print",
            // E8 on no word and at a word's start: a starting point, the shell's file completion.
            "find | --name a" to "find  --name a",
            "find |--name" to "find --name",
        )
        checkCells(cells) { (typed, expected) ->
            assertEquals(expected, find.tab(typed.replace("|", ""), typed.indexOf('|')), "E8 `$typed`")
        }
    }

    @Test
    fun `a finished word asked again offers itself`() = find.checkLists(
        at("E17", "find --xdev|", named("--xdev")),
        at("E17", "find --print0|", named("--print0")),
    )

    @Test
    fun `a line once a suggestion is taken binds as find reads it`() {
        // Each real line, single-dash spelled, walks in a throwaway directory and exits 0: `find . -xdev`,
        // `find . -print0`, `find . -nouser`, `find . -regextype posix-egrep`, `find . -maxdepth 2`, `find . -newer a`.
        val cells = listOf(
            Triple("find . --xd|", "--xdev", AT_DOT.copy(xdev = true)),
            Triple("find . --print|", "--print0", AT_DOT.copy(print0Action = true)),
            Triple("find . --nouse|", "--nouser", AT_DOT.copy(nouserTest = true)),
            Triple("find . --regextype=posix-e|", "--regextype=posix-egrep", AT_DOT.copy(regextype = "posix-egrep")),
            Triple("find . --maxd| 2", "--maxdepth", AT_DOT.copy(maxDepth = 2)),
            Triple("find . --newe| a", "--newer", AT_DOT.copy(newerThan = listOf("a"))),
        )
        checkCells(cells) { (typed, value, expected) ->
            val line = typed.replace("|", "")
            val cursor = typed.indexOf('|')
            val taken = find.suggestionsFor(line, cursor).firstOrNull { it.value == value }
            assertTrue(taken != null, "E15 `$typed`: must offer $value")
            val accepted = acceptSuggestion(line, taken, cursor)
            parity.binds(*accepted.split(' ').drop(1).toTypedArray(), expected = expected, because = "E15 `$typed`")
        }
    }

    @Test
    fun `a name taken and then its value asked for chains`() {
        // `find -files0-from -` with no starting point reads stdin; `find . -maxdepth 2` walks.
        val files0 = acceptSuggestion("find --files0", find.suggestionsFor("find --files0").single()) + " "
        assertEquals(listOf(filesAfter()), find.suggestionsFor(files0), "E16 `$files0|`")
        val depth = acceptSuggestion("find . --maxd", find.suggestionsFor("find . --maxd").single()) + " "
        assertEquals(emptyList(), find.suggestionsFor(depth), "E16 `$depth|`: a depth has no candidates")
        val chosen = "${depth}2"
        parity.binds(
            *chosen.split(' ').drop(1).toTypedArray(),
            expected = AT_DOT.copy(maxDepth = 2),
            because = "E16 `$chosen`",
        )
    }

    @Test
    fun `a completion script's call reaches find as its own words`() {
        // find declines klap's completion, so there is no __complete for a script to call.
        parity.rejects("__complete", "--cursor", "1", "--", "-", because = "real find: unknown predicate `--cursor'")
    }

    @Test
    fun `a name's description is the text its help row shows`() {
        val page = parity.helpPage("--help", because = "find --help")
        val spaces = Regex(" +")
        val flat = page.lines().joinToString(" ") { it.trim() }.replace(spaces, " ")
        checkCells(find.suggestionsFor("find -")) { offered ->
            val text = offered.description.orEmpty().replace(spaces, " ")
            assertTrue(text.isNotEmpty() && text in flat, "E20 `find -|` ${offered.value}: \"$text\" is not on --help")
        }
    }

    @Test
    fun `a description is one trimmed line with none of the help page's annotations`() {
        val annotations = listOf(
            "(default:", "(bare:", "(optional)", "(last of", "(one of", "(required)", "(repeatable)", "(absent when",
            "(0..",
        )
        checkCells(find.suggestionsFor("find -") + find.suggestionsFor("find --regextype=")) { offered ->
            val text = offered.description.orEmpty()
            assertTrue(annotations.none { it in text }, "G47 ${offered.value}: \"$text\" carries an annotation")
            assertEquals(text.trim(), text, "G51 ${offered.value}: untrimmed description")
            assertTrue('\n' !in text && '\t' !in text, "G50 G51 ${offered.value}: \"$text\" is not one line")
            assertTrue(text != TRAVERSAL_GROUP, "G48 ${offered.value}: described by its group title")
        }
    }

    @Test
    fun `no suggestion is offered twice`() {
        val lines = listOf("find -", "find --", "find --p", "find --regextype=", "find -P -", "find . --name a --")
        checkCells(lines) { line ->
            val values = find.suggestionsFor(line).map { it.value }
            assertEquals(values.distinct(), values, "G3 G27 `$line|`")
        }
    }

    @Test
    fun `asking twice gives the same answer`() {
        val lines = listOf("find -", "find --regextype=", "find --regextype ", "find --newer=", "find ")
        checkCells(lines) { line ->
            assertEquals(find.suggestionsFor(line), find.suggestionsFor(line), "E24 `$line|`")
        }
    }

    @Test
    fun `an input already given is offered again as find takes it again`() {
        // `find . -name a -name a`, `find -L -L .`, `find . -maxdepth 1 -maxdepth 2` and `find -P -L -H .` all walk.
        val cells = listOf(
            "find . --name a --|" to listOf("--name"),
            "find -L -|" to listOf("-L"),
            "find . --maxdepth 1 --max|" to listOf("--maxdepth"),
            "find -P -|" to listOf("-P", "-L", "-H"),
        )
        checkCells(cells) { (typed, wanted) ->
            val offered = find.suggestionsFor(typed.replace("|", ""), typed.indexOf('|')).map { it.value }
            assertTrue(offered.containsAll(wanted), "G99 G62 `$typed`: must offer $wanted, offered $offered")
        }
    }

    @Test
    fun `version is offered as find has it`() {
        // `find --version` prints the version; its description is pinned by `help and version are described in find's
        // words`.
        assertEquals(listOf("--version"), find.suggestionsFor("find --vers").map { it.value }, "G66 `find --vers|`")
    }

    @Test
    fun `a dash offers find's names and nothing else`() = find.checkLists(
        // `find --help` lists the shorts and every predicate; `find . -physical`, `find --json .`: unknown predicate.
        at("G1a G1b G4 G6a G11 G45 G101 E5 E6", "find -|", *pool(longsOnly = false)),
        at("G1a G1b G4 G6a G11 G45 G101 E5 E6", "find --|", *pool(longsOnly = true)),
        // `find . -files0-from a`: "file operands cannot be combined with -files0-from".
        at(
            "G1a G4 E21",
            "find -| .",
            *pool(longsOnly = false).filterNot { it.value == "--files0-from" }.toTypedArray(),
        ),
    )

    @Test
    fun `a built-in find does not have is not offered`() {
        // `find -h`, `find --json .`, `find --color .`, `find --completion bash`, `find --docs`: "unknown predicate".
        find.checkLists(
            at("G11 G64", "find -h|"),
            at("G11 G67 E18", "find --js|"),
            at("G11 G46", "find --col|"),
            at("G11 G67", "find --comp|"),
            at("G11 G67", "find --do|"),
        )
        find.assertOffersNone("G11 G64 G67 E22", "find -|", KLAP_BUILT_INS)
        find.assertOffersNone("G64 E22", "find -L|", listOf("-Lh"))
    }

    @Test
    fun `help and version are described in find's words`() = find.checkLists(
        // `find --help`: "--help  display this help and exit", "--version  output version information and exit".
        at("G6b G45", "find --he|", named("--help")),
        at("G6b G45 G66", "find --vers|", named("--version")),
    )

    @Test
    fun `a long find does not have is not offered`() = find.checkLists(
        // `find . -physical`, `-logical`, `-follow-args`, `-debug x`, `-optimise 3`: "unknown predicate" each.
        at("G1a E22", "find --phys|"),
        at("G1a E22", "find --logi|"),
        at("G1a E22", "find --follow-|"),
        at("G1a E22", "find --deb|"),
        at("G1a E22", "find --opt|"),
    )

    @Test
    fun `ignore_readdir_race is spelled as find spells it`() = find.checkLists(
        // `find . -ignore-readdir-race`: "unknown predicate"; `find . -ignore_readdir_race` walks.
        at("G1a G1b E9", "find --ig|", named("--ignore_readdir_race")),
    )

    @Test
    fun `a regex type is any of find's in find's order`() = find.checkLists(
        // `find . -regextype help`: "valid types are ‘findutils-default’, ‘awk’, ‘ed’, ‘egrep’, ‘emacs’,
        // ‘gnu-awk’, ‘grep’, ‘posix-awk’, ‘posix-basic’, ‘posix-egrep’, ‘posix-extended’, ‘posix-minimal-basic’,
        // ‘sed’"; `find . -regextype sed` walks.
        at("G1b G2", "find --regextype=|", *regexTypes(*REGEX_TYPES.toTypedArray()), ordered = true),
        at("G1b G2 G41", "find --regextype |", *REGEX_TYPES.map { Suggestion(it) }.toTypedArray(), ordered = true),
        at("G1b G14", "find --regextype=g|", *regexTypes("gnu-awk", "grep")),
        at("G1b G14", "find --regextype=posix-|", *regexTypes(*POSIX_REGEX_TYPES)),
        at("G1b E17", "find --regextype=sed|", *regexTypes("sed")),
    )

    @Test
    fun `an abbreviated long is an unknown option`() {
        // `find . -regext posix-egrep` and `find . -maxdep 1`: "unknown predicate".
        val spaced = find.suggestionsFor("find --regext ")
        assertTrue(spaced.all { it.value.startsWith(FILES.value) }, "G90 `find --regext |`: offers $spaced")
        find.checkLists(at("G90 G1a", "find --regext=|"))
    }

    @Test
    fun `a value after a built-in find lacks is none of klap's`() {
        // `find --color always .`: "unknown predicate `--color'".
        val spaced = find.suggestionsFor("find --color ")
        assertTrue(spaced.all { it.value.startsWith(FILES.value) }, "G68 `find --color |`: offers $spaced")
        find.checkLists(at("G68", "find --color=|"))
    }

    @Test
    fun `a short of find does not cluster`() = find.checkLists(
        // `find -LP .`, `find -Lh .`, `find -Ltree .`: "unknown predicate"; find matches -L only as a whole word.
        at("G1a E17 E22", "find -L|", named("-L")),
        at("G1a E17 E22", "find -P|", named("-P")),
        at("G1a E17 E22", "find -H|", named("-H")),
        at("G1a E22", "find -LP|"),
    )
}

/** The inputs whose help the name cells compare, each one the fixture declares and find has (F1). */
private val DECLARED: List<Input> = listOf(
    "--maxdepth", "--mindepth", "--regextype", "--files0-from", "--name", "--iname", "--path", "--regex", "--type",
    "--size", "--mtime", "--mmin", "--ctime", "--atime", "--links", "--perm", "--newer", "--user", "--group",
    "--printf", "--exec", "--depth", "--xdev", "--mount", "--noleaf", "--ignore_readdir_race", "--empty",
    "--readable", "--writable", "--executable", "--nouser", "--nogroup", "--print", "--print0", "--ls", "--delete",
    "--prune", "--quit",
).map { long -> PREDICATES.single { it.name == long } }

private val KLAP_BUILT_INS = listOf("-h") + KLAP_LONGS

private val POSIX_REGEX_TYPES = REGEX_TYPES.filter { it.startsWith("posix-") }.toTypedArray()

private const val TRAVERSAL_GROUP = "Traversal options (find's own position-independent set)"

private val AT_DOT: FindInputs = NOTHING_BOUND.copy(operand = listOf("."))

/** Every long find accepts, respelled. */
private val FIND_LONGS: List<String> = LONGS.map { it.name }

/** One Tab press: the matrix [rows] it checks, the line [typed] with `|` at the cursor, and what find accepts there. */
private data class ListCell(
    val rows: String,
    val typed: String,
    val offers: List<Suggestion>,
    val ordered: Boolean = false,
) {
    val line: String = typed.replace("|", "")
    val cursor: Int = typed.indexOf('|')

    override fun toString(): String = "$rows `$typed`"
}

/** A cell for [typed]: the matrix [rows] it checks and what find accepts there. */
private fun at(
    rows: String,
    typed: String,
    vararg offers: Suggestion,
    ordered: Boolean = false,
): ListCell = ListCell(rows, typed, offers.toList(), ordered)

/** The words for [name]: the fixture's help for a long or short find has, find's own for its built-ins. */
private fun helpOf(name: String): String? = (SHORTS + LONGS).firstOrNull { it.name == name }?.help

private fun named(name: String): Suggestion = Suggestion(name, helpOf(name))

private fun regexTypes(vararg values: String): Array<Suggestion> =
    values.map { Suggestion("--regextype=$it", helpOf("--regextype")) }.toTypedArray()

/** Every name find accepts after a dash: the shorts, `-O` with its levels glued (`find -O .` is refused), the longs. */
private fun pool(longsOnly: Boolean): Array<Suggestion> {
    val shorts = if (longsOnly) emptyList() else SHORTS.map { it.name }
    val levels = if (longsOnly) emptyList() else LEVELS
    return (shorts.plus(FIND_LONGS).map(::named) + levels).toTypedArray()
}

/** The cell for [typed], with [rest] of its word after the cursor and [after] past it: every long of find it starts. */
private fun nameCell(
    typed: String,
    rest: String = "",
    after: String = "",
): ListCell {
    val offers = FIND_LONGS.filter { it.startsWith(typed) }.map(::named)
    val rows = "G1a G6a G14 E1 E5 E6" + if (after.isEmpty()) "" else " E21"
    return ListCell(rows, "find $typed|$rest$after", offers)
}

/** For every long the fixture shares with find: the finished word, the last letter after the cursor, words after. */
private val NAME_CELLS: List<ListCell> by lazy {
    DECLARED.map { it.name }.flatMap { long ->
        listOf(
            nameCell(long),
            nameCell(long.dropLast(1), rest = long.takeLast(1)),
            nameCell(long, after = " ."),
        )
    }.filterNot { it.typed == "find --files0-from| ." }
}

/** Tab on [cell] offers exactly its set (E1 E5 E6) once each (G3). */
private fun Cli.assertList(cell: ListCell) {
    val actual = suggestionsFor(cell.line, cell.cursor)
    val expected = if (cell.ordered) cell.offers else cell.offers.sortedBy { it.value }
    val sorted = if (cell.ordered) actual else actual.sortedBy { it.value }
    assertEquals(expected.map { it.value }, sorted.map { it.value }, "$cell: offered values (find's set first)")
    assertEquals(expected, sorted, "$cell: descriptions (find's first)")
    assertEquals(actual.size, actual.toSet().size, "$cell: a suggestion is offered twice: $actual")
}

private fun Cli.checkLists(vararg cells: ListCell) = checkCells(cells.toList()) { assertList(it) }
