package com.fromwau.klap.fixture.rm

import com.fromwau.klap.fixture.rm.RmLineContext.assertOffersNone
import kotlin.test.Test

/** Cells whose word continues a short cluster of flags: rm's other letters, each once. */
private val CLUSTER_CONTINUATIONS: List<CompletionCell> = listOf(
    // K3, K7a, K7b, K7e2: rm counts no letter, so `-vf` offers no `-vff` (`rm -vv a` runs, but reads one -v).
    slot("K3", "rm -v|"),
    slot("K3 words after", "rm -v| a b"),
    slot("K7a", "rm -f|"),
    slot("K7a words after", "rm -f| a b"),
    slot("K7b", "rm -rf|"),
    slot("K7b inside", "rm -r|f"),
    slot("K7b words after", "rm -rf| a b"),
    slot("K7e2", "rm -rv|"),
    // K7c, S16: `rm -v -v a` and `rm -vv a` remove a, so a letter of an earlier word is offered again.
    slot("K7c", "rm -f -v|"),
    slot("K7c words after", "rm -f -v| a b"),
    // K7d: `rm -fi a` prompts, so a cluster holding -f still continues with -i and -I.
    slot("K7d lastWins", "rm -fi|"),
    // K7o: `rm -rR dd` removes dd: -R is a letter of its own beside -r.
    slot("K7o", "rm -r|"),
    // K7i, K7s: handed `-f`, the `h` or `z` after the cursor is not sent.
    slot("K7i inside", "rm -f|h"),
    slot("K7s inside", "rm -f|z"),
)

/** Cells whose word is a dash, or is handed to klap as one: every rm name, in rm's words, and nothing else. */
private val DASH_POOLS: List<CompletionCell> = listOf(
    slot("K3", "rm -|"),
    slot("K3 inside", "rm -|v"),
    slot("K3 words after", "rm -| a b"),
    slot("K2", "rm --|"),
    slot("K2 inside", "rm --|verb"),
    slot("K2 inside the dashes", "rm -|-verb"),
    slot("K5 inside", "rm --|inter"),
    slot("K7a inside", "rm -|f"),
    slot("K7b inside", "rm -|rf"),
    slot("K7c inside", "rm -f -|v"),
    slot("K7d", "rm -rf -|"),
    slot("K7d words after", "rm -rf -| a b"),
    slot("K14a -- after the cursor", "rm -| -- x"),
    slot("K29 inside", "rm -|5"),
)

/**
 * What Tab completes at each slot and cursor column of an rm line (K and K7 of the testing matrix, with their cursor
 * columns), checked against what real rm 9.12 would accept there: the exact suggestions with their descriptions. A
 * slot cell names its K row; the generated cells cover every input of rm. rm has no option whose value is required,
 * so the rows of a value in its own word, glued to a short or after a value-taking cluster letter (K6, K7f to K7h,
 * K8, K10, K32, K36, K40 to K43) have no rm cell. A cell whose answer holds a [CompletionCell.worded] entry is checked
 * whole by a test of its own.
 */
class RmCompletionTest {

    private val rm = rmCli().cli

    // --- generated cells, every input ---

    @Test
    fun `an operand slot hands the word to the shell's file completion`() = rm.checkSlots(
        cells("no word", OPERAND) + cells("word start", OPERAND) + cells("operand inside", OPERAND) +
            cells("operand end", OPERAND),
    )

    @Test
    fun `after the end of options every word is an operand`() = rm.checkSlots(cells("end of options", OPERAND))

    @Test
    fun `a long name completes to every long of rm it starts in rm's words`() =
        rm.checkSlots(NAME_KINDS.flatMap { cells(it, RM_NAME) }.plain())

    @Test
    fun `a long name completes to every long of rm it starts`() =
        rm.checkSlotValues(NAME_KINDS.flatMap { cells(it, RM_NAME) }.wordedOnly())

    @Test
    fun `a flag letter continues its cluster with every other letter of rm`() =
        rm.checkSlotValues(LETTER_KINDS.flatMap { cells(it, LETTERS) }.filterNot { it.foreignPrefix })

    @Test
    fun `a cluster continues with the letters rm accepts after it`() = rm.checkSlotValues(CLUSTER_CONTINUATIONS)

    @Test
    fun `a long name rm does not have and -h inside a cluster are not offered`() = rm.checkSlots(
        NAME_KINDS.flatMap { cells(it, UNKNOWN_NAME) }.filterNot { it.foreignPrefix } +
            slot("K13b", "rm -vh|") +
            slot("K7i", "rm -fh|"),
    )

    // --- K2 to K5, K13, K15, K20, K25: names ---

    @Test
    fun `a long flag completes from the cursor wherever it stands in the word`() = rm.checkSlots(
        slot("K2", "rm --verb|"),
        slot("K2 inside", "rm --verb|ose"),
        slot("K2 start", "rm |--verb"),
        slot("K2 words after", "rm --verb| a b"),
        slot("K2 a whole name", "rm --verbose|"),
        slot("K2", "rm --rec|"),
        slot("K2 words after", "rm --for| a b"),
        slot("K2", "rm --di|"),
    )

    @Test
    fun `a long option's name completes from the cursor wherever it stands in the word`() = rm.checkSlotValues(
        // K5: `--interactive[=WHEN]` and `--preserve-root[=all]` are offered bare, their values only after the `=`.
        slot("K5 start", "rm |--preserve-root"),
        slot("K5", "rm --preserve-r|"),
        slot("K5 inside", "rm --pre|serve"),
        slot("K5 words after", "rm --preserve-r| a b"),
        slot("K5 words after", "rm --preserve-r| all a"),
    )

    @Test
    fun `names are not offered before a dash`() = rm.checkSlots(
        slot("K2 no word", "rm |"),
        slot("K2 no word", "rm -v |"),
        slot("K3 start", "rm |-v"),
        slot("K13a start", "rm |--help"),
    )

    @Test
    fun `rm has no -h`() = rm.checkSlots(
        // `rm -h a`: "invalid option -- 'h'".
        slot("K13b", "rm -h|"),
        slot("K13b words after", "rm -h| a"),
    )

    @Test
    fun `a long name typed in part completes to every long it may become`() = rm.checkSlotValues(
        // `rm --v a`: "option '--v' is ambiguous; possibilities: '--verbose' '--version'"; `rm --r dd`, `rm --o -r dd`
        // and `rm --f nofile` run as --recursive, --one-file-system and --force.
        slot("K15a ambiguous", "rm --v|"),
        slot("K15a ambiguous inside", "rm --v|e"),
        slot("K15a ambiguous words after", "rm --ver| a b"),
        slot("K15a", "rm --r|"),
        slot("K15a", "rm --o|"),
        slot("K15a", "rm --f|"),
        slot("K15a inside", "rm --r|ec"),
        slot("K15a words after", "rm --rec| a b"),
    )

    @Test
    fun `--no-preserve-root completes to its whole name only`() = rm.checkSlotValues(
        // K4b, K25: `strings /usr/bin/rm`: "you may not abbreviate the --no-preserve-root option", so Tab offers the
        // whole word, which getopt reads as the one long (`getopt ... -- --no a` gives --no-preserve-root). Not run.
        slot("K4b", "rm --no|"),
        slot("K4b", "rm --no-pre|"),
        slot("K4b inside", "rm --no-|pre"),
        slot("K4b start", "rm |--no-preserve-root"),
        slot("K4b words after", "rm --no-p| a"),
    )

    // --- K9, K11, K33, K45 to K47, K52: value slots ---

    @Test
    fun `an attached value completes from the cursor wherever it stands`() = rm.checkSlots(
        // K9, K11a, K52: `rm --interactive=al a` and `=a` prompt, `rm --interactive=nev a` removes without asking, `rm
        // --interactive=o a b c e` asks once: argmatch reads a prefix every match of which names one mode.
        slot("K11a", "rm --interactive=al|"),
        slot("K11a inside", "rm --interactive=a|l"),
        slot("K11a start", "rm |--interactive=al"),
        slot("K11a words after", "rm --interactive=al| a b"),
        slot("K11a", "rm --interactive=o|"),
        slot("K11a", "rm --interactive=nev|"),
        slot("K11a E17", "rm --interactive=never|"),
        slot("K11a inside", "rm --interactive=ne|ver"),
    )

    @Test
    fun `a flag or an unknown or ambiguous long has no value to complete`() = rm.checkSlots(
        // `rm --verbose= a`: "option '--verbose' doesn't allow an argument"; `rm --zzz a`: "unrecognized option";
        // `rm --v a`: "option '--v' is ambiguous".
        slot("K45", "rm --verbose=|"),
        slot("K45 inside", "rm --verbose=|x"),
        slot("K45 words after", "rm --verbose=| a b"),
        slot("K45", "rm --force=|"),
        slot("K45", "rm --no-preserve-root=|"),
        slot("K46", "rm --zzz=|"),
        slot("K46", "rm --zzz=a|"),
        slot("K46 ambiguous", "rm --v=|"),
        slot("K46 words after", "rm --zzz=| a b"),
    )

    @Test
    fun `the word after an unknown long is an operand`() = rm.checkSlots(
        // K47: `--zzz` claims no word (`rm --zzz a`: "unrecognized option '--zzz'").
        slot("K47", "rm --zzz |"),
        slot("K47", "rm --zzz sr|"),
        slot("K47 words after", "rm --zzz | b"),
    )

    @Test
    fun `nothing completes after an ambiguous long`() = rm.checkSlots(
        // K34: `rm --v a`: "option '--v' is ambiguous; possibilities: '--verbose' '--version'", whatever follows.
        slot("K34", "rm --v |"),
        slot("K34", "rm --ver a|"),
        slot("K34 words after", "rm --ve | a"),
        slot("K34", "rm --ver -|"),
    )

    @Test
    fun `an optional value leaves a spaced word to the operands`() = rm.checkSlots(
        // K11b, K33: `rm --interactive never a` prompts for never and for a, `rm --preserve-root all` removes all.
        cells("value bare", OPERAND) + cells("value abbreviated", OPERAND) +
            slot("K11b", "rm --interactive |") +
            slot("K11b words after", "rm --interactive | a") +
            slot("K11b", "rm --preserve-root |") +
            slot("K33", "rm --pre |"),
    )

    @Test
    fun `a value in capitals is not offered`() = rm.checkSlots(
        // `rm --interactive=NE a`: "invalid argument"; `rm --preserve-root=ALL a`: "unrecognized --preserve-root
        // argument: 'ALL'".
        cells("value capitals", CAPITALS),
    )

    // --- K12, K14, K58, K59, K78 to K80: operand slots ---

    @Test
    fun `an operand completes as a file wherever the cursor stands`() = rm.checkSlots(
        slot("K12a", "rm fi|"),
        slot("K12a inside", "rm f|il"),
        slot("K12a start", "rm |file"),
        slot("K12a no word", "rm |"),
        slot("K12a words after", "rm | b"),
        slot("K12b", "rm a fi|"),
        slot("K12b inside", "rm a f|il"),
        slot("K12b start", "rm a |file"),
        slot("K12b no word", "rm a |"),
        slot("K12b words after", "rm a | b"),
        slot("K12b both sides", "rm a | b c"),
    )

    @Test
    fun `an operand -f makes optional still completes as a file`() = rm.checkSlots(
        // K78, L12, S29: `rm -f` exits 0 and `rm -f a` removes a, so the slot stays with its files.
        slot("K78", "rm -f fi|"),
        slot("K78 no word", "rm -f |"),
        slot("K78 no word", "rm |"),
        slot("K78 words after", "rm -f | other"),
        slot("K78 L12", "rm | -f"),
        slot("K78", "rm --force |"),
        slot("K78", "rm -rf |"),
    )

    @Test
    fun `an operand after an option completes as a file`() = rm.checkSlots(
        slot("K79", "rm -v fi|"),
        slot("K79", "rm --interactive=never fi|"),
        slot("K79 no word", "rm -v |"),
        slot("K79 no word", "rm --interactive=once |"),
        slot("K79 words after", "rm -v | b"),
        // K80: `rm -` is "cannot remove '-'", an operand like any other.
        slot("K80", "rm - fi|"),
        slot("K80", "rm - |"),
        slot("K80 words after", "rm - | b"),
    )

    @Test
    fun `after the end of options a name is an operand`() = rm.checkSlots(
        // K14a: `rm -- -foo`: "cannot remove '-foo'"; `rm a -- -v`: "cannot remove '-v'".
        slot("K14a", "rm -- -v|"),
        slot("K14a inside", "rm -- -|v"),
        slot("K14a start", "rm -- |-v"),
        slot("K14a no word", "rm -- |"),
        slot("K14a a second --", "rm -- -- |"),
        slot("K14a words after", "rm -- | b"),
    )

    @Test
    fun `odd words in a name slot complete as rm reads them`() = rm.checkSlots(
        // K29: `rm -5 a`: "invalid option -- '5'".
        slot("K29", "rm -5|"),
        slot("K29 words after", "rm -5| a b"),
    )

    @Test
    fun `the word after --help completes as rm reads it`() = rm.checkSlots(
        // K59: `rm --help a b` prints the help; the words after it are still read as operands.
        slot("K59", "rm --help |"),
        slot("K59", "rm --help fi|"),
        slot("K59 words after", "rm --help | b"),
    )

    // --- K7: clusters ---

    @Test
    fun `a letter rm does not know or a stray = ends what a cluster can become`() = rm.checkSlots(
        // K7s, K7t: `rm -fz a` and `rm -zf a`: "invalid option -- 'z'"; `rm -v=x a`: "invalid option -- '='".
        slot("K7s", "rm -fz|"),
        slot("K7s", "rm -zf|"),
        slot("K7s words after", "rm -fz |"),
        slot("K7t", "rm -v=|"),
        slot("K7t", "rm -fv=x|"),
        slot("K7t words after", "rm -v=| a b"),
    )

    @Test
    fun `a cluster holding every letter but one continues with that one`() = rm.checkSlotValues(
        // G88: `rm -fiIrdv a` and the same with R read every letter.
        slot("K7 G88", "rm -fiIrdv|"),
        slot("K7 G88", "rm -fiIrRd|"),
    )

    @Test
    fun `a whole cluster of every letter offers nothing`() = rm.checkSlots(slot("K7 G88", "rm -fiIrRdv|"))

    @Test
    fun `a name is described in rm's words wherever the cursor sits`() {
        // `rm --help`: -I "prompt once before removing more than three files, or when removing recursively; less
        // intrusive than -i, while still giving protection against most mistakes"; --one-file-system "when removing a
        // hierarchy recursively, skip any directory that is on a file system different from that of the corresponding
        // command line argument"; --no-preserve-root "do not treat '/' specially"; --preserve-root "do not remove '/'
        // (default); with 'all', reject any command line argument on a separate device from its parent"; -R is one of
        // `-r, -R, --recursive`; "display this help and exit", "output version information and exit".
        val generated = (NAME_KINDS + LETTER_KINDS + VALUE_KINDS).flatMap { cells(it) }.wordedOnly()
        rm.checkSlots(generated + CLUSTER_CONTINUATIONS + slot("K2 inside", "rm --he|lp"))
    }

    @Test
    fun `klap's built-ins rm lacks are not offered`() {
        // `rm --json a`, `rm --color a`, `rm --completion bash a`, `rm --docs a`, `rm --c a`, `rm --j a`, `rm --do a`:
        // "unrecognized option". The prefix `--d` is rm's --dir alone (`rm --d ed` removes ed).
        rm.checkSlotValues(
            CELLS.filter { it.builtinPrefix && !it.inventedPrefix && it.kind !in LETTER_KINDS } +
                slot("K15a", "rm --d|") +
                slot("K15a", "rm --c|") +
                slot("K15a", "rm --co|"),
        )
        rm.assertOffersNone("K2 K13e K13f K13g", "rm --|", KLAP_ONLY_LONGS)
        rm.assertOffersNone("K3 K13e K13f K13g", "rm -|", KLAP_ONLY_LONGS)
    }

    @Test
    fun `the fixture's --interactive-once is not offered`() {
        // `rm --interactive-once a`: "unrecognized option '--interactive-once'"; `rm --help` lists -I without a long.
        rm.checkSlotValues(CELLS.filter { it.inventedPrefix && !it.builtinPrefix && it.kind !in LETTER_KINDS })
        rm.assertOffersNone("K20", "rm --interactive|", listOf(INVENTED_LONG))
        rm.assertOffersNone("K2", "rm --|", listOf(INVENTED_LONG))
    }

    @Test
    fun `an abbreviation of --interactive reaches its value`() = rm.checkSlots(
        // K9, K11b, K33: `rm --i a` and `rm --inter a` prompt as --interactive, `rm --inter=o a b c e` asks once and
        // `rm --i=never a` removes without asking; getopt reads --i as the one long starting so.
        cells("value abbreviated", INVENTED_PREFIX) +
            slot("K9 abbreviated", "rm --inter=al|") +
            slot("K9 abbreviated", "rm --i=o|") +
            slot("K9 abbreviated", "rm --inter=nev|") +
            slot("K9 inside the name", "rm --inter|active=al") +
            slot("K9 before the =", "rm --interactive|=al") +
            slot("K33", "rm --inter |") +
            slot("K33 words after", "rm --i | a"),
    )

    @Test
    fun `--preserve-root= completes to all`() = rm.checkSlotValues(
        // K11a, K52: `rm --preserve-root=all a` and `rm --pre=all a` run; `--preserve-root=al` is "unrecognized
        // --preserve-root argument: 'al'", so Tab offers the whole word.
        cells("value attached", ALL_VALUE) +
            slot("K11a E17", "rm --preserve-root=all|") +
            slot("K9 abbreviated", "rm --p=|") +
            slot("K9 abbreviated", "rm --pre=a|"),
    )

    @Test
    fun `a dash offers exactly rm's names in rm's words`() =
        // `rm --help` lists every name rm has; klap's built-ins and --interactive-once are "unrecognized option".
        rm.checkSlots(DASH_POOLS + cells("dash", LETTERS))

    @Test
    fun `__complete as the first word is a file`() = rm.checkSlots(
        // K58: `rm __complete a`: "cannot remove '__complete'", an operand like any other.
        slot("K58", "rm __c|"),
        slot("K58 words after", "rm __c| a"),
        slot("K58 no word", "rm __complete |"),
        slot("K58", "rm __complete fi|"),
    )

    @Test
    fun `every value rm accepts after --interactive= is offered`() = rm.checkSlots(
        // `rm --interactive=bogus a`: "Valid arguments are: 'never', 'no', 'none' / 'once' / 'always', 'yes'"; `rm
        // --interactive=no a` and `=none` remove without asking, `=yes` and `=y` prompt.
        cells("value attached", WHEN_VALUE) +
            slot("K11a", "rm --interactive=y|") +
            slot("K11a", "rm --interactive=no|"),
    )
}
