package com.fromwau.klap.fixture.cp

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.io.CliPath
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * Every expected value is from GNU cp 9.12, run inside a throwaway directory holding a newer file `a`, an older
 * file `b` and a directory `d`; whether a choice took is read off the backup file cp made, or off whether the older
 * `b` was replaced. A refusal is cp's own parse error. cp's later refusals of a parsed line (a target directory that
 * does not exist, `--preserve=context` or `--reflink=always` on this kernel and file system) are the file system's
 * answer, so those lines bind.
 */

/** One cp line and what cp makes of it: the record it binds, or null when cp refuses it. */
private class ConverterCell(
    val argv: List<String>,
    val expected: CpInputs?,
    val because: String,
)

private fun bound(
    vararg argv: String,
    because: String = "exit 0",
    expected: CpInputs,
) = ConverterCell(argv.toList(), expected, because)

private fun refused(
    vararg argv: String,
    because: String,
) = ConverterCell(argv.toList(), null, because)

private fun ParitySuite<CpInputs>.checkValues(cells: List<ConverterCell>) = checkCells(cells) { cell ->
    val argv = cell.argv.toTypedArray()
    val expected = cell.expected
    if (expected == null) {
        rejects(argv = argv, because = cell.because)
    } else {
        binds(argv = argv, expected = expected, because = cell.because)
    }
}

private fun ab(change: CpInputs.() -> CpInputs): CpInputs = A_TO_B.change()

/** A choice of cp: each typed word cp reads and the value it reads it as, and each word argmatch refuses. */
private class ChoiceTable<T>(
    val long: String,
    val reads: List<Pair<String, T>>,
    val ambiguous: List<String>,
    val invalid: List<String>,
    val bind: CpInputs.(T) -> CpInputs,
) {
    fun accepted(): List<ConverterCell> = reads.map { (typed, value) ->
        bound("$long=$typed", "a", "b", because = "`cp $long=$typed a b` reads $value", expected = ab { bind(value) })
    }

    fun refusedValues(): List<ConverterCell> =
        ambiguous.map { refused("$long=$it", "a", "b", because = "cp: ambiguous argument '$it' for '$long'") } +
            invalid.map { refused("$long=$it", "a", "b", because = "cp: invalid argument '$it' for '$long'") }
}

private val BACKUP = ChoiceTable(
    long = "--backup",
    reads = listOf(
        "none" to BackupControl.None, "off" to BackupControl.None, "numbered" to BackupControl.Numbered,
        "t" to BackupControl.Numbered, "existing" to BackupControl.Existing, "nil" to BackupControl.Existing,
        "simple" to BackupControl.Simple, "never" to BackupControl.Simple, "nu" to BackupControl.Numbered,
        "no" to BackupControl.None, "ni" to BackupControl.Existing, "e" to BackupControl.Existing,
        "ex" to BackupControl.Existing, "s" to BackupControl.Simple, "si" to BackupControl.Simple,
        "o" to BackupControl.None, "of" to BackupControl.None, "ne" to BackupControl.Simple,
        "nev" to BackupControl.Simple,
    ),
    ambiguous = listOf("n"),
    invalid = listOf("NONE", "None", "T", "bogus", " none", "none ", "none,simple", "nil,t", "-x"),
) { copy(backup = it) }

private val SPARSE = ChoiceTable(
    long = "--sparse",
    reads = listOf(
        "auto" to When.Auto, "always" to When.Always, "never" to When.Never, "al" to When.Always, "au" to When.Auto,
        "n" to When.Never, "nev" to When.Never,
    ),
    ambiguous = listOf("a", ""),
    invalid = listOf("AUTO", "bogus", " auto"),
) { copy(sparse = it) }

private val REFLINK = ChoiceTable(
    long = "--reflink",
    reads = listOf(
        "auto" to When.Auto, "always" to When.Always, "never" to When.Never, "al" to When.Always, "au" to When.Auto,
        "n" to When.Never, "nev" to When.Never,
    ),
    ambiguous = listOf("a", ""),
    invalid = listOf("ALWAYS", "bogus"),
) { copy(reflink = it) }

private val UPDATE = ChoiceTable(
    long = "--update",
    reads = listOf(
        "all" to Update.All, "none" to Update.None, "none-fail" to Update.NoneFail, "older" to Update.Older,
        "a" to Update.All, "al" to Update.All, "o" to Update.Older, "ol" to Update.Older, "none-" to Update.NoneFail,
        "none-f" to Update.NoneFail,
    ),
    ambiguous = listOf("n", "no", "non", ""),
    invalid = listOf("ALL", "Older", "NONE", "None-Fail", "bogus", "none,all"),
) { copy(update = it) }

private val CHOICES = listOf(BACKUP, SPARSE, REFLINK, UPDATE)

/** cp's ATTR_LIST names, alone and in a list, each bound as typed. */
private val ATTRIBUTE_NAMES: List<Pair<String, List<String>>> =
    listOf("mode", "ownership", "timestamps", "links", "context", "xattr", "all").map { it to listOf(it) } +
        listOf("mode,links" to listOf("mode", "links"), "all,mode" to listOf("all", "mode"))

/** Unique prefixes of cp's ATTR_LIST names, alone and in a list, each with the names cp reads them as. */
private val ATTRIBUTE_PREFIXES: List<Pair<String, List<String>>> = listOf(
    "m" to listOf("mode"),
    "o" to listOf("ownership"),
    "t" to listOf("timestamps"),
    "ti" to listOf("timestamps"),
    "l" to listOf("links"),
    "c" to listOf("context"),
    "x" to listOf("xattr"),
    "a" to listOf("all"),
    "mo,li" to listOf("mode", "links"),
)

/** Each spelling of an attribute list: attached to either option, or the next word after the required one. */
private fun attributeCells(reads: List<Pair<String, List<String>>>): List<ConverterCell> =
    reads.flatMap { (typed, names) ->
        listOf(
            // `all` names every attribute, which the record lists one by one.
            bound("--preserve=$typed", "a", "b", expected = ab { preserving(names) }),
            bound("--no-preserve=$typed", "a", "b", expected = ab { preserving(names, on = false) }),
            bound("--no-preserve", typed, "a", "b", expected = ab { preserving(names, on = false) }),
        )
    }

/** Attribute lists cp refuses: an empty name is "ambiguous argument ''", any other the invalid name itself. */
private val ATTRIBUTE_REFUSALS: List<String> = listOf(
    "", "mode,", ",mode", "mode,,links", "mode, links", " mode", "mode ", "MODE", "bogus", "mode,bogus",
    "mode;links",
)

private fun intoD(
    dir: String,
    vararg sources: String,
): CpInputs = NOTHING_BOUND.copy(targetDirectory = CliPath(dir), sources = sources.map(::CliPath))

/** A backup under cp's default control named with [suffix]: `cp -S .x a b` makes b.x without -b. */
private fun CpInputs.suffixed(suffix: String): CpInputs = copy(backup = BackupControl.Existing, suffix = suffix)

/** The suffixes cp names a backup with, each typed as one word and bound as typed. */
private val SUFFIXES = listOf(".bak", "", "-x", "--", "-", " .s", "x y", "a\nb", ".ä", "/x", "a=b")

/** The target directories cp reads as typed; only d, d/, d// and ./d exist in the throwaway directory. */
private val DIRECTORIES = listOf("d", "d/", "d//", "./d", "", "~", "-", "--", "-x", "a=b", "d x")

/**
 * What the fixture makes of each typed cp value (X of the testing matrix): the choices of --backup, --sparse,
 * --reflink and --update with their unique prefixes, the attribute lists, and the free-text values of --suffix,
 * --context and --target-directory, bound verbatim.
 */
class CpConverterTest {

    private val parity = ParitySuite(cpCli())

    @Test
    fun `a choice binds every name and unique prefix cp reads`() =
        parity.checkValues(CHOICES.flatMap { it.accepted() })

    @Test
    fun `a choice refuses an ambiguous prefix and a word outside its names`() =
        parity.checkValues(CHOICES.flatMap { it.refusedValues() })

    @Test
    fun `a required choice takes the next word as its value`() = parity.checkValues(
        listOf(
            bound("--sparse", "always", "a", "b", expected = ab { copy(sparse = When.Always) }),
            bound("--sp=al", "a", "b", expected = ab { copy(sparse = When.Always) }),
            refused("--sparse", "-x", "a", "b", because = "cp: invalid argument '-x' for '--sparse'"),
            refused("--sparse", "", "a", "b", because = "cp: ambiguous argument '' for '--sparse'"),
            refused("a", "b", "--sparse", because = "cp: option '--sparse' requires an argument"),
        ),
    )

    @Test
    fun `an optional choice left out binds cp's default for it`() = parity.checkValues(
        listOf(
            bound("--backup", "a", "b", because = "makes b~", expected = ab { copy(backup = BackupControl.Existing) }),
            bound("--b", "a", "b", because = "makes b~", expected = ab { copy(backup = BackupControl.Existing) }),
            bound("--b=t", "a", "b", because = "makes b.~1~", expected = ab { copy(backup = BackupControl.Numbered) }),
            bound("--back=nu", "a", "b", because = "makes b.~1~", expected = ab {
                copy(backup = BackupControl.Numbered)
            }),
            bound("--reflink", "a", "b", because = "failed to clone", expected = ab { copy(reflink = When.Always) }),
            bound("--ref", "a", "b", because = "failed to clone", expected = ab { copy(reflink = When.Always) }),
            bound("--ref=au", "a", "b", expected = ab { copy(reflink = When.Auto) }),
            bound("--update", "a", "b", because = "replaces the older b", expected = ab {
                copy(update = Update.Older)
            }),
            bound("--upd", "a", "b", because = "replaces the older b", expected = ab { copy(update = Update.Older) }),
        ),
    )

    @Test
    fun `an optional choice left out leaves the next word an operand`() = parity.checkValues(
        listOf(
            // `cp --reflink auto a b` and `cp --update all a b`: target 'b': Not a directory, so the word was a source.
            bound(
                "--reflink", "auto", "a", "b",
                expected = operands("auto", "a", "b").copy(reflink = When.Always),
            ),
            bound(
                "--update", "all", "a", "b",
                expected = operands("all", "a", "b").copy(update = Update.Older),
            ),
            bound("--backup", "--", "a", "b", because = "makes b~", expected = ab {
                copy(backup = BackupControl.Existing)
            }),
            bound("--reflink", "--", "a", "b", because = "failed to clone", expected = ab {
                copy(reflink = When.Always)
            }),
            bound("--update", "--", "a", "b", because = "replaces b", expected = ab { copy(update = Update.Older) }),
            bound(
                "--backup", "-", "b",
                because = "cannot stat '-'",
                expected = operands("-", "b").copy(backup = BackupControl.Existing),
            ),
        ),
    )

    @Test
    fun `a choice given twice binds the later value`() = parity.checkValues(
        listOf(
            bound("--backup=numbered", "--backup=simple", "a", "b", because = "makes b~", expected = ab {
                copy(backup = BackupControl.Simple)
            }),
            bound("--backup=numbered", "--backup=s", "a", "b", because = "makes b~", expected = ab {
                copy(backup = BackupControl.Simple)
            }),
            bound("--backup", "--backup=never", "a", "b", because = "makes b~", expected = ab {
                copy(backup = BackupControl.Simple)
            }),
            bound("--sparse=auto", "--sparse=never", "a", "b", expected = ab { copy(sparse = When.Never) }),
            bound("--reflink=auto", "--reflink=never", "a", "b", expected = ab { copy(reflink = When.Never) }),
            bound("--reflink=au", "--reflink=al", "a", "b", because = "failed to clone", expected = ab {
                copy(reflink = When.Always)
            }),
            bound("--reflink=never", "--reflink", "a", "b", because = "failed to clone", expected = ab {
                copy(reflink = When.Always)
            }),
            bound("--update=all", "--update=none", "a", "b", because = "keeps the older b", expected = ab {
                copy(update = Update.None)
            }),
            bound("--update", "--update=none", "a", "b", because = "keeps the older b", expected = ab {
                copy(update = Update.None)
            }),
            bound("--update=none", "--update", "a", "b", because = "replaces the older b", expected = ab {
                copy(update = Update.Older)
            }),
            bound("--update=none-fail", "--update=none", "a", "b", because = "keeps b, exit 0", expected = ab {
                copy(update = Update.None)
            }),
        ),
    )

    @Test
    fun `an invalid earlier --sparse --reflink --update or attribute list refuses the line`() = parity.checkValues(
        // cp reads each of these values as it meets it, so a bad one anywhere on the line is "invalid argument".
        listOf(
            refused("--preserve=bogus", "--preserve=mode", "a", "b", because = "cp: invalid argument 'bogus'"),
            refused("--preserve=", "--preserve=mode", "a", "b", because = "cp: ambiguous argument ''"),
            refused("--no-preserve=bogus", "--no-preserve=mode", "a", "b", because = "cp: invalid argument 'bogus'"),
            refused("--sparse=bogus", "--sparse=never", "a", "b", because = "cp: invalid argument 'bogus'"),
            refused("--sparse=never", "--sparse=bogus", "a", "b", because = "cp: invalid argument 'bogus'"),
            refused("--reflink=bogus", "--reflink=auto", "a", "b", because = "cp: invalid argument 'bogus'"),
            refused("--update=bogus", "--update=all", "a", "b", because = "cp: invalid argument 'bogus'"),
            refused("--update=bogus", "--update=none", "a", "b", because = "cp: invalid argument 'bogus'"),
            refused("--backup=nil", "--backup=bogus", "a", "b", because = "cp: invalid argument 'bogus'"),
        ),
    )

    @Test
    fun `an invalid earlier --backup is never read`() = parity.checkValues(
        // cp keeps the last --backup word and reads it once, after the options: `cp --backup=bogus --backup=never a b`
        // makes b~ and exits 0.
        listOf(bound("--backup=bogus", "--backup=never", "a", "b", expected = ab {
            copy(backup = BackupControl.Simple)
        })),
    )

    @Test
    fun `-b beside --backup keeps the control --backup names`() = parity.checkValues(
        // `cp -b --backup=numbered a b` and `cp --backup=numbered -b a b` both make b.~1~.
        listOf(
            bound("-b", "--backup=numbered", "a", "b", expected = ab { copy(backup = BackupControl.Numbered) }),
            bound("--backup=numbered", "-b", "a", "b", expected = ab { copy(backup = BackupControl.Numbered) }),
        ),
    )

    @Test
    fun `an attribute list binds cp's attribute names`() = parity.checkValues(
        attributeCells(ATTRIBUTE_NAMES) + listOf(
            bound("--pres=mode", "a", "b", expected = ab { copy(preserve = listOf("mode")) }),
            bound("--no-pres=mode", "a", "b", expected = ab { copy(noPreserve = listOf("mode")) }),
        ),
    )

    @Test
    fun `an attribute list reads a unique prefix as the name it abbreviates`() =
        // `cp --preserve=m a b` exits 0 as mode.
        parity.checkValues(attributeCells(ATTRIBUTE_PREFIXES))

    @Test
    fun `an attribute list refuses a name cp does not have`() = parity.checkValues(
        ATTRIBUTE_REFUSALS.flatMap { list ->
            listOf(
                refused("--preserve=$list", "a", "b", because = "cp: invalid or ambiguous argument"),
                refused("--no-preserve=$list", "a", "b", because = "cp: invalid or ambiguous argument"),
            )
        } + listOf(
            refused("--no-preserve", "", "a", "b", because = "cp: ambiguous argument '' for '--no-preserve'"),
            refused("--no-preserve", "-x", "a", "b", because = "cp: invalid argument '-x' for '--no-preserve'"),
        ),
    )

    @Test
    fun `a repeated attribute list adds to the earlier one`() = parity.checkValues(
        // `cp --preserve=mode --preserve=timestamps a b` keeps both.
        listOf(
            bound("--preserve=mode", "--preserve=timestamps", "a", "b", expected = ab {
                copy(preserve = listOf("mode", "timestamps"))
            }),
            bound("--no-preserve=mode", "--no-preserve=links", "a", "b", expected = ab {
                copy(noPreserve = listOf("mode", "links"))
            }),
        ),
    )

    @Test
    fun `--no-preserve takes its value from the next word`() = parity.checkValues(
        listOf(
            // `cp --no-preserve a b` reads a as the list `all`, leaving b alone: missing destination file operand.
            refused("--no-preserve", "a", "b", because = "cp: missing destination file operand after 'b'"),
            refused("a", "b", "--no-preserve", because = "cp: option '--no-preserve' requires an argument"),
        ),
    )

    @Test
    fun `a list repeating a name is accepted`() {
        // `cp --preserve=mode,mode a b` and `cp --no-preserve=mode,mode a b` exit 0; cp keeps a set of attributes,
        // so how often the record holds a name is the fixture's choice.
        parity.accepts("--preserve=mode,mode", "a", "b") { assertEquals(setOf("mode"), it.preserve?.toSet()) }
        parity.accepts("--no-preserve=mode,mode", "a", "b") { assertEquals(setOf("mode"), it.noPreserve?.toSet()) }
    }

    @Test
    fun `--suffix binds its value verbatim`() = parity.checkValues(
        // `cp -b -S <value> a b` names the backup b<value>; a suffix holding a slash falls back to b~ at run time.
        SUFFIXES.flatMap { suffix ->
            listOf(
                bound("-b", "-S", suffix, "a", "b", expected = ab { suffixed(suffix) }),
                bound("-b", "--suffix=$suffix", "a", "b", expected = ab { suffixed(suffix) }),
            )
        } + listOf(
            bound("-S.bak", "a", "b", because = "makes b.bak", expected = ab { suffixed(".bak") }),
            bound("-bS.z", "a", "b", because = "makes b.z", expected = ab { suffixed(".z") }),
            bound("-S=x", "a", "b", because = "makes b=x", expected = ab { suffixed("=x") }),
            bound("-S x", "a", "b", because = "makes 'b x'", expected = ab { suffixed(" x") }),
            bound("--suffix==x", "a", "b", because = "makes b=x", expected = ab { suffixed("=x") }),
            bound("--suffix", ".y", "a", "b", because = "makes b.y", expected = ab { suffixed(".y") }),
            bound("--suf=.q", "a", "b", because = "makes b.q", expected = ab { suffixed(".q") }),
            bound("-S", ".a", "-S", ".b", "a", "b", because = "makes b.b", expected = ab { suffixed(".b") }),
            refused("a", "b", "-S", because = "cp: option requires an argument -- 'S'"),
            refused("a", "b", "--suffix", because = "cp: option '--suffix' requires an argument"),
        ),
    )

    @Test
    fun `--context binds an attached value verbatim`() = parity.checkValues(
        // Each warns "ignoring --context; it requires an SELinux-enabled kernel" and copies, exit 0.
        listOf("ctx", "", "a=b", " x", "-x", "u:r:t:s0").map {
            bound("--context=$it", "a", "b", expected = ab { copy(context = it) })
        } + listOf(
            bound("--cont=x", "a", "b", expected = ab { copy(context = "x") }),
            bound("--context=a", "--context=b", "a", "b", expected = ab { copy(context = "b") }),
        ),
    )

    @Test
    fun `--target-directory binds its value verbatim`() = parity.checkValues(
        // Every value but d, d/, d// and ./d is "target directory '<value>': No such file or directory", which cp
        // says after parsing, on looking the directory up.
        DIRECTORIES.flatMap { dir ->
            listOf(
                bound("-t", dir, "a", expected = intoD(dir, "a")),
                bound("--target-directory=$dir", "a", expected = intoD(dir, "a")),
            )
        } + listOf(
            bound("-td", "a", expected = intoD("d", "a")),
            bound("--target-directory", "d", "a", expected = intoD("d", "a")),
            bound("--tar=d", "a", expected = intoD("d", "a")),
            refused("-t", because = "cp: option requires an argument -- 't'"),
            refused("--target-directory", "a", because = "cp: missing file operand"),
        ),
    )

    @Test
    fun `the operands bind verbatim and need a source and a destination`() = parity.checkValues(
        listOf(
            bound("a", "d/", expected = operands("a", "d/")),
            bound("a//", "b", because = "cannot stat 'a//'", expected = operands("a//", "b")),
            bound("~", "b", expected = operands("~", "b")),
            bound("a", "b", "c", because = "target 'c'", expected = operands("a", "b", "c")),
            bound("-t", "d", "a", "b", expected = intoD("d", "a", "b")),
            refused("a", because = "cp: missing destination file operand after 'a'"),
            refused(because = "cp: missing file operand"),
        ),
    )
}
