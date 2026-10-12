package com.fromwau.klap.fixture.chmod

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.chmod.ChmodMode.OctalOp.REMOVE
import com.fromwau.klap.fixture.chmod.Dereference.Follow
import com.fromwau.klap.fixture.chmod.Dereference.NoFollow
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.ABBREVIATED
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.ABBREVIATED_SPACED
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.AFTER
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.AFTER_END
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.ALL
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.ATTACHED
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.BETWEEN
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.BUILTIN_VALUE
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.CLUSTER
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.Cell
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.DASH_VALUE
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.Differ
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.EMPTY_VALUE
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.END_VALUE
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.EQUALS_VALUE
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.LONG
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.LONG_ATTACHED
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.LONG_REPEATED
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.MISSING
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.MODE_AS_FILE
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.REPEATED
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.SHORT
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.SHORT_REPEATED
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.SPACED
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.checkAll
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.checkDiffer
import com.fromwau.klap.fixture.chmod.ChmodProjectionSupport.cells
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * Every expected value is from GNU coreutils 9.12 `chmod`, run inside a throwaway directory holding the files f and
 * g (mode 644), r (mode 600) and the directory d: a `because` quotes what it printed or the mode it left, and a
 * comment says where a claim was read from `chmod --help` or the info manual instead. A line chmod refuses only
 * while changing modes (a file that does not exist) still parsed, so it binds.
 */

private fun cell(
    argv: List<String>,
    expected: ChmodInputs?,
    because: String,
    differs: String? = null,
) = Cell("line", "whole line", argv, expected, because, differs)

private fun line(vararg argv: String) = argv.toList()

/** The inputs that set a flag. */
private val FLAGS: List<Input> = INPUTS.filterNot { it.value }

/** The one input that takes a value, --reference. */
private val VALUES: List<Input> = INPUTS.filter { it.value }

private fun modeOn(
    mode: ChmodMode,
    vararg files: String,
): ChmodInputs = NOTHING_BOUND.copy(mode = mode, files = files.toList())

private fun referencing(
    reference: String,
    vararg files: String,
): ChmodInputs = NOTHING_BOUND.copy(reference = reference, files = files.toList())

private val TO_D: ChmodInputs = modeOn(symbolic("u+x"), "d")

/**
 * The record a chmod line binds in the fixture, or its refusal, as real chmod 9.12 reads the line (P of the testing
 * matrix): every input in every spelling and position, values at the edges of their word, repeats and overrides, the
 * MODE operand in each of chmod's usage shapes, and help, version and klap's built-ins on a line.
 */
class ChmodProjectionTest {

    private val parity = ParitySuite(chmodCli())

    @Test
    fun `every flag binds by its long name its abbreviation and its short alone doubled and clustered`() =
        parity.checkAll(cells(FLAGS, LONG, LONG_REPEATED, ABBREVIATED, SHORT, SHORT_REPEATED, CLUSTER))

    @Test
    fun `a flag refuses a value attached to its long name`() = parity.checkAll(cells(FLAGS, LONG_ATTACHED))

    @Test
    fun `every input binds the same record before between and after the operands`() =
        parity.checkAll(cells(INPUTS, BETWEEN, AFTER))

    @Test
    fun `every spelling after the end of options is a file`() = parity.checkAll(cells(INPUTS, AFTER_END))

    @Test
    fun `--reference binds attached spaced and abbreviated and refuses a missing value`() =
        parity.checkAll(cells(VALUES, ATTACHED, SPACED, ABBREVIATED, ABBREVIATED_SPACED, MISSING))

    @Test
    fun `--reference takes the next word whatever it spells`() =
        parity.checkAll(cells(VALUES, DASH_VALUE, END_VALUE, BUILTIN_VALUE, EQUALS_VALUE, EMPTY_VALUE))

    @Test
    fun `--reference given twice binds the later file and turns every operand into a file`() =
        parity.checkAll(cells(VALUES, REPEATED, MODE_AS_FILE))

    @Test
    fun `every cell of every input is checked by one test`() {
        val covered = setOf(
            LONG, LONG_REPEATED, LONG_ATTACHED, ABBREVIATED, ABBREVIATED_SPACED, SHORT, SHORT_REPEATED, CLUSTER,
            BETWEEN, AFTER, AFTER_END, ATTACHED, SPACED, MISSING, DASH_VALUE, END_VALUE, BUILTIN_VALUE, EQUALS_VALUE,
            EMPTY_VALUE, REPEATED, MODE_AS_FILE,
        )
        val unchecked = ALL.filter { it.form !in covered }
        assertTrue(unchecked.isEmpty(), "cells no test checks: $unchecked")
    }

    @Test
    fun `the operands bind as each of chmod's three usage shapes reads them`() = parity.checkAll(
        listOf(
            cell(line("755", "f"), modeOn(octal("755"), "f"), "f becomes 755"),
            cell(line("u+x,go-w", "f", "g"), modeOn(symbolic("u+x", "go-w"), "f", "g"), "both change"),
            cell(line("--reference=r", "f"), referencing("r", "f"), "f becomes 600"),
            cell(line("--reference=r", "700", "f"), referencing("r", "700", "f"), "cannot access '700'"),
            cell(line("--", "-w", "f"), modeOn(symbolic("-w"), "f"), "f becomes 444"),
            cell(line("--", "-w", "--"), modeOn(symbolic("-w"), "--"), "cannot access '--'"),
            cell(line("u+x", "--", "--help"), modeOn(symbolic("u+x"), "--help"), "cannot access '--help'"),
            cell(line("-", "f"), modeOn(symbolic("-"), "f"), "`chmod - f` exits 0"),
            cell(line("u+x", ""), modeOn(symbolic("u+x"), ""), "cannot access ''"),
            cell(line("u+x", "a b"), modeOn(symbolic("u+x"), "a b"), "cannot access 'a b'"),
            // `~/x` was not run, as the oracle's safety rule forbids a `~` path; chmod gets the word as the shell
            // left it.
            cell(line("u+x", "ü*", "~/x"), modeOn(symbolic("u+x"), "ü*", "~/x"), "cannot access 'ü*'"),
            cell(line(), null, "chmod: missing operand"),
            cell(line("-R"), null, "chmod: missing operand"),
            cell(line("755"), null, "chmod: missing operand after '755'"),
            cell(line("--reference=r"), null, "chmod: missing operand"),
            cell(line("--", "f"), null, "chmod: missing operand after 'f'"),
            cell(line("--"), null, "chmod: missing operand"),
            cell(line("xyz", "f"), null, "chmod: invalid mode: 'xyz'"),
            cell(line("", "f"), null, "chmod: invalid mode: ''"),
        ),
    )

    @Test
    fun `options permute among the operands`() = parity.checkAll(
        listOf(
            cell(line("-R", "u+x", "d"), TO_D.copy(recursive = true), "d/x becomes 744"),
            cell(line("u+x", "-R", "d"), TO_D.copy(recursive = true), "d/x becomes 744"),
            cell(line("u+x", "d", "-R"), TO_D.copy(recursive = true), "d/x becomes 744"),
            cell(line("-R", "u+x", "-H", "d"), TO_D.copy(recursive = true, traverseArgLinks = true), "exit 0"),
            cell(line("--recu", "700", "d"), modeOn(octal("700"), "d").copy(recursive = true), "--recu is -R"),
        ),
    )

    @Test
    fun `a mode word that starts with a dash is the mode`() = parity.checkAll(
        listOf(
            cell(line("-w", "f"), modeOn(symbolic("-w"), "f"), "f becomes 444"),
            cell(line("-rwx", "f"), modeOn(symbolic("-rwx"), "f"), "f becomes 000"),
            cell(line("-755", "f"), modeOn(octal("755", REMOVE), "f"), "f becomes 000"),
            cell(line("-R", "-w", "d"), modeOn(symbolic("-w"), "d").copy(recursive = true), "d becomes 555"),
            cell(line("-w", "-R", "d"), modeOn(symbolic("-w"), "d").copy(recursive = true), "d becomes 555"),
            cell(line("-w", "755", "f"), modeOn(symbolic("-w"), "755", "f"), "cannot access '755'"),
            cell(line("-5", "f"), modeOn(octal("5", REMOVE), "f"), "f becomes 640"),
            cell(line("-w,u+x", "f"), modeOn(symbolic("-w", "u+x"), "f"), "f becomes 544"),
            cell(line("-Rw", "f"), null, "chmod: invalid mode: '-Rw'"),
            cell(line("-wR", "f"), null, "chmod: invalid mode: '-wR'"),
            cell(line("-cw", "f"), null, "chmod: invalid mode: '-cw'"),
            cell(line("-w5", "f"), null, "chmod: invalid mode: '-w5'"),
            cell(line("-a", "f"), null, "chmod: invalid mode: '-a'"),
            cell(line("-w"), null, "chmod: missing operand"),
            cell(
                line("-z", "f"),
                null,
                "chmod: invalid option -- 'z'",
                differs = "klap reads -z as the dash-led mode and refuses the mode, where getopt refuses the letter",
            ),
            cell(
                line("-8", "f"),
                null,
                "chmod: invalid option -- '8'",
                differs = "klap reads -8 as the dash-led mode and refuses the mode, where getopt refuses the letter",
            ),
        ),
    )

    @Test
    fun `a mode and --reference refuse each other`() = parity.checkAll(
        listOf(
            cell(
                line("--reference=r", "-w", "f"),
                null,
                "chmod: cannot combine mode and --reference options",
                differs = "klap refuses -w as an unknown option, as the mode slot is gone under --reference",
            ),
            cell(
                line("-w", "--reference=r", "f"),
                null,
                "chmod: cannot combine mode and --reference options",
                differs = "klap refuses -w as an unknown option, as the mode slot is gone under --reference",
            ),
            cell(line("--reference", "r", "--", "-w"), referencing("r", "-w"), "cannot access '-w'"),
        ),
    )

    @Test
    fun `the last of -H -L and -P holds`() = parity.checkAll(
        listOf(
            cell(line("-R", "-H", "-L", "u+x", "d"), TO_D.copy(recursive = true, traverseAllLinks = true), "exit 0"),
            cell(line("-R", "-L", "-H", "u+x", "d"), TO_D.copy(recursive = true, traverseArgLinks = true), "exit 0"),
            cell(line("-RHLP", "u+x", "d"), TO_D.copy(recursive = true, traverseNoLinks = true), "exit 0"),
            cell(line("-RPLH", "u+x", "d"), TO_D.copy(recursive = true, traverseArgLinks = true), "exit 0"),
        ),
    )

    @Test
    fun `the polarity written last holds for --dereference and --preserve-root`() = parity.checkAll(
        listOf(
            cell(line("--no-dereference", "u+x", "f"), U_X_F.copy(dereference = NoFollow), "exit 0"),
            cell(line("--no-dereference", "--dereference", "u+x", "f"), U_X_F.copy(dereference = Follow), "exit 0"),
            cell(line("--dereference", "--no-d", "u+x", "f"), U_X_F.copy(dereference = NoFollow), "exit 0"),
            cell(line("--preserve-root", "--no-preserve-root", "u+x", "f"), U_X_F.copy(preserveRoot = false), "exit 0"),
            cell(line("--no-preserve-root", "--preserve-root", "u+x", "f"), U_X_F.copy(preserveRoot = true), "exit 0"),
            cell(line("--no-p", "u+x", "f"), U_X_F, "--no-p is --no-preserve-root"),
            cell(line("--no-", "u+x", "f"), null, "chmod: option '--no-' is ambiguous"),
            cell(line("--n", "u+x", "f"), null, "chmod: option '--n' is ambiguous"),
            cell(line("--no-dereference=x", "u+x", "f"), null, "option '--no-dereference' doesn't allow an argument"),
        ),
    )

    @Test
    fun `--dereference under -R and -P with none of -H and -L after it is still read`() = parity.checkAll(
        listOf(
            cell(
                line("--dereference", "-P", "u+x", "f"),
                U_X_F.copy(dereference = Follow, traverseNoLinks = true),
                "without -R: exit 0",
            ),
            cell(
                line("-R", "--dereference", "-P", "-L", "u+x", "d"),
                TO_D.copy(recursive = true, dereference = Follow, traverseAllLinks = true),
                "exit 0",
            ),
            cell(
                line("-P", "-R", "--dereference", "-H", "u+x", "d"),
                TO_D.copy(recursive = true, dereference = Follow, traverseArgLinks = true),
                "exit 0",
            ),
            cell(
                line("-R", "--no-dereference", "-P", "u+x", "d"),
                TO_D.copy(recursive = true, dereference = NoFollow, traverseNoLinks = true),
                "exit 0",
            ),
            cell(
                line("-R", "--dereference", "-h", "-P", "u+x", "d"),
                TO_D.copy(recursive = true, dereference = NoFollow, traverseNoLinks = true),
                "exit 0: -h is the later",
            ),
        ),
    )

    @Test
    fun `an ambiguous or unknown long is refused`() = parity.checkAll(
        listOf(
            cell(line("--zzz"), null, "chmod: unrecognized option '--zzz'"),
            cell(line("-Q", "700", "d"), null, "chmod: invalid option -- 'Q'"),
            cell(line("--re", "700", "d"), null, "option '--re' is ambiguous: '--recursive' '--reference'"),
            cell(line("--r", "700", "d"), null, "chmod: option '--r' is ambiguous"),
            cell(line("--v", "u+x", "f"), null, "option '--v' is ambiguous; possibilities: '--verbose' '--version'"),
            cell(line("--ve", "u+x", "f"), null, "chmod: option '--ve' is ambiguous"),
            cell(line("--reference"), null, "chmod: option '--reference' requires an argument"),
            cell(line("--t", "u+x", "f"), null, "chmod: unrecognized option '--t'"),
            cell(line("--help-all"), null, "chmod: unrecognized option '--help-all'"),
            cell(line("--docs"), null, "chmod: unrecognized option '--docs'"),
            cell(line("--co", "u+x", "f"), null, "chmod: unrecognized option '--co'"),
        ),
    )

    @Test
    fun `--help and --version answer wherever they stand`() {
        checkCells(
            listOf(
                line("--help"), line("u+x", "f", "--help"), line("-R", "--help"), line("--help", "--bogus"),
                line("--help", "--version"), line("--help", "-Q"), line("xyz", "--help"), line("-w", "--help"),
                line("--he"), line("--h"),
            ),
        ) { parity.showsHelp(argv = it.toTypedArray(), because = "chmod prints its usage and exits 0") }
        checkCells(listOf(line("--version"), line("--vers"), line("--version", "--help"))) {
            parity.showsVersion(argv = it.toTypedArray(), because = "chmod prints its version and exits 0")
        }
        parity.checkAll(
            listOf(
                cell(line("--bogus", "--help"), null, "chmod: unrecognized option '--bogus'"),
                cell(line("--help=x"), null, "chmod: option '--help' doesn't allow an argument"),
                cell(line("--version=x"), null, "chmod: option '--version' doesn't allow an argument"),
                cell(line("--reference", "--help"), null, "chmod: missing operand: --help is RFILE"),
            ),
        )
    }

    @Test
    fun `a dash-led mode after an operand is still the mode`() = parity.checkAll(
        // `chmod u+x -w f` leaves f at 444 and says "cannot access 'u+x'": -w was the mode, u+x a file.
        listOf(
            cell(line("u+x", "-w", "f"), modeOn(symbolic("-w"), "u+x", "f"), "f 444"),
            cell(line("f", "-w"), modeOn(symbolic("-w"), "f"), "f becomes 444"),
        ),
    )

    @Test
    fun `the later of -c and -v holds`() {
        // `chmod -v -c 644 f` and `chmod -vc 644 f` print nothing, `chmod -c -v 644 f` and `chmod -cv 644 f` print
        // "mode of 'f' retained as 0644": one verbosity, the later letter deciding.
        parity.checkAll(
            listOf(
                cell(line("-c", "-v", "u+x", "f"), U_X_F.copy(verbose = true), "prints retained"),
                cell(line("-v", "-c", "u+x", "f"), U_X_F.copy(changes = true), "prints nothing"),
                cell(line("--changes", "--verbose", "u+x", "f"), U_X_F.copy(verbose = true), "prints retained"),
                cell(line("-vc", "u+x", "f"), U_X_F.copy(changes = true), "prints nothing"),
                cell(line("-cv", "u+x", "f"), U_X_F.copy(verbose = true), "prints retained"),
                cell(
                    line("-cfv", "644", "a", "b"),
                    modeOn(octal("644"), "a", "b").copy(silent = true, verbose = true),
                    "-v is the later",
                ),
            ),
        )
    }

    @Test
    fun `--quiet binds what --silent binds`() {
        // `chmod --help`: "-f, --silent, --quiet  suppress most error messages"; one input, so one record.
        parity.checkAll(
            cells(listOf(input("--quiet")), LONG, LONG_REPEATED, ABBREVIATED, BETWEEN, AFTER)
                .filter { cell -> cell.argv.any { it.startsWith("--q") } } +
                cell(line("--quiet", "--silent", "u+x", "f"), U_X_F.copy(silent = true), "exit 0"),
        )
    }

    @Test
    fun `-h is the short of --no-dereference`() {
        // `chmod --help`: "-h, --no-dereference  affect each symbolic link, rather than the referent"; `chmod -h u+x
        // lf` leaves the referent f at 644 where `chmod u+x lf` makes it 744.
        parity.checkAll(
            cells(listOf(input("-h")), SHORT, SHORT_REPEATED, CLUSTER, BETWEEN, AFTER)
                .filter { cell -> cell.argv.none { it.startsWith("--") } } +
                listOf(
                    cell(line("-Rh", "u+x", "d"), TO_D.copy(recursive = true, dereference = NoFollow), "exit 0"),
                    cell(line("-hR", "u+x", "d"), TO_D.copy(recursive = true, dereference = NoFollow), "exit 0"),
                    cell(line("-hc", "u+x", "f"), U_X_F.copy(dereference = NoFollow, changes = true), "exit 0"),
                    cell(line("--dereference", "-h", "u+x", "f"), U_X_F.copy(dereference = NoFollow), "exit 0"),
                    cell(line("-h", "--dereference", "u+x", "f"), U_X_F.copy(dereference = Follow), "exit 0"),
                ),
        )
    }

    @Test
    fun `-H -L and -P have no long names`() {
        // `chmod --help` lists "-H", "-L" and "-P" alone; `chmod --traverse-arg-links u+x f`: "unrecognized option".
        parity.checkAll(
            LONGS_CHMOD_LACKS.take(3).map {
                    cell(line(it, "u+x", "f"), null, "chmod: unrecognized option '$it'")
                } +
                cell(line("--traverse-arg", "u+x", "f"), null, "chmod: unrecognized option '--traverse-arg'"),
        )
    }

    @Test
    fun `an option chmod does not have is refused`() = parity.checkAll(
        // `chmod --json u+x f`, `--color`, `--completion bash`, `--docs man`, `--help-all`: "unrecognized option".
        listOf(
            cell(line("--json", "u+x", "f"), null, "chmod: unrecognized option '--json'"),
            cell(line("--j", "u+x", "f"), null, "chmod: unrecognized option '--j'"),
            cell(line("--color", "u+x", "f"), null, "chmod: unrecognized option '--color'"),
            cell(line("--color=always", "u+x", "f"), null, "chmod: unrecognized option '--color=always'"),
            cell(line("--completion", "bash"), null, "chmod: unrecognized option '--completion'"),
            cell(line("--docs", "man"), null, "chmod: unrecognized option '--docs'"),
        ),
    )

    @Test
    fun `--c and --d abbreviate --changes and --dereference`() = parity.checkAll(
        // `chmod --c u+x f` prints "mode of 'f' changed"; `chmod --d u+x f` exits 0.
        cells(listOf(input("--changes"), input("--dereference")), ABBREVIATED),
    )

    @Test
    fun `-R --dereference refuses -P as the final traversal`() = parity.checkAll(
        // `chmod -R --dereference -P u+x d`, `-R -P --dereference`, `-R --dereference -L -P`, `--dereference -R -H -P`
        // and `-R -h --dereference -P`: "-R --dereference requires either -H or -L", exit 1.
        listOf(
            cell(line("-R", "--dereference", "-P", "u+x", "d"), null, "-R --dereference requires either -H or -L"),
            cell(line("-R", "-P", "--dereference", "u+x", "d"), null, "-R --dereference requires either -H or -L"),
            cell(line("-R", "--dereference", "-L", "-P", "u+x", "d"), null, "requires either -H or -L"),
            cell(line("--dereference", "-R", "-H", "-P", "u+x", "d"), null, "requires either -H or -L"),
            cell(line("-R", "-h", "--dereference", "-P", "u+x", "d"), null, "requires either -H or -L"),
        ),
    )

    @Test
    fun `-R alone and -R --dereference bind apart`() = checkDiffer(
        // In a directory holding d/link -> ../f, `chmod -R u+x d` leaves f at 644 and `chmod -R --dereference u+x d`
        // makes it 744; info chmod: dereferencing "is the default for command line arguments, but not for symbolic
        // links encountered when recursing".
        listOf(Differ(line("-R", "u+x", "d"), line("-R", "--dereference", "u+x", "d"), "f 644 against f 744")),
    )

    @Test
    fun `__complete as the first word is the mode`() = parity.checkAll(
        // `chmod __complete f`: "invalid mode: '__complete'", so the word reached the MODE operand (todo 102).
        listOf(cell(line("__complete", "f"), null, "chmod: invalid mode: '__complete'")),
    )

    @Test
    fun `every dash-led mode word on the line adds to the one mode`() = parity.checkAll(
        // chmod appends every mode-letter word to the mode with a comma: `chmod -w -x f` leaves f at 444 and
        // `chmod -w -755 f` at 000, both exit 0.
        listOf(
            cell(line("-w", "-x", "f"), modeOn(symbolic("-w", "-x"), "f"), "f 444"),
            cell(line("-w", "-755", "f"), modeOn(symbolic("-w", "-755"), "f"), "f 000"),
        ),
    )
}
