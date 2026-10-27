package com.fromwau.klap.fixture.cp

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.cp.CpProjectionSupport.ABBREVIATED
import com.fromwau.klap.fixture.cp.CpProjectionSupport.ABBREVIATED_SPACED
import com.fromwau.klap.fixture.cp.CpProjectionSupport.AFTER
import com.fromwau.klap.fixture.cp.CpProjectionSupport.AFTER_END
import com.fromwau.klap.fixture.cp.CpProjectionSupport.ALL
import com.fromwau.klap.fixture.cp.CpProjectionSupport.ATTACHED
import com.fromwau.klap.fixture.cp.CpProjectionSupport.BARE
import com.fromwau.klap.fixture.cp.CpProjectionSupport.BARE_AFTER_VALUE
import com.fromwau.klap.fixture.cp.CpProjectionSupport.BARE_THEN_WORD
import com.fromwau.klap.fixture.cp.CpProjectionSupport.BETWEEN
import com.fromwau.klap.fixture.cp.CpProjectionSupport.BUILTIN_VALUE
import com.fromwau.klap.fixture.cp.CpProjectionSupport.CHOICE_ABBREVIATED
import com.fromwau.klap.fixture.cp.CpProjectionSupport.CHOICE_AMBIGUOUS
import com.fromwau.klap.fixture.cp.CpProjectionSupport.CHOICE_CAPITALS
import com.fromwau.klap.fixture.cp.CpProjectionSupport.CHOICE_EMPTY
import com.fromwau.klap.fixture.cp.CpProjectionSupport.CHOICE_UNKNOWN
import com.fromwau.klap.fixture.cp.CpProjectionSupport.CLUSTER
import com.fromwau.klap.fixture.cp.CpProjectionSupport.CLUSTER_SWALLOW
import com.fromwau.klap.fixture.cp.CpProjectionSupport.CLUSTER_VALUE
import com.fromwau.klap.fixture.cp.CpProjectionSupport.Cell
import com.fromwau.klap.fixture.cp.CpProjectionSupport.DASH_VALUE
import com.fromwau.klap.fixture.cp.CpProjectionSupport.Differ
import com.fromwau.klap.fixture.cp.CpProjectionSupport.EMPTY_VALUE
import com.fromwau.klap.fixture.cp.CpProjectionSupport.END_VALUE
import com.fromwau.klap.fixture.cp.CpProjectionSupport.EQUALS_VALUE
import com.fromwau.klap.fixture.cp.CpProjectionSupport.GLUED
import com.fromwau.klap.fixture.cp.CpProjectionSupport.LIST
import com.fromwau.klap.fixture.cp.CpProjectionSupport.LIST_MALFORMED
import com.fromwau.klap.fixture.cp.CpProjectionSupport.LONG
import com.fromwau.klap.fixture.cp.CpProjectionSupport.LONG_ATTACHED
import com.fromwau.klap.fixture.cp.CpProjectionSupport.LONG_REPEATED
import com.fromwau.klap.fixture.cp.CpProjectionSupport.MISSING
import com.fromwau.klap.fixture.cp.CpProjectionSupport.REPEATED
import com.fromwau.klap.fixture.cp.CpProjectionSupport.SHORT
import com.fromwau.klap.fixture.cp.CpProjectionSupport.SHORT_REPEATED
import com.fromwau.klap.fixture.cp.CpProjectionSupport.SPACED
import com.fromwau.klap.fixture.cp.CpProjectionSupport.Same
import com.fromwau.klap.fixture.cp.CpProjectionSupport.UnderEnvironment
import com.fromwau.klap.fixture.cp.CpProjectionSupport.checkAll
import com.fromwau.klap.fixture.cp.CpProjectionSupport.checkDiffer
import com.fromwau.klap.fixture.cp.CpProjectionSupport.checkSame
import com.fromwau.klap.fixture.cp.CpProjectionSupport.checkUnder
import com.fromwau.klap.fixture.cp.CpProjectionSupport.cells
import com.fromwau.klap.io.CliPath
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * Every expected value is from GNU coreutils 9.12 `cp`, run inside a throwaway directory: a `because` quotes what it
 * printed or which files it left, and a comment says where a claim was read from `cp --help` or the info manual
 * instead. A line cp refuses only while copying (a missing source, an unsupported clone) still parsed, so it binds.
 */

private val FLAGS: List<String> = INPUTS.filter { it.kind == Kind.Flag }.map { it.name }

private val VALUES: List<String> = INPUTS.filter { it.kind is Kind.Value }.map { it.name }

private fun cell(
    argv: List<String>,
    expected: CpInputs?,
    because: String,
) = Cell("line", "whole line", argv, expected, because)

private val TO_DIR: CpInputs = NOTHING_BOUND.copy(targetDirectory = CliPath("d"), sources = listOf(CliPath("a")))

/**
 * The record a cp line binds in the fixture, or its refusal, as real cp 9.12 reads the line (P of the testing matrix):
 * every input in every spelling and position, values at the edges of their word, repeats and overrides, the three
 * operand shapes, and help, version and klap's built-ins on a line.
 */
class CpProjectionTest {

    private val parity = ParitySuite(cpCli())

    @Test
    fun `the backup environment changes nothing where cp makes no backup or names its control`() = checkUnder(
        // Real cp 9.12 reads VERSION_CONTROL and SIMPLE_BACKUP_SUFFIX only when it backs up, and an explicit control
        // wins: `VERSION_CONTROL=bogus cp a b` copies, `VERSION_CONTROL=numbered cp --backup=simple a b` makes b~,
        // `SIMPLE_BACKUP_SUFFIX=.env cp a b` makes no backup, `SIMPLE_BACKUP_SUFFIX=.env cp -b -S .x a b` makes b.x.
        listOf(
            UnderEnvironment(NUMBERED, AB, AB, "no backup"),
            UnderEnvironment(mapOf(VC to "bogus"), AB, AB, "no backup, so the variable is not read"),
            UnderEnvironment(NUMBERED, listOf("--backup=simple") + AB, listOf("--backup=simple") + AB, "b~"),
            UnderEnvironment(
                mapOf(VC to "bogus"),
                listOf("--backup=simple") + AB,
                listOf("--backup=simple") + AB,
                "an explicit control is never checked against the variable",
            ),
            UnderEnvironment(SUFFIXED, AB, AB, "no backup"),
            UnderEnvironment(SUFFIXED, listOf("-b", "-S", ".x") + AB, listOf("-b", "-S", ".x") + AB, "b.x"),
        ),
    )

    @Test
    fun `every flag binds by its long name its abbreviation and each short alone doubled and clustered`() =
        parity.checkAll(cells(FLAGS, LONG, LONG_REPEATED, ABBREVIATED, SHORT, SHORT_REPEATED, CLUSTER))

    @Test
    fun `a flag refuses a value attached to its long name`() = parity.checkAll(cells(FLAGS, LONG_ATTACHED))

    @Test
    fun `every input binds the same record before between and after the operands`() =
        parity.checkAll(cells(FLAGS + VALUES, BETWEEN, AFTER))

    @Test
    fun `every spelling after the end of options is an operand`() = parity.checkAll(cells(FLAGS + VALUES, AFTER_END))

    @Test
    fun `every value binds attached spaced glued abbreviated and at the end of a cluster`() =
        parity.checkAll(cells(VALUES, ATTACHED, SPACED, GLUED, CLUSTER_VALUE, ABBREVIATED, ABBREVIATED_SPACED))

    @Test
    fun `a required value is refused when the line ends before it`() = parity.checkAll(cells(VALUES, MISSING))

    @Test
    fun `an optional value left out binds its default and leaves the next word an operand`() =
        // `cp --help`: "--preserve[=ATTR_LIST]" and "--context[=CTX]"; `cp --preserve mode a b` and
        // `cp --context x a b`: target 'b': Not a directory, so the word was a source. Bare --context is "like -Z",
        // from the help only, as this kernel has no SELinux.
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
    fun `an attribute list binds several names and refuses a malformed one`() =
        parity.checkAll(cells(VALUES, LIST, LIST_MALFORMED))

    @Test
    fun `a value given twice binds as cp reads the repeat`() = parity.checkAll(cells(VALUES, REPEATED))

    @Test
    fun `every cell of every input is checked by one test`() {
        val covered = setOf(
            LONG, LONG_REPEATED, LONG_ATTACHED, ABBREVIATED, ABBREVIATED_SPACED, SHORT, SHORT_REPEATED, CLUSTER,
            BETWEEN, AFTER, AFTER_END, ATTACHED, SPACED, GLUED, CLUSTER_VALUE, CLUSTER_SWALLOW, MISSING, DASH_VALUE,
            END_VALUE, BUILTIN_VALUE, EQUALS_VALUE, EMPTY_VALUE, BARE, BARE_THEN_WORD, BARE_AFTER_VALUE,
            CHOICE_ABBREVIATED, CHOICE_AMBIGUOUS, CHOICE_CAPITALS, CHOICE_UNKNOWN, CHOICE_EMPTY, LIST, LIST_MALFORMED,
            REPEATED,
        )
        val unchecked = ALL.filter { it.form !in covered }
        assertTrue(unchecked.isEmpty(), "cells no test checks: $unchecked")
    }

    @Test
    fun `the operands bind as each of cp's three usage shapes reads them`() = parity.checkAll(
        listOf(
            cell(listOf("a", "b"), A_TO_B, "cp a b copies a to b"),
            cell(listOf("a", "b", "d"), operands("a", "b", "d"), "into d"),
            cell(listOf("-t", "d", "a"), TO_DIR, "`cp -t d a` copies a into d"),
            cell(listOf("a", "-t", "d"), TO_DIR, "`cp a -t d` copies a into d"),
            cell(listOf("a", "b", "-t", "d"), TO_DIR.copy(sources = AB.map(::CliPath)), "`cp a b -t d`: both into d"),
            cell(listOf("a", "-t", "d", "b"), TO_DIR.copy(sources = AB.map(::CliPath)), "`cp a -t d b`: both into d"),
            cell(listOf("-T", "a", "b"), A_TO_B.copy(noTargetDirectory = true), "cp -T a b"),
            cell(listOf("a", "b", "-T"), A_TO_B.copy(noTargetDirectory = true), "`cp a b -T` exits 0"),
            cell(listOf("--", "-foo", "bar"), operands("-foo", "bar"), "--"),
            cell(listOf("a", "--", "-v"), operands("a", "-v"), "makes a file -v"),
            cell(listOf("--", "a", "--"), operands("a", "--"), "makes a file --"),
            cell(listOf("-t", "d", "--", "-x"), TO_DIR.copy(sources = listOf(CliPath("-x"))), "cp: cannot stat '-x'"),
            cell(listOf("--", "-5", "b"), operands("-5", "b"), "`cp a -- -5`"),
            cell(listOf("-", "b"), operands("-", "b"), "cp: cannot stat '-'"),
            cell(listOf("", "b"), operands("", "b"), "cp: cannot stat ''"),
            cell(listOf("a b", "b"), operands("a b", "b"), "cannot stat 'a b'"),
            // `~/x` was not run, as the oracle's safety rule forbids a `~` path; cp gets the word as the shell left it.
            cell(listOf("ü*", "~/x"), operands("ü*", "~/x"), "`cp 'ü*' b` exits 0"),
            cell(emptyList(), null, "cp: missing file operand"),
            cell(listOf("-i"), null, "cp: missing file operand"),
            cell(listOf("a"), null, "cp: missing destination file operand after 'a'"),
            cell(listOf("-t", "d"), null, "cp: missing file operand"),
            cell(listOf("-T", "a"), null, "cp: missing destination file operand after 'a'"),
            cell(listOf("--"), null, "cp: missing file operand"),
        ),
    )

    @Test
    fun `a line setting every input binds them all`() = parity.checkAll(
        listOf(
            // The whole line exits 0 under real cp, printing `'a' -> 'd/a'` and a warning that --context needs SELinux.
            cell(
                listOf(
                    "-a", "--attributes-only", "--backup=numbered", "-b", "--copy-contents", "-d", "--debug", "-f",
                    "-i", "-H", "-L", "--keep-directory-symlink", "-l", "-p", "--preserve=mode",
                    "--no-preserve=timestamps", "--parents", "-R", "--reflink=auto", "--remove-destination",
                    "--sparse=auto", "--strip-trailing-slashes", "-S", ".bak", "-t", "d", "-u", "--update=older", "-v",
                    "-x", "-Z", "--context=ctx", "a",
                ),
                TO_DIR.copy(
                    archive = true,
                    attributesOnly = true,
                    backup = BackupControl.Numbered,
                    copyContents = true,
                    debug = true,
                    force = true,
                    interactive = true,
                    dereference = Dereference.ALWAYS,
                    keepDirectorySymlink = true,
                    link = true,
                    // -a preserves every attribute, and the later --no-preserve=timestamps drops one.
                    preserve = listOf("mode", "ownership", "links", "context", "xattr"),
                    noPreserve = listOf("timestamps"),
                    parents = true,
                    recursive = true,
                    reflink = When.Auto,
                    removeDestination = true,
                    sparse = When.Auto,
                    stripTrailingSlashes = true,
                    suffix = ".bak",
                    update = Update.Older,
                    verbose = true,
                    oneFileSystem = true,
                    selinuxDefault = true,
                    context = "ctx",
                ),
                "cp exits 0",
            ),
            // `cp -s -T -P -n a s` makes the symbolic link s.
            cell(
                listOf("-s", "-T", "-P", "-n", "a", "s"),
                NOTHING_BOUND.copy(
                    dereference = Dereference.NEVER,
                    symbolicLink = true,
                    noTargetDirectory = true,
                    noClobber = true,
                    sources = listOf(CliPath("a")),
                    dest = CliPath("s"),
                ),
                "cp exits 0",
            ),
        ),
    )

    @Test
    fun `binds a line combining several inputs`() {
        parity.binds(
            "-iv", "a.txt", "b.txt", "archive",
            expected = NOTHING_BOUND.copy(
                interactive = true,
                verbose = true,
                sources = listOf(CliPath("a.txt"), CliPath("b.txt")),
                dest = CliPath("archive"),
            ),
        )
        // Five of cp's flags are short-only, so this cluster is the whole spelling each offers.
        parity.binds(
            "-dbHpZ", "a", "b",
            expected = A_TO_B.copy(
                backup = BackupControl.Existing,
                dereference = Dereference.COMMAND_LINE,
                preserve = listOf("mode", "ownership", "timestamps", "links"),
                selinuxDefault = true,
            ),
        )
        parity.binds(
            "--backup=numbered", "-S", ".bak", "a", "b",
            expected = A_TO_B.copy(backup = BackupControl.Numbered, suffix = ".bak"),
        )
        parity.binds("-vbS.bak", "a", "b", expected = A_TO_B.copy(
            verbose = true,
            backup = BackupControl.Existing,
            suffix = ".bak",
        ))
        parity.binds(
            "--no-preserve=mode", "--sparse=always", "--reflink=auto", "a", "b",
            expected = A_TO_B.copy(noPreserve = listOf("mode"), sparse = When.Always, reflink = When.Auto),
        )
        parity.binds(
            "--context=unconfined_u:object_r:user_home_t:s0", "a", "b",
            expected = A_TO_B.copy(context = "unconfined_u:object_r:user_home_t:s0"),
        )
    }

    @Test
    fun `a cluster of short-only flags binds the backup and update it implies`() {
        // With b older than a, `cp -dbuHpZ a b` replaces b and keeps the old one as b~; with b newer it leaves b.
        parity.binds(
            "-dbuHpZ", "a", "b",
            expected = A_TO_B.copy(
                backup = BackupControl.Existing,
                update = Update.Older,
                dereference = Dereference.COMMAND_LINE,
                preserve = listOf("mode", "ownership", "timestamps", "links"),
                selinuxDefault = true,
            ),
        )
    }

    @Test
    fun `the later of -i and -n decides the prompt in separate words and in one cluster`() {
        // `cp -n -i a b` and `cp -ni a b` prompt; `cp -i -n a b` and `cp -in a b` skip silently.
        parity.binds("-n", "-i", "a", "b", expected = A_TO_B.copy(interactive = true))
        parity.binds("-i", "-n", "a", "b", expected = A_TO_B.copy(noClobber = true))
        parity.binds("-ni", "a", "b", expected = A_TO_B.copy(interactive = true))
        parity.binds("-in", "a", "b", expected = A_TO_B.copy(noClobber = true))
        parity.binds("--no-clobber", "--interactive", "a", "b", expected = A_TO_B.copy(interactive = true))
        parity.binds("-n", "-i", "-f", "a", "b", expected = A_TO_B.copy(interactive = true, force = true))
    }

    @Test
    fun `-f and --update apply alongside -i and -n in either order`() {
        // `cp -f -i` and `cp -i -f` both prompt; `cp -i --update=none` skips without prompting; `cp -n -f` and
        // `cp -n --update=all` both leave the destination alone, so neither setting clears the other.
        parity.binds("-f", "-i", "a", "b", expected = A_TO_B.copy(force = true, interactive = true))
        parity.binds("-i", "-f", "a", "b", expected = A_TO_B.copy(force = true, interactive = true))
        parity.binds("-i", "--update=none", "a", "b", expected = A_TO_B.copy(interactive = true, update = Update.None))
        parity.binds("--update=none", "-i", "a", "b", expected = A_TO_B.copy(interactive = true, update = Update.None))
        parity.binds("-n", "-f", "a", "b", expected = A_TO_B.copy(noClobber = true, force = true))
        parity.binds("-n", "--update=all", "a", "b", expected = A_TO_B.copy(noClobber = true, update = Update.All))
    }

    @Test
    fun `the last of -L -P and -H decides how links are followed`() = checkSame(
        // With a symbolic link lnk to a file: `cp -L -P lnk out` and `cp -H -P` copy the link, `cp -P -L` and
        // `cp -H -L` copy the file, `cp -L -H` and `cp -P -H` copy the file as -H alone does.
        listOf(
            Same(listOf("-LP", "a", "b"), listOf("-P", "a", "b"), "`cp -L -P lnk out` makes a link"),
            Same(listOf("-PL", "a", "b"), listOf("-L", "a", "b"), "`cp -P -L lnk out` makes a file"),
            Same(listOf("-H", "-L", "a", "b"), listOf("-L", "a", "b"), "`cp -H -L lnk out` makes a file"),
            Same(listOf("-L", "-H", "a", "b"), listOf("-H", "a", "b"), "`cp -L -H lnk out` makes a file"),
            Same(listOf("-H", "-P", "a", "b"), listOf("-P", "a", "b"), "`cp -H -P lnk out` makes a link"),
            Same(listOf("-P", "-H", "a", "b"), listOf("-H", "a", "b"), "`cp -P -H lnk out` makes a file"),
            Same(listOf("--dereference", "--no-dereference", "a", "b"), listOf("-P", "a", "b"), "a link"),
            Same(listOf("-H", "--dereference", "a", "b"), listOf("-L", "a", "b"), "info: the last one overrides"),
        ),
    )

    @Test
    fun `the negation binds the same record in every spelling`() = checkSame(
        listOf("--no-dereference", "--no-deref", "--no-d").map { spelled ->
            Same(listOf(spelled, "a", "b"), listOf("-P", "a", "b"), "`cp $spelled a b` exits 0")
        },
    )

    @Test
    fun `binds the unique abbreviation of a long name and of a choice`() {
        // `cp --parent`, `cp --verb`, `cp --no-p=mode`, `cp --tar=d a`, `cp --upd=old` and `--update=none-` exit 0.
        parity.binds("--parent", "a", "b", expected = A_TO_B.copy(parents = true))
        parity.binds("--verb", "a", "b", expected = A_TO_B.copy(verbose = true))
        parity.binds("--no-p=mode", "a", "b", expected = A_TO_B.copy(noPreserve = listOf("mode")))
        parity.binds("--tar=d", "a", expected = TO_DIR)
        parity.binds("--upd=old", "a", "b", expected = A_TO_B.copy(update = Update.Older))
        parity.binds("--update=none-", "a", "b", expected = A_TO_B.copy(update = Update.NoneFail))
        parity.binds("--strip", "a/", "d", expected = NOTHING_BOUND.copy(
            stripTrailingSlashes = true,
            sources = listOf(CliPath("a/")),
            dest = CliPath("d"),
        ))
    }

    @Test
    fun `rejects an ambiguous abbreviation as getopt does`() {
        val ambiguous = mapOf(
            "--a" to "'--archive' '--attributes-only'",
            "--c" to "'--copy-contents' '--context'",
            "--co" to "'--copy-contents' '--context'",
            "--d" to "'--debug' '--dereference'",
            "--p" to "'--parents' '--preserve'",
            "--r" to "'--recursive' '--remove-destination' '--reflink'",
            "--s" to "'--sparse' '--strip-trailing-slashes' '--suffix' '--symbolic-link'",
            "--n" to "the four --no-* options",
            "--no-" to "'--no-clobber' '--no-dereference' '--no-preserve' '--no-target-directory'",
            "--v" to "'--verbose' '--version'",
            "--ver" to "'--verbose' '--version'",
        )
        checkCells(ambiguous.entries.toList()) { (prefix, candidates) ->
            val because = "real cp: option '$prefix' is ambiguous; possibilities: $candidates"
            parity.rejects(prefix, "a", "b", because = because)
        }
    }

    @Test
    fun `rejects what real cp rejects`() {
        parity.rejects("--zzz", because = "real cp: unrecognized option '--zzz'")
        parity.rejects("--x", "a", "b", because = "real cp: unrecognized option '--x'")
        parity.rejects("-Q", "a", "b", because = "real cp: invalid option -- 'Q'")
        parity.rejects("-c", "a", "b", because = "real cp: invalid option -- 'c'")
        parity.rejects("--Q", "a", "b", because = "real cp: unrecognized option '--Q'")
        parity.rejects("-5", "a", "b", because = "real cp: invalid option -- '5'")
        parity.rejects("a", "-q", "b", because = "real cp: invalid option -- 'q'")
        // --sparse is the one required-value choice, so the spaced form takes the next word as its value.
        parity.rejects("--sparse", "a", "b", because = "real cp: ambiguous argument 'a' for '--sparse'")
        parity.rejects("--sparse", "--help", "a", "b", because = "real cp: invalid argument '--help' for '--sparse'")
        parity.rejects("--update=n", "a", "b", because = "real cp: ambiguous argument 'n' for '--update'")
        parity.rejects("--suffix", "a", "b", because = "real cp: --suffix takes a, leaving b with no destination")
        parity.rejects("--no-p", "a", "b", because = "real cp: missing destination file operand after 'b'")
    }

    @Test
    fun `help and version answer wherever they sit on a valid line`() {
        parity.showsHelp("--help", because = "cp --help prints its usage")
        parity.showsHelp("--help", "a", "b", because = "cp --help a b prints its usage")
        parity.showsHelp("a", "b", "--help", because = "cp a b --help prints its usage")
        parity.showsHelp("-v", "--help", "a", "b", because = "cp -v --help a b prints its usage")
        parity.showsHelp("--he", because = "cp --he prints its usage")
        parity.showsHelp("--h", because = "cp has no other long starting with h")
        parity.showsVersion("--version", because = "cp --version prints its banner")
        parity.showsVersion("--vers", because = "cp --vers prints its banner")
        parity.showsVersion("-v", "--version", "a", "b", because = "cp -v --version a b prints its banner")
        parity.showsVersion("--version", "a", because = "cp --version a prints its banner")
    }

    @Test
    fun `help answers ahead of a refusal cp makes after reading its options`() {
        // cp checks its operands, -t against -T and the backup type only once getopt is done, so help comes first.
        parity.showsHelp("-T", "--help", "a", "b", "c", because = "cp -T --help a b c prints its usage")
        parity.showsHelp("-t", "d", "-T", "--help", "a", because = "cp -t d -T --help a prints its usage")
        parity.showsHelp("--help", "--backup=bogus", because = "cp --help --backup=bogus prints its usage")
        parity.showsHelp("--help", "-t", "a", "-t", "b", because = "cp --help -t a -t b prints its usage")
    }

    @Test
    fun `help written before a bad choice answers`() {
        for (option in listOf("--sparse", "--reflink", "--update", "--preserve", "--no-preserve")) {
            parity.showsHelp("--help", "$option=bogus", because = "cp --help $option=bogus prints its usage")
        }
    }

    @Test
    fun `the first of help and version answers`() {
        parity.showsHelp("--help", "--version", because = "cp --help --version prints its usage")
        parity.showsVersion("--version", "--help", because = "cp --version --help prints its banner")
    }

    @Test
    fun `an unknown option written before help or version refuses the line`() = parity.checkAll(
        listOf(
            cell(listOf("--bogus", "--help"), null, "cp: unrecognized option '--bogus'"),
            cell(listOf("--bogus", "--version"), null, "cp: unrecognized option '--bogus'"),
            cell(listOf("-5", "--version"), null, "cp: invalid option -- '5'"),
            cell(listOf("-h", "--help"), null, "cp: invalid option -- 'h'"),
            cell(listOf("--help-all", "--help"), null, "cp: unrecognized option '--help-all'"),
        ),
    )

    @Test
    fun `an option cp does not have is refused`() {
        // `cp --json`, `cp --color=never`, `cp --completion`, `cp --docs`, `cp --col`, `cp --j`, `cp --com`, `cp --do`,
        // `cp --recursive-r`: all "unrecognized option"; `cp -h`: invalid option -- 'h'.
        val lines = listOf(
            listOf("--recursive-r", "a", "b"),
            listOf("--json", "a", "b"),
            listOf("--color=never", "a", "b"),
            listOf("--color", "never", "a", "b"),
            listOf("--completion", "bash", "a", "b"),
            listOf("--docs", "markdown", "a", "b"),
            listOf("--col=never", "a", "b"),
            listOf("--j", "a", "b"),
            listOf("--com", "bash", "a", "b"),
            listOf("--do", "man", "a", "b"),
            listOf("--help-all", "a", "b"),
            listOf("-h", "a", "b"),
            listOf("-vh", "a", "b"),
        )
        checkCells(lines) { parity.rejects(argv = it.toTypedArray(), because = "real cp: unrecognized option") }
    }

    @Test
    fun `a word naming a klap built-in is an operand`() = parity.checkAll(
        // `cp a completion`, `cp a docs` and `cp a __complete` each make a file of that name.
        listOf(
            cell(listOf("completion", "b"), operands("completion", "b"), "a file"),
            cell(listOf("docs", "b"), operands("docs", "b"), "a file"),
            cell(listOf("a", "__complete"), operands("a", "__complete"), "a file"),
            cell(listOf("--", "--help", "b"), operands("--help", "b"), "copies it"),
        ),
    )

    @Test
    fun `options cp accepts together bind`() = parity.checkAll(
        listOf(
            // `cp -n -i -b a b` prompts, so -i won over -n and the backup stands.
            cell(
                listOf("-n", "-i", "-b", "a", "b"),
                A_TO_B.copy(interactive = true, backup = BackupControl.Existing),
                "prompts",
            ),
            cell(
                listOf("--update=none", "--update=all", "-b", "a", "b"),
                A_TO_B.copy(update = Update.All, backup = BackupControl.Existing),
                "cp exits 0",
            ),
            cell(
                listOf("--reflink=auto", "--sparse=never", "a", "b"),
                A_TO_B.copy(reflink = When.Auto, sparse = When.Never),
                "cp exits 0",
            ),
            cell(
                listOf("--sparse=never", "--reflink=always", "--reflink=auto", "a", "b"),
                A_TO_B.copy(reflink = When.Auto, sparse = When.Never),
                "cp exits 0",
            ),
            cell(listOf("--context=x", "-Z", "a", "b"), A_TO_B.copy(context = "x", selinuxDefault = true), "0"),
        ),
    )

    @Test
    fun `a bad choice written before help refuses the line`() = parity.checkAll(
        // `cp --sparse=bogus --help`: invalid argument 'bogus' for '--sparse', exit 1; likewise --reflink, --update,
        // --preserve and --no-preserve. (`--backup=bogus --help` prints the help, as cp checks it after the options.)
        listOf("--sparse", "--reflink", "--update", "--preserve", "--no-preserve").map { option ->
            cell(listOf("$option=bogus", "--help"), null, "cp: invalid argument 'bogus' for '$option'")
        },
    )

    @Test
    fun `a bad backup control ahead of help still shows help`() {
        // cp checks the backup type only once getopt is done, so `cp --backup=bogus --help` prints its usage.
        parity.showsHelp("--backup=bogus", "--help", because = "cp --backup=bogus --help prints its usage")
    }

    @Test
    fun `-u is --update without a value and the later of the two wins`() {
        // With b older than a: `cp --update=none -u a b` replaces b; `cp -u --update=none a b` leaves it.
        parity.binds("--update=none", "-u", "a", "b", expected = A_TO_B.copy(update = Update.Older))
        parity.binds("-u", "--update=none", "a", "b", expected = A_TO_B.copy(update = Update.None))
        checkSame(
            listOf(
                Same(listOf("-u", "a", "b"), listOf("--update", "a", "b"), "cp --help: equivalent to --update[=older]"),
                Same(listOf("-u", "a", "b"), listOf("--update=older", "a", "b"), "cp --help"),
            ),
        )
    }

    @Test
    fun `a bare --backup keeps the control already chosen and an empty one means what -b means`() = checkSame(
        // `cp --backup=numbered --backup a b` and `cp --backup=numbered -b a b` make b.~1~. An empty control does not
        // keep an earlier one: with b.~1~ present, `cp --backup=simple --backup= a b` makes b.~2~, as
        // `cp --backup= a b`, `cp --backup a b` and `cp -b a b` do: all three name cp's default control.
        listOf(
            Same(listOf("--backup=numbered", "--backup", "a", "b"), listOf("--backup=numbered", "a", "b"), "b.~1~"),
            Same(listOf("--backup=simple", "--backup=", "a", "b"), listOf("--backup=", "a", "b"), "b.~2~"),
            Same(listOf("--backup=", "a", "b"), listOf("-b", "a", "b"), "both name the default control"),
            Same(listOf("--backup=", "a", "b"), listOf("--backup", "a", "b"), "both name the default control"),
        ),
    )

    @Test
    fun `a backup without its own control or suffix takes them from the environment`() = checkUnder(
        // Real cp 9.12, b existing: under VERSION_CONTROL=numbered, `cp -b a b`, `cp --backup a b`, `cp --backup= a b`
        // and `cp -S .x a b` make b.~1~; VERSION_CONTROL=nu is numbered, `n` is "ambiguous argument 'n' for
        // '$VERSION_CONTROL'" and `bogus` "invalid argument", refused before copying; an empty VERSION_CONTROL is
        // the default. SIMPLE_BACKUP_SUFFIX=.env `cp -b a b` makes b.env; a suffix holding a slash, or an empty
        // one, falls back to b~.
        listOf(
            UnderEnvironment(NUMBERED, listOf("-b") + AB, listOf("--backup=numbered") + AB, "b.~1~"),
            UnderEnvironment(NUMBERED, listOf("--backup") + AB, listOf("--backup=numbered") + AB, "b.~1~"),
            UnderEnvironment(NUMBERED, listOf("--backup=") + AB, listOf("--backup=numbered") + AB, "b.~1~"),
            UnderEnvironment(NUMBERED, listOf("-S", ".x") + AB, listOf("--backup=numbered", "-S", ".x") + AB, "b.~1~"),
            UnderEnvironment(mapOf(VC to "nu"), listOf("-b") + AB, listOf("--backup=numbered") + AB, "b.~1~"),
            UnderEnvironment(mapOf(VC to "t"), listOf("-b") + AB, listOf("--backup=numbered") + AB, "b.~1~"),
            UnderEnvironment(mapOf(VC to "n"), listOf("-b") + AB, null, "ambiguous argument 'n'"),
            UnderEnvironment(mapOf(VC to "bogus"), listOf("-b") + AB, null, "invalid argument 'bogus'"),
            UnderEnvironment(mapOf(VC to ""), listOf("-b") + AB, listOf("-b") + AB, "the default control, b~"),
            UnderEnvironment(SUFFIXED, listOf("-b") + AB, listOf("-b", "-S", ".env") + AB, "b.env"),
            UnderEnvironment(mapOf(SBS to "a/b"), listOf("-b") + AB, listOf("-b") + AB, "a slash falls back to b~"),
            UnderEnvironment(mapOf(SBS to ""), listOf("-b") + AB, listOf("-b") + AB, "empty falls back to b~"),
            UnderEnvironment(
                NUMBERED + SUFFIXED,
                listOf("-b") + AB,
                listOf("--backup=numbered", "-S", ".env") + AB,
                "b.~1~: a numbered backup does not use the suffix, but cp still reads it",
            ),
        ),
    )

    @Test
    fun `-b and a bare --backup are one setting`() = checkSame(
        // cp --help: "-b  like --backup but does not accept an argument". Neither names a control, so an earlier one
        // stands: `cp --backup=none -b a b` and `cp -b --backup=none a b` make no backup.
        listOf(
            Same(listOf("--backup", "a", "b"), listOf("-b", "a", "b"), "neither names a control"),
            Same(listOf("--backup", "x", "b"), listOf("-b", "x", "b"), "`cp --backup x b`: x is a source"),
            Same(listOf("--backup=none", "-b", "a", "b"), listOf("--backup=none", "a", "b"), "no backup"),
            Same(listOf("-b", "--backup=none", "a", "b"), listOf("--backup=none", "a", "b"), "no backup"),
            Same(listOf("--backup=numbered", "-b", "a", "b"), listOf("--backup=numbered", "a", "b"), "b.~1~"),
        ),
    )

    @Test
    fun `a line without a link option binds apart from -P`() = checkDiffer(
        // With a symbolic link lnk to a file: `cp lnk out` makes a file and `cp -P lnk out` a link, while `cp -R lnk
        // out` makes a link and `cp -R -L lnk out` a file: without -L, -P or -H, cp follows links unless recursive.
        listOf(
            Differ(listOf("-P", "a", "b"), listOf("a", "b"), "`cp -P lnk out` makes a link, `cp lnk out` a file"),
            Differ(listOf("--no-dereference", "a", "b"), listOf("a", "b"), "as -P"),
            Differ(listOf("-H", "-P", "a", "b"), listOf("a", "b"), "`cp -H -P lnk out` makes a link"),
            Differ(listOf("-R", "-L", "a", "b"), listOf("-R", "a", "b"), "`cp -R -L lnk out` makes a file"),
        ),
    )

    @Test
    fun `a second target directory is refused`() {
        // `cp -t . -t . a` and `cp --target-directory=. --target-directory=. a`: multiple target directories specified.
        parity.rejects("-t", "d", "-t", "d", "a", because = "real cp: multiple target directories specified")
    }

    @Test
    fun `a second target directory written before help refuses the line`() {
        // getopt's loop meets the second -t before --help; `cp -t a --help -t b` still prints the usage.
        parity.rejects("-t", "a", "-t", "b", "--help", because = "real cp: multiple target directories specified")
        parity.showsHelp("-t", "a", "--help", "-t", "b", because = "cp -t a --help -t b prints its usage")
    }

    @Test
    fun `-T takes exactly a source and a destination`() {
        parity.rejects("-T", "a", "b", "c", because = "real cp: extra operand 'c'")
        parity.rejects("a", "b", "c", "-T", because = "real cp: extra operand 'c'")
    }

    @Test
    fun `-t and -T are refused together`() {
        val why = "real cp: cannot combine --target-directory (-t) and --no-target-directory (-T)"
        parity.rejects("-t", "d", "-T", "a", because = why)
        parity.rejects("-T", "-t", "d", "a", because = why)
        parity.rejects("-t", "dir", "src", "-T", because = why)
    }

    @Test
    fun `options cp cannot combine are refused`() = parity.checkAll(
        listOf(
            cell(listOf("-l", "-s", "a", "c"), null, "cp: cannot make both hard and symbolic links"),
            cell(listOf("-s", "-l", "a", "c"), null, "cp: cannot make both hard and symbolic links"),
            cell(listOf("--link", "--symbolic-link", "a", "c"), null, "cp: cannot make both hard and symbolic links"),
            cell(listOf("-n", "-b", "a", "b"), null, "cp: --backup is mutually exclusive"),
            cell(listOf("-b", "-n", "a", "b"), null, "cp: --backup is mutually exclusive with -n"),
            cell(listOf("-n", "--backup", "a", "b"), null, "cp: --backup is mutually exclusive with -n"),
            cell(listOf("--backup=none", "-n", "a", "b"), null, "cp: --backup is mutually exclusive with -n"),
            cell(listOf("-i", "-n", "-b", "a", "b"), null, "cp: --backup is mutually exclusive with -n"),
            cell(listOf("-n", "--update=all", "-b", "a", "b"), null, "cp: --backup is mutually exclusive with -n"),
            cell(listOf("--update=none", "-b", "a", "b"), null, "cp: --backup is mutually exclusive with -n"),
            cell(listOf("-b", "--update=none", "a", "b"), null, "cp: --backup is mutually exclusive with -n"),
            cell(listOf("--update=none-fail", "-b", "a", "b"), null, "cp: --backup is mutually exclusive"),
            cell(listOf("--reflink=always", "--sparse=never", "a", "b"), null, "cp: --reflink only with --sparse=auto"),
            cell(listOf("--reflink", "--sparse=never", "a", "b"), null, "cp: --reflink only with --sparse=auto"),
            cell(listOf("--sparse=never", "--reflink=always", "a", "b"), null, "cp: --reflink can be used only with"),
            cell(listOf("--reflink=always", "--sparse=always", "a", "b"), null, "cp: --reflink can be used only with"),
        ),
    )

    @Test
    fun `help or version written before an unknown option answers`() {
        // `cp --help --bogus` prints the usage, `cp --version --bogus` and `cp --version --help-all` the banner.
        parity.showsHelp("--help", "--bogus", because = "cp --help --bogus prints its usage")
        parity.showsHelp("--help", "-h", because = "cp --help -h prints its usage")
        parity.showsVersion("--version", "--bogus", because = "cp --version --bogus prints its banner")
        parity.showsVersion("--version", "-5", because = "cp --version -5 prints its banner")
        parity.showsVersion("--version", "--help-all", because = "cp --version --help-all prints its banner")
    }

    @Test
    fun `--context=-Z is a context and a bare --context the default one`() {
        // `cp --context=-Z a b` warns "ignoring --context; it requires an SELinux-enabled kernel", as for any context
        // it was given, while `cp --context a c` and `cp -Z a e` copy without a word.
        parity.binds("--context=-Z", "a", "b", expected = A_TO_B.copy(context = "-Z"))
        parity.binds("--context", "a", "b", expected = A_TO_B.copy(selinuxDefault = true))
    }

    @Test
    fun `--backup and --context show an optional value and no bare one`() {
        // `cp --help`: "--backup[=CONTROL]" and "--context[=CTX]", naming no value a bare one stands for. klap writes
        // the placeholder in angle brackets.
        val page = parity.helpPage("--help", because = "cp --help")
        val rows = page.lines().filter { "--backup" in it || "--context" in it }
        assertTrue(rows.any { "--backup[=CONTROL]" in it } && rows.any { "--context[=CTX]" in it }, page)
        assertTrue(rows.none { "bare:" in it }, rows.joinToString("\n"))
    }

    @Test
    fun `a source and a destination keep a trailing slash`() {
        // GNU cp 9.12, link -> dir: `cp -r link/ c1` copies the directory and `cp -r link c2` the link; `cp a nd/` is
        // "cannot create regular file 'nd/': Not a directory" where `cp a nd` makes the file nd.
        parity.binds("-r", "link/", "c1", expected = operands("link/", "c1").copy(recursive = true))
        parity.binds("-r", "link", "c2", expected = operands("link", "c2").copy(recursive = true))
        parity.binds("a", "nd/", expected = operands("a", "nd/"))
        parity.binds("a", "nd", expected = operands("a", "nd"))
    }

    @Test
    fun `a value option's row names its value as cp --help does`() {
        // `cp --help` (GNU coreutils 9.12) writes each of these spellings at the head of its row.
        val signatures = listOf(
            "--backup[=CONTROL]",
            "--context[=CTX]",
            "--no-preserve=ATTR_LIST",
            "--preserve[=ATTR_LIST]",
            "--reflink[=WHEN]",
            "--sparse=WHEN",
            "-S, --suffix=SUFFIX",
            "-t, --target-directory=DIRECTORY",
            "--update[=UPDATE]",
        )
        val heads = parity.helpPage("--help", because = "cp --help").lines().map { it.trim().substringBefore("  ") }
        assertEquals(signatures, signatures.filter { it in heads }, heads.joinToString("\n"))
    }

    @Test
    fun `the help page lists cp's options and nothing cp refuses`() {
        val page = parity.helpPage("--help", because = "cp --help")
        val missing = CP_LONGS.filterNot { it in page }
        val foreign = KLAP_ONLY_LONGS.filter { long -> Regex("${Regex.escape(long)}(?![\\w-])").containsMatchIn(page) }
        assertTrue(missing.isEmpty() && foreign.isEmpty(), "cp --help page: missing $missing, foreign $foreign\n$page")
    }

    @Test
    fun `a shorthand and a later link or preserve option bind as their order reads`() = checkDiffer(
        // cp --help: "-a same as -dR --preserve=all", "-d same as --no-dereference --preserve=links", "-p same as
        // --preserve=mode,ownership,timestamps". With a link lnk: `cp -d -L lnk out` and `cp -a -L` make a file,
        // `cp -L -d` and `cp -L -a` a link. With a mode 600 file and umask 022: `cp -p --no-preserve=mode` gives 644,
        // `cp --no-preserve=mode -p` 600, and `-a` likewise.
        listOf(
            Differ(listOf("-d", "-L", "a", "b"), listOf("-L", "-d", "a", "b"), "a file against a link"),
            Differ(listOf("-a", "-L", "a", "b"), listOf("-L", "-a", "a", "b"), "a file against a link"),
            Differ(
                listOf("-p", "--no-preserve=mode", "a", "b"),
                listOf("--no-preserve=mode", "-p", "a", "b"),
                "mode 644 against 600",
            ),
            Differ(
                listOf("-a", "--no-preserve=mode", "a", "b"),
                listOf("--no-preserve=mode", "-a", "a", "b"),
                "mode 644 against 600",
            ),
        ),
    )

    @Test
    fun `the later of --preserve and --no-preserve decides an attribute`() = checkDiffer(
        // A mode 600 file, umask 022: `cp --preserve=mode --no-preserve=mode a out` gives 644, the reverse 600.
        listOf(
            Differ(
                listOf("--preserve=mode", "--no-preserve=mode", "a", "b"),
                listOf("--no-preserve=mode", "--preserve=mode", "a", "b"),
                "mode 644 against 600",
            ),
        ),
    )

    @Test
    fun `__complete is a source file name`() {
        // `cp __complete a`: cannot stat '__complete', so it was a source like any other word.
        parity.binds("__complete", "a", expected = operands("__complete", "a"))
    }
}

private const val VC = "VERSION_CONTROL"

private const val SBS = "SIMPLE_BACKUP_SUFFIX"

private val NUMBERED = mapOf(VC to "numbered")

private val SUFFIXED = mapOf(SBS to ".env")

private val AB = listOf("a", "b")
