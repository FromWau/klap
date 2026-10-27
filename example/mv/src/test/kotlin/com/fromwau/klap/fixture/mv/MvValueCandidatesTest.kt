package com.fromwau.klap.fixture.mv

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.mv.MvLineContext.FILES
import com.fromwau.klap.fixture.mv.MvLineContext.at
import com.fromwau.klap.fixture.mv.MvLineContext.checkContext
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/*
 * Every expectation is what real mv 9.12 accepts at the cursor. A hand-written cell names its V rows (and the K, L
 * or G rows it meets) and is typed with `|` at the cursor. The evidence is a run of real mv inside a throwaway
 * directory unless a comment says `mv --help`.
 */

private val NOTHING: Array<Suggestion> = emptyArray()

private val VALUE_FORMS = listOf(
    "value no word",
    "value word start",
    "value spaced",
    "value attached",
    "value abbreviated",
    "value short",
    "value cluster",
)

/** [value] attached to [spelled], the way [long] is typed on the line, described by mv's help for [long]. */
private fun attached(
    spelled: String,
    value: String,
    long: String = spelled,
): Suggestion = Suggestion("$spelled=$value", input(long).help)

/** [values] attached to [spelled], each described by mv's help for [long]. */
private fun values(
    spelled: String,
    long: String,
    vararg values: String,
): Array<Suggestion> = values.map { attached(spelled, it, long) }.toTypedArray()

private fun backupValues(
    spelled: String,
    vararg values: String,
): Array<Suggestion> = values(spelled, "--backup", *values)

private fun Cli.checkGenerated(cells: List<CompletionCell>) = checkCells(cells) { assertMv(it) }

/**
 * Where the candidates for an mv value come from (V of the testing matrix): a choice's values in mv's case, the
 * shell's file completion for the target directory and every operand, nothing for free text, and the operand slot's
 * source wherever no option took the word. Every expectation is what real mv 9.12 accepts at the cursor.
 */
class MvValueCandidatesTest {

    private val mv = mvCli().cli

    @Test
    fun `a directory value completes through the shell in every form`() =
        mv.checkGenerated(VALUE_FORMS.flatMap { cells(it, PATH_VALUE) })

    @Test
    fun `a free text value has no candidates in any form`() =
        mv.checkGenerated(VALUE_FORMS.flatMap { cells(it, TEXT_VALUE) })

    @Test
    fun `an optional value completes only attached and leaves a spaced word to the operands`() =
        // `mv --help`: `--backup[=CONTROL]`, `--update[=UPDATE]`; `mv --backup numbered a b`: "target 'b': Not a
        // directory", so numbered was a source.
        mv.checkGenerated(VALUE_FORMS.flatMap { cells(it, OPTIONAL) }.plain())

    @Test
    fun `an optional value offers its values attached`() =
        mv.checkSlotValues(VALUE_FORMS.flatMap { cells(it, OPTIONAL) }.wordedOnly())

    @Test
    fun `a choice typed in capitals is not offered`() = mv.checkGenerated(cells("value capitals", CAPITALS))

    @Test
    fun `a choice typed in capitals is not offered in any form`() = mv.checkContext(
        // `mv --backup=NU a b`: "invalid argument 'NU' for 'backup type'"; `mv --update=OLDER a b`: "invalid argument
        // 'OLDER' for '--update'": argmatch matches a value in its own case only (V2c and V13 settled for G13).
        at("V13 V2c", "mv --backup=NU|", *NOTHING),
        at("V13 V2c V95", "mv --back=NU|", *NOTHING),
        at("V13 V2c", "mv --backup=Nu|", *NOTHING),
        at("V13 V2c", "mv --update=OL|", *NOTHING),
    )

    @Test
    fun `a choice that is a prefix of another offers itself and every longer value`() = mv.checkContext(
        // `mv --backup=n a b`: "ambiguous argument 'n' for 'backup type'", while each of none, nil, numbered and never
        // is accepted; `mv --backup=t a b` made `b.~1~`.
        at("V14", "mv --backup=n|", *backupValues("--backup", "none", "numbered", "nil", "never")),
        at("V14 L2", "mv --backup=n| a b", *backupValues("--backup", "none", "numbered", "nil", "never")),
        at("V14", "mv --backup=t|", attached("--backup", "t")),
        at("V14", "mv --backup=n|il", *backupValues("--backup", "none", "numbered", "nil", "never")),
    )

    @Test
    fun `an optional choice offers every value attached and leaves the spaced word to the operands`() =
        mv.checkContext(
            // `mv --backup numbered a b`: "target 'b': Not a directory"; `mv --update x b`: "cannot stat 'x'", so the
            // word after the bare option is a source (K11b).
            at("V18 V2d", "mv --backup=|", *backupValues("--backup", *BACKUP_CONTROLS.toTypedArray())),
            at("V18 V2d L2", "mv --backup=| a b", *backupValues("--backup", *BACKUP_CONTROLS.toTypedArray())),
            at("V18 K11b", "mv --backup |", FILES),
            at("V18 K11b L2", "mv --backup | a b", FILES),
            at("V18 K11b", "mv --backup nu|", FILES),
            at("V18 K11b", "mv --update |", FILES),
            at("V18 K11b", "mv --update no|", FILES),
        )

    @Test
    fun `the bare value of an optional choice is not put first`() {
        // `mv --help`: `--backup[=CONTROL]` lists none first, `--update[=UPDATE]` names older the default and last.
        for ((line, bare) in listOf("mv --backup=" to "--backup=existing", "mv --update=" to "--update=older")) {
            val offered = mv.suggestionsFor(line).map { it.value }
            assertEquals(true, bare in offered, "V18 `$line|`: the bare value $bare is offered: $offered")
            assertNotEquals(bare, offered.first(), "V18 `$line|`: the bare value is put first: $offered")
        }
    }

    @Test
    fun `the target directory and every source complete through the shell`() = mv.checkContext(
        // `mv -t b a` with `b` a file: "target directory 'a': Not a directory" names a directory there; klap has one
        // file directive (V27, decision K37).
        at("V27 V3a", "mv -t |", FILES),
        at("V27 V3a L2", "mv -t | a", FILES),
        at("V27 V3a", "mv --target-directory |", FILES),
        at("V27 V3b", "mv --target-directory=|", filesAfter("--target-directory=")),
        at("V27 V3b L2", "mv --target-directory=d| a", filesAfter("--target-directory=")),
        at("V3c V95", "mv --targ=d|", filesAfter("--targ=")),
        at("V3c V95", "mv --t=|", filesAfter("--t=")),
        at("V3d", "mv -td|", filesAfter("-t")),
        at("V3d L2", "mv -td| a", filesAfter("-t")),
        at("V3e", "mv -vtd|", filesAfter("-vt")),
        at("V3e", "mv -fvtd| a", filesAfter("-fvt")),
        // A source is any file, as many as given (V24).
        at("V24", "mv a |", FILES),
        at("V24", "mv a b |", FILES),
        at("V24", "mv a b c|", FILES),
        at("V24 L2", "mv a | b", FILES),
        at("V24 K12d1", "mv -t d a |", FILES),
        at("V24 K12d1", "mv -t d a b|", FILES),
    )

    @Test
    fun `an unknown option claims no word so the next one is an operand`() = mv.checkContext(
        // `mv --color a b`, `mv --completion bash a b` and `mv --docs a b`: "unrecognized option", like `--zzz`; an
        // unknown option claims no word (decision K47), so no color mode or shell name is offered after it (V20, V21).
        at("V96 K47", "mv --zzz |", FILES),
        at("V96 K47 L2", "mv --zzz | a b", FILES),
        at("V20 K47", "mv --color |", FILES),
        at("V20 K47 L2", "mv --color | a b", FILES),
        at("V21 K47", "mv --completion |", FILES),
        at("V21 K47", "mv --completion b|", FILES),
        at("V21 K47", "mv --docs |", FILES),
    )

    @Test
    fun `a value typed with dashes is completed by its option's source`() = mv.checkContext(
        // `mv -S -x -b a b` makes `b-x`; `mv -S --help -b a b` makes `b--help`: the word is the suffix.
        at("V63", "mv --suffix --h|", *NOTHING),
        at("V63", "mv -S -|", *NOTHING),
        at("V63", "mv -S --update=|", *NOTHING),
        // `mv -t -d a`: "target directory '-d'": the dash word is the directory.
        at("V63", "mv -t -|", FILES),
        at("V63", "mv --target-directory --h|", FILES),
    )

    @Test
    fun `a value slot reached through an abbreviation keeps the typed spelling as its head`() = mv.checkContext(
        // `mv --back=nu a b` made `b.~1~`; `mv --t d a` moved a into d.
        at("V95", "mv --back=nu|", attached("--back", "numbered", "--backup")),
        at("V95", "mv --ba=|", *backupValues("--ba", *BACKUP_CONTROLS.toTypedArray())),
        at("V95 V27", "mv --targ |", FILES),
        at("V95 V27", "mv --targ=|", filesAfter("--targ=")),
        at("V95 V1a", "mv --suf |", *NOTHING),
        at("V95 V1a", "mv --suf=.b|", *NOTHING),
        // `mv --back numbered a b`: "target 'b': Not a directory" (decision K11).
        at("V95 K11b", "mv --back |", FILES),
    )

    @Test
    fun `an ambiguous abbreviation leaves the next word without candidates`() = mv.checkContext(
        // `mv --s a b`: "option '--s' is ambiguous; possibilities: '--strip-trailing-slashes' '--suffix'"; `--no-c` the
        // same for --no-clobber and --no-copy. The line is refused whatever follows (decision K34, G90).
        at("V96 G90", "mv --s |", *NOTHING),
        at("V96 G90 L2", "mv --s | a b", *NOTHING),
        at("V96 G90", "mv --no-c |", *NOTHING),
        at("V96 G90", "mv --s .b|", *NOTHING),
    )

    @Test
    fun `an ambiguous abbreviation attached to its value has no candidates`() = mv.checkContext(
        // `mv --s=x a b`: "option '--s=x' is ambiguous", so no option's values (G90).
        at("V96 G90", "mv --s=|", *NOTHING),
        at("V96 G90", "mv --n=|", *NOTHING),
        at("V96 G90 L2", "mv --s=| a b", *NOTHING),
    )

    @Test
    fun `a spaced value after a cluster ending in its letter comes from that option's source`() = mv.checkContext(
        // `mv -bS.z a b` made `b.z`: the rest of the word, or the next one, is -S's or -t's value.
        at("V97", "mv -fvt |", FILES),
        at("V97 L2", "mv -fvt | a b", FILES),
        at("V97", "mv -vt d|", FILES),
        at("V97 V1a", "mv -fvS |", *NOTHING),
        at("V97 V1a L2", "mv -vS | a b", *NOTHING),
    )

    @Test
    fun `a word taken as an earlier option's value leaves the cursor on an operand`() = mv.checkContext(
        // `mv -S --help -b a b` made `b--help`: the word after -S is the suffix, so the cursor is a source.
        at("V98", "mv --suffix --update |", FILES),
        at("V98", "mv -S --backup |", FILES),
        at("V98 L2", "mv -S --update | a b", FILES),
        at("V98", "mv -t -S |", FILES),
    )

    @Test
    fun `a value taking short with nothing glued is offered as its own name`() = mv.checkContext(
        // `mv -S` and `mv -t`: "option requires an argument", so `-S` is a whole word whose value is the next one
        // (V99 settled: a name, as decision K10 has it).
        at("V99 K6", "mv -S|", Suggestion("-S", input("--suffix").help)),
        at("V99 K6 L2", "mv -S| .b a b", Suggestion("-S", input("--suffix").help)),
        at("V99 K6", "mv -t|", Suggestion("-t", input("--target-directory").help)),
    )

    @Test
    fun `an attached update mode is described by mv's help`() = mv.checkContext(
        // `mv --help`: "--update[=UPDATE]  control which existing files are updated; UPDATE={all,none,none-fail,
        // older(default)}". `mv --update=none a b` runs, `mv --update=none-f a b`: "not replacing 'b'".
        at("V14 V2d G5", "mv --update=none|", *values("--update", "--update", "none", "none-fail")),
        at("V14 V95", "mv --up=none|", *values("--up", "--update", "none", "none-fail")),
        at("V18 V2d", "mv --update=|", *values("--update", "--update", *UPDATE_MODES.toTypedArray())),
        at("V2d V95", "mv --upd=o|", *values("--upd", "--update", "older")),
    )

    @Test
    fun `an optional value completes attached in mv's words`() =
        mv.checkGenerated(VALUE_FORMS.flatMap { cells(it, OPTIONAL) }.wordedOnly())

    @Test
    fun `a built in mv does not have takes no attached value`() = mv.checkContext(
        // `mv --color a b`, `mv --color=never a b`, `mv --col a b`, `mv --completion bash a b`: "unrecognized option".
        // An unknown option claims no word (decision K47), so no color mode or shell name is offered after it (V20,
        // V21) and the word is the operand slot's; attached, nothing is offered (K46).
        at("V20 K46", "mv --color=|", *NOTHING),
        at("V20 K46", "mv --color=al|", *NOTHING),
        at("V20 K46", "mv --col=|", *NOTHING),
    )
}
