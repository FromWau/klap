package com.fromwau.klap.fixture.cp

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.cp.CpLineContext.FILES
import com.fromwau.klap.fixture.cp.CpLineContext.at
import com.fromwau.klap.fixture.cp.CpLineContext.checkContext
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/*
 * Every expectation is what real cp 9.12 accepts at the cursor. A hand-written cell names its V rows (and the K, L
 * or G rows it meets) and is typed with `|` at the cursor. The evidence is a run of real cp inside a throwaway
 * directory unless a comment says `cp --help`.
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

/** [value] attached to [spelled], the way [long] is typed on the line, described by cp's help for [long]. */
private fun attached(
    spelled: String,
    value: String,
    long: String = spelled,
): Suggestion = Suggestion("$spelled=$value", input(long).help)

/** A value in its own word: a choice carries no description there (G7). */
private fun spaced(vararg values: String): Array<Suggestion> = values.map { Suggestion(it) }.toTypedArray()

private fun Cli.checkGenerated(cells: List<CompletionCell>) = checkCells(cells) { assertCp(it) }

/**
 * Where the candidates for a cp value come from (V of the testing matrix): a choice's values in cp's case, the
 * shell's file completion for the target directory and every operand, nothing for free text, and the operand
 * slot's source wherever no option took the word. Every expectation is what real cp 9.12 accepts at the cursor.
 */
class CpValueCandidatesTest {

    private val cp = cpCli().cli

    @Test
    fun `a directory value completes through the shell in every form`() =
        cp.checkGenerated(VALUE_FORMS.flatMap { cells(it, PATH_VALUE) })

    @Test
    fun `a free text value has no candidates in any form`() =
        cp.checkGenerated(VALUE_FORMS.flatMap { cells(it, TEXT_VALUE) })

    @Test
    fun `a required choice completes its values in every form`() =
        cp.checkGenerated(VALUE_FORMS.flatMap { cells(it, CHOICE) })

    @Test
    fun `an optional value completes only attached and leaves a spaced word to the operands`() =
        // `cp --help`: `--preserve[=ATTR_LIST]`, `--context[=CTX]`; `cp --preserve mode src dst`: "target 'dst': Not a
        // directory", so mode was a source.
        cp.checkGenerated(VALUE_FORMS.flatMap { cells(it, OPTIONAL) })

    @Test
    fun `an attribute list completes cp's attribute names after each comma`() =
        // `cp --help`: ATTR_LIST is mode, ownership, timestamps, links, context, xattr and all; a name already in the
        // list is not offered again.
        cp.checkGenerated((VALUE_FORMS + "value list").flatMap { cells(it, ATTRIBUTE) })

    @Test
    fun `a choice typed in capitals is not offered`() = cp.checkGenerated(cells("value capitals", CAPITALS))

    @Test
    fun `a choice typed in capitals is not offered in any form`() = cp.checkContext(
        // `cp --sparse=AL a b`: "invalid argument 'AL' for '--sparse'"; `cp --backup=NU a b` the same for the
        // backup type: argmatch matches a value in its own case only (V2c and V13 settled for G13).
        at("V13 V2c", "cp --sparse AL|", *NOTHING),
        at("V13 V2c", "cp --sparse=AL|", *NOTHING),
        at("V13 V2c", "cp --backup=NU|", *NOTHING),
        at("V13 V2c V95", "cp --back=NU|", *NOTHING),
        at("V13 V2c", "cp --backup=Nu|", *NOTHING),
    )

    @Test
    fun `a choice that is a prefix of another offers itself and every longer value`() = cp.checkContext(
        // `cp --backup=n a b`: "ambiguous argument 'n'", while each of none, nil, numbered and never is accepted;
        // `cp --backup=t a b` exits 0 and makes `b.~1~`.
        at("V14", "cp --backup=n|", *backupValues("--backup", "none", "nil", "numbered", "never")),
        at("V14 L2", "cp --backup=n| a b", *backupValues("--backup", "none", "nil", "numbered", "never")),
        at("V14", "cp --backup=t|", attached("--backup", "t")),
        at("V14", "cp --backup=n|il", *backupValues("--backup", "none", "nil", "numbered", "never")),
        // `cp --sparse=a a b`: "ambiguous argument 'a'"; `cp --sparse=al a b` exits 0.
        at("V14 V2a", "cp --sparse a|", *spaced("auto", "always")),
        at("V14 V2a", "cp --sparse al|", *spaced("always")),
        at("V14 V2a L2", "cp --sparse n| a b", *spaced("never")),
    )

    @Test
    fun `an optional choice offers every value attached and leaves the spaced word to the operands`() =
        cp.checkContext(
            // `cp --backup numbered a b`, `cp --update none a b`, `cp --reflink never a b`: "target 'b': Not a
            // directory", so the word after the bare option is a source (K11b).
            at("V18 V2d", "cp --backup=|", *backupValues("--backup", *BACKUP_CONTROLS.toTypedArray())),
            at("V18 V2d L2", "cp --backup=| a b", *backupValues("--backup", *BACKUP_CONTROLS.toTypedArray())),
            at("V18 K11b", "cp --backup |", FILES),
            at("V18 K11b L2", "cp --backup | a b", FILES),
            at("V18 K11b", "cp --backup nu|", FILES),
            at("V18 K11b", "cp --update |", FILES),
            at("V18 K11b", "cp --reflink |", FILES),
            at("V18 K11b", "cp --reflink ne|", FILES),
        )

    @Test
    fun `an attached choice keeps cp's help through prefixes and abbreviations`() = cp.checkContext(
        // `cp --help`: "--sparse=WHEN  control creation of sparse files. See below", "--update[=UPDATE]  control which
        // existing files are updated; UPDATE={all,none,none-fail,older(default)}", "--reflink[=WHEN]  control
        // clone/CoW copies. See below". `cp --update=none a b` exits 0, `cp --update=none-f a b`: "not replacing 'b'".
        at("V14 V2d", "cp --update=none|", *values("--update", "--update", "none", "none-fail")),
        at("V14 V95", "cp --up=none|", *values("--up", "--update", "none", "none-fail")),
        at("V2d V95", "cp --spa=al|", *values("--spa", "--sparse", "always")),
        at("V2d V95", "cp --refl=a|", *values("--refl", "--reflink", "auto", "always")),
        at("V18 V2d", "cp --update=|", *values("--update", "--update", "all", "none", "none-fail", "older")),
    )

    @Test
    fun `a bare --preserve or --context leaves the next option to take the cursor word`() = cp.checkContext(
        // `cp --context --sparse always a b` and `cp --preserve --sparse always a b` exit 0: the bare option leaves
        // --sparse to take the cursor word.
        at("V98 V18", "cp --context --sparse |", *spaced("auto", "always", "never")),
        at("V98 V18", "cp --preserve --sparse |", *spaced("auto", "always", "never")),
    )

    @Test
    fun `the bare value of an optional choice is not put first`() {
        // `cp --help`: `--backup[=CONTROL]`, `--update[=UPDATE]` with older the default, `--reflink[=WHEN]`.
        for ((line, bare) in listOf(
            "cp --backup=" to "--backup=existing",
            "cp --update=" to "--update=older",
            "cp --reflink=" to "--reflink=always",
        )) {
            val offered = cp.suggestionsFor(line).map { it.value }
            assertEquals(true, bare in offered, "V18 `$line|`: the bare value $bare is offered: $offered")
            assertNotEquals(bare, offered.first(), "V18 `$line|`: the bare value is put first: $offered")
        }
    }

    @Test
    fun `the target directory and every source complete through the shell`() = cp.checkContext(
        // `cp -t b a` with `b` a file: "target directory 'b': Not a directory", so cp takes a directory there;
        // klap has one file directive (V27, decision K37).
        at("V27 V3a", "cp -t |", FILES),
        at("V27 V3a L2", "cp -t | a", FILES),
        at("V27 V3a", "cp --target-directory |", FILES),
        at("V27 V3b", "cp --target-directory=|", filesAfter("--target-directory=")),
        at("V27 V3b L2", "cp --target-directory=d| a", filesAfter("--target-directory=")),
        at("V3c V95", "cp --targ=d|", filesAfter("--targ=")),
        at("V3c V95", "cp --t=|", filesAfter("--t=")),
        at("V3d", "cp -td|", filesAfter("-t")),
        at("V3d L2", "cp -td| a", filesAfter("-t")),
        // `cp -vtd a` copies `a` into `d`.
        at("V3e", "cp -vtd|", filesAfter("-vt")),
        at("V3e", "cp -rftd| a", filesAfter("-rft")),
        // A source is any file, as many as given (V24).
        at("V24", "cp a |", FILES),
        at("V24", "cp a b |", FILES),
        at("V24", "cp a b c|", FILES),
        at("V24 L2", "cp a | b", FILES),
        at("V24 K12d1", "cp -t d a |", FILES),
        at("V24 K12d1", "cp -t d a b|", FILES),
    )

    @Test
    fun `a built in cp does not have claims no word so the next one is an operand`() = cp.checkContext(
        // `cp --color a b`, `cp --color=always a b`, `cp --col=always a b`, `cp --completion bash a b`:
        // "unrecognized option". An unknown option claims no word (decision K47), so no color mode or shell name is
        // offered after it (V20, V21) and the word is the operand slot's; attached, nothing is offered (K46).
        at("V20 K47", "cp --color |", FILES),
        at("V20 K47 L2", "cp --color | a b", FILES),
        at("V20 K46", "cp --color=|", *NOTHING),
        at("V20 K46", "cp --color=al|", *NOTHING),
        at("V20 K46", "cp --col=|", *NOTHING),
        at("V21 K47", "cp --completion |", FILES),
        at("V21 K47", "cp --completion b|", FILES),
        at("V96 K47", "cp --zzz |", FILES),
    )

    @Test
    fun `a value typed with dashes is completed by its option's source`() = cp.checkContext(
        // `cp --suf --h -b a b` makes `b--h`; `cp -S --sparse -b a b` makes `b--sparse`: the word is the suffix.
        at("V63", "cp --suffix --h|", *NOTHING),
        at("V63", "cp -S -|", *NOTHING),
        at("V63", "cp -S --sparse=|", *NOTHING),
        // `cp -t -d a` and `cp --target-directory -d a`: "target directory '-d': No such file or directory".
        at("V63", "cp -t -|", FILES),
        at("V63", "cp --target-directory --h|", FILES),
        // `cp --sparse --x a b`: "invalid argument '--x' for '--sparse'": no choice starts with a dash.
        at("V63", "cp --sparse -|", *NOTHING),
        at("V63", "cp --sparse --x|", *NOTHING),
    )

    @Test
    fun `a value slot reached through an abbreviation keeps the typed spelling as its head`() = cp.checkContext(
        // `cp --back=nu a b` exits 0 and makes `b.~1~`; `cp --targ d a` and `cp --targ=d a` copy into `d`.
        at("V95", "cp --back=nu|", attached("--back", "numbered", "--backup")),
        at("V95", "cp --ba=|", *backupValues("--ba", *BACKUP_CONTROLS.toTypedArray())),
        at("V95 V2a", "cp --spa |", *spaced("auto", "always", "never")),
        at("V95 V2a", "cp --spa al|", *spaced("always")),
        at("V95 V27", "cp --targ |", FILES),
        at("V95 V27", "cp --targ=|", filesAfter("--targ=")),
        at("V95 V1a", "cp --suf |", *NOTHING),
        at("V95 V1a", "cp --suf=.b|", *NOTHING),
        // `cp --back numbered a b`: "target 'b': Not a directory" (decision K11).
        at("V95 K11b", "cp --back |", FILES),
    )

    @Test
    fun `an ambiguous abbreviation leaves the next word without candidates`() = cp.checkContext(
        // `cp --co a b`: "option '--co' is ambiguous; possibilities: '--copy-contents' '--context'"; `--re` the same
        // for --recursive, --remove-destination and --reflink; `--s` for --sparse, --strip-trailing-slashes, --suffix
        // and --symbolic-link. The line is refused whatever follows (decision K34, G90).
        at("V96 G90", "cp --co |", *NOTHING),
        at("V96 G90 L2", "cp --co | a b", *NOTHING),
        at("V96 G90", "cp --re |", *NOTHING),
        at("V96 G90", "cp --s |", *NOTHING),
        at("V96 G90", "cp --s al|", *NOTHING),
    )

    @Test
    fun `an ambiguous abbreviation attached to its value has no candidates`() = cp.checkContext(
        // `cp --co=x a b` and `cp --s=x a b`: "option '--co=x' is ambiguous", so no option's values (G90).
        at("V96 G90", "cp --co=|", *NOTHING),
        at("V96 G90", "cp --re=|", *NOTHING),
        at("V96 G90", "cp --s=|", *NOTHING),
        at("V96 G90 L2", "cp --s=| a b", *NOTHING),
    )

    @Test
    fun `a spaced value after a cluster ending in its letter comes from that option's source`() = cp.checkContext(
        // `cp -vtd a` copies `a` into `d`: the word after `-vt` is the directory.
        at("V97", "cp -rft |", FILES),
        at("V97 L2", "cp -rft | a b", FILES),
        at("V97", "cp -vt d|", FILES),
        at("V97 V1a", "cp -rfS |", *NOTHING),
        at("V97 V1a L2", "cp -vS | a b", *NOTHING),
    )

    @Test
    fun `a word taken as an earlier option's value leaves the cursor on an operand`() = cp.checkContext(
        // `cp --suffix --sparse a b` exits 0 and makes `b--sparse`: --sparse is the suffix, so the cursor is a source.
        at("V98", "cp --suffix --sparse |", FILES),
        at("V98", "cp -S --sparse |", FILES),
        at("V98 L2", "cp -S --sparse | a b", FILES),
        // `cp -t -d a`: "target directory '-d'", so -S after -t is the directory and the cursor a source.
        at("V98", "cp -t -S |", FILES),
    )

    @Test
    fun `a value taking short with nothing glued is offered as its own name`() = cp.checkContext(
        // `cp -S` and `cp -t`: "option requires an argument", so `-S` is a whole word whose value is the next one
        // (V99 settled: a name, as decision K10 has it).
        at("V99 K6", "cp -S|", Suggestion("-S", input("--suffix").help)),
        at("V99 K6 L2", "cp -S| .b a b", Suggestion("-S", input("--suffix").help)),
        at("V99 K6", "cp -t|", Suggestion("-t", input("--target-directory").help)),
    )
}

/** [values] attached to [spelled], each described by cp's help for [long]. */
private fun values(
    spelled: String,
    long: String,
    vararg values: String,
): Array<Suggestion> = values.map { attached(spelled, it, long) }.toTypedArray()

private fun backupValues(
    spelled: String,
    vararg values: String,
): Array<Suggestion> = values(spelled, "--backup", *values)
