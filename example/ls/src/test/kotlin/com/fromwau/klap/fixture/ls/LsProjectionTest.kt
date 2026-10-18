package com.fromwau.klap.fixture.ls

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.ls.LsProjectionSupport.ABBREVIATED
import com.fromwau.klap.fixture.ls.LsProjectionSupport.AFTER
import com.fromwau.klap.fixture.ls.LsProjectionSupport.AFTER_END
import com.fromwau.klap.fixture.ls.LsProjectionSupport.ALL
import com.fromwau.klap.fixture.ls.LsProjectionSupport.AMBIGUOUS_PREFIXES
import com.fromwau.klap.fixture.ls.LsProjectionSupport.ATTACHED
import com.fromwau.klap.fixture.ls.LsProjectionSupport.BARE
import com.fromwau.klap.fixture.ls.LsProjectionSupport.BARE_THEN_WORD
import com.fromwau.klap.fixture.ls.LsProjectionSupport.BETWEEN
import com.fromwau.klap.fixture.ls.LsProjectionSupport.CHOICE_ABBREVIATED
import com.fromwau.klap.fixture.ls.LsProjectionSupport.CHOICE_AMBIGUOUS
import com.fromwau.klap.fixture.ls.LsProjectionSupport.CHOICE_CAPITALS
import com.fromwau.klap.fixture.ls.LsProjectionSupport.CHOICE_EMPTY
import com.fromwau.klap.fixture.ls.LsProjectionSupport.CHOICE_UNKNOWN
import com.fromwau.klap.fixture.ls.LsProjectionSupport.CLUSTER
import com.fromwau.klap.fixture.ls.LsProjectionSupport.CLUSTER_GLUED
import com.fromwau.klap.fixture.ls.LsProjectionSupport.CLUSTER_SPACED
import com.fromwau.klap.fixture.ls.LsProjectionSupport.Cell
import com.fromwau.klap.fixture.ls.LsProjectionSupport.GLUED
import com.fromwau.klap.fixture.ls.LsProjectionSupport.LONG
import com.fromwau.klap.fixture.ls.LsProjectionSupport.LONG_ATTACHED
import com.fromwau.klap.fixture.ls.LsProjectionSupport.LONG_REPEATED
import com.fromwau.klap.fixture.ls.LsProjectionSupport.MISSING_VALUE
import com.fromwau.klap.fixture.ls.LsProjectionSupport.REPEATED
import com.fromwau.klap.fixture.ls.LsProjectionSupport.SHORT
import com.fromwau.klap.fixture.ls.LsProjectionSupport.SHORT_EQUALS
import com.fromwau.klap.fixture.ls.LsProjectionSupport.SHORT_REPEATED
import com.fromwau.klap.fixture.ls.LsProjectionSupport.SHORT_SPACED
import com.fromwau.klap.fixture.ls.LsProjectionSupport.SPACED
import com.fromwau.klap.fixture.ls.LsProjectionSupport.SYNONYM
import com.fromwau.klap.fixture.ls.LsProjectionSupport.SYNONYM_ABBREVIATED
import com.fromwau.klap.fixture.ls.LsProjectionSupport.checkAll
import com.fromwau.klap.fixture.ls.LsProjectionSupport.cells
import com.fromwau.klap.fixture.ls.LsProjectionSupport.cellsIn
import com.fromwau.klap.io.CliPath
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * Every expected value is from GNU coreutils 9.12 `ls`, run inside a throwaway directory holding the files a and b
 * (and where a line names them, files of known sizes and times, a directory sub and a dotfile) with LS_COLORS,
 * TIME_STYLE, QUOTING_STYLE, COLUMNS, TABSIZE, BLOCK_SIZE, LS_BLOCK_SIZE and POSIXLY_CORRECT unset: a `because` quotes
 * what it printed. How a line binds is read off what ls lists: the layout, the order, the indicators. The cells that
 * depend on the environment say so; POSIXLY_CORRECT would stop ls reading an option after an operand.
 */

private fun cell(
    argv: List<String>,
    outcome: Outcome,
    because: String,
) = Cell("line", "whole line", argv, outcome, because)

private fun binds(
    vararg argv: String,
    because: String,
    expected: LsInputs,
) = cell(argv.toList(), Outcome.Binds(expected), because)

private fun same(
    line: List<String>,
    sameAs: List<String>,
    because: String,
) = cell(line, Outcome.SameAs(sameAs), because)

private fun refused(
    vararg argv: String,
    because: String,
) = cell(argv.toList(), Outcome.Rejects, because)

private fun files(vararg names: String): LsInputs = NOTHING_BOUND.copy(files = names.map(::CliPath))

/** `ls a` with [time] and the [sort] ls reads. */
private fun timed(
    time: TimeWord,
    sort: Sort,
): LsInputs = files("a").copy(time = time, sort = sort)

private fun spellingsOf(inputs: List<Input>): List<String> =
    inputs.flatMap { listOfNotNull(it.long) + it.shorts.map { short -> "-$short" } }

/** The spellings of every flag of ls. */
private val FLAG_KEYS: List<String> = spellingsOf(ALL_INPUTS.filter { it.kind == Kind.Flag })

/** The spellings of every value input of ls. */
private val VALUE_KEYS: List<String> = spellingsOf(ALL_INPUTS.filter { it.kind is Kind.Value })

/** The value inputs whose value is one of ls's words, checked as ls checks it. */
private val CHOICE_KEYS: List<String> =
    ALL_INPUTS.filter { (it.kind as? Kind.Value)?.let { kind -> kind.groups != null && kind.checked } == true }
        .mapNotNull { it.long }

/** The cells a test of their own checks: a repeated `--classify` whose later WHEN resolves to no. */
private val CLASSIFY_REPEAT: (Cell) -> Boolean = { it.key == "--classify" && it.form == REPEATED }

/**
 * The record an ls line binds in the fixture, or its refusal, as real ls 9.12 reads the line (P of the testing
 * matrix): every input in every spelling and position, values at the edges of their word, repeats and overrides, the
 * operands, and help, version and klap's built-ins on a line.
 */
class LsProjectionTest {

    private val parity = ParitySuite(lsCli())

    @Test
    fun `every flag binds by its long name and each short alone doubled and clustered`() =
        parity.checkAll(cells(FLAG_KEYS, LONG, LONG_REPEATED, SHORT, SHORT_REPEATED, CLUSTER))

    @Test
    fun `every long binds by its shortest abbreviation among ls's longs`() =
        parity.checkAll(cells(FLAG_KEYS + VALUE_KEYS, ABBREVIATED))

    @Test
    fun `a flag refuses a value attached to its long name`() = parity.checkAll(cells(FLAG_KEYS, LONG_ATTACHED))

    @Test
    fun `every input binds the same record before between and after the operands`() =
        parity.checkAll(cells(FLAG_KEYS + VALUE_KEYS, BETWEEN, AFTER))

    @Test
    fun `every spelling after the end of options is an operand`() = parity.checkAll(cellsIn(AFTER_END))

    @Test
    fun `every value option binds its value attached and in its own word`() =
        parity.checkAll(cells(VALUE_KEYS, ATTACHED, SPACED))

    @Test
    fun `a value-taking short binds its value glued spaced and after a cluster`() =
        parity.checkAll(cells(VALUE_KEYS, GLUED, SHORT_SPACED, CLUSTER_GLUED, CLUSTER_SPACED, SHORT_EQUALS))

    @Test
    fun `a required value left out refuses the line`() = parity.checkAll(cells(VALUE_KEYS, MISSING_VALUE))

    @Test
    fun `an optional value left out binds always and leaves the next word an operand`() =
        parity.checkAll(cells(VALUE_KEYS, BARE, BARE_THEN_WORD))

    @Test
    fun `a value in capitals one ls does not know an empty one and an ambiguous prefix are refused`() =
        parity.checkAll(cells(CHOICE_KEYS, CHOICE_CAPITALS, CHOICE_UNKNOWN, CHOICE_EMPTY, CHOICE_AMBIGUOUS))

    @Test
    fun `a unique prefix of a value binds that value`() = parity.checkAll(cells(CHOICE_KEYS, CHOICE_ABBREVIATED))

    @Test
    fun `a value given twice binds as ls reads the repeat`() =
        parity.checkAll(cells(VALUE_KEYS, REPEATED).filterNot(CLASSIFY_REPEAT))

    @Test
    fun `every prefix ls calls ambiguous is refused`() = parity.checkAll(AMBIGUOUS_PREFIXES)

    @Test
    fun `every generated cell is checked by one test`() {
        val forms = listOf(
            LONG, LONG_REPEATED, SHORT, SHORT_REPEATED, CLUSTER, ABBREVIATED, LONG_ATTACHED, BETWEEN, AFTER, AFTER_END,
            ATTACHED, SPACED, GLUED, SHORT_SPACED, CLUSTER_GLUED, CLUSTER_SPACED, SHORT_EQUALS, MISSING_VALUE, BARE,
            BARE_THEN_WORD, CHOICE_CAPITALS, CHOICE_UNKNOWN, CHOICE_EMPTY, CHOICE_AMBIGUOUS, CHOICE_ABBREVIATED,
            SYNONYM, SYNONYM_ABBREVIATED, REPEATED,
        )
        val unchecked = ALL.filter { it.form !in forms }
        assertTrue(unchecked.isEmpty(), "cells no test checks: $unchecked")
    }

    @Test
    fun `the sort shorts and --sort are one setting the last given wins`() = parity.checkAll(
        // `ls -m -S -t` lists by time, `-t -S` and `-tS` by size, `-St` by time; `-S --sort=time` by time and
        // `--sort=time -S` by size; `-U -S` by size and `-S -U` unsorted; `--sort=size --sort=time` by time.
        listOf(
            binds("-S", "-t", "a", because = "by time", expected = files("a").copy(sort = Sort.Time)),
            binds("-t", "-S", "a", because = "by size", expected = files("a").copy(sort = Sort.Size)),
            binds("-tS", "a", because = "by size", expected = files("a").copy(sort = Sort.Size)),
            binds("-St", "a", because = "by time", expected = files("a").copy(sort = Sort.Time)),
            binds("-S", "--sort=time", "a", because = "by time", expected = files("a").copy(sort = Sort.Time)),
            binds("--sort=time", "-S", "a", because = "by size", expected = files("a").copy(sort = Sort.Size)),
            binds("-U", "-S", "a", because = "by size", expected = files("a").copy(sort = Sort.Size)),
            binds("-S", "-U", "a", because = "unsorted", expected = files("a").copy(sort = Sort.None)),
            binds(
                "-t", "--sort=extension", "a",
                because = "by extension",
                expected = files("a").copy(sort = Sort.Extension),
            ),
            binds(
                "-mX", "-U", "a",
                because = "unsorted",
                expected = files("a").copy(layout = Layout.Commas, sort = Sort.None),
            ),
            // `ls -m -S -v f9 f10 y` lists f9, f10, y and `-v -S` y, f10, f9: -v is one of the sorts.
            binds("-S", "-v", "a", because = "by version", expected = files("a").copy(sort = Sort.Version)),
            binds("-v", "-S", "a", because = "by size", expected = files("a").copy(sort = Sort.Size)),
            // `ls -m -f -S` lists by size and `-S -f` in the order given: -f is -a -U.
            binds(
                "-f", "-S", "a",
                because = "by size",
                expected = files("a").copy(hidden = Hidden.All, sort = Sort.Size),
            ),
            binds(
                "-S", "-f", "a",
                because = "unsorted",
                expected = files("a").copy(hidden = Hidden.All, sort = Sort.None),
            ),
        ),
    )

    @Test
    fun `the operands bind as ls reads them`() = parity.checkAll(
        listOf(
            // `ls` with no operand lists the current directory: the list may be empty.
            binds(because = "ls lists .", expected = NOTHING_BOUND),
            binds("a", because = "ls a lists a", expected = files("a")),
            binds("b", "a", because = "ls b a lists both", expected = files("b", "a")),
            binds("--", because = "ls -- lists .", expected = NOTHING_BOUND),
            binds("--", "-a", because = "ls -- -a: cannot access '-a'", expected = files("-a")),
            binds("--", "--", because = "ls -- --: cannot access '--'", expected = files("--")),
            binds("--", "--", "a", because = "ls -- -- a: cannot access '--'", expected = files("--", "a")),
            binds("--", "--help", because = "ls -- --help: cannot access '--help'", expected = files("--help")),
            binds("--", "-5", because = "getopt: an operand after --", expected = files("-5")),
            binds("-", because = "ls -: cannot access '-'", expected = files("-")),
            binds("a", "--", "-l", because = "ls a -- -l: cannot access 'a'", expected = files("a", "-l")),
            binds(
                "-la", "--",
                because = "ls -la -- lists . long",
                expected = NOTHING_BOUND.copy(hidden = Hidden.All, layout = Layout.Long),
            ),
            binds("", because = "ls \"\": cannot access ''", expected = files("")),
            binds("a b", because = "ls reads one word", expected = files("a b")),
            // `~/x` and `ü*` were not run, as the oracle's safety rule forbids them; ls gets the word as the shell
            // left it.
            binds("ü*", "~/x", because = "ls reads the word", expected = files("ü*", "~/x")),
            binds(
                "completion", "a",
                because = "ls completion a: cannot access 'completion'",
                expected = files("completion", "a"),
            ),
            binds("docs", "a", because = "ls docs a: cannot access 'docs'", expected = files("docs", "a")),
        ),
    )

    @Test
    fun `a dash-led number is refused`() = parity.checkAll(
        // P59, K29: `ls -5` is "invalid option -- '5'", `ls -100` "invalid option -- '0'"... with -1 its one digit.
        listOf(
            refused("-5", because = "ls: invalid option -- '5'"),
            refused("-0", because = "ls: invalid option -- '0'"),
            refused("-15", because = "ls: invalid option -- '5'"),
            refused("-100", because = "ls: invalid option -- '0'"),
        ),
    )

    @Test
    fun `an unknown option or letter is refused`() = parity.checkAll(
        listOf(
            refused("--zzz", "a", because = "ls: unrecognized option '--zzz'"),
            refused("--colour", "a", because = "ls: unrecognized option '--colour'"),
            refused("--help-all", because = "ls: unrecognized option '--help-all'"),
            refused("---x", because = "ls: unrecognized option '---x'"),
            refused("-e", "a", because = "ls: invalid option -- 'e'"),
            refused("-le", "a", because = "ls: invalid option -- 'e'"),
            refused("-el", "a", because = "ls: invalid option -- 'e'"),
            refused("-l=x", "a", because = "ls: invalid option -- '='"),
            refused("-F=never", "a", because = "ls: invalid option -- '='"),
            refused("-Fnever", "a", because = "ls: invalid option -- 'e'"),
            refused("--=x", "a", because = "ls: option '--=x' is ambiguous"),
        ),
    )

    @Test
    fun `help and version answer wherever they sit on a valid line`() {
        parity.showsHelp("--help", because = "ls --help prints its usage")
        parity.showsHelp("--help", "a", because = "ls --help a prints its usage")
        parity.showsHelp("a", "--help", because = "ls a --help prints its usage")
        parity.showsHelp("-l", "--help", because = "ls -l --help prints its usage")
        parity.showsHelp("--he", because = "ls --he prints its usage")
        parity.showsHelp("--hel", because = "ls --hel prints its usage")
        parity.showsVersion("--version", because = "ls --version prints its banner")
        parity.showsVersion("--v", because = "ls --v prints its banner")
        parity.showsVersion("--vers", because = "ls --vers prints its banner")
        parity.showsVersion("--version", "a", because = "ls --version a prints its banner")
    }

    @Test
    fun `the first of help and version answers`() {
        parity.showsHelp("--help", "--version", because = "ls --help --version prints its usage")
        parity.showsVersion("--version", "--help", because = "ls --version --help prints its banner")
    }

    @Test
    fun `help or version written before a refused word answers`() {
        parity.showsHelp("--help", "--bogus", because = "ls --help --bogus prints its usage")
        parity.showsHelp("--help", "-5", because = "ls --help -5 prints its usage")
        parity.showsHelp("--help", "--sort=bogus", because = "ls --help --sort=bogus prints its usage")
        parity.showsHelp("--help", "-w", "abc", because = "ls --help -w abc prints its usage")
        parity.showsVersion("--version", "--bogus", because = "ls --version --bogus prints its banner")
    }

    @Test
    fun `a refused word written before help refuses the line`() = parity.checkAll(
        listOf(
            refused("--bogus", "--help", because = "ls: unrecognized option '--bogus'"),
            refused("-5", "--help", because = "ls: invalid option -- '5'"),
            refused("--sort=bogus", "--help", because = "ls: invalid argument 'bogus' for '--sort'"),
            refused("-w", "abc", "--help", because = "ls: invalid line width: 'abc'"),
            refused("--bogus", "--version", because = "ls: unrecognized option '--bogus'"),
            refused("--h", because = "ls: option '--h' is ambiguous; possibilities: '--human-readable' ... '--help'"),
        ),
    )

    @Test
    fun `an abbreviation of a klap built-in is refused`() = parity.checkAll(
        // `ls --do a` and `ls --com a`: "unrecognized option".
        listOf(
            refused("--do", "a", because = "ls: unrecognized option '--do'"),
            refused("--com", "a", because = "ls: unrecognized option '--com'"),
        ),
    )

    @Test
    fun `-F and --classify both bind and never turns no indicator off`() = parity.checkAll(
        // `ls -d -F sub`, `ls -d -F --classify=never sub` and `ls -d --classify=never -F sub` print sub/.
        listOf(
            binds("-F", "a", because = "prints a", expected = files("a").copy(indicator = IndicatorStyle.Classify)),
            binds(
                "-F", "--classify=never", "a",
                because = "sub/",
                expected = files("a").copy(indicator = IndicatorStyle.Classify),
            ),
            binds(
                "--classify=never", "-F", "a",
                because = "sub/",
                expected = files("a").copy(indicator = IndicatorStyle.Classify),
            ),
            binds(
                "-FZ", "a",
                because = "-F takes no value",
                expected = files("a").copy(indicator = IndicatorStyle.Classify, context = true),
            ),
            binds(
                "-Fla",
                because = "-F takes no value",
                expected = NOTHING_BOUND.copy(
                    indicator = IndicatorStyle.Classify,
                    layout = Layout.Long,
                    hidden = Hidden.All,
                ),
            ),
        ),
    )

    @Test
    fun `--ignore and --hide collect every pattern in line order`() = parity.checkAll(
        // `ls -m -I a.y -Ib.z --ignore c.x --hide=big --ignore=sub` lists only -l: every pattern took.
        listOf(
            binds(
                "-I", "a", "-Ib", "--ignore", "c", "--hide=d", "--ignore=e",
                because = "every pattern took",
                expected = NOTHING_BOUND.copy(ignore = listOf("a", "b", "c", "e"), hide = listOf("d")),
            ),
            binds(
                "-I", "-l",
                because = "`ls -m -I -l` hides the file -l",
                expected = NOTHING_BOUND.copy(ignore = listOf("-l")),
            ),
            binds(
                "--hide", "-l",
                because = "`ls -m --hide -l` hides -l",
                expected = NOTHING_BOUND.copy(hide = listOf("-l")),
            ),
            binds(
                "-I", "--", "a",
                because = "`ls -m -I -- a.y` lists a.y",
                expected = files("a").copy(ignore = listOf("--")),
            ),
            binds(
                "--hide=",
                because = "`ls -m --hide=` lists everything",
                expected = NOTHING_BOUND.copy(hide = listOf("")),
            ),
            binds("--hide=--", because = "lists everything", expected = NOTHING_BOUND.copy(hide = listOf("--"))),
        ),
    )

    @Test
    fun `a value word that spells an option or a built-in is the value`() = parity.checkAll(
        // P32, P39, P43: `ls -m -I --help` hides the file --help, `ls -m --hide --json` lists everything, `ls -m -Ila`
        // hides la and lists nothing long; `ls -w --help a`: "invalid line width: '--help'", `ls --sort --help`:
        // "invalid argument '--help' for '--sort'".
        listOf(
            binds("-I", "--help", because = "hides --help", expected = NOTHING_BOUND.copy(ignore = listOf("--help"))),
            binds(
                "--hide", "--json",
                because = "lists everything",
                expected = NOTHING_BOUND.copy(hide = listOf("--json")),
            ),
            binds("-Ila", because = "hides la", expected = NOTHING_BOUND.copy(ignore = listOf("la"))),
            binds(
                "-rIla",
                because = "hides la, reversed",
                expected = NOTHING_BOUND.copy(reverse = true, ignore = listOf("la")),
            ),
            binds("--hide=a=b", because = "lists everything", expected = NOTHING_BOUND.copy(hide = listOf("a=b"))),
            refused("-w", "--help", "a", because = "ls: invalid line width: '--help'"),
            refused("--sort", "--help", because = "ls: invalid argument '--help' for '--sort'"),
            refused("--sort", "--", "a", because = "ls: invalid argument '--' for '--sort'"),
            refused("--format", "--", "a", because = "ls: invalid argument '--' for '--format'"),
            refused("-w", "--", "a", because = "ls: invalid line width: '--'"),
            refused("-T", "--", "a", because = "ls: invalid tab size: '--'"),
        ),
    )

    @Test
    fun `a bare --color after a value is always and a value after a bare one is that value`() = parity.checkAll(
        // P76: `ls -d --color=never --color sub` colours sub, `ls -d --color --color=never sub` does not.
        listOf(
            binds(
                "--color=never", "--color", "a",
                because = "coloured",
                expected = files("a").copy(color = When.Always),
            ),
            binds("--color", "--color=never", "a", because = "plain", expected = files("a").copy(color = When.Never)),
            binds(
                "--color=always", "--color=never", "a",
                because = "plain",
                expected = files("a").copy(color = When.Never),
            ),
        ),
    )

    @Test
    fun `a line naming no width tab size or colour binds none of them`() = parity.checkAll(
        // P6d: ls takes the width from COLUMNS, the tab size from TABSIZE and the colours from LS_COLORS when the line
        // names none (`COLUMNS=17 ls -x` lists three per line, `COLUMNS=17 ls -x -w 18` four), so the record claims
        // none of them for a line without them.
        listOf(binds("-x", "a", because = "ls -x a", expected = files("a").copy(layout = Layout.Across))),
    )

    @Test
    fun `every input real ls has is accepted`() =
        parity.checkAll(cells(spellingsOf(ALL_INPUTS), LONG, SHORT, ATTACHED, BARE))

    @Test
    fun `a prefix --directory --inode --format or --ignore shares with another long is refused`() = parity.checkAll(
        // `ls --di a`: "option '--di' is ambiguous; possibilities: '--directory' '--dired'"; `--in`: '--inode'
        // '--indicator-style'; `--f`: '--full-time' '--file-type' '--format'; `--ig`: '--ignore-backups' '--ignore'.
        listOf(
            refused("--di", "a", because = "ls: option '--di' is ambiguous"),
            refused("--in", "a", because = "ls: option '--in' is ambiguous"),
            refused("--f", "a", because = "ls: option '--f' is ambiguous"),
            refused("--ig", "a", because = "ls: option '--ig' is ambiguous"),
        ),
    )

    @Test
    fun `every word ls reads for one value binds that value`() =
        parity.checkAll(cells(CHOICE_KEYS, SYNONYM, SYNONYM_ABBREVIATED))

    @Test
    fun `a --classify WHEN that resolves to no leaves the indicator style as it was`() = parity.checkAll(
        // `ls -d --classify --classify=never sub`, `ls -d --classify=always --classify=auto sub`, `ls -d -p
        // --classify=never sub` and `ls -d -p --classify=auto sub` print sub/; `ls -d --classify=auto sub` and `ls -d
        // -F --indicator-style=none sub` print sub, off a terminal; `ls --file-type -p lnk` prints lnk, where `ls -p
        // --file-type lnk` prints lnk@ for a symbolic link.
        cells(listOf("--classify"), REPEATED) + listOf(
            binds(
                "--classify", "--classify=never", "a",
                because = "sub/",
                expected = files("a").copy(indicator = IndicatorStyle.Classify),
            ),
            binds(
                "--classify=always", "--classify=auto", "a",
                because = "sub/",
                expected = files("a").copy(indicator = IndicatorStyle.Classify),
            ),
            binds(
                "-p", "--classify=never", "a",
                because = "sub/",
                expected = files("a").copy(indicator = IndicatorStyle.Slash),
            ),
            binds(
                "-p", "--classify=auto", "a",
                because = "sub/",
                expected = files("a").copy(indicator = IndicatorStyle.Slash),
            ),
            binds("--classify=auto", "a", because = "sub", expected = files("a")),
            binds("-F", "--indicator-style=none", "a", because = "sub", expected = files("a")),
            binds(
                "--indicator-style=none", "--classify", "a",
                because = "sub/",
                expected = files("a").copy(indicator = IndicatorStyle.Classify),
            ),
            binds(
                "--file-type", "-p", "a",
                because = "lnk",
                expected = files("a").copy(indicator = IndicatorStyle.Slash),
            ),
        ),
    )

    @Test
    fun `--classify=auto appends indicators on a terminal and --color=auto stays auto`() =
        ParitySuite(lsCli(terminal = true)).checkAll(
            // `ls --classify=auto` appends indicators only where standard output is a terminal; `--color=auto` is
            // decided when ls lists, so the record keeps it as given.
            listOf(
                binds(
                    "--classify=auto", "a",
                    because = "on a terminal",
                    expected = files("a").copy(indicator = IndicatorStyle.Classify),
                ),
                binds(
                    "-p", "--classify=tty", "a",
                    because = "on a terminal",
                    expected = files("a").copy(indicator = IndicatorStyle.Classify),
                ),
                binds("--color=auto", "a", because = "on a terminal", expected = files("a").copy(color = When.Auto)),
            ),
        )

    @Test
    fun `the help page lists ls's options and nothing ls refuses`() {
        // `ls --help` lists every long of LS_LONGS; `ls --json a`, `--completion` and `--docs`: "unrecognized option".
        val page = parity.helpPage("--help", because = "ls --help")
        val missing = LS_LONGS.filterNot { Regex("${Regex.escape(it)}(?![\\w-])").containsMatchIn(page) }
        val foreign = KLAP_ONLY_LONGS.filter { Regex("${Regex.escape(it)}(?![\\w-])").containsMatchIn(page) }
        assertTrue(missing.isEmpty() && foreign.isEmpty(), "ls --help page: missing $missing, foreign $foreign\n$page")
    }

    @Test
    fun `the layout shorts and --format are one setting the last given wins`() = parity.checkAll(
        // `ls -l -C a b` lists in columns, `-C -l` long; `--format=long -C` columns, `-C --format=long` long; `-lx`
        // across, `-xl` long; `-l -m` commas, `-m -l` long; `-x -1` one per line, `-1 -x` across; `-m -1` one per
        // line, `-1 -m` commas; `-l -1`, `-1 -l`, `-l1`, `-1l` and `--format=long -1` long; `-l --format=single-column`
        // and `-l -C -1` one per line.
        listOf(
            binds("-l", "-C", because = "columns", expected = NOTHING_BOUND.copy(layout = Layout.Vertical)),
            binds("-C", "-l", because = "long", expected = NOTHING_BOUND.copy(layout = Layout.Long)),
            binds("--format=long", "-C", because = "columns", expected = NOTHING_BOUND.copy(layout = Layout.Vertical)),
            binds("-C", "--format=verbose", because = "long", expected = NOTHING_BOUND.copy(layout = Layout.Long)),
            binds("-lx", because = "across", expected = NOTHING_BOUND.copy(layout = Layout.Across)),
            binds("-xl", because = "long", expected = NOTHING_BOUND.copy(layout = Layout.Long)),
            binds("-l", "-m", because = "commas", expected = NOTHING_BOUND.copy(layout = Layout.Commas)),
            binds("-m", "-l", because = "long", expected = NOTHING_BOUND.copy(layout = Layout.Long)),
            binds("-x", "-1", because = "one per line", expected = NOTHING_BOUND.copy(layout = Layout.SingleColumn)),
            binds("-1", "-x", because = "across", expected = NOTHING_BOUND.copy(layout = Layout.Across)),
            binds("-m", "-1", because = "one per line", expected = NOTHING_BOUND.copy(layout = Layout.SingleColumn)),
            binds("-1", "-m", because = "commas", expected = NOTHING_BOUND.copy(layout = Layout.Commas)),
            binds("-l", "-1", because = "long", expected = NOTHING_BOUND.copy(layout = Layout.Long)),
            binds("-1", "-l", because = "long", expected = NOTHING_BOUND.copy(layout = Layout.Long)),
            binds("-l1", because = "long", expected = NOTHING_BOUND.copy(layout = Layout.Long)),
            binds("-1l", because = "long", expected = NOTHING_BOUND.copy(layout = Layout.Long)),
            binds("--format=verbose", "-1", because = "long", expected = NOTHING_BOUND.copy(layout = Layout.Long)),
            binds(
                "-l", "--format=single-column",
                because = "one per line",
                expected = NOTHING_BOUND.copy(layout = Layout.SingleColumn),
            ),
            binds(
                "-l", "-C", "-1",
                because = "one per line",
                expected = NOTHING_BOUND.copy(layout = Layout.SingleColumn),
            ),
            binds(
                "-mxrU", "a",
                because = "across",
                expected = files("a").copy(layout = Layout.Across, reverse = true, sort = Sort.None),
            ),
            // `ls -C --zero x y z` ends each name with NUL one per line, `--zero -C` lists in columns, and `-l --zero`
            // long: --zero sets the layout as -1 does.
            binds(
                "-C", "--zero",
                because = "one per line",
                expected = NOTHING_BOUND.copy(layout = Layout.SingleColumn, zero = true),
            ),
            binds(
                "--zero", "-C",
                because = "columns",
                expected = NOTHING_BOUND.copy(layout = Layout.Vertical, zero = true),
            ),
            binds("-l", "--zero", because = "long", expected = NOTHING_BOUND.copy(layout = Layout.Long, zero = true)),
            // `ls --help`: -g "like -l, but do not list owner"; `ls -g -C` lists in columns and `ls -C -g` long.
            binds(
                "-g", "-C",
                because = "columns",
                expected = NOTHING_BOUND.copy(layout = Layout.Vertical, omitOwner = true),
            ),
            binds("-C", "-o", because = "long", expected = NOTHING_BOUND.copy(layout = Layout.Long, omitGroup = true)),
            binds(
                "-n", "-1",
                because = "long",
                expected = NOTHING_BOUND.copy(layout = Layout.Long, numericUidGid = true),
            ),
            binds(
                "-x", "--full-time",
                because = "long",
                expected = NOTHING_BOUND.copy(layout = Layout.Long, fullTime = true),
            ),
        ),
    )

    @Test
    fun `-c -u and --time are one timestamp that sorts only where nothing else sorts and the listing is not long`() =
        parity.checkAll(
            // Over x, y and z whose mtime, atime, ctime and name orders differ: `ls -1 -c` lists by ctime, `-c
            // --time=atime` by atime and `--time=atime -c` by ctime; `-c -S`, `-S -c`, `-c --sort=size` and
            // `--sort=size -c` by size; `-c --sort=name` by name, `-c -U` and `-f -c` in the order given; `-1
            // --time=mtime` by mtime. `ls -l -c` and `-c -l` show ctime sorted by name, `-l -c -t` by ctime, and `-l -c
            // -C` and `-l -c -m` drop the long format and sort by ctime again; `-c -g` sorts by name.
            listOf(
                binds("-c", "a", because = "by ctime", expected = timed(TimeWord.Ctime, Sort.Time)),
                binds("-u", "a", because = "by atime", expected = timed(TimeWord.Atime, Sort.Time)),
                binds("--time=status", "a", because = "by ctime", expected = timed(TimeWord.Ctime, Sort.Time)),
                binds("-c", "--time=atime", "a", because = "by atime", expected = timed(TimeWord.Atime, Sort.Time)),
                binds("--time=atime", "-c", "a", because = "by ctime", expected = timed(TimeWord.Ctime, Sort.Time)),
                binds("-u", "-c", "a", because = "by ctime", expected = timed(TimeWord.Ctime, Sort.Time)),
                binds("-c", "-u", "a", because = "by atime", expected = timed(TimeWord.Atime, Sort.Time)),
                binds("--time=mtime", "a", because = "by mtime", expected = timed(TimeWord.Mtime, Sort.Time)),
                binds("-c", "-S", "a", because = "by size", expected = timed(TimeWord.Ctime, Sort.Size)),
                binds("-S", "-c", "a", because = "by size", expected = timed(TimeWord.Ctime, Sort.Size)),
                binds("--sort=size", "-c", "a", because = "by size", expected = timed(TimeWord.Ctime, Sort.Size)),
                binds("-c", "--sort=name", "a", because = "by name", expected = timed(TimeWord.Ctime, Sort.Name)),
                binds("-c", "-U", "a", because = "as given", expected = timed(TimeWord.Ctime, Sort.None)),
                binds(
                    "-f", "-c", "a",
                    because = "as given",
                    expected = timed(TimeWord.Ctime, Sort.None).copy(hidden = Hidden.All),
                ),
                binds(
                    "-l", "-c", "a",
                    because = "ctime by name",
                    expected = timed(TimeWord.Ctime, Sort.Name).copy(layout = Layout.Long),
                ),
                binds(
                    "-c", "-l", "a",
                    because = "ctime by name",
                    expected = timed(TimeWord.Ctime, Sort.Name).copy(layout = Layout.Long),
                ),
                binds(
                    "-l", "-c", "-t", "a",
                    because = "by ctime",
                    expected = timed(TimeWord.Ctime, Sort.Time).copy(layout = Layout.Long),
                ),
                binds(
                    "-l", "-c", "-C", "a",
                    because = "by ctime",
                    expected = timed(TimeWord.Ctime, Sort.Time).copy(layout = Layout.Vertical),
                ),
                binds(
                    "-l", "-c", "-m", "a",
                    because = "by ctime",
                    expected = timed(TimeWord.Ctime, Sort.Time).copy(layout = Layout.Commas),
                ),
                binds(
                    "-c", "-g", "a",
                    because = "by name",
                    expected = timed(TimeWord.Ctime, Sort.Name).copy(layout = Layout.Long, omitOwner = true),
                ),
            ),
        )

    @Test
    fun `-a -A and -f are one setting the last given wins`() = parity.checkAll(
        // `ls -m -a -A` and `-aA` hide . and .., `ls -m -A -a` and `-Aa` show them; `ls -m -A -f .` shows them and
        // `-f -A .` hides them.
        listOf(
            binds("-a", "-A", because = "no . or ..", expected = NOTHING_BOUND.copy(hidden = Hidden.AlmostAll)),
            binds("-A", "-a", because = ". and ..", expected = NOTHING_BOUND.copy(hidden = Hidden.All)),
            binds("-aA", because = "no . or ..", expected = NOTHING_BOUND.copy(hidden = Hidden.AlmostAll)),
            binds("-Aa", because = ". and ..", expected = NOTHING_BOUND.copy(hidden = Hidden.All)),
            binds(
                "--all", "--almost-all",
                because = "no . or ..",
                expected = NOTHING_BOUND.copy(hidden = Hidden.AlmostAll),
            ),
            binds(
                "-A", "-f",
                because = ". and ..",
                expected = NOTHING_BOUND.copy(hidden = Hidden.All, sort = Sort.None),
            ),
            binds(
                "-f", "-A",
                because = "no . or ..",
                expected = NOTHING_BOUND.copy(hidden = Hidden.AlmostAll, sort = Sort.None),
            ),
        ),
    )

    @Test
    fun `-h --si and --block-size are one setting the last given wins`() = parity.checkAll(
        // `ls -s -h --si big` prints 4.1k, `ls -s --si -h big` 4.0K; `ls -l -h --si big` 2.0k, `-l --si -h` 2.0K;
        // `ls -s -h --block-size=1 big` prints 5001216 and `--block-size=1 -h` 4.8M; `ls -s -h -k big` and `-k -h`
        // both print 4.8M, `-k --block-size=1` and `--block-size=1 -k` 5001216: -k changes none of them in either
        // order.
        listOf(
            binds("-h", "--si", "a", because = "4.1k", expected = files("a").copy(units = SizeUnits.Si)),
            binds("--si", "-h", "a", because = "4.0K", expected = files("a").copy(units = SizeUnits.Human)),
            binds("--human-readable", "--si", "a", because = "4.1k", expected = files("a").copy(units = SizeUnits.Si)),
            binds(
                "-h", "--block-size=1", "a",
                because = "5001216",
                expected = files("a").copy(units = SizeUnits.Block("1")),
            ),
            binds("--block-size=1", "-h", "a", because = "4.8M", expected = files("a").copy(units = SizeUnits.Human)),
            binds(
                "-h", "-k", "a",
                because = "4.8M",
                expected = files("a").copy(units = SizeUnits.Human, kibibytes = true),
            ),
            binds(
                "-k", "-h", "a",
                because = "4.8M",
                expected = files("a").copy(units = SizeUnits.Human, kibibytes = true),
            ),
        ),
    )

    @Test
    fun `an option ls does not have is refused`() = parity.checkAll(
        // `ls --json a`, `ls --completion bash`, `ls --docs markdown`, `ls --j a`, `ls --do a`, `ls --com a`:
        // "unrecognized option".
        listOf(
            refused("--json", "a", because = "ls: unrecognized option '--json'"),
            refused("--json", because = "ls: unrecognized option '--json'"),
            refused("--completion", "bash", because = "ls: unrecognized option '--completion'"),
            refused("--docs", "markdown", because = "ls: unrecognized option '--docs'"),
            refused("--j", "a", because = "ls: unrecognized option '--j'"),
        ),
    )

    @Test
    fun `__complete is a file name`() = parity.checkAll(
        // `ls __complete a`: "cannot access '__complete'", an operand like any other.
        listOf(binds("__complete", "a", because = "cannot access '__complete'", expected = files("__complete", "a"))),
    )

    @Test
    fun `each spelling of one value binds one record`() = parity.checkAll(
        // `ls --help`: "long (-l)", "commas (-m)", "across,horizontal (-x)", "vertical (-C)", "single-column (-1)",
        // "none (-U)", "size (-S)", "time (-t)", "extension (-X)"; `ls -m --sort=none` and `ls -m -U` list alike.
        listOf(
            same(listOf("-l"), listOf("--format=long"), "ls --help: long (-l)"),
            same(listOf("-m"), listOf("--format=commas"), "ls --help: commas (-m)"),
            same(listOf("-x"), listOf("--format=across"), "ls --help: across,horizontal (-x)"),
            same(listOf("-C"), listOf("--format=vertical"), "ls --help: vertical (-C)"),
            same(listOf("-1"), listOf("--format=single-column"), "ls --help: single-column (-1)"),
            same(listOf("-U"), listOf("--sort=none"), "ls --help: none (-U)"),
            same(listOf("-S"), listOf("--sort=size"), "ls --help: size (-S)"),
            same(listOf("-t"), listOf("--sort=time"), "ls --help: time (-t)"),
            same(listOf("-X"), listOf("--sort=extension"), "ls --help: extension (-X)"),
            same(listOf("-F"), listOf("--classify"), "ls --help: -F, --classify[=WHEN]"),
        ),
    )
}
