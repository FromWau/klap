package com.fromwau.klap.fixture.find

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.find.FindCompletionSupport.CompletionCell
import com.fromwau.klap.fixture.find.FindCompletionSupport.DEBUG_OPTIONS
import com.fromwau.klap.fixture.find.FindCompletionSupport.FILES
import com.fromwau.klap.fixture.find.FindCompletionSupport.REGEX_TYPES
import com.fromwau.klap.fixture.find.FindCompletionSupport.TYPE_LETTERS
import com.fromwau.klap.fixture.find.FindCompletionSupport.assertFind
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter
import kotlin.test.Ignore
import kotlin.test.Test

/*
 * Every expectation is what real GNU find 4.11.0 (`/usr/bin/find`) accepts at the cursor, read through the fixture's
 * respelling (decision F1): find's `-newer` is the fixture's `--newer`, its attached `--newer=` form included (F1a),
 * while `-P -L -H -D -O` are compared word for word. A cell names its V rows (and the K, L or G rows it meets) and
 * is typed with `|` at the cursor. The evidence is a run of `/usr/bin/find` in a throwaway directory unless a
 * comment says `man find`.
 */

private val NOTHING: Array<Suggestion> = emptyArray()

private const val REGEXTYPE_HELP = "syntax used by --regex"

private const val TYPE_HELP = "file type: one or more of b c d p f l s (comma-separated)"

private const val OPTIMISE_HELP = "query optimisation level"

/** One Tab press on [typed] (`|` at the cursor), what find would accept there, and the [rows] it covers. */
private data class ValueCell(
    val rows: String,
    val typed: String,
    val offers: List<Suggestion>,
) {
    val line: String = typed.replace("|", "")
    val cursor: Int = typed.indexOf('|')

    override fun toString(): String = "$rows `$typed`"
}

private fun at(
    rows: String,
    typed: String,
    vararg offers: Suggestion,
): ValueCell = ValueCell(rows, typed, offers.toList())

/** Where a fixture predicate's value comes from, as real find takes it. */
private sealed interface Source {
    data object Path : Source

    data object Text : Source

    data class Choice(
        val values: List<String>,
        val help: String,
    ) : Source
}

/** A predicate's value slot: its fixture spelling [long], its [source] and a [partial] value find accepts. */
private data class Slot(
    val long: String,
    val source: Source,
    val partial: String,
)

private val PATH_SLOTS = listOf(
    // `find . -newer a` and `find . -newer sub` both run: a file or a directory.
    Slot("--newer", Source.Path, "a"),
    // `find -files0-from a` runs; `-files0-from nonexist`: "cannot open ‘nonexist’ for reading".
    Slot("--files0-from", Source.Path, "a"),
)

private val TEXT_SLOTS = listOf(
    Slot("--name", Source.Text, "*.k"),
    Slot("--iname", Source.Text, "*.K"),
    Slot("--path", Source.Text, "./s"),
    Slot("--regex", Source.Text, ".*"),
    Slot("--printf", Source.Text, "%p"),
    Slot("--exec", Source.Text, "ec"),
    Slot("--user", Source.Text, "ro"),
    Slot("--group", Source.Text, "wh"),
    Slot("--maxdepth", Source.Text, "3"),
    Slot("--mindepth", Source.Text, "1"),
    Slot("--size", Source.Text, "+1"),
    Slot("--mtime", Source.Text, "+7"),
    Slot("--mmin", Source.Text, "-5"),
    Slot("--ctime", Source.Text, "2"),
    Slot("--atime", Source.Text, "+1"),
    Slot("--links", Source.Text, "2"),
    Slot("--perm", Source.Text, "64"),
)

private val REGEXTYPE = Slot("--regextype", Source.Choice(REGEX_TYPES, REGEXTYPE_HELP), "posix-e")

private val TYPE = Slot("--type", Source.Choice(TYPE_LETTERS, TYPE_HELP), "d")

/** What [slot] offers for [typed] in a word that starts with [head]: a choice is described only when attached (G7). */
private fun offers(
    slot: Slot,
    head: String,
    typed: String,
): List<Suggestion> = when (val source = slot.source) {
    Source.Path -> listOf(filesAfter(head))
    Source.Text -> emptyList()
    is Source.Choice -> source.values
        .filter { it.startsWith(typed) }
        .map { Suggestion(head + it, source.help.takeIf { head.isNotEmpty() }) }
}

/** The cells of [slot] in every form and cursor column, with [partial] typed where a value is. */
private fun cellsOf(
    slot: Slot,
    partial: String = slot.partial,
): List<ValueCell> = emptyCellsOf(slot) + typedCellsOf(slot, partial)

/** The cells of [slot] where nothing of the value is typed yet. */
private fun emptyCellsOf(slot: Slot): List<ValueCell> {
    val p = "find ${slot.long}"
    val attached = "${slot.long}="
    return listOf(
        ValueCell("V1a V2d V3a no word", "$p |", offers(slot, "", "")),
        ValueCell("V1a V2d V3a no word L2", "$p | .", offers(slot, "", "")),
        ValueCell("V1a V2d V3a start", "$p |zz", offers(slot, "", "")),
        ValueCell("V1a V2d V3b attached", "find $attached|", offers(slot, attached, "")),
    )
}

/** The cells of [slot] with [partial] typed, spaced and attached, at the end and inside the word. */
private fun typedCellsOf(
    slot: Slot,
    partial: String = slot.partial,
): List<ValueCell> {
    val p = "find ${slot.long}"
    val attached = "${slot.long}="
    return listOf(
        ValueCell("V1a V2a V3a end", "$p $partial|", offers(slot, "", partial)),
        ValueCell("V1a V2a V3a inside", "$p $partial|zz", offers(slot, "", partial)),
        ValueCell("V1a V2a V3a end L2", "$p $partial| .", offers(slot, "", partial)),
        ValueCell("V1a V2d V3b attached", "find $attached$partial|", offers(slot, attached, partial)),
        ValueCell("V1a V2d V3b attached inside", "find $attached$partial|zz", offers(slot, attached, partial)),
        ValueCell("V1a V2d V3b attached L2", "find $attached$partial| .", offers(slot, attached, partial)),
    )
}

/** Tab on [cell] offers exactly its set once each. */
private fun Cli.assertValues(cell: ValueCell) =
    assertFind(CompletionCell(cell.rows, cell.rows, cell.line, cell.cursor, cell.offers.toSet()))

private fun Cli.checkValues(vararg cells: ValueCell) = checkValues(cells.toList())

private fun Cli.checkValues(cells: List<ValueCell>) = checkCells(cells) { assertValues(it) }

private fun spaced(vararg values: String): Array<Suggestion> = values.map { Suggestion(it) }.toTypedArray()

private fun attached(
    head: String,
    help: String,
    vararg values: String,
): Array<Suggestion> = values.map { Suggestion(head + it, help) }.toTypedArray()

/**
 * Where the candidates for a find value come from (V of the testing matrix): the shell's file completion for
 * `--newer`, `--files0-from` and every starting point, nothing for a free text predicate value, and find's own
 * regex types, type letters and debug keys. Every expectation is what real find 4.11.0 accepts at the cursor; the
 * record of what the respelling keeps is [FindValueCandidatesTargetsTest].
 */
class FindValueCandidatesTest {

    private val find = findCli().cli

    @Test
    fun `a file value completes through the shell in every form`() =
        // `find . -files0-from a`: "file operands cannot be combined with -files0-from", and `find -files0-from a .`
        // is "paths must precede expression", so with a starting point after it no list file makes the line run.
        find.checkValues(
            PATH_SLOTS.flatMap { cellsOf(it) }.map { cell ->
                val beside = cell.typed.startsWith("find --files0-from") && cell.typed.endsWith(" .")
                if (beside) cell.copy(offers = emptyList()) else cell
            },
        )

    @Test
    fun `a free text value has no candidates in any form`() =
        // `find . -name -perm`, `-size`, `-user`, ... each takes any word as far as the parse goes; `-user root` and
        // `-user 0` run, so no closed set to offer (V1a).
        find.checkValues(TEXT_SLOTS.flatMap { cellsOf(it) })

    @Test
    fun `a regex type prefix that only find's declared types extend offers them`() =
        // `find . -regextype posix-egrep` and `posix-extended` run; no other type starts with posix-e, gnu or f.
        find.checkValues(
            typedCellsOf(REGEXTYPE) +
                typedCellsOf(REGEXTYPE, "gnu") +
                typedCellsOf(REGEXTYPE, "findutils-") +
                at("V2a", "find --regextype posix-b|", *spaced("posix-basic")) +
                at("V2d", "find --regextype=posix-b|", *attached("--regextype=", REGEXTYPE_HELP, "posix-basic")),
        )

    @Test
    fun `a regex type typed in another case or abbreviated is not offered`() = find.checkValues(
        // `find . -regextype POSIX-EGREP`: "Unknown regular expression type ‘POSIX-EGREP’"; types match in their own
        // case only (V2c and V13 settled for G13). `find . -regextype posix-e`: "Unknown regular expression type
        // ‘posix-e’", so a value never abbreviates either.
        at("V13 V2c", "find --regextype PO|", *NOTHING),
        at("V13 V2c", "find --regextype=PO|", *NOTHING),
        at("V13 V2c", "find --regextype Posix-e|", *NOTHING),
        at("V13 V2c", "find --regextype=GNU|", *NOTHING),
        at("V13 V2c L2", "find --regextype SED| .", *NOTHING),
    )

    @Test
    fun `a value typed with dashes is completed by its predicate's source`() = find.checkValues(
        // `find . -newer -x`: "‘-x’: No such file or directory"; `find -files0-from -x`: "cannot open ‘-x’", so the
        // word is the file.
        at("V63", "find --newer -|", FILES),
        at("V63", "find --newer --h|", FILES),
        at("V63", "find --files0-from -|", FILES),
        // `find . -regextype -x`: "Unknown regular expression type ‘-x’": no type starts with a dash.
        at("V63", "find --regextype -|", *NOTHING),
        at("V63", "find --regextype --h|", *NOTHING),
        // `find . -perm -644`, `-size -100c`, `-mtime -1` take the dash as a comparison: free text.
        at("V63 V1a", "find --perm -|", *NOTHING),
        at("V63 V1a", "find --size -|", *NOTHING),
        at("V63 V1a", "find --mtime -|", *NOTHING),
        at("V63 V1a", "find --name -|", *NOTHING),
    )

    @Test
    fun `a word taken as an earlier value leaves the cursor on a starting point`() = find.checkValues(
        // `find . -name -newer` takes -newer as the pattern; `find -D -x .`: "Ignoring unrecognised debug flag ‘-x’",
        // so the word after -D is its value. The cursor then stands on a starting point (order is F1's record).
        at("V98", "find --name --newer |", FILES),
        at("V98", "find --printf --newer |", FILES),
        at("V98 L2", "find --name --newer | .", FILES),
        at("V98", "find -D --newer |", FILES),
    )

    @Test
    fun `every starting point completes through the shell`() = find.checkValues(
        // `find a b` lists both: as many starting points as given, each any file (V24).
        at("V24", "find |", FILES),
        at("V24", "find a |", FILES),
        at("V24", "find a b|", FILES),
        at("V24", "find a b|zz", FILES),
        at("V24 L2", "find a | b", FILES),
        at("V24", "find -L |", FILES),
        at("V24", "find -O3 |", FILES),
        at("V24", "find -D tree |", FILES),
    )

    @Test
    fun `a dash led word after -- completes from the operand slot and never offers names`() = find.checkValues(
        // `find -- a` lists a. After the fixture's `--` the words are its raw expression (F1); real find would read
        // `find -- -x` as "unknown predicate `-x'". Either way no option name belongs there (V61, K14).
        at("V61", "find -- |", FILES),
        at("V61", "find -- -|", FILES),
        at("V61", "find . -- -na|", FILES),
        at("V61", "find . -- -name x -|", FILES),
    )

    @Test
    fun `a repeated free text predicate is a value slot again`() = find.checkValues(
        // `find . -name a -name b` runs: a repeat is another test (S3), and its value is free text.
        at("V24 S3", "find --name a --name |", *NOTHING),
        at("V24 S3", "find --exec rm --exec |", *NOTHING),
        at("V24 S3 L2", "find --name a --name | .", *NOTHING),
    )

    @Test
    fun `-D takes its value from the next word and offers itself as a name`() = find.checkValues(
        // `find -D tree .` runs, while `find -Dtree .` is "unknown predicate `-Dtree'": -D never takes a glued value
        // (V99 settled for find: `-D` is a whole word whose value is the next one).
        at("V99 K6", "find -D|", Suggestion("-D", "print diagnostics (comma-separated)")),
        at("V99 K6 L2", "find -D| tree .", Suggestion("-D", "print diagnostics (comma-separated)")),
        at("V3d V1a", "find -Dt|", *NOTHING),
        at("V3d V1a", "find -Dtree|", *NOTHING),
    )

    @Test
    fun `a built in find does not have leaves the next word to the starting points`() = find.checkValues(
        // `find . --completion bash` and `find . --docs x`: "unknown predicate". An unknown option claims no word
        // (decision K47), so no shell name is offered (V21) and the word is a starting point.
        at("V21 K47", "find --completion |", FILES),
        at("V21 K47", "find --completion b|", FILES),
        at("V21 K47", "find --docs |", FILES),
    )

    @Test
    fun `the regex type offers every type find takes in every form`() =
        // `find . -regextype help` lists 13 types, each of which `find . -regextype <type>` runs with.
        find.checkValues(
            emptyCellsOf(REGEXTYPE) +
                typedCellsOf(REGEXTYPE, "e") +
                typedCellsOf(REGEXTYPE, "po") +
                typedCellsOf(REGEXTYPE, "posix-m") +
                at("V2a", "find --regextype s|", *spaced("sed")) +
                at("V2a", "find --regextype a|", *spaced("awk")),
        )

    @Test
    fun `the file type offers the letters find takes`() = find.checkValues(
        // `man find` -type: b c d p f l s D; `-type D`: "not supported because Solaris doors are not supported on the
        // platform", `-type F`: "Unknown argument to -type: F", `-type f,d` runs, `-type f,f`: "Duplicate file type
        // 'f'", `-type ,f`: "Unknown argument to -type: ,".
        *cellsOf(TYPE).toTypedArray(),
        at("V2a", "find --type d,|", *spaced("d,b", "d,c", "d,p", "d,f", "d,l", "d,s")),
        at("V2a", "find --type d,f|", *spaced("d,f")),
        at("V2d", "find --type=d,|", *attached("--type=", TYPE_HELP, "d,b", "d,c", "d,p", "d,f", "d,l", "d,s")),
        at("V13", "find --type F|", *NOTHING),
        at("V2a", "find --type D|", *NOTHING),
        at("V2a", "find --type ,|", *NOTHING),
    )

    @Test
    fun `the debug option offers the keys find takes`() = find.checkValues(
        // `find -D help` lists the nine keys; `-D tree,search` and `-D tree,tree` run, `-D TREE`: "Ignoring
        // unrecognised debug flag ‘TREE’".
        at("V2a", "find -D |", *spaced(*DEBUG_OPTIONS.toTypedArray())),
        at("V2a L2", "find -D | .", *spaced(*DEBUG_OPTIONS.toTypedArray())),
        at("V2a", "find -D |zz", *spaced(*DEBUG_OPTIONS.toTypedArray())),
        at("V2a", "find -D t|", *spaced("time", "tree")),
        at("V2a", "find -D t|zz", *spaced("time", "tree")),
        at("V2a", "find -D s|", *spaced("search", "stat")),
        at("V2a", "find -D tree,|", *spaced(*DEBUG_OPTIONS.map { "tree,$it" }.toTypedArray())),
        at("V2a", "find -D tree,st|", *spaced("tree,stat")),
        at("V13", "find -D TR|", *NOTHING),
    )

    @Test
    fun `an abbreviated predicate is an unknown option whose next word is a starting point`() = find.checkValues(
        // `find . -regext egrep`, `-newe a`, `-files0 a`: "unknown predicate" (ruling F1b). An unknown option claims
        // no word (K47), so spaced the word is a starting point; attached it is nothing (K46).
        at("V95 K47", "find --regext |", FILES),
        at("V95 K47", "find --regext po|", FILES),
        at("V95 K47", "find --max |", FILES),
        at("V95 K47", "find --new |", FILES),
        at("V95 K46 V3c", "find --regext=po|", *NOTHING),
        at("V95 K46 V3c", "find --new=a|", *NOTHING),
        at("V95 K46 V3c", "find --ne=|", *NOTHING),
        at("V95 K46 V3c", "find --files0=|", *NOTHING),
        // `--n`, `--re`, `--m` are ambiguous only to klap; find calls each an unknown predicate (V96).
        at("V96 K47", "find --n |", FILES),
        at("V96 K47", "find --re |", FILES),
        at("V96 K47 L2", "find --m | .", FILES),
    )

    @Test
    fun `the long spellings of -D and -O are unknown options`() = find.checkValues(
        // `find . -debug x` and `find . -optimise 3`: "unknown predicate"; find spells these only `-D` and `-O`.
        at("K47", "find --debug |", FILES),
        at("K47", "find --optimise |", FILES),
        at("K47 L2", "find --debug | .", FILES),
        at("K46", "find --debug=|", *NOTHING),
        at("K46", "find --optimise=|", *NOTHING),
    )

    @Test
    fun `a built in --color claims no word so the next one is a starting point`() = find.checkValues(
        // `find . --color always`: "unknown predicate `--color'" (V20, K47); attached, nothing (K46).
        at("V20 K47", "find --color |", FILES),
        at("V20 K47 L2", "find --color | .", FILES),
        at("V20 K46", "find --color=|", *NOTHING),
        at("V20 K46", "find --color=al|", *NOTHING),
        at("V20 K46", "find --col=|", *NOTHING),
    )

    @Test
    fun `a cluster find refuses leaves the next word to the starting points`() = find.checkValues(
        // `find -LD tree .`: "unknown predicate `-LD'": find matches each switch whole, so the cluster claims no
        // word (V97, K47).
        at("V97 K47", "find -LD |", FILES),
        at("V97 K47 L2", "find -PD | .", FILES),
    )

    @Test
    fun `a bare -O offers its levels glued`() = find.checkValues(
        // `find -O .`: "The -O option must be immediately followed by a decimal integer"; `man find` -Olevel
        // documents 0 to 3 (`-O9` also runs). Offering `-O` alone leads to a refused line (V99).
        at("V99", "find -O|", *attached("-O", OPTIMISE_HELP, "0", "1", "2", "3")),
        at("V99 L2", "find -O| .", *attached("-O", OPTIMISE_HELP, "0", "1", "2", "3")),
    )

    @Test
    fun `-O takes no value in the next word`() = find.checkValues(
        // `find -O 3 .`: "The -O option must be immediately followed by a decimal integer".
        at("V1a", "find -O |", *NOTHING),
        at("V1a", "find -O 3|", *NOTHING),
        at("V1a L2", "find -O | .", *NOTHING),
    )
}

/** The find value cells kept as a record of the respelling (F1a), each stating real find 4.11.0's offer. */
class FindValueCandidatesTargetsTest {

    private val find = findCli().cli

    /** A record, not a target: under F1a the attached `--opt=value` belongs to the respelling. */
    @Ignore("Will not do (F1a): the respelled predicate is a GNU long option and keeps its --opt=value form")
    @Test
    fun `an attached predicate value is refused as find refuses -newer=a`() = find.checkValues(
        // `find . -newer=a`: "invalid predicate `-newer=a'"; find has no `=` form for any predicate.
        at("V3b", "find --newer=|", *NOTHING),
        at("V3b", "find --files0-from=|", *NOTHING),
        at("V2d", "find --regextype=|", *NOTHING),
    )
}
