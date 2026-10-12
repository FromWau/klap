package com.fromwau.klap.fixture.cp

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.testing.Suggestion
import kotlin.test.Test

/** [marked] with its `|` taken out, the cursor where it stood, and what real cp accepts there. */
private fun slot(
    row: String,
    marked: String,
    expected: Set<Suggestion>? = null,
): CompletionCell {
    val cursor = marked.indexOf('|')
    require(cursor >= 0 && marked.indexOf('|', cursor + 1) < 0) { "one | marks the cursor in `$marked`" }
    val line = marked.removeRange(cursor, cursor + 1)
    return CompletionCell(row, row, line, cursor, expected ?: cpOffersAt(line, cursor))
}

private fun Cli.checkSlots(cells: List<CompletionCell>) = checkCells(cells) { assertCp(it) }

private fun Cli.checkSlots(vararg cells: CompletionCell) = checkSlots(cells.toList())

private val NOTHING: Set<Suggestion> = emptySet()

/** The generated cells naming klap's `--help-all`, or its `-h` inside a cluster. */
private val HELP_ALL_OR_HELP_IN_CLUSTER: (CompletionCell) -> Boolean = { cell ->
    cell.line.split(' ').any { it == "-vh" || it.startsWith("--help-a") }
}

private val NAME_KINDS = listOf("long end", "long inside", "long abbreviated")

private val LETTER_KINDS = listOf("short end", "cluster end", "cluster inside")

private val VALUE_KINDS = listOf(
    "value no word", "value word start", "value spaced", "value attached", "value abbreviated", "value list",
)

/** Cells whose word continues a short cluster of flags: cp's other letters, each once, in cp's words. */
private val CLUSTER_CONTINUATIONS: List<CompletionCell> = listOf(
    // K3, K4c, K7a, K7b, K7e2: cp counts no letter, so `-rv` offers no `-rvv`.
    slot("K3", "cp -v|"),
    slot("K3 words after", "cp -v| a b"),
    slot("K4c", "cp -L|"),
    slot("K7a", "cp -r|"),
    slot("K7a words after", "cp -r| a b"),
    slot("K7b", "cp -rf|"),
    slot("K7b inside", "cp -r|f"),
    slot("K7b words after", "cp -rf| a b"),
    slot("K7e2", "cp -rv|"),
    // K7c, S16: `cp -v -v a b` and `cp -rvv a b` copy, so a letter of an earlier word is offered again.
    slot("K7c", "cp -r -f|"),
    slot("K7c words after", "cp -r -f| a b"),
    // K7i, K7s: handed `-r`, the `h` or `z` after the cursor is not sent.
    slot("K7i inside", "cp -r|h"),
    slot("K7s inside", "cp -r|z"),
    // K7o: -R and -r are two spellings of one cp option and `cp -rR a b` copies.
    slot("K7o", "cp -R|"),
    // K4c, K7j: `cp -rP a b`, `cp -Pr a b` and `cp -LP a b` copy, so -P, the negative short of --dereference, is one
    // more letter of the cluster.
    slot("K4c", "cp -P|"),
    slot("K7j", "cp -rP|"),
    slot("K7j words after", "cp -rP| a b"),
    slot("K7j", "cp -Pr|"),
    slot("K7j", "cp -LP|"),
)

/** Cells whose word is a dash, or is handed to klap as one: every cp name, in cp's words, and nothing else. */
private val DASH_POOLS: List<CompletionCell> = listOf(
    slot("K3", "cp -|"),
    slot("K3 inside", "cp -|v"),
    slot("K3 words after", "cp -| a b"),
    slot("K2", "cp --|"),
    slot("K2 inside", "cp --|verb"),
    slot("K2 inside the dashes", "cp -|-verb"),
    slot("K5 inside", "cp --|back"),
    slot("K6 inside", "cp -|S"),
    slot("K7a inside", "cp -|r"),
    slot("K7b inside", "cp -|rf"),
    slot("K7c inside", "cp -r -|f"),
    slot("K7d", "cp -rf -|"),
    slot("K7d words after", "cp -rf -| a b"),
    slot("K14a -- after the cursor", "cp -| -- x"),
)

/**
 * What Tab completes at each slot and cursor column of a cp line (K and K7 of the testing matrix, with their cursor
 * columns), checked against what real cp 9.12 would accept there: the exact suggestions with their descriptions.
 * A slot cell names its K row; the generated cells cover every input of cp.
 */
class CpCompletionTest {

    private val cp = cpCli().cli

    // --- generated cells, every input ---

    @Test
    fun `an operand slot hands the word to the shell's file completion`() = cp.checkSlots(
        cells("no word", OPERAND) + cells("word start", OPERAND) + cells("operand inside", OPERAND) +
            cells("operand end", OPERAND),
    )

    @Test
    fun `after the end of options every word is an operand`() = cp.checkSlots(cells("end of options", OPERAND))

    @Test
    fun `a long name completes to every long of cp it starts in cp's words`() =
        cp.checkSlots(NAME_KINDS.flatMap { cells(it, CP_NAME) })

    @Test
    fun `a letter taking a value ends its cluster`() = cp.checkSlots(LETTER_KINDS.flatMap { cells(it, VALUE_LETTER) })

    @Test
    fun `a flag letter continues its cluster with the letters cp accepts after it`() =
        cp.checkSlots(LETTER_KINDS.flatMap { cells(it, LETTERS) })

    @Test
    fun `a long name cp does not have and -h inside a cluster are not offered`() =
        cp.checkSlots(
            (NAME_KINDS.flatMap { cells(it, UNKNOWN_NAME) } + CELLS.filter(HELP_ALL_OR_HELP_IN_CLUSTER)).distinct(),
        )

    // --- K2 to K6, K13, K15: names ---

    @Test
    fun `a long flag completes from the cursor wherever it stands in the word`() = cp.checkSlots(
        slot("K2", "cp --verb|"),
        slot("K2 inside", "cp --verb|ose"),
        slot("K2 inside", "cp --ve|rb"),
        slot("K2 start", "cp |--verb"),
        slot("K2 words after", "cp --verb| a b"),
        slot("K2 a whole name", "cp --verbose|"),
    )

    @Test
    fun `a long option's name completes from the cursor wherever it stands in the word`() = cp.checkSlots(
        slot("K5", "cp --back|"),
        slot("K5 inside", "cp --ba|ck"),
        slot("K5 start", "cp |--backup"),
        slot("K5 words after", "cp --back| a b"),
        slot("K5 words after", "cp --back| numbered a b"),
        slot("K5", "cp --targ|"),
        slot("K5", "cp --suf|"),
    )

    @Test
    fun `a short option's name offers itself alone`() = cp.checkSlots(
        // `cp -S`: "option requires an argument -- 'S'"; the value is the next word or the rest of this one.
        slot("K6", "cp -S|"),
        slot("K6", "cp -t|"),
        slot("K6 start", "cp |-S"),
        slot("K6 words after", "cp -S| .bak a b"),
    )

    @Test
    fun `names are not offered before a dash`() = cp.checkSlots(
        slot("K2 no word", "cp |"),
        slot("K2 no word", "cp -v |"),
        slot("K3 start", "cp |-v"),
        slot("K13a start", "cp |--help"),
    )

    @Test
    fun `cp has no -h and klap's other built-ins are not offered`() = cp.checkSlots(
        // `cp -h`: "invalid option -- 'h'"; `cp --json`, `--color`, `--completion`, `--docs`: "unrecognized option".
        slot("K13b", "cp -h|"),
        slot("K13e", "cp --js|"),
        slot("K13f", "cp --col|"),
        slot("K13g", "cp --compl|"),
        slot("K13g", "cp --doc|"),
    )

    @Test
    fun `a long name typed in part completes to every long it may become`() = cp.checkSlots(
        // `cp --a a b`: "option '--a' is ambiguous; possibilities: '--archive' '--attributes-only'"; `cp --li a b`
        // is --link.
        slot("K15a ambiguous", "cp --a|"),
        slot("K15a ambiguous inside", "cp --a|r"),
        slot("K15a ambiguous words after", "cp --a| a b"),
        slot("K15a ambiguous", "cp --v|"),
        slot("K15a ambiguous", "cp --no|"),
        slot("K15a", "cp --li|"),
        slot("K15a", "cp --pre|"),
    )

    @Test
    fun `a cluster continues with the letters cp accepts after it in cp's words`() =
        cp.checkSlots(CLUSTER_CONTINUATIONS)

    // --- K9 to K11, K32 to K47: value slots ---

    @Test
    fun `an attached value completes from the cursor wherever it stands`() = cp.checkSlots(
        slot("K9", "cp --backup=nu|"),
        slot("K9 abbreviated", "cp --back=nu|"),
        slot("K9 inside", "cp --backup=n|u"),
        slot("K9 inside the name", "cp --back|up=nu"),
        slot("K9 before the =", "cp --backup|=nu"),
        slot("K9 start", "cp |--backup=nu"),
        slot("K9 no word", "cp --backup=|"),
        slot("K9 words after", "cp --backup=| a b"),
        slot("K15b", "cp --back=|"),
        slot("K9 path", "cp --target-directory=d|"),
        slot("K9 path", "cp --target=|"),
    )

    @Test
    fun `a flag or an unknown or ambiguous long has no value to complete`() = cp.checkSlots(
        // `cp --verbose=x a b`: "option '--verbose' doesn't allow an argument"; `cp --zzz=a a b`: "unrecognized
        // option '--zzz=a'"; `cp --re a b`: "option '--re' is ambiguous".
        slot("K45", "cp --verbose=|"),
        slot("K45 inside", "cp --verbose=|x"),
        slot("K45 words after", "cp --verbose=| a b"),
        slot("K46", "cp --zzz=|"),
        slot("K46", "cp --zzz=a|"),
        slot("K46 ambiguous", "cp --re=|"),
        slot("K46 words after", "cp --zzz=| a b"),
    )

    @Test
    fun `the word after an unknown long is an operand`() = cp.checkSlots(
        // K47: `--zzz` claims no word (`cp --zzz a b`: "unrecognized option '--zzz'").
        slot("K47", "cp --zzz |"),
        slot("K47", "cp --zzz sr|"),
        slot("K47 words after", "cp --zzz | b"),
    )

    @Test
    fun `nothing completes after an ambiguous long`() = cp.checkSlots(
        // K34: `cp --re a b`: "option '--re' is ambiguous; possibilities: '--recursive' '--remove-destination'
        // '--reflink'", so no word is accepted after it.
        slot("K34", "cp --re |", expected = NOTHING),
        slot("K34", "cp --re 7|", expected = NOTHING),
        slot("K34 words after", "cp --re | a", expected = NOTHING),
        slot("K34", "cp --no |", expected = NOTHING),
    )

    @Test
    fun `a glued short value completes from the cursor wherever it stands`() = cp.checkSlots(
        slot("K10 free text", "cp -S.b|"),
        slot("K10 inside", "cp -S|.b"),
        slot("K10 start", "cp |-S.b"),
        slot("K10 words after", "cp -S.b| a b"),
        slot("K10 path", "cp -td|"),
        slot("K10 path inside", "cp -td|i"),
    )

    @Test
    fun `a value in its own word completes from the cursor wherever it stands`() = cp.checkSlots(
        // K32, K33, K36, K7x: `-S`'s suffix is free text, `-t`'s directory a path, `--sparse` a choice.
        slot("K32", "cp -S |"),
        slot("K32 words after", "cp -S | a b"),
        slot("K32", "cp -t di|"),
        slot("K32 inside", "cp -t d|i"),
        slot("K32 start", "cp -t |di"),
        slot("K32 no word", "cp -t |"),
        slot("K32 words after", "cp -t | a"),
        slot("K33", "cp --targ |"),
        slot("K33", "cp --suf |"),
        slot("K33", "cp --spa a|"),
        slot("K33 no word", "cp --spa |"),
        slot("K36", "cp -rS |"),
        slot("K36 words after", "cp -rS | a b"),
        slot("K36", "cp -vt di|"),
        slot("K7x", "cp -vt |"),
        slot("K7x words after", "cp -vt | a"),
    )

    @Test
    fun `a dash-led word in a value slot is that value`() = cp.checkSlots(
        // K40, K81: getopt hands -S the next word whatever it looks like (`cp -S -v --backup=simple a b` made `b-v`).
        slot("K40", "cp -S -v|"),
        slot("K40 inside", "cp -S -|v"),
        slot("K40 words after", "cp -S -v| a b"),
        slot("K81", "cp -t -|"),
    )

    @Test
    fun `the end of options marker in a value slot is that value`() = cp.checkSlots(
        // K41: `cp -S -- --backup=simple a b` made `b--`, so getopt gave `--` to -S and options went on.
        slot("K41", "cp -S --|"),
        slot("K41 words after", "cp -S --| a b"),
        slot("K41 no word", "cp -S -- |"),
        slot("K41 a name after", "cp -S -- --verb|"),
    )

    @Test
    fun `an optional value completes attached and leaves a spaced word to the operands`() = cp.checkSlots(
        // K11: `cp --backup numbered a b`: "target 'b': Not a directory", so numbered was a source.
        VALUE_KINDS.flatMap { cells(it, OPTIONAL) } +
            slot("K11a", "cp --backup=ex|") +
            slot("K11a inside", "cp --backup=e|x") +
            slot("K11a start", "cp |--backup=ex") +
            slot("K11a words after", "cp --backup=ex| a b") +
            slot("K11b", "cp --backup |") +
            slot("K11b words after", "cp --backup | a") +
            slot("K11b", "cp --reflink |") +
            slot("K11b", "cp --update |") +
            slot("K33 optional", "cp --back |"),
    )

    // --- K12, K14, K55 to K59, K79, K80, K83: operand slots ---

    @Test
    fun `an operand completes as a file wherever the cursor stands`() = cp.checkSlots(
        slot("K12a", "cp sr|"),
        slot("K12a inside", "cp s|rc"),
        slot("K12a start", "cp |src"),
        slot("K12a no word", "cp |"),
        slot("K12a words after", "cp | b"),
        slot("K12b", "cp a sr|"),
        slot("K12b inside", "cp a s|rc"),
        slot("K12b start", "cp a |src"),
        slot("K12b no word", "cp a |"),
        slot("K12b words after", "cp a | b"),
        slot("K12b both sides", "cp a | b c"),
        slot("K12c2", "cp a b ds|"),
        slot("K12c2 inside", "cp a b d|st"),
        slot("K12c2 start", "cp a b |dst"),
        slot("K12c2 no word", "cp a b |"),
    )

    @Test
    fun `an operand after an option completes as a file`() = cp.checkSlots(
        slot("K79", "cp -v sr|"),
        slot("K79", "cp -S .b sr|"),
        slot("K79", "cp --backup=t sr|"),
        slot("K79 no word", "cp -v |"),
        slot("K79 no word", "cp -S .b |"),
        slot("K79 no word", "cp --backup=t |"),
        slot("K79 words after", "cp -S .b | b"),
        slot("K80", "cp - fi|"),
        slot("K80", "cp - |"),
        slot("K80 words after", "cp - | b"),
    )

    @Test
    fun `a source still completes once -t takes the destination's place`() = cp.checkSlots(
        // K12d, K83: `cp -t d a`, `cp --target=d a` and `cp -td a` copy a into d.
        slot("K12d1", "cp -t dir a|"),
        slot("K12d1 inside", "cp -t dir a|b"),
        slot("K12d1 start", "cp -t dir |a"),
        slot("K12d1 no word", "cp -t dir |"),
        slot("K12d2", "cp | -t dir"),
        slot("K12d3", "cp --target-directory=d a|"),
        slot("K12d3", "cp -td a|"),
        slot("K12d3", "cp --target=d a|"),
        slot("K83 no word", "cp -td |"),
        slot("K83 words after", "cp | --target=d"),
    )

    @Test
    fun `after the end of options a name is an operand`() = cp.checkSlots(
        // K14a: `cp -- -v a`: "cannot stat '-v'"; `cp -- -- a`: "cannot stat '--'".
        slot("K14a", "cp -- -v|"),
        slot("K14a inside", "cp -- -|v"),
        slot("K14a start", "cp -- |-v"),
        slot("K14a no word", "cp -- |"),
        slot("K14a a second --", "cp -- -- |"),
        slot("K14a words after", "cp -- | b"),
    )

    @Test
    fun `the word after a built-in cp does not have is an operand`() = cp.checkSlots(
        // K55: `cp --completion bash a b`, `cp --docs man a b`: "unrecognized option", so no word is their value.
        slot("K55", "cp --completion |"),
        slot("K55", "cp --completion ba|"),
        slot("K55", "cp --docs ma|"),
        slot("K55 words after", "cp --completion | a"),
        slot("K55 attached", "cp --completion=ba|"),
    )

    @Test
    fun `a source named like klap's completion protocol completes as a file`() = cp.checkSlots(
        // K58: cp reads `__complete` as a file to copy.
        slot("K58", "cp __c|"),
        slot("K58 words after", "cp __c| a"),
    )

    @Test
    fun `the words after --help complete as cp reads them`() = cp.checkSlots(
        // K59: `cp --help a` prints the help; the words after it are still read as operands.
        slot("K59", "cp --help |"),
        slot("K59", "cp --help sr|"),
        slot("K59 words after", "cp --help | b"),
    )

    // --- K7: clusters ---

    @Test
    fun `a value letter takes the rest of the cluster`() = cp.checkSlots(
        // K7f, K7g, K7h: `cp -rfS=.b --backup=simple a b` made `b=.b`, so -S took `=.b`.
        slot("K7f", "cp -rfS|"),
        slot("K7f", "cp -rfS.b|"),
        slot("K7f inside", "cp -rfS.|b"),
        slot("K7f words after", "cp -rfS.b| a b"),
        slot("K7f path", "cp -rtdi|"),
        slot("K7g", "cp -rfS=|"),
        slot("K7g inside", "cp -rfS=.|b"),
        slot("K7g words after", "cp -rfS=| a b"),
        slot("K7g path", "cp -rt=|"),
        slot("K7h", "cp -rSf|"),
        slot("K7h words after", "cp -rSf| a b"),
    )

    @Test
    fun `a letter cp does not know or a stray = ends what a cluster can become`() = cp.checkSlots(
        // K7g, K7i, K7s, K7t: `cp -rfb=`, `cp -v=`: "invalid option -- '='"; `cp -rh`: "invalid option -- 'h'";
        // `cp -rz`: "invalid option -- 'z'".
        slot("K7g flag", "cp -rfb=|"),
        slot("K7i", "cp -rh|"),
        slot("K7i words after", "cp -rh| a b"),
        slot("K7i start", "cp |-rh"),
        slot("K7s", "cp -rz|"),
        slot("K7s", "cp -zr|"),
        slot("K7s words after", "cp -rz |"),
        slot("K7t", "cp -v=|"),
        slot("K7t", "cp -rv=x|"),
        slot("K7t words after", "cp -v=| a b"),
    )

    private fun check(cells: List<CompletionCell>) = cp.checkSlots(cells.filterNot(HELP_ALL_OR_HELP_IN_CLUSTER))

    @Test
    fun `-T leaves no room for a third operand`() {
        // `cp -T a b c`: "extra operand 'c'"; the same with -T after the cursor (`cp | -T a b`). Todo 21; klap core,
        // as no declaration caps a variadic.
        check(cells("no word", FULL_OPERANDS) + cells("operand end", FULL_OPERANDS))
    }

    @Test
    fun `-t and -T leave each other out and -t is not offered twice`() {
        // `cp -t a -T b c`: "cannot combine --target-directory (-t) and --no-target-directory (-T)"; `cp -t a -t b x`:
        // "multiple target directories specified". Words after the cursor count as much as words before it. Todo 21;
        // the dash cells also need cp's wording (todo 25).
        val refused = (listOf("dash", "value no word", "value attached") + NAME_KINDS + LETTER_KINDS)
            .flatMap { cells(it, REFUSED) }
            .filterNot { it.line == "cp -h" }
        check(refused)
    }
}
