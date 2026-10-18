package com.fromwau.klap.fixture.cp

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.cp.CpLineContext.Cell
import com.fromwau.klap.fixture.cp.CpLineContext.FILES
import com.fromwau.klap.fixture.cp.CpLineContext.assertCell
import com.fromwau.klap.fixture.cp.CpLineContext.assertOffersNone
import com.fromwau.klap.fixture.cp.CpLineContext.at
import com.fromwau.klap.fixture.cp.CpLineContext.checkContext
import com.fromwau.klap.fixture.cp.CpLineContext.cpHelp
import com.fromwau.klap.fixture.cp.CpLineContext.name
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.acceptSuggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import com.fromwau.klap.testing.tab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The suggestion list Tab gives on a cp line and what every completion cell asserts about it (tables G and E of the
 * testing matrix): which names are offered and which not, their order, their descriptions, what one Tab inserts, the
 * line once a suggestion is taken, and the wire. Every expectation is what real cp 9.12 accepts at the cursor, in the
 * words of `cp --help`; a list cell compares whole suggestions sorted by value, since the shell sorts, and the order
 * klap hands over is pinned on its own.
 */
class CpSuggestionListTest {

    private val cp = cpCli().cli

    private val parity = ParitySuite(cpCli())

    @Test
    fun `a dash offers cp's names in cp's words and nothing else`() = cp.checkContext(
        // `cp --help` lists every name with its wording; `cp --recursive-r a b`: "unrecognized option".
        at("G1a G1b G4 G6a G45 E5 E6", "cp -|", *pool(longsOnly = false)),
        at("G1a G1b G4 G6a G45 E5 E6", "cp --|", *pool(longsOnly = true)),
        at("G1a G4 E21", "cp -| a b", *pool(longsOnly = false)),
        at("G1a G4 E21", "cp --| a b", *pool(longsOnly = true)),
    )

    @Test
    fun `a long name completes to every long of cp it starts in cp's words`() =
        // `cp --help`: "explain how a file is copied.  Implies -v", "prompt before overwrite (overrides a previous -n
        // option)", --force's and --remove-destination's two-line texts, "-P, --no-dereference  never follow symbolic
        // links in SOURCE", "--help  display this help and exit", "--version  output version information and exit",
        // and so on; `cp --rec a b` is --recursive.
        checkCells(NAME_CELLS) { cp.assertCell(it) }

    @Test
    fun `a name cp does not have offers nothing`() = cp.checkContext(
        // `cp --zzz a b`: "unrecognized option '--zzz'".
        at("G15 E12", "cp --zzz|"),
        // `cp --make a b`: unrecognized, though --backup's help says "make a backup".
        at("G54", "cp --make|"),
        // `cp --VERBOSE a b`: cp's names are matched in their case.
        at("G77", "cp --VERB|"),
        // `cp --help-all a b`: "unrecognized option '--help-all'".
        at("G11 G65", "cp --help-|"),
        // `cp --json`, `cp --color=always`, `cp --completion bash`, `cp --docs`: "unrecognized option" each.
        at("G11 G67", "cp --js|"),
        at("G11", "cp --col|"),
        at("G11 G67", "cp --comp|"),
        at("G11 G67", "cp --do|"),
        // `cp -h a b` and `cp -vh a b`: "invalid option -- 'h'".
        at("G64", "cp -h|"),
        at("G64", "cp -vh|"),
        // `cp --recursive-r a b`: "unrecognized option '--recursive-r'".
        at("G1a E22", "cp --recursive-|"),
    )

    @Test
    fun `a value-taking long is offered bare and described by its own help`() = cp.checkContext(
        // `cp --help`: `--suffix=SUFFIX` and `--backup[=CONTROL]`; fish's own cp completion shows `--sparse` bare too.
        // The fixture declares --backup, -b and --suffix in `group("Backup")`.
        at("G29 G48", "cp --suf|", name("--suffix")),
        at("G30 G48", "cp --backu|", name("--backup")),
        at("G29 E21", "cp --suf| .bak a b", name("--suffix")),
    )

    @Test
    fun `a single dash offers both spellings and two dashes only the longs`() {
        // `cp --help`: `-v, --verbose` and the rest; a short is never a `--` word.
        val single = cp.suggestionsFor("cp -").map { it.value }
        val double = cp.suggestionsFor("cp --").map { it.value }
        assertEquals(single.filter { it.startsWith("--") }, double, "G4 `cp --|` against the longs of `cp -|`")
        val bothSpellings = INPUTS.filter { it.long != null && it.shorts.isNotEmpty() }
        checkCells(bothSpellings) { input ->
            val wanted = listOf(input.long!!) + input.shorts.map { "-$it" }
            assertTrue(single.containsAll(wanted), "G4 `cp -|`: must offer $wanted, offered $single")
        }
    }

    @Test
    fun `names come grouped by kind and each input's spellings together`() {
        val names = cp.suggestionsFor("cp -").map { it.value }
        val valueNames = INPUTS.filter { it.kind is Kind.Value }.flatMap(::spellings).filter { it in names }
        val flagNames = INPUTS.filter { it.kind is Kind.Flag }.flatMap(::spellings).filter { it in names }
        // The fixture declares --update after --remove-destination and the backup options after -H; klap still
        // lists every option before every flag. The shell sorts the list again, so cp has no order of its own here.
        assertTrue(
            valueNames.maxOf(names::indexOf) < flagNames.minOf(names::indexOf),
            "G16 `cp -|`: every option name before every flag name, got $names",
        )
        val dereference = listOf("--dereference", "-L", "--no-dereference", "-P")
        val at = names.indexOf(dereference.first())
        assertEquals(dereference, names.subList(at, at + dereference.size), "G17 `cp -|`: longs, shorts, negations")
        assertEquals(listOf("--help", "--version"), names.takeLast(2), "G18 `cp -|`: the built-ins come last")
        val continued = cp.suggestionsFor("cp -v").map { it.value }
        assertEquals(listOf("-vS", "-vt"), continued.take(2), "G21 `cp -v|`: value letters first")
    }

    @Test
    fun `choices come in the order cp documents them`() {
        // `cp --help`: "none, off / numbered, t / existing, nil / simple, never", UPDATE={all,none,none-fail,older},
        // and the sparse and reflink paragraphs name auto, always, never in that order.
        assertEquals(
            BACKUP_CONTROLS.map { attached("--backup", it) },
            cp.suggestionsFor("cp --backup="),
            "G2 `cp --backup=|`",
        )
        val orders = listOf(
            "--update" to listOf("all", "none", "none-fail", "older"),
            "--sparse" to listOf("auto", "always", "never"),
            "--reflink" to listOf("auto", "always", "never"),
        )
        checkCells(orders) { (long, values) ->
            assertEquals(
                values.map { "$long=$it" },
                cp.suggestionsFor("cp $long=").map { it.value },
                "G2 `cp $long=|`",
            )
        }
    }

    @Test
    fun `no suggestion is offered twice`() {
        val lines = listOf("cp -", "cp --", "cp -r", "cp -L", "cp --re", "cp --backup=")
        checkCells(lines) { line ->
            val values = cp.suggestionsFor(line).map { it.value }
            assertEquals(values.distinct(), values, "G3 G27 `$line|`")
        }
    }

    @Test
    fun `a cluster continues with cp's letters in cp's words`() = cp.checkContext(
        // fish's own `complete -C "cp -v"` lists -va to -vZ; the descriptions are cp --help's. `cp -vr` is -R.
        at("G5 G21 G32 G6a", "cp -v|", *continuations("-v")),
        at("G5 G21 G32 G6a", "cp -x|", *continuations("-x")),
        at("G5 G21 G32 G6a", "cp -rv|", *continuations("-rv")),
        at("G5 G21 G32 G6a G44", "cp -L|", *continuations("-L")),
        at("G5 G6a E21", "cp -d| a b", *continuations("-d")),
    )

    @Test
    fun `a short is described alike alone and in a cluster`() {
        val alone = cp.suggestionsFor("cp -").filter { it.value.length == 2 }.associate { it.value[1] to it }
        val continued = cp.suggestionsFor("cp -v").associate { it.value.last() to it }
        checkCells((alone.keys - 'v').toList()) { letter ->
            assertEquals(
                alone.getValue(letter).description,
                continued[letter]?.description,
                "G32 `cp -|` -$letter against `cp -v|` -v$letter",
            )
        }
    }

    @Test
    fun `a spaced choice value carries no description`() = cp.checkContext(
        // `cp --sparse never a b` exits 0. The attached form is described by the option's help (G41, disputed).
        at("G41 G7", "cp --sparse |", Suggestion("auto"), Suggestion("always"), Suggestion("never")),
        at("G41 G7 G14", "cp --sparse al|", Suggestion("always")),
        at("G41 G7 E21", "cp --sparse | a b", Suggestion("auto"), Suggestion("always"), Suggestion("never")),
    )

    @Test
    fun `an attached backup control is filtered on the part after the equals sign`() = cp.checkContext(
        // `cp --backup=nu a b` exits 0; `cp --backup=n a b` is "ambiguous argument 'n' for 'backup type'", so Tab
        // offers every control starting n and puts in nothing.
        at("G14 G80 E10", "cp --backup=n|", *backupControls("none", "numbered", "nil", "never")),
        at("G80 E9", "cp --backup=nu|", attached("--backup", "numbered")),
        at("G80", "cp --backup=nu|x", attached("--backup", "numbered")),
        at("G80 E21", "cp --backup=nu| a b", attached("--backup", "numbered")),
        // `cp --backup=numbered= a b`: "invalid argument 'numbered=' for 'backup type'"; `cp --update=none= a b`
        // the same.
        at("G84", "cp --backup=numbered=|"),
        at("G84", "cp --update=none=|"),
    )

    @Test
    fun `a directory value is the shell's file completion alone`() = cp.checkContext(
        // `cp --targ=d a` and `cp -vtd a` copy into d; `cp -t -x a`: "target directory '-x'", so -x is the value.
        at("G12 G72 E11", "cp --target-directory=|", filesAfter("--target-directory=")),
        at("G72", "cp --targ=|", filesAfter("--targ=")),
        at("G72", "cp -td|", filesAfter("-t")),
        at("G72 G85", "cp -vtd|", filesAfter("-vt")),
        at("G72 G85", "cp -vtdi|", filesAfter("-vt")),
        at("G72", "cp -t |", filesAfter()),
        at("G82", "cp -t -|", filesAfter()),
        at("G75 G84", "cp --target-directory=a=b|", filesAfter("--target-directory=")),
        at("G72 E21", "cp -td| a", filesAfter("-t")),
        // A glued -t with nothing after it is still the name word: `cp -t|` is the option.
        at("G73", "cp -t|", name("-t")),
    )

    @Test
    fun `a value-taking short closing a cluster leaves nothing to offer`() = cp.checkContext(
        // `cp -vrS .k a b` takes .k as the suffix, `cp -rt a b` takes a as the directory: the value is the next word.
        at("G89", "cp -rS|"),
        at("G89", "cp -rt|"),
        // `cp -vSfo a b` made the backup `bfo`: a glued free text has no candidates.
        at("G85", "cp -vSfo|"),
        at("G82", "cp -S -|"),
    )

    @Test
    fun `a choice in capitals is not offered`() = cp.checkContext(
        // `cp --sparse=ALWAYS a b`: "invalid argument 'ALWAYS' for '--sparse'"; `--backup=NUMBERED`, `--update=OLDER`
        // and `--reflink=Always` are refused alike: cp matches its values in their case.
        at("G13 G76 E13 E22", "cp --sparse=AL|"),
        at("G13 G76 E13 E22", "cp --backup=NU|"),
        at("G13 G76 E13 E22", "cp --update=OL|"),
        at("G13 G76 E13 E22", "cp --reflink=Al|"),
    )

    @Test
    fun `tab puts in what the suggestions share and no further`() {
        val cells = listOf(
            // E9: one suggestion equal to the word leaves the line.
            "cp --backup|" to "cp --backup",
            "cp --st|" to "cp --strip-trailing-slashes",
            // E10: nothing shared past the typed word.
            "cp --no-|" to "cp --no-",
            "cp --s|" to "cp --s",
            // E18: Tab gives `--de`, which cp refuses as it stands: "option '--de' is ambiguous; possibilities:
            // '--debug' '--dereference'".
            "cp --d|" to "cp --de",
            // E8 inside a word: the typed part is replaced and the rest of the word stays, as bash does; zsh, fish and
            // PowerShell replace the whole word (E21).
            "cp --st|x" to "cp --strip-trailing-slashesx",
            "cp --st| a b" to "cp --strip-trailing-slashes a b",
            // E8 on no word and at a word's start: an operand, the shell's file completion.
            "cp | a b" to "cp  a b",
            "cp |--verbose" to "cp --verbose",
        )
        checkCells(cells) { (typed, expected) ->
            assertEquals(expected, cp.tab(typed.replace("|", ""), typed.indexOf('|')), "E8 `$typed`")
        }
    }

    @Test
    fun `a cursor on no word or at a word's start is an operand`() = cp.checkContext(
        at("E1", "cp | a b", filesAfter()),
        at("E1", "cp |--verbose", filesAfter()),
        at("E1 G83", "cp -- -|", filesAfter()),
    )

    @Test
    fun `a finished word asked again offers itself`() {
        cp.checkContext(
            at("E17", "cp --verbose|", name("--verbose")),
            at("E17", "cp --backup=numbered|", attached("--backup", "numbered")),
        )
        // E17 cluster half: `cp -v|` offers continuations only, never `-v` itself.
        cp.assertOffersNone("E17 G99", "cp -v|", listOf("-v", "-vv"))
    }

    @Test
    fun `a line once a suggestion is taken binds as cp reads it`() {
        // Each accepted line run against real cp in a throwaway directory exits 0.
        val cells = listOf(
            Triple("cp --backup=nu| a b", "--backup=numbered", A_TO_B.copy(backup = BackupControl.Numbered)),
            Triple("cp --spa=al| a b", "--spa=always", A_TO_B.copy(sparse = When.Always)),
            // `cp --suffix .bak a b` makes b.bak: a suffix asks for a backup under cp's default control.
            Triple("cp --suf| .bak a b", "--suffix", A_TO_B.copy(backup = BackupControl.Existing, suffix = ".bak")),
            Triple("cp --verb| a b", "--verbose", A_TO_B.copy(verbose = true)),
            Triple("cp --keep| a b", "--keep-directory-symlink", A_TO_B.copy(keepDirectorySymlink = true)),
            Triple("cp --st| a b", "--strip-trailing-slashes", A_TO_B.copy(stripTrailingSlashes = true)),
            Triple("cp -v| a b", "-vx", A_TO_B.copy(verbose = true, oneFileSystem = true)),
            Triple("cp --no-d| a b", "--no-dereference", A_TO_B.copy(dereference = Dereference.NEVER)),
        )
        checkCells(cells) { (typed, value, expected) ->
            val line = typed.replace("|", "")
            val cursor = typed.indexOf('|')
            val taken = cp.suggestionsFor(line, cursor).firstOrNull { it.value == value }
            assertTrue(taken != null, "E15 `$typed`: must offer $value")
            val accepted = acceptSuggestion(line, taken, cursor)
            parity.binds(*accepted.split(' ').drop(1).toTypedArray(), expected = expected, because = "E15 `$typed`")
        }
    }

    @Test
    fun `a name taken and then its value taken bind the value`() {
        val named = cp.suggestionsFor("cp --spars").single()
        val line = acceptSuggestion("cp --spars", named) + " "
        val values = cp.suggestionsFor(line)
        assertEquals(listOf("auto", "always", "never").map { Suggestion(it) }, values, "E16 `$line|`")
        val chosen = acceptSuggestion(line, values[2]) + " a b"
        parity.binds(
            *chosen.split(' ').drop(1).toTypedArray(),
            expected = A_TO_B.copy(sparse = When.Never),
            because = "E16 `$chosen`",
        )
    }

    @Test
    fun `a cluster continuation taken continues with the letters left`() {
        val taken = cp.suggestionsFor("cp -v").first { it.value == "-vx" }
        val line = acceptSuggestion("cp -v", taken)
        val next = cp.suggestionsFor(line).map { it.value }
        assertTrue("-vxa" in next && "-vxS" in next, "E16 `$line|`: must continue, offered $next")
        cp.assertOffersNone("E16 E22", "$line|", listOf("-vxv", "-vxx", "-vxh", "-vx"))
    }

    @Test
    fun `a completion script's call reaches cp as its own words`() {
        // cp declines klap's completion, so there is no __complete for a script to call.
        parity.rejects("__complete", "--cursor", "1", "--", "-", because = "real cp: unrecognized option '--cursor'")
    }

    @Test
    fun `a name's description is the text its help row shows`() {
        val page = parity.helpPage("--help", because = "cp --help")
        val spaces = Regex(" +")
        val flat = page.lines().joinToString(" ") { it.trim() }.replace(spaces, " ")
        checkCells(cp.suggestionsFor("cp -")) { offered ->
            // Spaces collapsed as on the page, since cp's own words put two after a full stop.
            val text = offered.description.orEmpty().replace(spaces, " ")
            assertTrue(text.isNotEmpty() && text in flat, "E20 `cp -|` ${offered.value}: \"$text\" is not on --help")
        }
    }

    @Test
    fun `a description carries none of the help page's annotations`() {
        val annotations = listOf(
            "(default:", "(bare:", "(optional)", "(last of", "(one of", "(required)", "(repeatable)", "(absent when",
        )
        checkCells(cp.suggestionsFor("cp -") + cp.suggestionsFor("cp --backup=")) { offered ->
            val text = offered.description.orEmpty()
            assertTrue(annotations.none { it in text }, "G47 ${offered.value}: \"$text\" carries an annotation")
        }
    }

    @Test
    fun `asking twice gives the same answer`() {
        val lines = listOf("cp -", "cp --backup=", "cp --sparse ", "cp -v", "cp -td")
        checkCells(lines) { line ->
            assertEquals(cp.suggestionsFor(line), cp.suggestionsFor(line), "E24 `$line|`")
        }
    }

    @Test
    fun `the negation is spelled as cp spells it and offered after its positive`() {
        // `cp --help`: the only `--no-` names are --no-preserve, --no-clobber, --no-dereference and
        // --no-target-directory; `cp -L --no-dereference a b` exits 0, the later one winning.
        val noNames = listOf("--no-clobber", "--no-dereference", "--no-preserve", "--no-target-directory")
        assertEquals(noNames, cp.suggestionsFor("cp --no-").map { it.value }.sorted(), "G59 `cp --no-|`")
        cp.checkContext(at("G44 G62", "cp -L --no-d|", name("--no-dereference")))
    }

    @Test
    fun `an input already given is offered again as cp takes it again`() {
        // `cp --verbose --verbose a b` and `cp -vv a b` exit 0.
        val offered = cp.suggestionsFor("cp -v -").map { it.value }
        assertTrue("-v" in offered && "--verbose" in offered, "G99 `cp -v -|`: must offer -v again, offered $offered")
    }

    @Test
    fun `a built in cp does not have is not offered`() {
        cp.assertOffersNone("G11 G64 G65 G67 E22", "cp -|", KLAP_BUILT_INS)
        cp.assertOffersNone("G64 E22", "cp -r|", listOf("-rh"))
        assertEquals(listOf("--version"), cp.suggestionsFor("cp --vers").map { it.value }, "G66 `cp --vers|`")
    }

    @Test
    fun `a value after a refused option is none of klap's or the option's own`() {
        // `cp --color a b`, `cp --completion bash a b`, `cp --json a b`: "unrecognized option"; `cp --re a b`: "option
        // '--re' is ambiguous; possibilities: '--recursive' '--remove-destination' '--reflink'".
        val lines = listOf("cp --color |", "cp --completion |", "cp --json |", "cp --docs |", "cp --re |")
        checkCells(lines) { typed ->
            val offered = cp.suggestionsFor(typed.replace("|", ""), typed.indexOf('|'))
            assertTrue(offered.all { it.value.startsWith(FILES.value) }, "G68 G90 `$typed`: offers $offered")
        }
    }
}

private val KLAP_BUILT_INS = listOf("-h", "--help-all", "--json", "--color", "--completion", "--docs")

/** [value] attached to [long], described by cp's help for [long]. */
private fun attached(
    long: String,
    value: String,
): Suggestion = Suggestion("$long=$value", cpHelp(long))

private fun backupControls(vararg values: String): Array<Suggestion> =
    values.map { attached("--backup", it) }.toTypedArray()

private fun spellings(input: Input): List<String> = listOfNotNull(input.long) + input.shorts.map { "-$it" }

/** Every name cp accepts after a dash, in cp's words: each input's long and shorts, then `--help` and `--version`. */
private fun pool(longsOnly: Boolean): Array<Suggestion> = (INPUTS.flatMap(::spellings) + "--help" + "--version")
    .filter { !longsOnly || it.startsWith("--") }
    .map(::name)
    .toTypedArray()

/** What cp continues [cluster] with: each of its letters not yet in it, in cp's words. */
private fun continuations(cluster: String): Array<Suggestion> = INPUTS
    .flatMap { it.shorts }
    .filter { it !in cluster.drop(1) }
    .map { Suggestion(cluster + it, inputFor(it)!!.help) }
    .toTypedArray()

/** The cell for [typed], with [rest] of its word after the cursor and [after] past it: every long of cp it starts. */
private fun nameCell(
    typed: String,
    rest: String = "",
    after: String = "",
): Cell {
    val offers = CP_LONGS.filter { it.startsWith(typed) }.map(::name)
    val rows = "G1a G6a G14 E1 E5 E6" + if (after.isEmpty()) "" else " E21"
    return Cell(rows, "cp $typed|$rest$after", offers)
}

/** For every long of cp: the finished word (E17), the word with its last letter after the cursor, and words after. */
private val NAME_CELLS: List<Cell> by lazy {
    CP_LONGS.flatMap { long ->
        listOf(
            nameCell(long),
            nameCell(long.dropLast(1), rest = long.takeLast(1)),
            nameCell(long, after = " a b"),
        )
    }
}
