package com.fromwau.klap.fixture.mv

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.mv.BackupControl.Existing
import com.fromwau.klap.fixture.mv.BackupControl.Numbered
import com.fromwau.klap.fixture.mv.BackupControl.Simple
import kotlin.test.Test

/*
 * Every expected value is from GNU mv 9.12, run inside a throwaway directory holding a file `a`, a newer file `b` and,
 * where a line names it, a directory `d`; whether a choice took is read off the backup file mv made, or off whether
 * `b` was replaced. A refusal is mv's own parse error. mv's later refusals of a parsed line (a target directory that
 * does not exist, a source that does not) are the file system's answer, so those lines bind.
 */

/** One mv line and what mv makes of it: the record it binds, or null when mv refuses it. */
private class ConverterCell(
    val argv: List<String>,
    val expected: MvInputs?,
    val because: String,
)

private fun bound(
    vararg argv: String,
    because: String = "exit 0",
    expected: MvInputs,
) = ConverterCell(argv.toList(), expected, because)

private fun refused(
    vararg argv: String,
    because: String,
) = ConverterCell(argv.toList(), null, because)

private fun ParitySuite<MvInputs>.checkValues(cells: List<ConverterCell>) = checkCells(cells) { cell ->
    val argv = cell.argv.toTypedArray()
    val expected = cell.expected
    if (expected == null) {
        rejects(argv = argv, because = cell.because)
    } else {
        binds(argv = argv, expected = expected, because = cell.because)
    }
}

private fun ab(change: MvInputs.() -> MvInputs): MvInputs = A_TO_B.change()

/** A choice of mv: each typed word mv reads and the name it reads it as, and each word argmatch refuses. */
private class ChoiceTable(
    val long: String,
    val reads: List<Pair<String, String>>,
    val ambiguous: List<String>,
    val invalid: List<String>,
    val bind: MvInputs.(String) -> MvInputs,
) {
    fun accepted(): List<ConverterCell> = reads.map { (typed, value) ->
        bound("$long=$typed", "a", "b", because = "`mv $long=$typed a b` reads $value", expected = ab { bind(value) })
    }

    fun refusedValues(): List<ConverterCell> =
        ambiguous.map { refused("$long=$it", "a", "b", because = "mv: ambiguous argument '$it' for '$long'") } +
            invalid.map { refused("$long=$it", "a", "b", because = "mv: invalid argument '$it' for '$long'") }
}

private val BACKUP = ChoiceTable(
    long = "--backup",
    reads = listOf(
        "none" to "none", "off" to "off", "numbered" to "numbered", "t" to "t", "existing" to "existing",
        "nil" to "nil", "simple" to "simple", "never" to "never", "nu" to "numbered", "no" to "none",
        "ni" to "nil", "e" to "existing", "ex" to "existing", "s" to "simple", "si" to "simple",
        "o" to "off", "of" to "off", "ne" to "never", "nev" to "never",
    ),
    ambiguous = listOf("n"),
    invalid = listOf("NONE", "None", "T", "bogus", " none", "none ", "none,simple", "nil,t", "-x", "numbered="),
) { copy(backup = controlOf(it)) }

private val UPDATE = ChoiceTable(
    long = "--update",
    reads = listOf(
        "all" to "all", "none" to "none", "none-fail" to "none-fail", "older" to "older", "a" to "all",
        "al" to "all", "o" to "older", "ol" to "older", "none-" to "none-fail", "none-f" to "none-fail",
    ),
    ambiguous = listOf("n", "no", "non", ""),
    invalid = listOf("ALL", "Older", "NONE", "None-Fail", "bogus", "none,all", "OLDER"),
) { copy(update = updateOf(it)) }

private val CHOICES = listOf(BACKUP, UPDATE)

private fun intoD(
    dir: String,
    vararg sources: String,
): MvInputs = NOTHING_BOUND.copy(targetDirectory = dir, sources = sources.toList())

/** A backup with mv's default control, named with [suffix]. */
private fun MvInputs.suffixed(suffix: String): MvInputs = copy(backup = Existing, suffix = suffix)

/** The suffixes mv names a backup with, each typed as one word and bound as typed. */
private val SUFFIXES = listOf(".bak", "", "-x", "--", "-", " .s", "x y", ".ä", "/x", "a=b")

/** The target directories mv reads as typed; only d, d/, d// and ./d exist in the throwaway directory. */
private val DIRECTORIES = listOf("d", "d/", "d//", "./d", "", "-", "--", "-x", "a=b", "d x")

/**
 * What the fixture makes of each typed mv value (X of the testing matrix): the choices of --backup and --update with
 * their unique prefixes, and the free-text values of --suffix and --target-directory, bound verbatim.
 */
class MvConverterTest {

    private val parity = ParitySuite(mvCli())

    @Test
    fun `a choice binds every name and unique prefix mv reads`() =
        parity.checkValues(CHOICES.flatMap { it.accepted() })

    @Test
    fun `a choice refuses an ambiguous prefix and a word outside its names`() =
        parity.checkValues(CHOICES.flatMap { it.refusedValues() })

    @Test
    fun `an optional choice left out binds mv's default for it`() = parity.checkValues(
        listOf(
            bound("--backup", "a", "b", because = "made b~", expected = ab { copy(backup = Existing) }),
            bound("--b", "a", "b", because = "made b~", expected = ab { copy(backup = Existing) }),
            bound("--b=t", "a", "b", because = "made b.~1~", expected = ab { copy(backup = Numbered) }),
            bound("--back=nu", "a", "b", because = "made b.~1~", expected = ab { copy(backup = Numbered) }),
            bound("--update", "a", "b", because = "kept the newer b", expected = ab { copy(update = Update.Older) }),
            bound("--upd", "a", "b", because = "kept the newer b", expected = ab { copy(update = Update.Older) }),
        ),
    )

    @Test
    fun `an optional choice left out leaves the next word an operand`() = parity.checkValues(
        listOf(
            // `mv --update all a b`: target 'b': Not a directory, so the word was a source.
            bound(
                "--update", "all", "a", "b",
                expected = NOTHING_BOUND.copy(update = Update.Older, sources = listOf("all", "a"), dest = "b"),
            ),
            bound("--backup", "--", "a", "b", because = "made b~", expected = ab { copy(backup = Existing) }),
            bound("--update", "--", "a", "b", because = "kept b", expected = ab { copy(update = Update.Older) }),
            bound(
                "--backup", "-", "b",
                because = "cannot stat '-'",
                expected = NOTHING_BOUND.copy(backup = Existing, sources = listOf("-"), dest = "b"),
            ),
        ),
    )

    @Test
    fun `a choice given twice binds the later value`() = parity.checkValues(
        listOf(
            bound("--backup=numbered", "--backup=simple", "a", "b", because = "made b~", expected = ab {
                copy(backup = Simple)
            }),
            bound("--backup=numbered", "--backup=s", "a", "b", because = "made b~", expected = ab {
                copy(backup = Simple)
            }),
            bound("--backup", "--backup=never", "a", "b", because = "made b~", expected = ab {
                copy(backup = Simple)
            }),
            bound("--update=all", "--update=none", "a", "b", because = "kept b", expected = ab {
                copy(update = Update.None)
            }),
            bound("--update", "--update=none", "a", "b", because = "kept b", expected = ab {
                copy(update = Update.None)
            }),
            bound("--update=none", "--update", "a", "b", because = "replaced the older b", expected = ab {
                copy(update = Update.Older)
            }),
            bound("--update=none-fail", "--update=none", "a", "b", because = "kept b, exit 0", expected = ab {
                copy(update = Update.None)
            }),
        ),
    )

    @Test
    fun `an invalid --update anywhere on the line refuses it`() = parity.checkValues(
        // mv reads each --update as it meets it, so a bad one anywhere is "invalid argument"; the last --backup is read
        // once the options are done, so a bad last one is refused too.
        listOf(
            refused("--update=bogus", "--update=all", "a", "b", because = "mv: invalid argument 'bogus'"),
            refused("--update=all", "--update=bogus", "a", "b", because = "mv: invalid argument 'bogus'"),
            refused("--backup=nil", "--backup=bogus", "a", "b", because = "mv: invalid argument 'bogus'"),
        ),
    )

    @Test
    fun `--suffix binds its value verbatim`() = parity.checkValues(
        // `mv -b -S <value> a b` names the backup b<value>; an empty suffix or one holding a slash falls back to b~
        // when mv moves, which the record leaves to the action.
        SUFFIXES.flatMap { suffix ->
            listOf(
                bound("-b", "-S", suffix, "a", "b", expected = ab { suffixed(suffix) }),
                bound("-b", "--suffix=$suffix", "a", "b", expected = ab { suffixed(suffix) }),
            )
        } + listOf(
            bound("-bS.z", "a", "b", because = "made b.z", expected = ab { suffixed(".z") }),
            bound("-b", "-S=", "a", "b", because = "made b=", expected = ab { suffixed("=") }),
            bound("--suffix==x", "-b", "a", "b", because = "made b=x", expected = ab { suffixed("=x") }),
            bound("-S", ".a", "-S", ".b", "-b", "a", "b", because = "made b.b", expected = ab { suffixed(".b") }),
            bound("-S .x", "a", "b", because = "made 'b .x'", expected = ab { suffixed(" .x") }),
            bound("--suf=.q", "a", "b", because = "made b.q", expected = ab { suffixed(".q") }),
            refused("a", "b", "-S", because = "mv: option requires an argument -- 'S'"),
            refused("a", "b", "--suffix", because = "mv: option '--suffix' requires an argument"),
        ),
    )

    @Test
    fun `--target-directory binds its value verbatim`() = parity.checkValues(
        // Every value but d, d/, d// and ./d is "target directory '<value>': No such file or directory", which mv says
        // after parsing, on looking the directory up.
        DIRECTORIES.flatMap { dir ->
            listOf(
                bound("-t", dir, "a", expected = intoD(dir, "a")),
                bound("--target-directory=$dir", "a", expected = intoD(dir, "a")),
            )
        } + listOf(
            bound("-td", "a", expected = intoD("d", "a")),
            bound("--target-directory", "d", "a", expected = intoD("d", "a")),
            bound("--tar=d", "a", expected = intoD("d", "a")),
            // `~` was not run, as the oracle's safety rule forbids it as a value; mv gets the word as the shell
            // left it.
            bound("-t", "~", "a", because = "mv reads the word", expected = intoD("~", "a")),
            refused("-t", because = "mv: option requires an argument -- 't'"),
            refused("--target-directory", "a", because = "mv: missing file operand"),
        ),
    )

    @Test
    fun `the operands bind verbatim and need a source and a destination`() = parity.checkValues(
        listOf(
            bound("a", "d/", expected = NOTHING_BOUND.copy(sources = listOf("a"), dest = "d/")),
            bound("a//", "b", because = "cannot stat 'a//'", expected = NOTHING_BOUND.copy(
                sources = listOf("a//"),
                dest = "b",
            )),
            bound("a", "b", "c", because = "target 'c'", expected = NOTHING_BOUND.copy(
                sources = listOf("a", "b"),
                dest = "c",
            )),
            bound("-t", "d", "a", "b", expected = intoD("d", "a", "b")),
            refused("a", because = "mv: missing destination file operand after 'a'"),
            refused(because = "mv: missing file operand"),
        ),
    )

    @Test
    fun `an invalid earlier --backup is never read`() = parity.checkValues(
        // mv keeps the last --backup word and reads it once, after the options: `mv --backup=bogus --backup=never a b`
        // made b~ and exited 0.
        listOf(
            bound("--backup=bogus", "--backup=never", "a", "b", expected = ab { copy(backup = Simple) }),
            bound("--backup=NU", "--backup=t", "a", "b", because = "made b.~1~", expected = ab {
                copy(backup = Numbered)
            }),
        ),
    )
}
