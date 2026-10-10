package com.fromwau.klap.fixture.cp

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What real cp 9.12 does with a line where klap or the fixture does something else today. Each test states cp's
 * behaviour and is ignored with the reason `TDD target: <what must change>`; removing the annotation shows it
 * failing until the fixture or klap is changed to match, and nothing else in the suite pins the wrong behaviour,
 * so a fix breaks no other test. Every claim was run with real cp inside a throwaway directory unless its comment
 * says it was read from the help or the info manual.
 */
class CpTargetsTest {

    private val parity = ParitySuite(cpCli())

    private fun check(cells: List<ParseCell>) = checkCells(cells) { parity.check(it) }

    @Ignore("TDD target: the fixture must give --recursive both -R and -r and drop the invented --recursive-r")
    @Test
    fun `-r is a short of --recursive`() {
        // `cp --help`: "-R, -r, --recursive"; `cp --rec` reaches --recursive; `cp --recursive-r`: unrecognized option.
        check(LOWERCASE_R_CELLS)
        parity.rejects("--recursive-r", "a", "b", because = "real cp: unrecognized option '--recursive-r'")
    }

    @Ignore("TDD target: the fixture must make -u write the --update setting so the later of the two wins")
    @Test
    fun `-u is --update without a value and the later of the two wins`() {
        // `cp --update=none -u src dst` replaces an older dst; `cp -u --update=none src dst` leaves it.
        check(U_CELLS)
        parity.binds("--update=none", "-u", "a", "b", expected = A_TO_B.copy(update = "older"))
        parity.binds("-u", "--update=none", "a", "b", expected = A_TO_B.copy(update = "none"))
        parity.binds("-dbuHpZ", "a", "b", expected = A_TO_B.copy(
            noDereferencePreserveLinks = true,
            backupSimple = true,
            update = "older",
            dereferenceArgs = true,
            preserveDefaults = true,
            selinuxDefault = true,
        ))
    }

    @Ignore("TDD target: the fixture must check each --preserve and --no-preserve name against cp's attribute list")
    @Test
    fun `an attribute list holds only cp's attribute names or their unique prefixes`() {
        // `cp --preserve=bogus`, `=MODE`, `=` and `=mode,`: invalid or ambiguous argument; `cp --preserve=m` is
        // mode. `cp "--preserve=mode, links"`: invalid argument ' links', so no name is trimmed.
        check(ATTRIBUTE_NAME_CELLS)
        parity.rejects(
            "--preserve=mode,ownership, timestamps", "a", "b",
            because = "real cp: invalid argument ' timestamps' for '--preserve'",
        )
    }

    @Ignore("TDD target: the fixture must collect every --preserve and every --no-preserve, not the last one only")
    @Test
    fun `a repeated attribute list adds to the earlier one`() {
        // `cp --preserve=mode --preserve=timestamps src out` keeps both; `cp -p --no-preserve=mode
        // --no-preserve=timestamps src out` drops both.
        check(ATTRIBUTE_REPEAT_CELLS)
    }

    @Ignore("TDD target: the fixture must give --preserve and --context an optional value as cp's help shows")
    @Test
    fun `--preserve and --context take their value only when attached`() {
        // `cp --help`: "--preserve[=ATTR_LIST]" and "--context[=CTX]"; `cp --preserve mode src dst`: target 'dst':
        // Not a directory, so mode was a source. Bare --context is "like -Z", from the help only.
        check(OPTIONAL_VALUE_CELLS)
    }

    @Ignore("TDD target: a bare --backup must keep an earlier control, and an empty one must mean the default")
    @Test
    fun `a bare or empty --backup keeps the backup control already chosen`() {
        // `cp --backup=numbered --backup src dst` makes dst.~1~; `cp --backup= src dst` makes dst~.
        check(BACKUP_CONTROL_CELLS)
    }

    @Ignore("TDD target: the fixture must refuse a second -t, as cp does")
    @Test
    fun `a second target directory is refused`() {
        // `cp -t a -t b x` and `cp --target-directory=a --target-directory=a x`: multiple target directories specified.
        check(TARGET_DIRECTORY_TWICE_CELLS)
        parity.rejects("-t", "d", "-t", "d", "a", because = "real cp: multiple target directories specified")
    }

    @Ignore("TDD target: the fixture must refuse -T with more than two operands while parsing")
    @Test
    fun `-T takes exactly a source and a destination`() {
        parity.rejects("-T", "a", "b", "c", because = "real cp: extra operand 'c'")
        parity.rejects("a", "b", "c", "-T", because = "real cp: extra operand 'c'")
    }

    @Ignore("TDD target: the fixture must refuse -t together with -T while parsing")
    @Test
    fun `-t and -T are refused together`() {
        val why = "real cp: cannot combine --target-directory (-t) and --no-target-directory (-T)"
        parity.rejects("-t", "d", "-T", "a", because = why)
        parity.rejects("-T", "-t", "d", "a", because = why)
        parity.rejects("-t", "dir", "src", "-T", because = why)
    }

    @Test
    fun `an option cp does not have is refused`() {
        // `cp --json`, `cp --color=never`, `cp --completion`, `cp --docs`, `cp --col`, `cp --j`, `cp --com`, `cp --do`:
        // all "unrecognized option"; `cp -h`: invalid option -- 'h'.
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
            listOf("-h", "a", "b"),
            listOf("-vh", "a", "b"),
        )
        checkCells(lines) { parity.rejects(argv = it.toTypedArray(), because = "real cp: unrecognized option") }
    }

    @Test
    fun `--help-all is refused`() {
        parity.rejects("--help-all", "a", "b", because = "real cp: unrecognized option '--help-all'")
    }

    @Ignore("Will not do (todo 40): klap reserves the first word __complete")
    @Test
    fun `__complete is a source file name`() {
        // `cp __complete a`: cannot stat '__complete', so it was a source like any other word.
        parity.binds("__complete", "a", expected = NOTHING_BOUND.copy(sources = listOf("__complete"), dest = "a"))
    }

    @Ignore("TDD target: the fixture must print GNU coreutils 9.12 as its version")
    @Test
    fun `--version names coreutils 9 dot 12`() {
        val text = parity.versionText("--version", because = "cp --version: cp (GNU coreutils) 9.12")
        assertTrue("9.12" in text, "`cp --version` printed: $text")
    }

    @Ignore("TDD target: the help page must list cp's options only, without --recursive-r or klap's built-ins")
    @Test
    fun `the help page lists cp's options and nothing cp refuses`() {
        val page = parity.helpPage("--help", because = "cp --help")
        val missing = CP_LONGS.filterNot { it in page }
        val foreign = KLAP_ONLY_LONGS.filter { long -> Regex("${Regex.escape(long)}(?![\\w-])").containsMatchIn(page) }
        assertTrue(missing.isEmpty() && foreign.isEmpty(), "cp --help page: missing $missing, foreign $foreign\n$page")
    }
}
