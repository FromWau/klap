package com.fromwau.klap.fixture.find

import com.fromwau.kern.terminal.test.RecordingTerminal
import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.find.FindProjectionSupport.ABBREVIATED
import com.fromwau.klap.fixture.find.FindProjectionSupport.AFTER
import com.fromwau.klap.fixture.find.FindProjectionSupport.AFTER_END
import com.fromwau.klap.fixture.find.FindProjectionSupport.ALL
import com.fromwau.klap.fixture.find.FindProjectionSupport.ALONE
import com.fromwau.klap.fixture.find.FindProjectionSupport.ATTACHED
import com.fromwau.klap.fixture.find.FindProjectionSupport.BAD
import com.fromwau.klap.fixture.find.FindProjectionSupport.BOTH_APPLY
import com.fromwau.klap.fixture.find.FindProjectionSupport.BUILTIN_VALUE
import com.fromwau.klap.fixture.find.FindProjectionSupport.Cell
import com.fromwau.klap.fixture.find.FindProjectionSupport.DASH_VALUE
import com.fromwau.klap.fixture.find.FindProjectionSupport.EMPTY_REFUSED
import com.fromwau.klap.fixture.find.FindProjectionSupport.EMPTY_VALUE
import com.fromwau.klap.fixture.find.FindProjectionSupport.END_VALUE
import com.fromwau.klap.fixture.find.FindProjectionSupport.EQUALS_VALUE
import com.fromwau.klap.fixture.find.FindProjectionSupport.LONG
import com.fromwau.klap.fixture.find.FindProjectionSupport.LONG_ATTACHED
import com.fromwau.klap.fixture.find.FindProjectionSupport.LONG_REPEATED
import com.fromwau.klap.fixture.find.FindProjectionSupport.MISSING
import com.fromwau.klap.fixture.find.FindProjectionSupport.REPEATED
import com.fromwau.klap.fixture.find.FindProjectionSupport.SPACED
import com.fromwau.klap.fixture.find.FindProjectionSupport.checkAll
import com.fromwau.klap.fixture.find.FindProjectionSupport.checkDiffer
import com.fromwau.klap.fixture.find.FindProjectionSupport.cells
import com.fromwau.klap.io.CliPath
import com.fromwau.klap.run
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Every expected value is from GNU findutils 4.11.0 `/usr/bin/find`, run inside a throwaway directory holding an
 * empty file b and a directory a, with the predicate spelled as find spells it (`-maxdepth`) where the cell spells
 * the fixture's respelling (`--maxdepth`, owner decision F1). A `because` quotes what find printed.
 */

private fun cell(
    argv: List<String>,
    expected: FindInputs?,
    because: String,
    loose: Boolean = false,
) = Cell("line", "whole line", argv, expected, because, loose)

private val HERE: FindInputs = walking(".")

/** The line walks `.` alone: an operator word is part of the expression, never a starting point. */
private fun walksHere(inputs: FindInputs) = assertEquals(listOf(CliPath(".")), inputs.startingPoints)

/**
 * The record a find line binds in the fixture, or its refusal, as real find 4.11.0 reads the line (P of the testing
 * matrix): every predicate in every spelling the respelling allows, values at the edges of their word, repeats, the
 * pre-path switches word for word, the starting points, and help, version and klap's built-ins on a line.
 */
class FindProjectionTest {

    private val parity = ParitySuite(findCli())

    @Test
    fun `every input binds after the starting point alone twice and after two starting points`() =
        parity.checkAll(cells(LONG, ALONE, LONG_REPEATED, AFTER))

    @Test
    fun `a flag refuses a value attached to its name`() = parity.checkAll(cells(LONG_ATTACHED))

    @Test
    fun `every value binds spaced and attached`() = parity.checkAll(cells(SPACED, ATTACHED))

    @Test
    fun `a value is refused when the line ends before it`() = parity.checkAll(cells(MISSING))

    @Test
    fun `a value find refuses is refused`() = parity.checkAll(cells(BAD))

    @Test
    fun `a dash-led value find takes binds as the value`() = parity.checkAll(cells(DASH_VALUE))

    @Test
    fun `a free text value takes a built-in's name as its value`() = parity.checkAll(cells(BUILTIN_VALUE))

    @Test
    fun `a free text value takes -- as its value`() = parity.checkAll(cells(END_VALUE))

    @Test
    fun `a free text value keeps an equals sign and may be empty`() =
        parity.checkAll(cells(EQUALS_VALUE, EMPTY_VALUE))

    @Test
    fun `an empty value find refuses is refused`() =
        // `find -files0-from ""`: cannot open ''; `-newer ""`: No such file; `-user ""` and `-group ""`: invalid name.
        parity.checkAll(cells(EMPTY_REFUSED))

    @Test
    fun `a value given twice binds as find reads the repeat`() = parity.checkAll(cells(REPEATED))

    @Test
    fun `a predicate after the end of options is a word of the raw expression`() =
        parity.checkAll(cells(AFTER_END))

    @Test
    fun `every cell of every input is checked by one test`() {
        val covered = setOf(
            LONG, ALONE, LONG_REPEATED, AFTER, LONG_ATTACHED, SPACED, ATTACHED, MISSING, BAD, DASH_VALUE,
            BUILTIN_VALUE, END_VALUE, EQUALS_VALUE, EMPTY_VALUE, EMPTY_REFUSED, REPEATED, AFTER_END, ABBREVIATED,
        )
        val unchecked = ALL.filter { it.form !in covered }
        assertTrue(unchecked.isEmpty(), "cells no test checks: $unchecked")
    }

    @Test
    fun `the exact name wins over a longer one it prefixes`() = parity.checkAll(
        // `find . -print`, `find . -print0` and `find . -printf %p` each run their own action.
        listOf(
            cell(listOf(".", "--print"), HERE.copy(printAction = true), "`find . -print`"),
            cell(listOf(".", "--print0"), HERE.copy(print0Action = true), "`find . -print0`"),
            cell(listOf(".", "--printf", "%p"), HERE.copy(printfAction = listOf("%p")), "`find . -printf %p`"),
            cell(listOf(".", "--name", "x"), HERE.copy(namePattern = listOf("x")), "`find . -name x`"),
        ),
    )

    @Test
    fun `the pre-path switches bind word for word and the last of -P -L and -H wins`() = parity.checkAll(
        // man find: "If more than one of -H, -L and -P is specified, each overrides the others; the last one appearing
        // on the command line takes effect". Each line below exits 0.
        listOf(
            cell(listOf("-P", "."), HERE.copy(physical = true), "`find -P .`"),
            cell(listOf("-L", "."), HERE.copy(logical = true), "`find -L .`"),
            cell(listOf("-H", "."), HERE.copy(followArgs = true), "`find -H .`"),
            cell(listOf("-L"), NOTHING_BOUND.copy(logical = true), "`find -L` walks ."),
            cell(listOf("-L", "-L", "."), HERE.copy(logical = true), "`find -L -L .`"),
            cell(listOf("-L", "-P", "."), HERE.copy(physical = true), "`find -L -P .`"),
            cell(listOf("-P", "-L", "."), HERE.copy(logical = true), "`find -P -L .`"),
            cell(listOf("-H", "-L", "."), HERE.copy(logical = true), "`find -H -L .`"),
            cell(listOf("-P", "-H", "."), HERE.copy(followArgs = true), "`find -P -H .`"),
            cell(listOf("-L", "-H", "."), HERE.copy(followArgs = true), "`find -L -H .`"),
            cell(
                listOf("-P", "-D", "tree", "-O3", "-L", "."),
                HERE.copy(logical = true, debug = listOf("tree"), optimise = 3),
                "`find -P -D tree -O3 -L .`",
            ),
            cell(listOf("-P", "--", "."), HERE.copy(physical = true), "`find -P -- .`"),
            cell(listOf("--", "."), HERE, "`find -- .` walks ."),
        ),
    )

    @Test
    fun `a cluster of the pre-path switches or a list glued to -D is refused`() = parity.checkAll(
        // find matches each pre-path switch as a whole word (`shortOptions = ShortOptions.Alone`), so a cluster is an
        // unknown predicate and `-D` takes its list in the next word only.
        listOf(
            cell(listOf("-LP", "."), null, "find: unknown predicate `-LP'"),
            cell(listOf("-PL", "."), null, "find: unknown predicate `-PL'"),
            cell(listOf("-LL", "."), null, "find: unknown predicate `-LL'"),
            cell(listOf("-Dtree", "."), null, "find: unknown predicate `-Dtree'"),
        ),
    )

    @Test
    fun `a long spelling of a pre-path switch or a spaced -O level is refused`() = parity.checkAll(
        // find has no long name for any pre-path switch, and `-O` takes its level glued only.
        listOf(
            cell(listOf("--physical", "."), null, "find: unknown predicate `--physical'"),
            cell(listOf("--logical", "."), null, "find: unknown predicate `--logical'"),
            cell(listOf("--follow-args", "."), null, "find: unknown predicate `--follow-args'"),
            cell(listOf("--debug=tree", "."), null, "find: unknown predicate `--debug=tree'"),
            cell(listOf("-O", "3", "."), null, "find: The -O option must be immediately followed by a decimal integer"),
            cell(listOf("--optimise=3", "."), null, "find: unknown predicate `--optimise=3'"),
        ),
    )

    @Test
    fun `the starting points bind in order and verbatim`() = parity.checkAll(
        // A starting point that does not exist is reported while walking, after the line was read.
        listOf(
            cell(emptyList(), NOTHING_BOUND, "`find` walks ."),
            cell(listOf("."), HERE, "`find .`"),
            cell(listOf("a", "."), walking("a", "."), "`find a .` walks a, then ."),
            // With link -> dir, `find link` prints link alone and `find link/` prints link/ and link/a.
            cell(listOf("link/"), walking("link/"), "`find link/` walks the directory link names"),
            cell(listOf(".", "."), walking(".", "."), "`find . .` walks . twice"),
            cell(listOf(""), walking(""), "find: '': No such file or directory"),
            cell(listOf("-"), walking("-"), "find: '-': No such file or directory"),
            cell(listOf("a b"), walking("a b"), "find: 'a b': No such file or directory"),
            cell(listOf("~"), walking("~"), "find: '~': No such file or directory"),
            cell(listOf("ü*"), walking("ü*"), "find: 'ü*': No such file or directory"),
            cell(
                listOf("--", "-x"),
                NOTHING_BOUND.copy(expression = listOf("-x")),
                "find: unknown predicate `-x'",
                true,
            ),
            cell(listOf("-5"), null, "find: unknown predicate `-5'"),
            cell(listOf(".", "-5"), null, "find: unknown predicate `-5'"),
            cell(listOf(".", "--zzz"), null, "find: unknown predicate `--zzz'"),
        ),
    )

    @Test
    fun `a reference file keeps the trailing slash that makes find follow a link`() = parity.checkAll(
        // With link a symlink to dir: `find dir link -samefile link` prints link and `-samefile link/` prints dir;
        // `find f -newer link` reads the link's own time and `-newer link/` the directory's.
        listOf(
            cell(listOf(".", "--samefile", "link/"), HERE.copy(samefile = listOf(CliPath("link/"))), "matches dir"),
            cell(listOf(".", "--samefile", "link"), HERE.copy(samefile = listOf(CliPath("link"))), "matches link"),
            cell(listOf(".", "--newer", "link/"), HERE.copy(newerThan = listOf(CliPath("link/"))), "dir's time"),
            cell(listOf(".", "--newer", "link"), HERE.copy(newerThan = listOf(CliPath("link"))), "the link's time"),
        ),
    )

    @Test
    fun `a lone dash is a starting point the action walks`() {
        // `find - -maxdepth 0`: "‘-’: No such file or directory", so find walks `-` and not its default `.`.
        val terminal = RecordingTerminal()
        findCli().cli.run(listOf("-"), terminal)
        val printed = terminal.writes.joinToString("") { it.text }
        assertTrue(printed.startsWith("would walk - "), "`find -` prints: $printed")
    }

    @Test
    fun `a word naming a klap built-in is a starting point`() = parity.checkAll(
        listOf(
            cell(listOf("completion"), walking("completion"), "No such file"),
            cell(listOf("docs"), walking("docs"), "find: 'docs': No such file"),
            cell(listOf(".", "completion"), walking(".", "completion"), "No such file"),
        ),
    )

    @Test
    fun `repeated tests collect in argv order each in its own field`() = parity.checkAll(
        listOf(
            cell(
                listOf(".", "--name", "a", "--iname", "b", "--name", "c", "--path", "d", "--regex", "e"),
                HERE.copy(
                    namePattern = listOf("a", "c"),
                    inamePattern = listOf("b"),
                    pathPattern = listOf("d"),
                    regexPattern = listOf("e"),
                ),
                "`find . -name a -iname b -name c -path d -regex e` exits 0",
            ),
        ),
    )

    @Test
    fun `--exec takes one word of the command per occurrence`() = parity.checkAll(
        // The fixture's respelling of `-exec COMMAND ;`: `find . -exec echo -n {} ;` runs `echo -n ./...` per file.
        listOf(
            cell(listOf(".", "--exec", "echo"), HERE.copy(execAction = listOf("echo")), "`find . -exec echo ;`"),
            cell(
                listOf(".", "--exec", "echo", "--exec", "-n", "--exec", "{}"),
                HERE.copy(execAction = listOf("echo", "-n", "{}")),
                "`find . -exec echo -n {} ;`",
            ),
            cell(
                listOf(".", "--exec", "echo", "--exec=-n"),
                HERE.copy(execAction = listOf("echo", "-n")),
                "`find . -exec echo -n ;`",
            ),
            cell(listOf(".", "--exec"), null, "find: missing argument to `-exec'"),
        ),
    )

    @Test
    fun `a line setting every input binds them all`() = parity.checkAll(
        // With an empty file e, the whole line exits 0 and touches nothing, as -files0-from e names no starting point;
        // find opens the -fprint, -fprint0 and -fls files while parsing, and -ok reads its answer from /dev/null.
        listOf(
            cell(
                listOf(
                    "-L", "-D", "tree", "-O2", "--files0-from", "e", "--maxdepth", "2", "--mindepth", "1", "--depth",
                    "--xdev", "--mount", "--noleaf", "--ignore_readdir_race", "--regextype", "posix-egrep",
                    "--daystart", "--follow", "--warn", "--name", "*.kt", "--iname", "*.KT", "--path", "*a*",
                    "--ipath", "*A*", "--wholename", "*a*", "--iwholename", "*A*", "--regex", ".*", "--iregex", ".*",
                    "--lname", "x", "--ilname", "X", "--fstype", "ext4", "--type", "f", "--xtype", "f", "--size",
                    "-1M", "--mtime", "-7", "--mmin", "-60", "--ctime", "-7", "--cmin", "-60", "--atime", "-7",
                    "--amin", "-60", "--used", "1", "--links", "1", "--inum", "1", "--uid", "0", "--gid", "0",
                    "--perm", "-u+r", "--newer", "b", "--anewer", "b", "--cnewer", "b", "--samefile", "b",
                    "--newermt", "2020-01-01", "--newerma", "b", "--user", "root", "--group", "root", "--empty",
                    "--readable", "--writable", "--executable", "--nouser", "--nogroup", "--true", "--false",
                    "--print", "--print0", "--printf", "%p", "--fprint", "out", "--fprint0", "out0", "--fls", "outl",
                    "--ls", "--delete", "--prune", "--quit", "--exec", "echo", "--exec", "{}", "--execdir", "echo",
                    "--execdir", "{}", "--ok", "echo", "--ok", "{}", "--okdir", "echo", "--okdir", "{}",
                ),
                NOTHING_BOUND.copy(
                    logical = true,
                    debug = listOf("tree"),
                    optimise = 2,
                    files0From = CliPath("e"),
                    maxDepth = 2,
                    minDepth = 1,
                    depth = true,
                    xdev = true,
                    mount = true,
                    noleaf = true,
                    ignoreReaddirRace = true,
                    regextype = RegexType.PosixEgrep,
                    daystart = true,
                    follow = true,
                    warn = true,
                    namePattern = listOf("*.kt"),
                    inamePattern = listOf("*.KT"),
                    pathPattern = listOf("*a*"),
                    ipathPattern = listOf("*A*"),
                    wholenamePattern = listOf("*a*"),
                    iwholenamePattern = listOf("*A*"),
                    regexPattern = listOf(".*"),
                    iregexPattern = listOf(".*"),
                    lnamePattern = listOf("x"),
                    ilnamePattern = listOf("X"),
                    fstype = listOf("ext4"),
                    typeTest = listOf("f"),
                    xtype = listOf("f"),
                    sizeTest = listOf(FindSize('-', 1u, 'M')),
                    mtime = listOf(FindCount('-', 7.0)),
                    mmin = listOf(FindCount('-', 60.0)),
                    ctime = listOf(FindCount('-', 7.0)),
                    cmin = listOf(FindCount('-', 60.0)),
                    atime = listOf(FindCount('-', 7.0)),
                    amin = listOf(FindCount('-', 60.0)),
                    used = listOf(FindCount('=', 1.0)),
                    linkCount = listOf(FindNumber('=', 1u)),
                    inum = listOf(FindNumber('=', 1u)),
                    uid = listOf(FindNumber('=', 0u)),
                    gid = listOf(FindNumber('=', 0u)),
                    permTest = listOf(FindPerm('-', "u+r")),
                    newerThan = listOf(CliPath("b")),
                    anewer = listOf(CliPath("b")),
                    cnewer = listOf(CliPath("b")),
                    samefile = listOf(CliPath("b")),
                    newerXY = listOf(FindNewer.ThanFile("ma", CliPath("b")), FindNewer.ThanDate("mt", "2020-01-01")),
                    ownerUser = listOf("root"),
                    ownerGroup = listOf("root"),
                    emptyTest = true,
                    readableTest = true,
                    writableTest = true,
                    executableTest = true,
                    nouserTest = true,
                    nogroupTest = true,
                    trueTest = true,
                    falseTest = true,
                    printAction = true,
                    print0Action = true,
                    printfAction = listOf("%p"),
                    fprintAction = listOf(CliPath("out")),
                    fprint0Action = listOf(CliPath("out0")),
                    flsAction = listOf(CliPath("outl")),
                    lsAction = true,
                    deleteAction = true,
                    pruneAction = true,
                    quitAction = true,
                    execAction = listOf("echo", "{}"),
                    execdirAction = listOf("echo", "{}"),
                    okAction = listOf("echo", "{}"),
                    okdirAction = listOf("echo", "{}"),
                ),
                "find exits 0",
            ),
        ),
    )

    @Test
    fun `help and version answer wherever they sit on a valid line`() {
        parity.showsHelp("--help", because = "find --help prints its usage")
        parity.showsHelp(".", "--help", because = "find . --help prints its usage")
        parity.showsHelp("-L", "--help", because = "find -L --help prints its usage")
        parity.showsHelp("-D", "tree", "--help", because = "find -D tree --help prints its usage")
        parity.showsHelp("-O3", "--help", because = "find -O3 --help prints its usage")
        parity.showsHelp(".", "--maxdepth", "0", "--help", because = "find . -maxdepth 0 --help prints its usage")
        parity.showsVersion("--version", because = "find --version prints its banner")
        parity.showsVersion(".", "--maxdepth", "0", "--version", because = "find . -maxdepth 0 --version")
        parity.showsVersion("--version", "--zzz", because = "find --version --zzz prints its banner")
        parity.showsVersion("--version", "--help", because = "find --version --help prints its banner")
    }

    @Test
    fun `help written before a bad value or an unknown predicate answers`() {
        parity.showsHelp(".", "--help", "--type", "x", because = "find . --help -type x prints its usage")
        parity.showsHelp("--help", "--zzz", because = "find --help --zzz prints its usage")
        parity.showsHelp("--help", "--version", because = "find --help --version prints its usage")
    }

    @Test
    fun `an unknown predicate or a bad value written before help refuses the line`() = parity.checkAll(
        listOf(
            cell(listOf("--zzz", "--help"), null, "find: unknown predicate `--zzz'"),
            cell(listOf("--zzz", "--version"), null, "find: unknown predicate `--zzz'"),
            cell(listOf(".", "--type", "x", "--help"), null, "find: Unknown argument to -type: x"),
            cell(listOf(".", "--maxdepth", "--help"), null, "find: Expected a positive decimal integer, got '--help'"),
            cell(listOf("-Ox", "--help"), null, "find: Please specify a decimal number immediately after -O"),
        ),
    )

    @Test
    fun `--help-all is refused as find refuses it`() {
        // klap offers --help-all only under Help.ByTree, and only beside a subcommand.
        parity.rejects("--help-all", because = "find: unknown predicate `--help-all'")
    }

    @Test
    fun `tests find accepts together bind`() = parity.checkAll(
        listOf(
            cell(listOf(".", "--type", "f,l"), HERE.copy(typeTest = listOf("f,l")), "`find . -type f,l` exits 0"),
            cell(listOf(".", "--xdev", "--mount"), HERE.copy(xdev = true, mount = true), "`find . -xdev -mount`"),
            cell(
                listOf(".", "--maxdepth", "1", "--mindepth", "1"),
                HERE.copy(maxDepth = 1, minDepth = 1),
                "`find . -mindepth 1 -maxdepth 1` lists ./a and ./b",
            ),
        ),
    )

    @Test
    fun `a raw expression after the end of options keeps every word in order`() = parity.checkAll(
        // The fixture's escape hatch: find itself reads `find . -- -name x` as "unknown predicate `--'", and with no
        // starting point `find -- -name '*.kt' -o -print` runs the expression over `.`.
        listOf(
            cell(
                listOf(".", "--", "-name", "*.kt", "-o", "-size", "+1M", "-print"),
                walking(".").copy(expression = listOf("-name", "*.kt", "-o", "-size", "+1M", "-print")),
                "find: unknown predicate `--'",
                loose = true,
            ),
            cell(
                listOf("--", "-name", "*.kt", "-o", "-print"),
                NOTHING_BOUND.copy(expression = listOf("-name", "*.kt", "-o", "-print")),
                "`find -- -name '*.kt' -o -print` exits 0, the words are find's expression, not starting points",
            ),
        ),
    )

    @Test
    fun `a built-in's name as a value is the value`() = parity.checkAll(
        listOf(
            cell(listOf(".", "--name", "--version"), HERE.copy(namePattern = listOf("--version")), "runs, no banner"),
            cell(
                listOf(".", "--printf", "--help"),
                HERE.copy(printfAction = listOf("--help")),
                "prints --help per file",
            ),
        ),
    )

    @Test
    fun `a prefix of a predicate is refused as find refuses it`() = parity.checkAll(cells(ABBREVIATED))

    @Test
    fun `a prefix of help or version is refused`() = parity.checkAll(
        // F1b: find matches --help and --version whole.
        listOf("--he", "--h", "--vers", "--v").map { prefix ->
            cell(listOf(prefix), null, "find: unknown predicate `$prefix'")
        },
    )

    @Test
    fun `an option klap adds that find does not have is refused`() = parity.checkAll(
        // Each is "find: unknown predicate" in real find.
        listOf(
            listOf("--json", "."),
            listOf("--color=never", "."),
            listOf("--color", "never", "."),
            listOf("--completion", "bash"),
            listOf("--docs", "markdown"),
            listOf("-h"),
            listOf(".", "-h"),
        ).map { cell(it, null, "find: unknown predicate `${it.first { word -> word.startsWith("-") }}'") },
    )

    @Test
    fun `--files0-from refuses a starting point beside it`() = parity.checkAll(
        listOf(
            cell(listOf(".", "--files0-from", "b"), null, "find: file operands cannot be combined with -files0-from"),
            cell(listOf("a", "--files0-from=b"), null, "find: file operands cannot be combined with -files0-from"),
        ),
    )

    @Test
    fun `--ignore_readdir_race is spelled with underscores`() = parity.checkAll(
        listOf(
            cell(listOf(".", "--ignore_readdir_race"), HERE.copy(ignoreReaddirRace = true), "exits 0"),
            cell(listOf(".", "--ignore-readdir-race"), null, "find: unknown predicate `-ignore-readdir-race'"),
        ),
    )

    @Test
    fun `a test given twice applies both`() = checkDiffer(BOTH_APPLY)

    @Test
    fun `a predicate find has binds`() {
        // Each line exits 0 under real find with the single-dash spelling. `-context` is left out: this kernel refuses
        // it ("SELinux is not enabled"). `-fprintf FILE FORMAT` takes two words, which no klap option holds.
        val lines = listOf(
            listOf(".", "--daystart"), listOf(".", "--follow"), listOf(".", "--nowarn"), listOf(".", "--warn"),
            listOf(".", "--noignore_readdir_race"), listOf(".", "--d"), listOf(".", "--amin", "-5"),
            listOf(".", "--anewer", "b"), listOf(".", "--cmin", "-5"), listOf(".", "--cnewer", "b"),
            listOf(".", "--false"), listOf(".", "--true"), listOf(".", "--fstype", "ext4"), listOf(".", "--gid", "0"),
            listOf(".", "--uid", "0"), listOf(".", "--ilname", "x"), listOf(".", "--lname", "x"),
            listOf(".", "--inum", "1"), listOf(".", "--iwholename", "x"), listOf(".", "--wholename", "x"),
            listOf(".", "--ipath", "x"), listOf(".", "--iregex", "x"), listOf(".", "--newermt", "2020-01-01"),
            listOf(".", "--samefile", "b"), listOf(".", "--used", "1"), listOf(".", "--xtype", "f"),
            listOf(".", "--fprint", "out"), listOf(".", "--fprint0", "out"), listOf(".", "--fls", "out"),
        )
        checkCells(lines) { line ->
            parity.accepts(argv = line.toTypedArray(), because = "find ${line.joinToString(" ").replace("--", "-")}")
        }
    }

    @Test
    fun `__complete is a starting point`() {
        // `find __complete .`: '__complete': No such file or directory, then `.` is walked.
        parity.binds("__complete", ".", expected = walking("__complete", "."))
    }
}

/**
 * The find lines klap reads otherwise by the owner's decision (F1, todo 48a, todo 67), each `@Ignore`d as a
 * record of real find 4.11.0's reading, kept once rather than per cell.
 */
class FindProjectionTargetsTest {

    private val parity = ParitySuite(findCli())

    // --- will not do: what the respelling cannot carry (F1), each recorded once ---

    /** A record, not a target: find's own single-dash spelling, which klap keeps out (todo 48a). */
    @Ignore("Will not do (F1, todo 48a): klap does not spell a multi-letter option with one dash")
    @Test
    fun `find's single-dash spelling binds as the respelling does`() = checkCells(
        listOf(
            { parity.binds(".", "-name", "*.kt", expected = HERE.copy(namePattern = listOf("*.kt"))) },
            { parity.binds(".", "-type", "f", expected = HERE.copy(typeTest = listOf("f"))) },
            { parity.binds(".", "-maxdepth", "3", expected = HERE.copy(maxDepth = 3)) },
            { parity.binds(".", "-print", expected = HERE.copy(printAction = true)) },
            // `find -help` prints the usage and `find -version` the banner.
            { parity.showsHelp("-help", because = "find -help prints its usage") },
            { parity.showsVersion("-version", because = "find -version prints its banner") },
        ),
    ) { it() }

    /**
     * A record, not a target: find's expression grammar. The operators, grouping and the meaning of order have no place
     * in an unordered record, so these lines show what real find accepts and refuses where the fixture reads otherwise.
     */
    @Ignore("Will not do (F1, todo 48a): klap binds an unordered record, not find's expression grammar")
    @Test
    fun `find's expression grammar and its order are read as find reads them`() = checkCells(
        // Operators, as find spells them, and the word operators respelled: each line exits 0 and walks . alone.
        listOf(
            listOf(".", "--name", "a", "-o", "--name", "b") to "find . -name a -o -name b",
            listOf(".", "--empty", "-a", "--print") to "find . -empty -a -print",
            listOf(".", "!", "--empty") to "find . ! -empty",
            listOf(".", "--not", "--empty") to "find . -not -empty",
            listOf(".", "--empty", "--or", "--print") to "find . -empty -or -print",
            listOf(".", "--empty", "--and", "--print") to "find . -empty -and -print",
            listOf(".", "(", "--empty", "-o", "--print", ")") to "find . ( -empty -o -print )",
            listOf(".", "--empty", ",", "--print") to "find . -empty , -print",
            listOf(".", "--name", "*.kt", "--type", "f", "(", "--mtime", "+7", "-o", "--size", "+1M", ")", "--print")
                to "find . -name '*.kt' -type f ( -mtime +7 -o -size +1M ) -print",
        ).map { (line, why) -> { parity.accepts(argv = line.toTypedArray(), because = why, assert = ::walksHere) } } +
            listOf(
                // Starting points come first: everything from the first predicate on is the expression.
                { parity.rejects("--empty", ".", because = "find: paths must precede expression: `.'") },
                { parity.rejects("a", "--empty", "b", because = "find: paths must precede expression: `b'") },
                { parity.rejects(".", "--maxdepth", "0", "a", because = "find: paths must precede expression: `a'") },
                { parity.rejects(".", "-L", because = "find: unknown predicate `-L'") },
                { parity.rejects("--", "-L", ".", because = "find: unknown predicate `-L'") },
                // A `--` after a starting point is not the end of options but an unknown predicate.
                { parity.rejects(".", "--", "--name", "x", because = "find: unknown predicate `--'") },
                // --help is a predicate of the expression, so after `--` it still prints the usage.
                { parity.showsHelp("--", "--help", because = "find -- --help prints its usage") },
            ),
    ) { it() }

    /** A record, not a target: `-exec`, `-execdir`, `-ok` and `-okdir` run to a `;` or `+` no option can hold. */
    @Ignore("Will not do (F1, todo 48a): klap has no option whose value runs to a terminator word")
    @Test
    fun `-exec takes the command's words up to its terminator`() = checkCells(
        listOf(
            // `find . -maxdepth 0 -exec echo {} ;` prints `.`, `... -exec echo {} +` too.
            {
                parity.binds(
                    ".", "--exec", "echo", "{}", ";",
                    expected = HERE.copy(execAction = listOf("echo", "{}")),
                )
            },
            {
                parity.binds(
                    ".", "--exec", "echo", "{}", "+",
                    expected = HERE.copy(execAction = listOf("echo", "{}")),
                )
            },
            { parity.accepts(".", "--execdir", "echo", "{}", ";", because = "find . -execdir echo {} ; prints ./.") },
            { parity.accepts(".", "--ok", "echo", "{}", ";", because = "find . -ok echo {} ; asks < echo ... . >") },
            { parity.accepts(".", "--okdir", "echo", "{}", ";", because = "find . -okdir echo {} ; asks") },
        ),
    ) { it() }

    /** A record, not a target: `-fprintf FILE FORMAT` takes two words, which no klap option holds. */
    @Ignore("Will not do (F1, todo 67): klap has no option whose value is two words")
    @Test
    fun `-fprintf takes a file and a format`() {
        // `find . -maxdepth 0 -fprintf out %p` writes `.` into out and exits 0.
        parity.accepts(".", "--fprintf", "out", "%p", because = "find . -fprintf out %p")
    }
}
