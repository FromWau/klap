package com.fromwau.klap.fixture.rm

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.rm.RmProjectionSupport.ABBREVIATED
import com.fromwau.klap.fixture.rm.RmProjectionSupport.AFTER
import com.fromwau.klap.fixture.rm.RmProjectionSupport.AFTER_END
import com.fromwau.klap.fixture.rm.RmProjectionSupport.ALL
import com.fromwau.klap.fixture.rm.RmProjectionSupport.ATTACHED
import com.fromwau.klap.fixture.rm.RmProjectionSupport.BARE
import com.fromwau.klap.fixture.rm.RmProjectionSupport.BARE_AFTER_VALUE
import com.fromwau.klap.fixture.rm.RmProjectionSupport.BARE_THEN_WORD
import com.fromwau.klap.fixture.rm.RmProjectionSupport.BETWEEN
import com.fromwau.klap.fixture.rm.RmProjectionSupport.CHOICE_ABBREVIATED
import com.fromwau.klap.fixture.rm.RmProjectionSupport.CHOICE_CAPITALS
import com.fromwau.klap.fixture.rm.RmProjectionSupport.CHOICE_EMPTY
import com.fromwau.klap.fixture.rm.RmProjectionSupport.CHOICE_UNKNOWN
import com.fromwau.klap.fixture.rm.RmProjectionSupport.CLUSTER
import com.fromwau.klap.fixture.rm.RmProjectionSupport.Cell
import com.fromwau.klap.fixture.rm.RmProjectionSupport.LONG
import com.fromwau.klap.fixture.rm.RmProjectionSupport.LONG_ATTACHED
import com.fromwau.klap.fixture.rm.RmProjectionSupport.LONG_REPEATED
import com.fromwau.klap.fixture.rm.RmProjectionSupport.REPEATED
import com.fromwau.klap.fixture.rm.RmProjectionSupport.SHORT
import com.fromwau.klap.fixture.rm.RmProjectionSupport.SHORT_REPEATED
import com.fromwau.klap.fixture.rm.RmProjectionSupport.Same
import com.fromwau.klap.fixture.rm.RmProjectionSupport.checkAll
import com.fromwau.klap.fixture.rm.RmProjectionSupport.checkSame
import com.fromwau.klap.fixture.rm.RmProjectionSupport.cells
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * Every expected value is from GNU coreutils 9.12 `rm`, run inside a throwaway directory holding the files a and b (and
 * where a line names them, a directory dd and an empty directory ed): a `because` quotes what it printed or which
 * files it left, and a comment says where a claim was read from `rm --help`, getopt or the strings of /usr/bin/rm
 * instead. A line rm refuses only while removing (a file that does not exist, a directory without -r) still parsed, so
 * it binds. -i, -I and --interactive lines ran with `n` on standard input, so nothing was removed by a prompt.
 */

/** The spellings of every flag of rm but -R, by key. */
private val FLAG_KEYS: List<String> = listOf(
    "--force", "-f", "-i", "-I", "--one-file-system", "--no-preserve-root", "--recursive", "-r", "--dir", "-d",
    "--verbose", "-v",
)

// --no-preserve-root refuses every abbreviation and `--d` is checked as --dir: each has a test of its own.
private val ABBREVIATING_FLAG_KEYS: List<String> = FLAG_KEYS - "--no-preserve-root" - "--dir"

private fun cell(
    argv: List<String>,
    expected: RmInputs?,
    because: String,
) = Cell("line", "whole line", argv, expected, because)

private fun files(vararg names: String): RmInputs = NOTHING_BOUND.copy(files = names.toList())

private val AB = listOf("a", "b")

/**
 * The record an rm line binds in the fixture, or its refusal, as real rm 9.12 reads the line (P of the testing matrix):
 * every input in every spelling and position, values at the edges of their word, repeats and overrides, the operand
 * list -f lets go empty, and help, version and klap's built-ins on a line.
 */
class RmProjectionTest {

    private val parity = ParitySuite(rmCli())

    @Test
    fun `every flag binds by its long name and each short alone doubled and clustered`() =
        parity.checkAll(cells(FLAG_KEYS, LONG, LONG_REPEATED, SHORT, SHORT_REPEATED, CLUSTER))

    @Test
    fun `every flag binds by the shortest abbreviation of its long name`() =
        parity.checkAll(cells(ABBREVIATING_FLAG_KEYS, ABBREVIATED))

    @Test
    fun `a flag refuses a value attached to its long name`() = parity.checkAll(cells(FLAG_KEYS, LONG_ATTACHED))

    @Test
    fun `every flag binds the same record before between and after the operands`() =
        parity.checkAll(cells(FLAG_KEYS, BETWEEN, AFTER))

    @Test
    fun `every spelling after the end of options is an operand`() =
        parity.checkAll(cells(FLAG_KEYS + "-R" + "--interactive" + "--preserve-root", AFTER_END))

    @Test
    fun `--interactive binds each documented mode attached and the bare one as always`() = parity.checkAll(
        cells(listOf("--interactive"), ATTACHED, BARE, BARE_THEN_WORD, BETWEEN, AFTER, REPEATED, BARE_AFTER_VALUE)
            .filterNot { it.alias },
    )

    @Test
    fun `--interactive binds every unique prefix of a documented mode`() =
        parity.checkAll(cells(listOf("--interactive"), CHOICE_ABBREVIATED).filterNot { it.alias })

    @Test
    fun `--interactive refuses a value in capitals one rm does not know and the empty one`() =
        parity.checkAll(cells(listOf("--interactive"), CHOICE_CAPITALS, CHOICE_UNKNOWN, CHOICE_EMPTY))

    @Test
    fun `--preserve-root binds bare and leaves the next word an operand`() = parity.checkAll(
        cells(listOf("--preserve-root"), BARE, BARE_THEN_WORD) +
            cells(listOf("--preserve-root"), ABBREVIATED).filter { '=' !in it.argv.first() },
    )

    @Test
    fun `--preserve-root refuses every value but all`() = parity.checkAll(
        cells(listOf("--preserve-root"), CHOICE_CAPITALS, CHOICE_UNKNOWN, CHOICE_EMPTY),
    )

    @Test
    fun `every generated cell is checked by one test`() {
        // The pass tests above and the targets below split ALL by key and form; a cell of no key they name is lost.
        val keys = FLAG_KEYS + "-R" + "--interactive" + "--preserve-root"
        val unchecked = ALL.filter { it.key !in keys }
        assertTrue(unchecked.isEmpty(), "cells no test checks: $unchecked")
    }

    @Test
    fun `the operands bind as rm reads them`() = parity.checkAll(
        listOf(
            cell(listOf("a"), files("a"), "rm a removes a"),
            cell(AB, files("a", "b"), "rm a b removes both"),
            cell(listOf("--", "-foo"), files("-foo"), "rm -- -foo: cannot remove '-foo'"),
            cell(listOf("a", "--", "-v"), files("a", "-v"), "rm a -- -v: cannot remove '-v'"),
            cell(listOf("--", "--"), files("--"), "rm -- --: cannot remove '--'"),
            cell(listOf("--", "--", "a"), files("--", "a"), "rm -- -- a: cannot remove '--'"),
            cell(listOf("--", "--help"), files("--help"), "rm -- --help: cannot remove '--help'"),
            cell(listOf("-"), files("-"), "rm -: cannot remove '-'"),
            cell(listOf(""), files(""), "rm \"\": cannot remove ''"),
            cell(listOf("a b"), files("a b"), "rm 'a b' removes the file a b"),
            cell(listOf("completion", "a"), files("completion", "a"), "rm completion a: cannot remove 'completion'"),
            cell(listOf("docs", "a"), files("docs", "a"), "rm docs a: cannot remove 'docs'"),
            cell(listOf("--", "-5"), files("-5"), "getopt: an operand after --"),
            // `~/x` and `ü*` were not run, as the oracle's safety rule forbids them; rm gets the word as the shell
            // left it.
            cell(listOf("ü*", "~/x"), files("ü*", "~/x"), "rm reads the word"),
            cell(emptyList(), null, "rm: missing operand"),
            cell(listOf("--"), null, "rm: missing operand"),
            cell(listOf("-v"), null, "rm: missing operand"),
            cell(listOf("-r"), null, "rm: missing operand"),
            cell(listOf("--interactive=never"), null, "rm: missing operand"),
            cell(listOf("-i"), null, "rm: missing operand"),
        ),
    )

    @Test
    fun `-f lets the operand list go empty`() = parity.checkAll(
        listOf(
            cell(listOf("-f"), NOTHING_BOUND.forcing(), "rm -f exits 0"),
            cell(listOf("--force"), NOTHING_BOUND.forcing(), "rm --force exits 0"),
            cell(listOf("--f"), NOTHING_BOUND.forcing(), "rm --f exits 0"),
            cell(listOf("-f", "--"), NOTHING_BOUND.forcing(), "rm -f -- exits 0"),
            cell(listOf("-rf"), NOTHING_BOUND.forcing().copy(recursive = true), "rm -rf exits 0"),
            cell(listOf("-f", "-v"), NOTHING_BOUND.forcing().copy(verbose = true), "rm -f -v exits 0"),
            cell(listOf("-f", "-d"), NOTHING_BOUND.forcing().copy(dir = true), "rm -f -d exits 0"),
            cell(listOf("-f", "-f"), NOTHING_BOUND.forcing(), "rm -f -f exits 0"),
            cell(listOf("-f", "--one-file-system"), NOTHING_BOUND.forcing().copy(oneFileSystem = true), "exit 0"),
            cell(listOf("-f", "--preserve-root"), NOTHING_BOUND.forcing(), "rm -f --preserve-root exits 0"),
            cell(listOf("-f", "nofile"), NOTHING_BOUND.forcing().copy(files = listOf("nofile")), "exit 0"),
            cell(listOf("--", "-f"), files("-f"), "rm -- -f: cannot remove '-f'"),
        ),
    )

    @Test
    fun `a line setting every input binds them all`() = parity.checkAll(
        listOf(
            // `rm --interactive=never --one-file-system --preserve-root -rRdv a dd` removed both, verbosely.
            cell(
                listOf("--interactive=never", "--one-file-system", "--preserve-root", "-rdv", "a", "dd"),
                NOTHING_BOUND.promptingBy(Prompt.Never).copy(
                    oneFileSystem = true,
                    recursive = true,
                    dir = true,
                    verbose = true,
                    files = listOf("a", "dd"),
                ),
                "rm exits 0",
            ),
            cell(listOf("-f", "-v", "--", "a"), files("a").forcing().copy(verbose = true), "rm exits 0"),
        ),
    )

    @Test
    fun `help and version answer wherever they sit on a valid line`() {
        parity.showsHelp("--help", because = "rm --help prints its usage")
        parity.showsHelp("--help", "a", "b", because = "rm --help a b prints its usage")
        parity.showsHelp("a", "b", "--help", because = "rm a b --help prints its usage")
        parity.showsHelp("-v", "--help", "a", because = "rm -v --help a prints its usage")
        parity.showsHelp("-f", "--help", because = "rm -f --help prints its usage")
        parity.showsHelp("--interactive=never", "--help", because = "rm --interactive=never --help prints its usage")
        parity.showsHelp("--he", because = "rm --he prints its usage")
        parity.showsHelp("--h", because = "rm has no other long starting with h")
        parity.showsVersion("--version", because = "rm --version prints its banner")
        parity.showsVersion("--vers", because = "rm --vers prints its banner")
        parity.showsVersion("--version", "a", because = "rm --version a prints its banner")
    }

    @Test
    fun `the first of help and version answers`() {
        parity.showsHelp("--help", "--version", because = "rm --help --version prints its usage")
        parity.showsVersion("--version", "--help", because = "rm --version --help prints its banner")
    }

    @Test
    fun `help or version written before a refused word answers`() {
        // `rm --help --bogus`, `rm --help -h`, `rm --help --interactive=bogus` and `rm --help --preserve-root=bogus`
        // print the usage; `rm --version -5` prints the banner.
        parity.showsHelp("--help", "--bogus", because = "rm --help --bogus prints its usage")
        parity.showsHelp("--help", "-h", because = "rm --help -h prints its usage")
        parity.showsHelp("--help", "--interactive=bogus", because = "rm --help --interactive=bogus prints its usage")
        parity.showsVersion("--version", "-5", because = "rm --version -5 prints its banner")
    }

    @Test
    fun `an unknown option or a bad value written before help refuses the line`() = parity.checkAll(
        listOf(
            cell(listOf("--bogus", "--help"), null, "rm: unrecognized option '--bogus'"),
            cell(listOf("-h", "--help"), null, "rm: invalid option -- 'h'"),
            cell(listOf("--interactive=bogus", "--help"), null, "rm: invalid argument 'bogus' for '--interactive'"),
        ),
    )

    @Test
    fun `a bad --preserve-root value before help refuses the line`() {
        // `rm --preserve-root=bogus --help`: "unrecognized --preserve-root argument: 'bogus'"; `rm --help
        // --preserve-root=bogus` prints the usage.
        parity.rejects(
            "--preserve-root=bogus", "--help",
            because = "rm: unrecognized --preserve-root argument",
        )
        parity.showsHelp("--help", "--preserve-root=bogus", because = "rm --help --preserve-root=bogus prints usage")
    }

    @Test
    fun `rejects what real rm rejects`() {
        parity.rejects("--zzz", "a", because = "real rm: unrecognized option '--zzz'")
        parity.rejects("--R", "a", because = "real rm: unrecognized option '--R'")
        parity.rejects("--help-all", "a", because = "real rm: unrecognized option '--help-all'")
        parity.rejects("-z", "a", because = "real rm: invalid option -- 'z'")
        parity.rejects("-fz", "a", because = "real rm: invalid option -- 'z'")
        parity.rejects("-zf", "a", because = "real rm: invalid option -- 'z'")
        parity.rejects("-h", "a", because = "real rm: invalid option -- 'h'")
        parity.rejects("-vh", "a", because = "real rm: invalid option -- 'h'")
        parity.rejects("-5", "a", because = "real rm: invalid option -- '5'")
        parity.rejects("-v=x", "a", because = "real rm: invalid option -- '='")
        parity.rejects("-i=x", "a", because = "real rm: invalid option -- '='")
        parity.rejects("-inever", "a", because = "real rm: invalid option -- 'n'")
        parity.rejects("--force=yes", "a", because = "real rm: option '--force' doesn't allow an argument")
        parity.rejects("--verbose=", "a", because = "real rm: option '--verbose' doesn't allow an argument")
        parity.rejects("--interactive=nev=", "a", because = "real rm: invalid argument 'nev=' for '--interactive'")
        parity.rejects("--=x", "a", because = "real rm: option '--=x' is ambiguous")
        parity.rejects("--interactive", "-x", "a", because = "real rm: invalid option -- 'x'")
    }

    @Test
    fun `rejects an ambiguous abbreviation as getopt does`() {
        val ambiguous = listOf("--v", "--ve", "--ver")
        checkCells(ambiguous) { prefix ->
            val because = "real rm: option '$prefix' is ambiguous; possibilities: '--verbose' '--version'"
            parity.rejects(prefix, "a", because = because)
        }
    }

    @Test
    fun `--c is no long of rm`() {
        // `rm --c a` and `rm --co a`: "unrecognized option".
        parity.rejects("--c", "a", because = "real rm: unrecognized option '--c'")
        parity.rejects("--co", "a", because = "real rm: unrecognized option '--co'")
    }

    @Test
    fun `--no-preserve-root binds whole and refuses a value`() {
        // `rm --help`: "--no-preserve-root  do not treat '/' specially"; `getopt ... -- --no-preserve-root=x a`:
        // "option '--no-preserve-root' doesn't allow an argument". Not run, as the oracle's safety rule forbids it.
        parity.binds("--no-preserve-root", "a", expected = files("a").copy(preserveRoot = PreserveRoot.Off))
        parity.rejects("--no-preserve-root=x", "a", because = "getopt: doesn't allow an argument")
    }

    @Test
    fun `-R binds what -r binds in every spelling`() {
        // `rm --help`: "-r, -R, --recursive"; `rm -R dd` and `rm -rR dd` remove dd as `rm -r dd` does.
        parity.checkAll(cells(listOf("-R")))
        checkSame(
            listOf(
                Same(listOf("-R", "dd"), listOf("-r", "dd"), "rm --help"),
                Same(listOf("-rR", "dd"), listOf("-r", "dd"), "rm -rR dd removes dd"),
                Same(listOf("-R", "dd"), listOf("--recursive", "dd"), "rm --help"),
            ),
        )
    }

    @Test
    fun `the later of -f -i -I and --interactive decides the prompt`() = parity.checkAll(
        // `rm -f -i a` and `rm -fi a` prompt, `rm -i -f a` and `rm -if a` do not; `rm -I -i a` prompts for a, `rm -i -I
        // a b c e` asks once; `rm --interactive=never -i a` prompts, `rm -i --interactive=never a` does not; `rm -f
        // --interactive=always a` prompts, `rm --interactive=always -f a` does not; `rm -I -f a b c e` asks nothing.
        listOf(
            cell(listOf("-f", "-i", "a"), files("a").promptingBy(Prompt.Always), "prompts"),
            cell(listOf("-fi", "a"), files("a").promptingBy(Prompt.Always), "prompts"),
            cell(listOf("-i", "-f", "a"), files("a").forcing(), "removes a"),
            cell(listOf("-if", "a"), files("a").forcing(), "removes a"),
            cell(listOf("-I", "-i", "a"), files("a").promptingBy(Prompt.Always), "prompts for a"),
            cell(listOf("-i", "-I", "a"), files("a").promptingBy(Prompt.Once), "asks once"),
            cell(listOf("--interactive=never", "-i", "a"), files("a").promptingBy(Prompt.Always), "prompts"),
            cell(listOf("-i", "--interactive=never", "a"), files("a").promptingBy(Prompt.Never), "removes a"),
            cell(listOf("-f", "--interactive=always", "a"), files("a").promptingBy(Prompt.Always), "prompts"),
            cell(listOf("--interactive=always", "-f", "a"), files("a").forcing(), "removes a"),
            cell(listOf("-I", "-f", "a"), files("a").forcing(), "asks nothing"),
            cell(listOf("-f", "-I", "a"), files("a").promptingBy(Prompt.Once), "asks once"),
            // `rm -i -f`, `rm -if`, `rm -I -f`, `rm --interactive=never -f` and `rm -f -i -f` exit 0 with no operand.
            cell(listOf("-i", "-f"), NOTHING_BOUND.forcing(), "rm -i -f exits 0"),
            cell(listOf("-if"), NOTHING_BOUND.forcing(), "rm -if exits 0"),
            cell(listOf("-I", "-f"), NOTHING_BOUND.forcing(), "rm -I -f exits 0"),
            cell(listOf("--interactive=never", "-f"), NOTHING_BOUND.forcing(), "exits 0"),
            cell(listOf("-f", "-i", "-f"), NOTHING_BOUND.forcing(), "rm -f -i -f exits 0"),
        ),
    )

    @Test
    fun `a prompt option after -f needs the operands again`() = parity.checkAll(
        // `rm -f -i`, `rm -fi`, `rm -ffi`, `rm -f -I`, `rm -f --interactive`, `rm -f --interactive=once` and `rm -i -f
        // -i`: "missing operand".
        listOf(
            cell(listOf("-f", "-i"), null, "rm: missing operand"),
            cell(listOf("-fi"), null, "rm: missing operand"),
            cell(listOf("-ffi"), null, "rm: missing operand"),
            cell(listOf("-f", "-I"), null, "rm: missing operand"),
            cell(listOf("-f", "--interactive"), null, "rm: missing operand"),
            cell(listOf("-f", "--interactive=once"), null, "rm: missing operand"),
            cell(listOf("-f", "--interactive=always"), null, "rm: missing operand"),
            cell(listOf("-i", "-f", "-i"), null, "rm: missing operand"),
            cell(listOf("-f", "--interactive=never", "--interactive=once"), null, "rm: missing operand"),
        ),
    )

    @Test
    fun `each spelling of a prompt mode binds one record`() = checkSame(
        // `info coreutils 'rm invocation'`: -i is "Equivalent to --interactive=always", -I "Equivalent to
        // --interactive=once", and "--interactive with no WHEN is equivalent to --interactive=always". `rm -i a`, `rm
        // --interactive a` and `rm --interactive=always a` each prompt for a; `rm -I a b c e` and `rm
        // --interactive=once a b c e` each ask "remove 4 arguments?".
        listOf(
            Same(listOf("-i", "a"), listOf("--interactive=always", "a"), "info: equivalent"),
            Same(listOf("--interactive", "a"), listOf("--interactive=always", "a"), "info: equivalent"),
            Same(listOf("-I", "a"), listOf("--interactive=once", "a"), "info: equivalent"),
            Same(listOf("-f", "-i", "a"), listOf("-i", "a"), "rm -f -i a prompts as -i does"),
            Same(listOf("-i", "-f", "a"), listOf("-f", "a"), "rm -i -f a removes as -f does"),
        ),
    )

    @Test
    fun `--preserve-root=all binds in every spelling`() = parity.checkAll(
        // `rm --preserve-root=all a`, `rm --p=all a`, `rm --pre=all a` and `rm --preserve-root=all -- a` remove a; `rm
        // --preserve-root=al a` is "unrecognized --preserve-root argument: 'al'".
        cells(listOf("--preserve-root"), ATTACHED, BETWEEN, AFTER, REPEATED) +
            cells(listOf("--preserve-root"), ABBREVIATED).filter { '=' in it.argv.first() } +
            cell(
                listOf("--pre=all", "a"),
                files("a").copy(preserveRoot = PreserveRoot.All),
                "rm --pre=all a removes a",
            ),
    )

    @Test
    fun `--interactive-once is no long of rm and --i abbreviates --interactive`() {
        // `rm --interactive-once a`: "unrecognized option"; `rm --i a`, `rm --inter a` and `rm --i=never a` read
        // --interactive, the one long of rm starting so.
        parity.rejects(INVENTED_LONG, "a", because = "real rm: unrecognized option '--interactive-once'")
        parity.checkAll(
            cells(listOf("--interactive"), ABBREVIATED) +
                cell(listOf("--inter", "a"), files("a").promptingBy(Prompt.Always), "rm --inter a prompts") +
                cell(listOf("--inter=o", "a"), files("a").promptingBy(Prompt.Once), "rm --inter=o a b c e asks once") +
                cell(listOf("--i=never", "a"), files("a").promptingBy(Prompt.Never), "rm --i=never a removes a"),
        )
    }

    @Test
    fun `an option rm does not have is refused`() {
        // `rm --json a`, `rm --color=never a`, `rm --color a`, `rm --completion bash a`, `rm --docs a`, `rm --j a`, `rm
        // --col a`, `rm --com a`, `rm --do a`: all "unrecognized option", whatever follows, as getopt refuses the name.
        val lines = listOf(
            listOf("--json", "a"),
            listOf("--color=never", "a"),
            listOf("--color", "a"),
            listOf("--completion", "bash", "a"),
            listOf("--docs", "markdown", "a"),
            listOf("--j", "a"),
            listOf("--col", "a"),
            listOf("--com", "bash", "a"),
            listOf("--do", "man", "a"),
        )
        checkCells(lines) { parity.rejects(argv = it.toTypedArray(), because = "real rm: unrecognized option") }
    }

    @Test
    fun `--d abbreviates --dir`() {
        // `rm --d ed` removed the empty directory ed, as `rm --dir ed` does.
        parity.binds("--d", "ed", expected = files("ed").copy(dir = true))
    }

    @Test
    fun `__complete is a file name`() {
        // `rm __complete a`: "cannot remove '__complete'", and a was removed: a file like any other.
        parity.binds("__complete", "a", expected = files("__complete", "a"))
        parity.binds("a", "__complete", expected = files("a", "__complete"))
    }

    @Test
    fun `--interactive and --preserve-root show an optional value and no bare one`() {
        // `rm --help`: "--interactive[=WHEN]" and "--preserve-root[=all]". klap writes the placeholder in angle
        // brackets (todo 64), so the claim is the optional value and the missing bare hint.
        val page = parity.helpPage("--help", because = "rm --help")
        val rows = page.lines().filter { "--interactive" in it || "--preserve-root" in it }
        assertTrue(rows.any { "--interactive[=" in it } && rows.any { "--preserve-root[=" in it }, page)
        assertTrue(rows.none { "bare:" in it }, rows.joinToString("\n"))
    }

    @Test
    fun `the help page lists rm's options and nothing rm refuses`() {
        // `rm --help` gives --no-preserve-root and --preserve-root[=all] a row each.
        val page = parity.helpPage("--help", because = "rm --help")
        val missing = RM_LONGS.filterNot { it in page }
        val foreign = (KLAP_ONLY_LONGS + INVENTED_LONG).filter { long ->
            Regex("${Regex.escape(long)}(?![\\w-])").containsMatchIn(page)
        }
        assertTrue(missing.isEmpty() && foreign.isEmpty(), "rm --help page: missing $missing, foreign $foreign\n$page")
    }

    @Test
    fun `--interactive binds every word rm reads as a mode`() = parity.checkAll(
        // `rm --interactive=bogus a`: "Valid arguments are: 'never', 'no', 'none' / 'once' / 'always', 'yes'"; `rm
        // --interactive=no a`, `=none` and `=non` remove without asking, `=yes`, `=ye` and `=y` prompt.
        cells(listOf("--interactive")).filter { it.alias },
    )

    @Test
    fun `--no-preserve-root refuses every abbreviation`() {
        // `strings /usr/bin/rm`: "you may not abbreviate the --no-preserve-root option"; getopt reads --n, --no, --no-p
        // and --no-preserve as --no-preserve-root. Not run, as the oracle's safety rule forbids every spelling.
        val because = "rm: you may not abbreviate the --no-preserve-root option"
        parity.checkAll(
            cells(listOf("--no-preserve-root"), ABBREVIATED) +
                listOf("--no", "--no-p", "--no-preserve", "--no-preserve-roo")
                    .map { cell(listOf(it, "a"), null, because) },
        )
    }

    @Test
    fun `--preserve-root refuses a prefix of all`() =
        // `rm --preserve-root=al a`: "unrecognized --preserve-root argument: 'al'", exit 1, and a is left.
        parity.checkAll(cells(listOf("--preserve-root"), CHOICE_ABBREVIATED))
}

/**
 * The cells of the P table the fixture or klap fails today, each a TDD target: the record real rm 9.12 reads the line
 * as, or its refusal, and an `@Ignore` saying what has to change for it to pass. The comment on each gives the
 * evidence; a target is proven by running it without its `@Ignore` and seeing it fail for that reason. A test ignored
 * as `Will not do` is a record instead: what rm does where the fixture reads otherwise by decision.
 */
class RmProjectionTargetsTest {

    private val parity = ParitySuite(rmCli())

    /** A record, not a target: -f's relaxed operand count follows lastWins, while rm keeps it under a later =never. */
    @Ignore(
        "Will not do (todo 118): a later --interactive=never takes -f's place under lastWins, so the operands are " +
            "needed",
    )
    @Test
    fun `-f with a later --interactive=never still lets the operand list go empty`() = parity.checkAll(
        listOf(
            cell(
                listOf("-f", "--interactive=never"),
                NOTHING_BOUND.forcing().promptingBy(Prompt.Never),
                "rm -f --interactive=never exits 0",
            ),
            cell(
                listOf("-f", "--interactive=nev"),
                NOTHING_BOUND.forcing().promptingBy(Prompt.Never),
                "rm -f --interactive=nev exits 0",
            ),
        ),
    )

    /** A record, not a target: klap spells a long without its leading dashes, so none can start with a third. */
    @Ignore(
        "Will not do (todo 121): klap refuses a long starting with '-', and rm's ---presume-input-tty is an " +
            "undocumented test hook",
    )
    @Test
    fun `---presume-input-tty is accepted`() {
        // `rm ---presume-input-tty a` and `rm ---p a` remove a; with a write-protected a, `rm ---presume-input-tty a`
        // asks "remove write-protected regular empty file 'a'?". The record has no field for it, so only the line's
        // acceptance is claimed.
        parity.accepts(PRESUME_INPUT_TTY, "a", because = "rm removes a") { assertTrue(it.files == listOf("a")) }
        parity.accepts("---p", "a", because = "rm removes a") { assertTrue(it.files == listOf("a")) }
    }
}
