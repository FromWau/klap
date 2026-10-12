package com.fromwau.klap.fixture.mv

import com.fromwau.klap.testing.Suggestion
import kotlin.test.Test

private val NOTHING: Set<Suggestion> = emptySet()

/** Cells whose word continues a short cluster of flags: mv's other letters, each once, in mv's words. */
private val CLUSTER_CONTINUATIONS: List<CompletionCell> = listOf(
    // K3, K7a, K7b, K7e2: mv counts no letter, so `-vf` offers no `-vff`.
    slot("K3", "mv -v|"),
    slot("K3 words after", "mv -v| a b"),
    slot("K7a", "mv -f|"),
    slot("K7a words after", "mv -f| a b"),
    slot("K7b", "mv -fv|"),
    slot("K7b inside", "mv -f|v"),
    slot("K7b words after", "mv -fv| a b"),
    slot("K7e2", "mv -vb|"),
    // K7c, S16: `mv -v -v a b` and `mv -vv a b` move, so a letter of an earlier word is offered again.
    slot("K7c", "mv -f -v|"),
    slot("K7c words after", "mv -f -v| a b"),
    // K7i, K7s: handed `-f`, the `h` or `z` after the cursor is not sent.
    slot("K7i inside", "mv -f|h"),
    slot("K7s inside", "mv -f|z"),
    // K7d of the override set: `mv -fi a b` prompts, so a cluster holding -f still continues with -i and -n.
    slot("K7d lastWins", "mv -fi|"),
)

/** Cells whose word is a dash, or is handed to klap as one: every mv name, in mv's words, and nothing else. */
private val DASH_POOLS: List<CompletionCell> = listOf(
    slot("K3", "mv -|"),
    slot("K3 inside", "mv -|v"),
    slot("K3 words after", "mv -| a b"),
    slot("K2", "mv --|"),
    slot("K2 inside", "mv --|verb"),
    slot("K2 inside the dashes", "mv -|-verb"),
    slot("K5 inside", "mv --|back"),
    slot("K6 inside", "mv -|S"),
    slot("K7a inside", "mv -|f"),
    slot("K7b inside", "mv -|fv"),
    slot("K7c inside", "mv -f -|v"),
    slot("K7d", "mv -fv -|"),
    slot("K7d words after", "mv -fv -| a b"),
    slot("K14a -- after the cursor", "mv -| -- x"),
)

/** The generated cells of a name mv refuses on their line, -h aside. */
private val REFUSED_CELLS: List<CompletionCell> by lazy {
    (listOf("dash", "value no word", "value attached") + NAME_KINDS + LETTER_KINDS)
        .flatMap { cells(it, REFUSED) }
        .filterNot { it.line == "mv -h" || it.line == "mv -vh" }
}

/** A cell completing a second -t, which only the repeat refuses. */
private val SECOND_TARGET: (CompletionCell) -> Boolean =
    { it.line in setOf("mv -t d -t ", "mv -t d --target-directory") }

/** The generated cells naming klap's `--help-all`, or `-h` inside a cluster. */
private val HELP_ALL_OR_HELP_IN_CLUSTER: (CompletionCell) -> Boolean = { cell ->
    cell.line.split(' ').any { it == "-vh" || it.startsWith("--help-a") }
}

/**
 * What Tab completes at each slot and cursor column of an mv line (K and K7 of the testing matrix, with their cursor
 * columns), checked against what real mv 9.12 would accept there: the exact suggestions with their descriptions. A
 * slot cell names its K row; the generated cells cover every input of mv. A cell whose answer holds one of
 * [WORDED_HELP] is checked by its values in the test of its kind, and whole by the one on mv's words.
 */
class MvCompletionTest {

    private val mv = mvCli().cli

    // --- generated cells, every input ---

    @Test
    fun `an operand slot hands the word to the shell's file completion`() = mv.checkSlots(
        cells("no word", OPERAND) + cells("word start", OPERAND) + cells("operand inside", OPERAND) +
            cells("operand end", OPERAND),
    )

    @Test
    fun `after the end of options every word is an operand`() = mv.checkSlots(cells("end of options", OPERAND))

    @Test
    fun `a long name completes to every long of mv it starts in mv's words`() =
        mv.checkSlots(NAME_KINDS.flatMap { cells(it, MV_NAME) }.plain())

    @Test
    fun `a long name completes to every long of mv it starts`() =
        mv.checkSlotValues(NAME_KINDS.flatMap { cells(it, MV_NAME) }.wordedOnly())

    @Test
    fun `a letter taking a value ends its cluster`() =
        mv.checkSlots(LETTER_KINDS.flatMap { cells(it, VALUE_LETTER) }.plain())

    @Test
    fun `a flag letter continues its cluster with the letters mv accepts after it`() =
        mv.checkSlotValues(LETTER_KINDS.flatMap { cells(it, LETTERS) }.filterNot { it.holdsNoTarget })

    @Test
    fun `a long name mv does not have and -h inside a cluster are not offered`() =
        mv.checkSlots(
            (NAME_KINDS.flatMap { cells(it, UNKNOWN_NAME) }.filterNot { it.builtinPrefix } +
                CELLS.filter(HELP_ALL_OR_HELP_IN_CLUSTER)).distinct(),
        )

    // --- K2 to K6, K13, K15: names ---

    @Test
    fun `a long flag completes from the cursor wherever it stands in the word`() = mv.checkSlots(
        slot("K2", "mv --verb|"),
        slot("K2 inside", "mv --verb|ose"),
        slot("K2 start", "mv |--verb"),
        slot("K2 words after", "mv --verb| a b"),
        slot("K2 a whole name", "mv --verbose|"),
        slot("K2", "mv --exch|"),
        slot("K2 words after", "mv --no-cop| a b"),
    )

    @Test
    fun `a long option's name completes from the cursor wherever it stands in the word`() = mv.checkSlots(
        slot("K5", "mv --back|"),
        slot("K5 inside", "mv --ba|ck"),
        slot("K5 start", "mv |--backup"),
        slot("K5 words after", "mv --back| a b"),
        slot("K5 words after", "mv --back| numbered a b"),
        slot("K5", "mv --targ|"),
        slot("K5", "mv --suf|"),
    )

    @Test
    fun `a short option's name offers itself alone`() = mv.checkSlots(
        // `mv -S`: "option requires an argument -- 'S'"; the value is the next word or the rest of this one.
        slot("K6", "mv -S|"),
        slot("K6", "mv -t|"),
        slot("K6 start", "mv |-S"),
        slot("K6 words after", "mv -S| .bak a b"),
    )

    @Test
    fun `names are not offered before a dash`() = mv.checkSlots(
        slot("K2 no word", "mv |"),
        slot("K2 no word", "mv -v |"),
        slot("K3 start", "mv |-v"),
        slot("K13a start", "mv |--help"),
    )

    @Test
    fun `mv has no -h`() = mv.checkSlots(
        // `mv -h a b`: "invalid option -- 'h'".
        slot("K13b", "mv -h|"),
        slot("K7i", "mv -vh|"),
    )

    @Test
    fun `a long name typed in part completes to every long it may become`() = mv.checkSlots(
        // `mv --n a b`: "option '--n' is ambiguous; possibilities: '--no-clobber' '--no-copy' '--no-target-directory'";
        // `mv --st a b` is --strip-trailing-slashes, `mv --t d a` --target-directory.
        slot("K15a ambiguous", "mv --s|"),
        slot("K15a ambiguous inside", "mv --s|t"),
        slot("K15a ambiguous words after", "mv --s| a b"),
        slot("K15a", "mv --st|"),
        slot("K15a", "mv --t|"),
        slot("K15a", "mv --e|"),
    )

    // --- K9 to K11, K32 to K47: value slots ---

    @Test
    fun `an attached value completes from the cursor wherever it stands`() = mv.checkSlots(
        slot("K9", "mv --backup=nu|"),
        slot("K9 abbreviated", "mv --back=nu|"),
        slot("K9 inside", "mv --backup=n|u"),
        slot("K9 inside the name", "mv --back|up=nu"),
        slot("K9 before the =", "mv --backup|=nu"),
        slot("K9 start", "mv |--backup=nu"),
        slot("K9 no word", "mv --backup=|"),
        slot("K9 words after", "mv --backup=| a b"),
        slot("K15b", "mv --back=|"),
        slot("K9 path", "mv --target-directory=d|"),
        slot("K9 path", "mv --target=|"),
    )

    @Test
    fun `a flag or an unknown or ambiguous long has no value to complete`() = mv.checkSlots(
        // `mv --verbose=x a b`: "option '--verbose' doesn't allow an argument"; `mv --zzz=a a b`: "unrecognized
        // option '--zzz=a'"; `mv --s a b`: "option '--s' is ambiguous".
        slot("K45", "mv --verbose=|"),
        slot("K45 inside", "mv --verbose=|x"),
        slot("K45 words after", "mv --verbose=| a b"),
        slot("K45", "mv --exchange=|"),
        slot("K46", "mv --zzz=|"),
        slot("K46", "mv --zzz=a|"),
        slot("K46 ambiguous", "mv --s=|"),
        slot("K46 words after", "mv --zzz=| a b"),
    )

    @Test
    fun `the word after an unknown long is an operand`() = mv.checkSlots(
        // K47: `--zzz` claims no word (`mv --zzz a b`: "unrecognized option '--zzz'").
        slot("K47", "mv --zzz |"),
        slot("K47", "mv --zzz sr|"),
        slot("K47 words after", "mv --zzz | b"),
    )

    @Test
    fun `nothing completes after an ambiguous long`() = mv.checkSlots(
        // K34: `mv --s a b`: "option '--s' is ambiguous; possibilities: '--strip-trailing-slashes' '--suffix'", so no
        // word is accepted after it.
        slot("K34", "mv --s |", expected = NOTHING),
        slot("K34", "mv --s 7|", expected = NOTHING),
        slot("K34 words after", "mv --s | a", expected = NOTHING),
        slot("K34", "mv --no |", expected = NOTHING),
    )

    @Test
    fun `a glued short value completes from the cursor wherever it stands`() = mv.checkSlots(
        slot("K10 free text", "mv -S.b|"),
        slot("K10 inside", "mv -S|.b"),
        slot("K10 start", "mv |-S.b"),
        slot("K10 words after", "mv -S.b| a b"),
        slot("K10 path", "mv -td|"),
        slot("K10 path inside", "mv -td|i"),
    )

    @Test
    fun `a value in its own word completes from the cursor wherever it stands`() = mv.checkSlots(
        // K32, K33, K36, K7x: `-S`'s suffix is free text, `-t`'s directory a path.
        slot("K32", "mv -S |"),
        slot("K32 words after", "mv -S | a b"),
        slot("K32", "mv -t di|"),
        slot("K32 inside", "mv -t d|i"),
        slot("K32 start", "mv -t |di"),
        slot("K32 no word", "mv -t |"),
        slot("K32 words after", "mv -t | a"),
        slot("K33", "mv --targ |"),
        slot("K33", "mv --suf |"),
        slot("K33", "mv --t |"),
        slot("K36", "mv -fS |"),
        slot("K36 words after", "mv -fS | a b"),
        slot("K36", "mv -vt di|"),
        slot("K7x", "mv -vt |"),
        slot("K7x words after", "mv -vt | a"),
    )

    @Test
    fun `a dash-led word in a value slot is that value`() = mv.checkSlots(
        // K40, K81: getopt hands -S the next word whatever it looks like (`mv -S -x -b a b` made `b-x`).
        slot("K40", "mv -S -v|"),
        slot("K40 inside", "mv -S -|v"),
        slot("K40 words after", "mv -S -v| a b"),
        slot("K81", "mv -t -|"),
    )

    @Test
    fun `the end of options marker in a value slot is that value`() = mv.checkSlots(
        // K41: `mv -S -- -b a b` made `b--`, so getopt gave `--` to -S and options went on.
        slot("K41", "mv -S --|"),
        slot("K41 words after", "mv -S --| a b"),
        slot("K41 no word", "mv -S -- |"),
        slot("K41 a name after", "mv -S -- --verb|"),
    )

    @Test
    fun `an optional value completes attached and leaves a spaced word to the operands`() = mv.checkSlots(
        // K11: `mv --backup numbered a b`: "target 'b': Not a directory", so numbered was a source.
        VALUE_KINDS.flatMap { cells(it, OPTIONAL) }.plain() +
            slot("K11a", "mv --backup=ex|") +
            slot("K11a inside", "mv --backup=e|x") +
            slot("K11a start", "mv |--backup=ex") +
            slot("K11a words after", "mv --backup=ex| a b") +
            slot("K11b", "mv --backup |") +
            slot("K11b words after", "mv --backup | a") +
            slot("K11b", "mv --update |") +
            slot("K33 optional", "mv --back |"),
    )

    @Test
    fun `an optional value completes attached`() =
        mv.checkSlotValues(VALUE_KINDS.flatMap { cells(it, OPTIONAL) }.wordedOnly())

    // --- K12, K14, K55 to K59, K79, K80, K83: operand slots ---

    @Test
    fun `an operand completes as a file wherever the cursor stands`() = mv.checkSlots(
        slot("K12a", "mv sr|"),
        slot("K12a inside", "mv s|rc"),
        slot("K12a start", "mv |src"),
        slot("K12a no word", "mv |"),
        slot("K12a words after", "mv | b"),
        slot("K12b", "mv a sr|"),
        slot("K12b inside", "mv a s|rc"),
        slot("K12b start", "mv a |src"),
        slot("K12b no word", "mv a |"),
        slot("K12b words after", "mv a | b"),
        slot("K12b both sides", "mv a | b c"),
        slot("K12c2", "mv a b ds|"),
        slot("K12c2 inside", "mv a b d|st"),
        slot("K12c2 start", "mv a b |dst"),
        slot("K12c2 no word", "mv a b |"),
        slot("K77", "mv |"),
    )

    @Test
    fun `an operand after an option completes as a file`() = mv.checkSlots(
        slot("K79", "mv -v sr|"),
        slot("K79", "mv -S .b sr|"),
        slot("K79", "mv --backup=t sr|"),
        slot("K79 no word", "mv -v |"),
        slot("K79 no word", "mv -S .b |"),
        slot("K79 no word", "mv --backup=t |"),
        slot("K79 words after", "mv -S .b | b"),
        slot("K80", "mv - fi|"),
        slot("K80", "mv - |"),
        slot("K80 words after", "mv - | b"),
    )

    @Test
    fun `a source still completes once -t takes the destination's place`() = mv.checkSlots(
        // K12d, K83: `mv -t d a`, `mv --t d a` and `mv -td a` move a into d.
        slot("K12d1", "mv -t dir a|"),
        slot("K12d1 inside", "mv -t dir a|b"),
        slot("K12d1 start", "mv -t dir |a"),
        slot("K12d1 no word", "mv -t dir |"),
        slot("K12d2", "mv | -t dir"),
        slot("K12d3", "mv --target-directory=d a|"),
        slot("K12d3", "mv -td a|"),
        slot("K12d3", "mv --target=d a|"),
        slot("K83 no word", "mv -td |"),
        slot("K83 words after", "mv | --target=d"),
    )

    @Test
    fun `after the end of options a name is an operand`() = mv.checkSlots(
        // K14a: `mv -- -foo bar`: "cannot stat '-foo'"; `mv a -- -v` makes a file named -v.
        slot("K14a", "mv -- -v|"),
        slot("K14a inside", "mv -- -|v"),
        slot("K14a start", "mv -- |-v"),
        slot("K14a no word", "mv -- |"),
        slot("K14a a second --", "mv -- -- |"),
        slot("K14a words after", "mv -- | b"),
    )

    @Test
    fun `odd words in a name or value slot complete as mv reads them`() = mv.checkSlots(
        // K29: `mv -5 a b`: "invalid option -- '5'". K42: `mv -S --help -b a b` made b--help, so the next word is an
        // operand. K43: `mv --suffix=a=b -b a b` made ba=b, a free text value. K58: `mv __complete a`: "cannot stat
        // '__complete'", a source like any other.
        slot("K29", "mv -5|"),
        slot("K29 words after", "mv -5| a b"),
        slot("K42", "mv -S --help |"),
        slot("K42", "mv -S --help sr|"),
        slot("K43", "mv --suffix=a=b|"),
        slot("K43", "mv --suffix=a=|"),
        slot("K58", "mv __c|"),
        slot("K58 words after", "mv __c| a"),
    )

    @Test
    fun `the word after --help completes as mv reads it`() = mv.checkSlots(
        // K59: `mv a b --help` prints the help; the words after it are still read as operands.
        slot("K59", "mv --help |"),
        slot("K59", "mv --help sr|"),
        slot("K59 words after", "mv --help | b"),
    )

    // --- K7: clusters ---

    @Test
    fun `a value letter takes the rest of the cluster`() = mv.checkSlots(
        // K7f, K7g, K7h: `mv -b -S= a b` made `b=` and `mv -Sb a b` made `bb`, so -S took the rest of its word.
        slot("K7f", "mv -fvS|"),
        slot("K7f", "mv -fvS.b|"),
        slot("K7f inside", "mv -fvS.|b"),
        slot("K7f words after", "mv -fvS.b| a b"),
        slot("K7f path", "mv -ftdi|"),
        slot("K7g", "mv -fvS=|"),
        slot("K7g inside", "mv -fvS=.|b"),
        slot("K7g words after", "mv -fvS=| a b"),
        slot("K7g path", "mv -ft=|"),
        slot("K7h", "mv -fSv|"),
        slot("K7h words after", "mv -fSv| a b"),
    )

    @Test
    fun `a letter mv does not know or a stray = ends what a cluster can become`() = mv.checkSlots(
        // K7g, K7i, K7s, K7t: `mv -v=x a b`: "invalid option -- '='"; `mv -vh a b`: "invalid option -- 'h'".
        slot("K7g flag", "mv -fvb=|"),
        slot("K7i", "mv -fh|"),
        slot("K7i words after", "mv -fh| a b"),
        slot("K7i start", "mv |-fh"),
        slot("K7s", "mv -fz|"),
        slot("K7s", "mv -zf|"),
        slot("K7s words after", "mv -fz |"),
        slot("K7t", "mv -v=|"),
        slot("K7t", "mv -fv=x|"),
        slot("K7t words after", "mv -v=| a b"),
    )

    @Test
    fun `a cluster continues with the letters mv accepts after it`() = mv.checkSlotValues(CLUSTER_CONTINUATIONS)

    @Test
    fun `-t is not offered twice`() =
        // `mv -t d -t e a`: "multiple target directories specified".
        mv.checkSlots(REFUSED_CELLS.filter(SECOND_TARGET))

    @Test
    fun `a name is described in mv's words wherever the cursor sits`() {
        // `mv --help`: "explain how a file is copied.  Implies -v", "(deprecated) silently skip existing files.  See
        // also --update", "control which existing files are updated; UPDATE={all,none,none-fail,older(default)}",
        // "equivalent to --update[=older].  See below", "set SELinux security context of destination file to default
        // type", "display this help and exit", "output version information and exit".
        val generated = (NAME_KINDS + LETTER_KINDS + VALUE_KINDS)
            .flatMap { cells(it) }
            .wordedOnly()
            .filterNot { it.holdsNoTarget || it.subject == FULL_OPERANDS }
        mv.checkSlots(generated + CLUSTER_CONTINUATIONS + slot("K2 inside", "mv --ve|rb"))
    }

    @Test
    fun `klap's built-ins mv lacks are not offered`() {
        // `mv --json a b`, `mv --color a b`, `mv --completion bash a b`, `mv --docs a b`, `mv --col a b`, `mv --j a b`,
        // `mv --com a b`, `mv --do a b`: "unrecognized option". Their prefixes `--c` and `--d` are mv's --context and
        // --debug alone (`mv --c a b` and `mv --d a b` run).
        val prefixed = CELLS.filter { it.builtinPrefix && it.subject != REFUSED }
        mv.checkSlots(
            prefixed + DASH_POOLS + slot("K15a", "mv --c|") + slot("K15a", "mv --d|") + slot("K15a", "mv --co|"),
        )
    }

    @Test
    fun `-t and -T leave each other out`() {
        // `mv -t d -T a` and `mv -T -t d a`: "cannot combine --target-directory (-t) and --no-target-directory (-T)".
        // Words after the cursor count as much as words before it.
        val clusters = LETTER_KINDS.flatMap { cells(it, LETTERS) }.filter { it.holdsNoTarget }
        mv.checkSlots(REFUSED_CELLS.filterNot(SECOND_TARGET) + clusters)
    }

    @Test
    fun `-T leaves no room for a third operand`() {
        // `mv -T a b c` and `mv a b c -T`: "extra operand 'c'"; the same with -T after the cursor (`mv | -T a b`).
        mv.checkSlots(cells("no word", FULL_OPERANDS) + cells("operand end", FULL_OPERANDS))
    }
}
