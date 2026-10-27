package com.fromwau.klap.fixture.mv

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.mv.MvProjectionSupport.ABBREVIATED
import com.fromwau.klap.fixture.mv.MvProjectionSupport.ABBREVIATED_SPACED
import com.fromwau.klap.fixture.mv.MvProjectionSupport.AFTER
import com.fromwau.klap.fixture.mv.MvProjectionSupport.AFTER_END
import com.fromwau.klap.fixture.mv.MvProjectionSupport.ALL
import com.fromwau.klap.fixture.mv.MvProjectionSupport.ATTACHED
import com.fromwau.klap.fixture.mv.MvProjectionSupport.BARE
import com.fromwau.klap.fixture.mv.MvProjectionSupport.BARE_AFTER_VALUE
import com.fromwau.klap.fixture.mv.MvProjectionSupport.BARE_THEN_WORD
import com.fromwau.klap.fixture.mv.MvProjectionSupport.BETWEEN
import com.fromwau.klap.fixture.mv.MvProjectionSupport.BUILTIN_VALUE
import com.fromwau.klap.fixture.mv.MvProjectionSupport.CHOICE_ABBREVIATED
import com.fromwau.klap.fixture.mv.MvProjectionSupport.CHOICE_AMBIGUOUS
import com.fromwau.klap.fixture.mv.MvProjectionSupport.CHOICE_CAPITALS
import com.fromwau.klap.fixture.mv.MvProjectionSupport.CHOICE_EMPTY
import com.fromwau.klap.fixture.mv.MvProjectionSupport.CHOICE_UNKNOWN
import com.fromwau.klap.fixture.mv.MvProjectionSupport.CLUSTER
import com.fromwau.klap.fixture.mv.MvProjectionSupport.CLUSTER_SWALLOW
import com.fromwau.klap.fixture.mv.MvProjectionSupport.CLUSTER_VALUE
import com.fromwau.klap.fixture.mv.MvProjectionSupport.Cell
import com.fromwau.klap.fixture.mv.MvProjectionSupport.DASH_VALUE
import com.fromwau.klap.fixture.mv.MvProjectionSupport.EMPTY_VALUE
import com.fromwau.klap.fixture.mv.MvProjectionSupport.END_VALUE
import com.fromwau.klap.fixture.mv.MvProjectionSupport.EQUALS_VALUE
import com.fromwau.klap.fixture.mv.MvProjectionSupport.GLUED
import com.fromwau.klap.fixture.mv.MvProjectionSupport.LONG
import com.fromwau.klap.fixture.mv.MvProjectionSupport.LONG_ATTACHED
import com.fromwau.klap.fixture.mv.MvProjectionSupport.LONG_REPEATED
import com.fromwau.klap.fixture.mv.MvProjectionSupport.MISSING
import com.fromwau.klap.fixture.mv.MvProjectionSupport.REPEATED
import com.fromwau.klap.fixture.mv.MvProjectionSupport.SHORT
import com.fromwau.klap.fixture.mv.MvProjectionSupport.SHORT_REPEATED
import com.fromwau.klap.fixture.mv.MvProjectionSupport.SPACED
import com.fromwau.klap.fixture.mv.MvProjectionSupport.Same
import com.fromwau.klap.fixture.mv.MvProjectionSupport.UnderEnvironment
import com.fromwau.klap.fixture.mv.MvProjectionSupport.checkAll
import com.fromwau.klap.fixture.mv.MvProjectionSupport.checkSame
import com.fromwau.klap.fixture.mv.MvProjectionSupport.checkUnder
import com.fromwau.klap.fixture.mv.MvProjectionSupport.cells
import com.fromwau.klap.io.CliPath
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * Every expected value is from GNU coreutils 9.12 `mv`, run inside a throwaway directory holding the files a and b: a
 * `because` quotes what it printed or which files it left, and a comment says where a claim was read from `mv --help`
 * instead. A line mv refuses only while moving (a missing source, a target directory that does not exist) still
 * parsed, so it binds.
 */

private val FLAGS: List<String> = INPUTS.filter { it.kind == Kind.Flag }.map { it.name }

private val VALUES: List<String> = INPUTS.filter { it.kind is Kind.Value }.map { it.name }

private fun cell(
    argv: List<String>,
    expected: MvInputs?,
    because: String,
) = Cell("line", "whole line", argv, expected, because)

private val TO_DIR: MvInputs = NOTHING_BOUND.copy(targetDirectory = CliPath("d"), sources = listOf(CliPath("a")))

private val AB = listOf("a", "b")

private val EXISTING = BackupControl.Existing

private const val VC = "VERSION_CONTROL"

private const val SBS = "SIMPLE_BACKUP_SUFFIX"

private val NUMBERED = mapOf(VC to "numbered")

private val SUFFIXED = mapOf(SBS to ".env")

/**
 * The record an mv line binds in the fixture, or its refusal, as real mv 9.12 reads the line (P of the testing matrix):
 * every input in every spelling and position, values at the edges of their word, repeats and overrides, the three
 * operand shapes, and help, version and klap's built-ins on a line.
 */
class MvProjectionTest {

    private val parity = ParitySuite(mvCli())

    @Test
    fun `every flag binds by its long name its abbreviation and each short alone doubled and clustered`() =
        parity.checkAll(cells(FLAGS, LONG, LONG_REPEATED, ABBREVIATED, SHORT, SHORT_REPEATED, CLUSTER))

    @Test
    fun `a flag refuses a value attached to its long name`() = parity.checkAll(cells(FLAGS, LONG_ATTACHED))

    @Test
    fun `every input binds the same record before between and after the operands`() =
        parity.checkAll(cells(FLAGS + VALUES, BETWEEN, AFTER))

    @Test
    fun `every spelling after the end of options is an operand`() =
        parity.checkAll(cells(FLAGS + VALUES, AFTER_END))

    @Test
    fun `every value binds attached spaced glued abbreviated and at the end of a cluster`() =
        parity.checkAll(cells(VALUES, ATTACHED, SPACED, GLUED, CLUSTER_VALUE, ABBREVIATED, ABBREVIATED_SPACED))

    @Test
    fun `a required value is refused when the line ends before it`() = parity.checkAll(cells(VALUES, MISSING))

    @Test
    fun `an optional value left out binds its default and leaves the next word an operand`() =
        // `mv --backup numbered a b`: target 'b': Not a directory, so the word was a source.
        parity.checkAll(cells(VALUES, BARE, BARE_THEN_WORD, BARE_AFTER_VALUE))

    @Test
    fun `a free text value takes the next word whatever it spells`() =
        parity.checkAll(cells(VALUES, DASH_VALUE, END_VALUE, BUILTIN_VALUE))

    @Test
    fun `a free text value keeps an equals sign and may be empty`() =
        parity.checkAll(cells(VALUES, EQUALS_VALUE, EMPTY_VALUE))

    @Test
    fun `a value-taking short takes the rest of its cluster as its value`() =
        parity.checkAll(cells(VALUES, CLUSTER_SWALLOW))

    @Test
    fun `a choice binds every value and its unique prefix and refuses the rest`() =
        parity.checkAll(
            cells(VALUES, CHOICE_ABBREVIATED, CHOICE_AMBIGUOUS, CHOICE_CAPITALS, CHOICE_UNKNOWN, CHOICE_EMPTY),
        )

    @Test
    fun `a value given twice binds as mv reads the repeat`() = parity.checkAll(cells(VALUES, REPEATED))

    @Test
    fun `every cell of every input is checked by one test`() {
        val covered = setOf(
            LONG, LONG_REPEATED, LONG_ATTACHED, ABBREVIATED, ABBREVIATED_SPACED, SHORT, SHORT_REPEATED, CLUSTER,
            BETWEEN, AFTER, AFTER_END, ATTACHED, SPACED, GLUED, CLUSTER_VALUE, CLUSTER_SWALLOW, MISSING, DASH_VALUE,
            END_VALUE, BUILTIN_VALUE, EQUALS_VALUE, EMPTY_VALUE, BARE, BARE_THEN_WORD, BARE_AFTER_VALUE,
            CHOICE_ABBREVIATED, CHOICE_AMBIGUOUS, CHOICE_CAPITALS, CHOICE_UNKNOWN, CHOICE_EMPTY, REPEATED,
        )
        val unchecked = ALL.filter { it.form !in covered }
        assertTrue(unchecked.isEmpty(), "cells no test checks: $unchecked")
    }

    @Test
    fun `the operands bind as each of mv's three usage shapes reads them`() = parity.checkAll(
        listOf(
            cell(AB, A_TO_B, "mv a b renames a to b"),
            cell(listOf("a", "b", "d"), operands("a", "b", "d"), "into d"),
            cell(listOf("-t", "d", "a"), TO_DIR, "`mv -t d a` moves a into d"),
            cell(listOf("a", "-t", "d"), TO_DIR, "`mv a -t d` moves a into d"),
            cell(listOf("a", "b", "-t", "d"), TO_DIR.copy(sources = AB.map(::CliPath)), "`mv a b -t d`: both into d"),
            cell(listOf("a", "-t", "d", "b"), TO_DIR.copy(sources = AB.map(::CliPath)), "`mv a -t d b`: both into d"),
            cell(listOf("--target-directory=d", "a"), TO_DIR, "`mv --target-directory=d a`"),
            cell(listOf("-T", "a", "b"), A_TO_B.copy(noTargetDirectory = true), "mv -T a b"),
            cell(listOf("a", "b", "-T"), A_TO_B.copy(noTargetDirectory = true), "`mv a b -T` runs"),
            cell(listOf("--", "-foo", "bar"), operands("-foo", "bar"), "--"),
            cell(listOf("a", "--", "-v"), operands("a", "-v"), "makes a file -v"),
            cell(listOf("--", "a", "--"), operands("a", "--"), "makes a file --"),
            cell(listOf("-t", "d", "--", "-x"), TO_DIR.copy(sources = listOf(CliPath("-x"))), "getopt: an operand"),
            cell(listOf("--", "-5", "b"), operands("-5", "b"), "cannot stat '-5'"),
            cell(listOf("-", "b"), operands("-", "b"), "mv: cannot stat '-'"),
            cell(listOf("", "b"), operands("", "b"), "mv: cannot stat ''"),
            cell(listOf("a b", "c"), operands("a b", "c"), "cannot stat 'a b'"),
            // `~/x` was not run, as the oracle's safety rule forbids a `~` path; mv gets the word as the shell left it.
            cell(listOf("ü*", "~/x"), operands("ü*", "~/x"), "mv reads the word"),
            cell(emptyList(), null, "mv: missing file operand"),
            cell(listOf("-i"), null, "mv: missing file operand"),
            cell(listOf("a"), null, "mv: missing destination file operand after 'a'"),
            cell(listOf("-t", "d"), null, "mv: missing file operand"),
            cell(listOf("-T", "a"), null, "mv: missing destination file operand after 'a'"),
            cell(listOf("--"), null, "mv: missing file operand"),
        ),
    )

    @Test
    fun `a line setting every input binds them all`() = parity.checkAll(
        listOf(
            // `mv --backup=numbered -b --debug -f --no-copy --strip-trailing-slashes -S .bak -t d -u --update=older -v
            // a` moved a into d; -Z is left out, as the oracle may not run it.
            cell(
                listOf(
                    "--backup=numbered", "-b", "--debug", "-f", "--no-copy", "--strip-trailing-slashes", "-S", ".bak",
                    "-t", "d", "--update=older", "-v", "a",
                ),
                TO_DIR.copy(
                    backup = BackupControl.Numbered,
                    debug = true,
                    force = true,
                    noCopy = true,
                    stripTrailingSlashes = true,
                    suffix = ".bak",
                    update = Update.Older,
                    verbose = true,
                ),
                "mv exits 0",
            ),
            // `mv --exchange -T -n a b` exchanged a and b.
            cell(
                listOf("--exchange", "-T", "-n", "a", "b"),
                A_TO_B.copy(exchange = true, noTargetDirectory = true, noClobber = true),
                "mv exits 0",
            ),
        ),
    )

    @Test
    fun `the later of -f -i and -n decides in separate words and in one cluster`() {
        // `mv -n -i a b` and `mv -ni a b` prompt; `mv -i -n a b` and `mv -in a b` skip silently; `mv -i -f a b` and
        // `mv -nf a b` replace b; `mv -f -i a b` prompts.
        parity.binds("-n", "-i", "a", "b", expected = A_TO_B.copy(interactive = true))
        parity.binds("-i", "-n", "a", "b", expected = A_TO_B.copy(noClobber = true))
        parity.binds("-ni", "a", "b", expected = A_TO_B.copy(interactive = true))
        parity.binds("-in", "a", "b", expected = A_TO_B.copy(noClobber = true))
        parity.binds("-i", "-f", "a", "b", expected = A_TO_B.copy(force = true))
        parity.binds("-f", "-i", "a", "b", expected = A_TO_B.copy(interactive = true))
        parity.binds("-nf", "a", "b", expected = A_TO_B.copy(force = true))
        parity.binds("-fn", "a", "b", expected = A_TO_B.copy(noClobber = true))
        parity.binds("--no-clobber", "--interactive", "a", "b", expected = A_TO_B.copy(interactive = true))
    }

    @Test
    fun `--update applies alongside -f -i and -n in either order`() {
        // `mv -f --update=none a b` and `mv --update=none -f a b` both keep b; `mv -n --update=all a b` and
        // `mv --update=all -n a b` both keep it too: --update is a setting of its own beside the prompt.
        parity.binds("-f", "--update=none", "a", "b", expected = A_TO_B.copy(force = true, update = Update.None))
        parity.binds("--update=none", "-f", "a", "b", expected = A_TO_B.copy(force = true, update = Update.None))
        parity.binds("-n", "--update=all", "a", "b", expected = A_TO_B.copy(noClobber = true, update = Update.All))
        parity.binds("--update=all", "-n", "a", "b", expected = A_TO_B.copy(noClobber = true, update = Update.All))
    }

    @Test
    fun `binds the unique abbreviation of a long name and of a choice`() {
        // `mv --st a/ c`, `mv --verb a b`, `mv --t d a`, `mv --upd=old a b`, `mv --update=none-f a b`, `mv --e a b`
        // and `mv --no-t a b` each run as the long they abbreviate.
        parity.binds("--verb", "a", "b", expected = A_TO_B.copy(verbose = true))
        parity.binds("--t", "d", "a", expected = TO_DIR)
        parity.binds("--upd=old", "a", "b", expected = A_TO_B.copy(update = Update.Older))
        parity.binds("--update=none-", "a", "b", expected = A_TO_B.copy(update = Update.NoneFail))
        parity.binds("--e", "a", "b", expected = A_TO_B.copy(exchange = true))
        parity.binds("--no-t", "a", "b", expected = A_TO_B.copy(noTargetDirectory = true))
        parity.binds("--back=nu", "a", "b", expected = A_TO_B.copy(backup = BackupControl.Numbered))
        parity.binds("--strip", "a/", "c", expected = NOTHING_BOUND.copy(
            stripTrailingSlashes = true,
            sources = listOf(CliPath("a/")),
            dest = CliPath("c"),
        ))
    }

    @Test
    fun `rejects an ambiguous abbreviation as getopt does`() {
        val ambiguous = mapOf(
            "--n" to "'--no-clobber' '--no-copy' '--no-target-directory'",
            "--no-" to "'--no-clobber' '--no-copy' '--no-target-directory'",
            "--no-c" to "'--no-clobber' '--no-copy'",
            "--s" to "'--strip-trailing-slashes' '--suffix'",
            "--v" to "'--verbose' '--version'",
            "--ver" to "'--verbose' '--version'",
        )
        checkCells(ambiguous.entries.toList()) { (prefix, candidates) ->
            val because = "real mv: option '$prefix' is ambiguous; possibilities: $candidates"
            parity.rejects(prefix, "a", "b", because = because)
        }
    }

    @Test
    fun `rejects what real mv rejects`() {
        parity.rejects("--zzz", because = "real mv: unrecognized option '--zzz'")
        parity.rejects("--r", "a", "b", because = "real mv: unrecognized option '--r'")
        parity.rejects("--x", "a", "b", because = "real mv: unrecognized option '--x'")
        parity.rejects("--help-all", "a", "b", because = "real mv: unrecognized option '--help-all'")
        parity.rejects("-Q", "a", "b", because = "real mv: invalid option -- 'Q'")
        parity.rejects("-h", "a", "b", because = "real mv: invalid option -- 'h'")
        parity.rejects("-vh", "a", "b", because = "real mv: invalid option -- 'h'")
        parity.rejects("-5", "a", "b", because = "real mv: invalid option -- '5'")
        parity.rejects("-v=x", "a", "b", because = "real mv: invalid option -- '='")
        parity.rejects("--force=yes", "a", "b", because = "real mv: option '--force' doesn't allow an argument")
        parity.rejects("--verbose=", "a", "b", because = "real mv: option '--verbose' doesn't allow an argument")
        parity.rejects("--update=n", "a", "b", because = "real mv: ambiguous argument 'n' for '--update'")
        parity.rejects("--update=OLDER", "a", "b", because = "real mv: invalid argument 'OLDER' for '--update'")
        parity.rejects("--backup=n", "a", "b", because = "real mv: ambiguous argument 'n' for 'backup type'")
        parity.rejects("--backup=NU", "a", "b", because = "real mv: invalid argument 'NU' for 'backup type'")
        parity.rejects("--backup=numbered=", "a", "b", because = "real mv: invalid argument 'numbered='")
        parity.rejects("--su", "a", "b", because = "real mv: --suffix takes a, leaving b with no destination")
        parity.rejects("-S", because = "real mv: option requires an argument -- 'S'")
        parity.rejects("--target-directory", because = "real mv: option '--target-directory' requires an argument")
    }

    @Test
    fun `help and version answer wherever they sit on a valid line`() {
        parity.showsHelp("--help", because = "mv --help prints its usage")
        parity.showsHelp("--help", "a", "b", because = "mv --help a b prints its usage")
        parity.showsHelp("a", "b", "--help", because = "mv a b --help prints its usage")
        parity.showsHelp("-v", "--help", "a", "b", because = "mv -v --help a b prints its usage")
        parity.showsHelp("--he", because = "mv --he prints its usage")
        parity.showsHelp("--h", because = "mv has no other long starting with h")
        parity.showsVersion("--version", because = "mv --version prints its banner")
        parity.showsVersion("--vers", because = "mv --vers prints its banner")
        parity.showsVersion("--version", "a", because = "mv --version a prints its banner")
    }

    @Test
    fun `help answers ahead of a refusal mv makes after reading its options`() {
        // mv checks its operands, and -t against -T, only once getopt is done, so help comes first.
        parity.showsHelp("-T", "--help", "a", "b", "c", because = "mv -T --help a b c prints its usage")
        parity.showsHelp("-t", "d", "-T", "--help", "a", because = "mv -t d -T --help a prints its usage")
        parity.showsHelp("--help", "-t", "a", "-t", "b", because = "mv --help -t a -t b prints its usage")
        parity.showsHelp("-t", "a", "--help", "-t", "b", because = "mv -t a --help -t b prints its usage")
        parity.showsHelp("--help", "--update=bogus", because = "mv --help --update=bogus prints its usage")
        parity.showsHelp("--help", "--backup=bogus", because = "mv --help --backup=bogus prints its usage")
    }

    @Test
    fun `the first of help and version answers`() {
        parity.showsHelp("--help", "--version", because = "mv --help --version prints its usage")
        parity.showsVersion("--version", "--help", because = "mv --version --help prints its banner")
    }

    @Test
    fun `help or version written before an unknown option answers`() {
        // `mv --help --bogus` and `mv --help -h` print the usage; `mv --version --bogus` and `mv --version -5` print
        // the banner.
        parity.showsHelp("--help", "--bogus", because = "mv --help --bogus prints its usage")
        parity.showsHelp("--help", "-h", because = "mv --help -h prints its usage")
        parity.showsVersion("--version", "--bogus", because = "mv --version --bogus prints its banner")
        parity.showsVersion("--version", "-5", because = "mv --version -5 prints its banner")
    }

    @Test
    fun `an unknown option or a bad value written before help refuses the line`() = parity.checkAll(
        listOf(
            cell(listOf("--bogus", "--help"), null, "mv: unrecognized option '--bogus'"),
            cell(listOf("-h", "--help"), null, "mv: invalid option -- 'h'"),
            cell(listOf("--update=bogus", "--help"), null, "mv: invalid argument 'bogus' for '--update'"),
            cell(listOf("-t", "a", "-t", "b", "--help"), null, "mv: multiple target directories specified"),
        ),
    )

    @Test
    fun `a word naming a klap built-in is an operand`() = parity.checkAll(
        // `mv a completion` and `mv a __complete` each make a file of that name.
        listOf(
            cell(listOf("completion", "b"), operands("completion", "b"), "a file"),
            cell(listOf("docs", "b"), operands("docs", "b"), "a file"),
            cell(listOf("a", "__complete"), operands("a", "__complete"), "a file"),
            cell(listOf("--", "--help", "b"), operands("--help", "b"), "an operand"),
        ),
    )

    @Test
    fun `options mv accepts together bind`() = parity.checkAll(
        listOf(
            // `mv -n -i -b a b` prompts, so -i won over -n and the backup stands.
            cell(listOf("-n", "-i", "-b", "a", "b"), A_TO_B.copy(interactive = true, backup = EXISTING), "prompts"),
            // `mv -n -f -b a b` made b~.
            cell(listOf("-n", "-f", "-b", "a", "b"), A_TO_B.copy(force = true, backup = EXISTING), "made b~"),
            cell(
                listOf("--update=none", "--update=all", "-b", "a", "b"),
                A_TO_B.copy(update = Update.All, backup = EXISTING),
                "made b~",
            ),
            cell(listOf("--exchange", "-n", "a", "b"), A_TO_B.copy(exchange = true, noClobber = true), "exit 0"),
            cell(listOf("--exchange", "--no-copy", "a", "b"), A_TO_B.copy(exchange = true, noCopy = true), "exit 0"),
            cell(listOf("-T", "-T", "a", "b"), A_TO_B.copy(noTargetDirectory = true), "`mv -T -T a b` runs"),
        ),
    )

    @Test
    fun `a second target directory is refused`() {
        val why = "real mv: multiple target directories specified"
        parity.rejects("-t", "d", "-t", "e", "a", because = why)
        parity.rejects("-t", "d", "-t", "d", "a", because = why)
        parity.rejects("-t", "d", "--target-directory=d", "a", because = why)
    }

    @Test
    fun `a backup without its own control or suffix takes them from the environment`() = checkUnder(
        // Real mv 9.12, b existing: under VERSION_CONTROL=numbered, `mv -b a b`, `mv --backup a b` and `mv -S .x a b`
        // make b.~1~; VERSION_CONTROL=nu is numbered, `n` is "ambiguous argument 'n' for '$VERSION_CONTROL'" and
        // `bogus` "invalid argument", refused before moving; an empty VERSION_CONTROL is the default, b~.
        // SIMPLE_BACKUP_SUFFIX=.env `mv -b a b` makes b.env; an empty one, or one holding a slash, makes b~.
        listOf(
            UnderEnvironment(NUMBERED, listOf("-b") + AB, listOf("--backup=numbered") + AB, "b.~1~"),
            UnderEnvironment(NUMBERED, listOf("--backup") + AB, listOf("--backup=numbered") + AB, "b.~1~"),
            UnderEnvironment(NUMBERED, listOf("-S", ".x") + AB, listOf("--backup=numbered", "-S", ".x") + AB, "b.~1~"),
            UnderEnvironment(mapOf(VC to "nu"), listOf("-b") + AB, listOf("--backup=numbered") + AB, "b.~1~"),
            UnderEnvironment(mapOf(VC to "n"), listOf("-b") + AB, null, "ambiguous argument 'n'"),
            UnderEnvironment(mapOf(VC to "bogus"), listOf("-b") + AB, null, "invalid argument 'bogus'"),
            UnderEnvironment(mapOf(VC to ""), listOf("-b") + AB, listOf("-b") + AB, "the default control, b~"),
            UnderEnvironment(SUFFIXED, listOf("-b") + AB, listOf("-b", "-S", ".env") + AB, "b.env"),
            UnderEnvironment(mapOf(SBS to ""), listOf("-b") + AB, listOf("-b") + AB, "empty is ignored, b~"),
            UnderEnvironment(mapOf(SBS to "x/y"), listOf("-b") + AB, listOf("-b") + AB, "a slash falls back to b~"),
        ),
    )

    @Test
    fun `the backup environment changes nothing where mv makes no backup or names its control`() = checkUnder(
        // Real mv 9.12 reads VERSION_CONTROL and SIMPLE_BACKUP_SUFFIX only when it backs up, and an explicit control or
        // suffix wins: `VERSION_CONTROL=bogus mv a b` and `VERSION_CONTROL=bogus mv -n a b` exit 0,
        // `VERSION_CONTROL=numbered mv --backup=simple a b` makes b~, `VERSION_CONTROL=bogus mv --backup=t a b` b.~1~,
        // `SIMPLE_BACKUP_SUFFIX=.env mv a b` makes no backup and `SIMPLE_BACKUP_SUFFIX=.env mv -b -S .x a b` makes b.x.
        listOf(
            UnderEnvironment(NUMBERED, AB, AB, "no backup"),
            UnderEnvironment(mapOf(VC to "bogus"), AB, AB, "no backup, so the variable is not read"),
            UnderEnvironment(mapOf(VC to "bogus"), listOf("-n") + AB, listOf("-n") + AB, "no backup"),
            UnderEnvironment(NUMBERED, listOf("--backup=simple") + AB, listOf("--backup=simple") + AB, "b~"),
            UnderEnvironment(mapOf(VC to "bogus"), listOf("--backup=t") + AB, listOf("--backup=t") + AB, "b.~1~"),
            UnderEnvironment(SUFFIXED, AB, AB, "no backup"),
            UnderEnvironment(SUFFIXED, listOf("-b", "-S", ".x") + AB, listOf("-b", "-S", ".x") + AB, "b.x"),
        ),
    )

    @Test
    fun `a line without --update binds what --update=all binds`() = checkSame(
        // `mv --help`: 'all' is the default operation when an --update option is not specified. Both lines replace b.
        listOf(Same(listOf("--update=all", "a", "b"), AB, "the default update mode named and left out")),
    )

    @Test
    fun `-b and a bare --backup are one setting`() = checkSame(
        // `mv --help`: "-b  like --backup but does not accept an argument". `mv --backup=simple -b a b` made b~ beside
        // b.~1~; `mv --backup=none -b a b` and `mv -b --backup=none a b` made no backup.
        listOf(
            Same(listOf("-b", "a", "b"), listOf("--backup", "a", "b"), "neither names a control"),
            Same(listOf("--backup=simple", "-b", "a", "b"), listOf("--backup=simple", "a", "b"), "b~"),
            Same(listOf("-b", "--backup=none", "a", "b"), listOf("--backup=none", "a", "b"), "no backup"),
            Same(listOf("--backup=none", "-b", "a", "b"), listOf("--backup=none", "a", "b"), "no backup"),
            Same(listOf("-bb", "a", "b"), listOf("--backup", "a", "b"), "`mv -bb a b` made b~"),
        ),
    )

    @Test
    fun `a bare --backup after a value keeps the value`() =
        // `mv --backup=simple --backup a b` beside b.~1~ made b~, not b.~2~: the bare one names no control.
        parity.checkAll(cells(listOf("--backup"), BARE_AFTER_VALUE))

    @Test
    fun `an empty backup control means the default one`() {
        // `mv --backup= a b` beside b.~1~ made b.~2~, as `mv -b` does there; `mv --backup=simple --backup= a b` made
        // b.~2~ too, so an empty control does not keep an earlier one.
        parity.checkAll(cells(listOf("--backup"), CHOICE_EMPTY))
        checkSame(
            listOf(
                Same(listOf("--backup=", "a", "b"), listOf("-b", "a", "b"), "both name the default control"),
                Same(listOf("--backup=simple", "--backup=", "a", "b"), listOf("--backup=", "a", "b"), "b.~2~"),
                Same(listOf("--b=", "a", "b"), listOf("--backup", "a", "b"), "`mv --b= a b` reads as --backup="),
            ),
        )
    }

    @Test
    fun `a backup control alias binds what its control binds`() = checkSame(
        // `mv --help`: "none, off / numbered, t / existing, nil / simple, never". `mv --backup=t a b` made b.~1~,
        // `--backup=nil` b~, `--backup=off` none.
        CONTROL_OF.filter { (name, control) -> name != control }.map { (alias, control) ->
            Same(listOf("--backup=$alias", "a", "b"), listOf("--backup=$control", "a", "b"), "mv --help")
        },
    )

    @Test
    fun `a bad backup control ahead of help still shows help`() {
        // mv checks the backup type only once getopt is done, so `mv --backup=bogus --help` prints its usage.
        parity.showsHelp("--backup=bogus", "--help", because = "mv --backup=bogus --help prints its usage")
    }

    @Test
    fun `a suffix asks for a backup`() = checkSame(
        // `mv -S .x a b` and `mv --suffix=.x a b` made b.x without -b.
        listOf(
            Same(listOf("-S", ".x", "a", "b"), listOf("-b", "-S", ".x", "a", "b"), "made b.x"),
            Same(listOf("--suffix=.x", "a", "b"), listOf("--backup", "--suffix=.x", "a", "b"), "made b.x"),
        ),
    )

    @Test
    fun `-u is --update without a value and the later of the two wins`() {
        // With b older than a: `mv --update=none -u a b` replaced b; `mv -u --update=none a b` left it.
        checkSame(
            listOf(
                Same(listOf("-u", "a", "b"), listOf("--update", "a", "b"), "mv --help: equivalent to --update[=older]"),
                Same(listOf("-u", "a", "b"), listOf("--update=older", "a", "b"), "mv --help"),
                Same(listOf("--update=none", "-u", "a", "b"), listOf("--update=older", "a", "b"), "replaced b"),
                Same(listOf("-u", "--update=none", "a", "b"), listOf("--update=none", "a", "b"), "kept b"),
            ),
        )
    }

    @Test
    fun `--debug implies -v`() = checkSame(
        // `mv --help`: "explain how a file is copied.  Implies -v"; `mv --debug a b` printed "renamed 'a' -> 'b'".
        listOf(Same(listOf("--debug", "a", "b"), listOf("--debug", "-v", "a", "b"), "both print renamed 'a' -> 'b'")),
    )

    @Test
    fun `options mv cannot combine are refused`() {
        val why = "mv: cannot combine --backup with --exchange, -n, or --update=none-fail"
        parity.checkAll(
            listOf(
                cell(listOf("-n", "-b", "a", "b"), null, why),
                cell(listOf("-b", "-n", "a", "b"), null, why),
                cell(listOf("-i", "-n", "-b", "a", "b"), null, why),
                cell(listOf("--backup=none", "-n", "a", "b"), null, why),
                cell(listOf("--update=none", "-b", "a", "b"), null, why),
                cell(listOf("-u", "--update=none", "-b", "a", "b"), null, why),
                cell(listOf("--update=none-fail", "-b", "a", "b"), null, why),
                cell(listOf("--backup", "--update=none-fail", "a", "b"), null, why),
                cell(listOf("-b", "--exchange", "a", "b"), null, why),
                cell(listOf("--backup=off", "--exchange", "a", "b"), null, why),
                cell(listOf("-S", ".x", "-n", "a", "b"), null, why),
                cell(listOf("-S", ".x", "--exchange", "a", "b"), null, why),
            ),
        )
    }

    @Test
    fun `-t and -T are refused together`() {
        val why = "real mv: cannot combine --target-directory (-t) and --no-target-directory (-T)"
        parity.rejects("-t", "d", "-T", "a", because = why)
        parity.rejects("-T", "-t", "d", "a", because = why)
        parity.rejects("-t", "dir", "src", "-T", because = why)
    }

    @Test
    fun `-T takes exactly a source and a destination`() {
        parity.rejects("-T", "a", "b", "c", because = "real mv: extra operand 'c'")
        parity.rejects("a", "b", "c", "-T", because = "real mv: extra operand 'c'")
    }

    @Test
    fun `an option mv does not have is refused`() {
        // `mv --json`, `mv --color=never`, `mv --completion bash`, `mv --docs`, `mv --col`, `mv --j`, `mv --com`,
        // `mv --do`: all "unrecognized option".
        val lines = listOf(
            listOf("--json", "a", "b"),
            listOf("--color=never", "a", "b"),
            listOf("--color", "never", "a", "b"),
            listOf("--completion", "bash", "a", "b"),
            listOf("--docs", "markdown", "a", "b"),
            listOf("--col=never", "a", "b"),
            listOf("--j", "a", "b"),
            listOf("--com", "bash", "a", "b"),
            listOf("--do", "man", "a", "b"),
        )
        checkCells(lines) { parity.rejects(argv = it.toTypedArray(), because = "real mv: unrecognized option") }
    }

    @Test
    fun `--c and --d abbreviate --context and --debug`() {
        // `mv --d a b` printed "renamed 'a' -> 'b'" as --debug. `getopt -o bfint:uvS:TZ -l <mv's longs> -- --c a b`
        // gives --context, mv's one long starting with c.
        parity.checkAll(cells(listOf("--debug", "--context"), ABBREVIATED))
        checkSame(listOf(Same(listOf("--co", "a", "b"), listOf("--context", "a", "b"), "getopt_long")))
    }

    @Test
    fun `__complete is a source file name`() {
        // `mv __complete a`: "cannot stat '__complete'", so it was a source like any other word.
        parity.binds("__complete", "a", expected = operands("__complete", "a"))
    }

    @Test
    fun `--backup and --update show an optional value and no bare one`() {
        // `mv --help`: "--backup[=CONTROL]" and "--update[=UPDATE]".
        val page = parity.helpPage("--help", because = "mv --help")
        val rows = page.lines().filter { "--backup" in it || "--update" in it }
        assertTrue(rows.any { "--backup[=CONTROL]" in it } && rows.any { "--update[=UPDATE]" in it }, page)
        assertTrue(rows.none { "bare:" in it }, rows.joinToString("\n"))
    }

    @Test
    fun `a source and a destination keep a trailing slash`() {
        // GNU mv 9.12, link -> dir: `mv link/ m1` is "cannot move 'link/' to 'm1': Not a directory" where `mv link m2`
        // renames the link; `mv a nd/` is "cannot move 'a' to 'nd/': Not a directory" where `mv a nd` renames a.
        parity.binds("link/", "m1", expected = operands("link/", "m1"))
        parity.binds("link", "m2", expected = operands("link", "m2"))
        parity.binds("a", "nd/", expected = operands("a", "nd/"))
        parity.binds("a", "nd", expected = operands("a", "nd"))
    }

    @Test
    fun `a value option's row names its value as mv --help does`() {
        // `mv --help` (GNU coreutils 9.12) writes each of these spellings at the head of its row.
        val signatures = listOf(
            "--backup[=CONTROL]",
            "-S, --suffix=SUFFIX",
            "-t, --target-directory=DIRECTORY",
            "--update[=UPDATE]",
        )
        val heads = parity.helpPage("--help", because = "mv --help").lines().map { it.trim().substringBefore("  ") }
        assertEquals(signatures, signatures.filter { it in heads }, heads.joinToString("\n"))
    }

    @Test
    fun `the help page lists mv's options and nothing mv refuses`() {
        val page = parity.helpPage("--help", because = "mv --help")
        val missing = MV_LONGS.filterNot { it in page }
        val foreign = KLAP_ONLY_LONGS.filter { long -> Regex("${Regex.escape(long)}(?![\\w-])").containsMatchIn(page) }
        assertTrue(missing.isEmpty() && foreign.isEmpty(), "mv --help page: missing $missing, foreign $foreign\n$page")
    }
}
