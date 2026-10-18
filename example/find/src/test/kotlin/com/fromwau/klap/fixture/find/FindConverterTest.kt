package com.fromwau.klap.fixture.find

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.io.CliPath
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/*
 * Every expected value is from GNU findutils 4.11.0 (`/usr/bin/find`), run inside a throwaway directory holding a
 * file `a`, a NUL-separated list `list0` naming `a`, and a directory `sub`, as `find . -size <value>` and so on: the
 * fixture's `--size <value>` and `--size=<value>` stand for find's `-size <value>` (owner rulings F1 and F1a). A
 * refusal is find's own parse error, quoted as evidence only. find's later answers to a parsed line (a -newer file
 * or a -files0-from list that does not exist, a -user name the password database lacks) are the system's answer,
 * so those lines bind, as cp's fixture binds a target directory that does not exist.
 */

/** How find reads one line: the record it binds, a parse whose value the record cannot hold, or a refusal. */
private sealed interface Reading {
    class Binds(val expected: FindInputs) : Reading

    class Accepts(val assert: (FindInputs) -> Unit) : Reading

    data object Refused : Reading
}

/** One find line, how find reads it, and find's own answer as evidence. */
private class ConverterCell(
    val argv: List<String>,
    val reading: Reading,
    val because: String,
)

private fun ParitySuite<FindInputs>.checkValues(cells: List<ConverterCell>) = checkCells(cells) { cell ->
    val argv = cell.argv.toTypedArray()
    when (val reading = cell.reading) {
        is Reading.Binds -> binds(argv = argv, expected = reading.expected, because = cell.because)
        is Reading.Accepts -> accepts(argv = argv, because = cell.because, assert = reading.assert)
        Reading.Refused -> rejects(argv = argv, because = cell.because)
    }
}

/** `find .` with nothing else: the starting point every predicate line below carries. */
private val AT_DOT: FindInputs = walking(".")

/** The fixture's two spellings of a respelled predicate's value: spaced `--opt v` and attached `--opt=v`. */
private fun spellings(
    long: String,
    typed: String,
): List<List<String>> = listOf(listOf(".", long, typed), listOf(".", "$long=$typed"))

/** find's own spelling of the fixture's `--opt`, for the evidence in a cell. */
private fun real(long: String): String = long.drop(1)

/** A respelled predicate or traversal option with a value, and how its value lands in the record. */
private class Predicate<T>(
    val long: String,
    val bind: FindInputs.(T) -> FindInputs,
) {
    /** Each typed value find accepts, bound as [T]. */
    fun reads(vararg cells: Pair<String, T>): List<ConverterCell> = cells.flatMap { (typed, value) ->
        spellings(long, typed).map { argv ->
            ConverterCell(argv, Reading.Binds(AT_DOT.bind(value)), "`find . ${real(long)} '$typed'` exits 0")
        }
    }

    /** Each typed value find accepts whose reading the record's type cannot hold; [assert] checks what it can. */
    fun accepts(
        vararg typed: String,
        assert: (FindInputs) -> Unit = {},
    ): List<ConverterCell> = typed.flatMap { value ->
        spellings(long, value).map { argv ->
            ConverterCell(argv, Reading.Accepts(assert), "`find . ${real(long)} '$value'` exits 0")
        }
    }

    /** Each typed value find refuses while parsing; [message] is find's error for it. */
    fun refuses(
        vararg typed: String,
        message: (String) -> String,
    ): List<ConverterCell> = typed.flatMap { value ->
        spellings(long, value).map { argv -> ConverterCell(argv, Reading.Refused, message(value)) }
    }
}

private val SIZE = Predicate<FindSize>("--size") { copy(sizeTest = listOf(it)) }

private fun size(
    comparison: Char,
    value: Long,
    unit: Char = 'b',
) = FindSize(comparison, value.toULong(), unit)

private fun invalidSizeType(value: String) = "find: invalid -size type `${value.last()}'"

private fun invalidSize(value: String) = "find: Invalid argument `$value' to -size"

/** find's four time predicates, which read their count with one grammar (strtod after the comparison). */
private val TIMES: List<Predicate<FindCount>> = listOf(
    Predicate("--mtime") { copy(mtime = listOf(it)) },
    Predicate("--mmin") { copy(mmin = listOf(it)) },
    Predicate("--ctime") { copy(ctime = listOf(it)) },
    Predicate("--atime") { copy(atime = listOf(it)) },
)

private fun Predicate<FindCount>.count(record: FindInputs): FindCount? = when (long) {
    "--mtime" -> record.mtime
    "--mmin" -> record.mmin
    "--ctime" -> record.ctime
    else -> record.atime
}.singleOrNull()

/** A time predicate accepts [typed], read with [comparison]; the count itself is a real number find keeps. */
private fun Predicate<FindCount>.acceptsReal(
    comparison: Char,
    vararg typed: String,
): List<ConverterCell> = accepts(*typed) { record ->
    assertEquals(comparison, assertNotNull(count(record), "$long bound nothing").comparison, long)
}

private fun invalidTime(long: String) = { value: String -> "find: invalid argument `$value' to `${real(long)}'" }

private val LINKS = Predicate<FindNumber>("--links") { copy(linkCount = listOf(it)) }

private fun nonNumericLinks(value: String) = "find: non-numeric argument to -links: '$value'"

private fun count(
    comparison: Char,
    value: Long,
) = FindCount(comparison, value.toDouble())

private fun links(
    comparison: Char,
    value: Long,
) = FindNumber(comparison, value.toULong())

private val PERM = Predicate<FindPerm>("--perm") { copy(permTest = listOf(it)) }

private fun invalidMode(value: String) = "find: invalid file mode '$value'"

private val TYPE = Predicate<String>("--type") { copy(typeTest = listOf(it)) }

private val DEPTHS: List<Predicate<Int>> = listOf(
    Predicate("--maxdepth") { copy(maxDepth = it) },
    Predicate("--mindepth") { copy(minDepth = it) },
)

private fun notPositiveDecimal(long: String) =
    { value: String -> "find: Expected a positive decimal integer argument to ${real(long)}, but got '$value'" }

private val REGEXTYPE = Predicate<RegexType>("--regextype") { copy(regextype = it) }

private fun unknownRegexType(value: String) = "find: Unknown regular expression type '$value'; valid types are ..."

/** find's thirteen regex types, as `find . -regextype help` lists them, each with the type it binds. */
private val FIND_REGEX_TYPES = listOf(
    "findutils-default" to RegexType.FindutilsDefault,
    "awk" to RegexType.Awk,
    "ed" to RegexType.Ed,
    "egrep" to RegexType.Egrep,
    "emacs" to RegexType.Emacs,
    "gnu-awk" to RegexType.GnuAwk,
    "grep" to RegexType.Grep,
    "posix-awk" to RegexType.PosixAwk,
    "posix-basic" to RegexType.PosixBasic,
    "posix-egrep" to RegexType.PosixEgrep,
    "posix-extended" to RegexType.PosixExtended,
    "posix-minimal-basic" to RegexType.PosixMinimalBasic,
    "sed" to RegexType.Sed,
)

/** `-O<level>`, glued, as find spells it before the starting points. */
private fun optimise(
    level: String,
    reading: Reading,
    because: String,
) = ConverterCell(listOf("-O$level", "."), reading, because)

private fun optimiseBinds(vararg levels: Pair<String, Int>) = levels.map { (typed, level) ->
    optimise(typed, Reading.Binds(AT_DOT.copy(optimise = level)), "`find -O$typed .` exits 0")
}

private fun optimiseRefuses(vararg levels: Pair<String, String>) = levels.map { (typed, message) ->
    optimise(typed, Reading.Refused, message)
}

/** `-D <list>`, spaced, as find spells it before the starting points. */
private fun debugBinds(vararg lists: Pair<String, List<String>>) = lists.map { (typed, keys) ->
    ConverterCell(listOf("-D", typed, "."), Reading.Binds(AT_DOT.copy(debug = keys)), "`find -D '$typed' .` exits 0")
}

/** A respelled predicate that keeps every occurrence: each typed word lands in its list verbatim. */
private class Pattern(
    val long: String,
    val bind: FindInputs.(List<String>) -> FindInputs,
) {
    fun verbatim(vararg typed: String): List<ConverterCell> = typed.flatMap { value ->
        spellings(long, value).map { argv ->
            ConverterCell(argv, Reading.Binds(AT_DOT.bind(listOf(value))), "`find . ${real(long)} '$value'` exits 0")
        }
    }
}

private val PATTERNS = listOf(
    Pattern("--name") { copy(namePattern = it) },
    Pattern("--iname") { copy(inamePattern = it) },
    Pattern("--path") { copy(pathPattern = it) },
)

private val REGEX = Pattern("--regex") { copy(regexPattern = it) }

private val PRINTF = Predicate<String>("--printf") { copy(printfAction = listOf(it)) }

private val USER = Predicate<String>("--user") { copy(ownerUser = listOf(it)) }

private val GROUP = Predicate<String>("--group") { copy(ownerGroup = listOf(it)) }

private val NEWER = Predicate<CliPath>("--newer") { copy(newerThan = listOf(it)) }

/** `-files0-from FILE` with no starting point, since find refuses both together ("extra operand '.'"). */
private fun files0From(vararg typed: String) = typed.flatMap { value ->
    listOf(listOf("--files0-from", value), listOf("--files0-from=$value")).map { argv ->
        ConverterCell(
            argv,
            Reading.Binds(NOTHING_BOUND.copy(files0From = CliPath(value))),
            "`find -files0-from '$value'` parses; a list that does not open is find's run-time answer",
        )
    }
}

private fun refusedLine(
    vararg argv: String,
    because: String,
) = ConverterCell(argv.toList(), Reading.Refused, because)

private fun boundLine(
    vararg argv: String,
    expected: FindInputs,
    because: String = "exits 0",
) = ConverterCell(argv.toList(), Reading.Binds(expected), because)

/**
 * What the fixture makes of each typed find value (X of the testing matrix): the counts of -size, -mtime, -mmin,
 * -ctime, -atime and -links, the modes of -perm, the type lists of -type, the depths of -maxdepth and -mindepth,
 * -O's level, -D's list, -regextype's names, and the free-text values bound verbatim. A predicate is spelled with two
 * dashes, spaced and attached (F1, F1a); `-O` and `-D` as find spells them.
 */
class FindConverterTest {

    private val parity = ParitySuite(findCli())

    @Test
    fun `--size binds each unit find reads`() = parity.checkValues(
        SIZE.reads(
            "1" to size('=', 1), "1b" to size('=', 1), "1c" to size('=', 1, 'c'), "1w" to size('=', 1, 'w'),
            "1k" to size('=', 1, 'k'), "1M" to size('=', 1, 'M'), "1G" to size('=', 1, 'G'),
            "+1M" to size('+', 1, 'M'), "-100c" to size('-', 100, 'c'), "+1k" to size('+', 1, 'k'),
            "-1G" to size('-', 1, 'G'),
        ),
    )

    @Test
    fun `--size reads a leading plus or minus as the comparison and the rest as a decimal count`() =
        // `find . -size 010c` matches a 10-byte file, so a leading zero is decimal; `-size -+9c` matches files
        // under 9 bytes, so the sign after the comparison belongs to the count.
        parity.checkValues(
            SIZE.reads(
                "+1" to size('+', 1), "-1" to size('-', 1), "0" to size('=', 0), "-0" to size('-', 0),
                "00" to size('=', 0), "010" to size('=', 10), "++1" to size('+', 1), "-+1" to size('-', 1),
                "9223372036854775807" to size('=', Long.MAX_VALUE),
            ),
        )

    @Test
    fun `--size refuses a unit find does not have`() = parity.checkValues(
        SIZE.refuses("1T", "1K", "1Q", "1B", "1m", "1g", "1t", "1P", "K", message = ::invalidSizeType) +
            SIZE.refuses("k", "1kk", "1Kb", "1kc", "1x2", "1 k", message = ::invalidSize),
    )

    @Test
    fun `--size refuses a value that is not a decimal count`() = parity.checkValues(
        SIZE.refuses("", message = { "find: invalid null argument to -size" }) +
            SIZE.refuses("+", "-", "--", "1 ", message = ::invalidSizeType) +
            SIZE.refuses("1e3", "0x10", "1.5", "99999999999999999999", message = ::invalidSize),
    )

    @Test
    fun `a time predicate reads a whole count with its comparison`() = parity.checkValues(
        // find reads the count after the comparison with strtod, so a second sign belongs to the count.
        TIMES.flatMap { time ->
            time.reads(
                "1" to count('=', 1), "+1" to count('+', 1), "-1" to count('-', 1), "-5" to count('-', 5),
                "0" to count('=', 0), "-0" to count('-', 0), "+0" to count('+', 0), "00" to count('=', 0),
                "010" to count('=', 10), "080" to count('=', 80), "++1" to count('+', 1), "-+1" to count('-', 1),
                "+-1" to count('+', -1), "--1" to count('-', -1),
                "9223372036854775807" to count('=', Long.MAX_VALUE),
            )
        },
    )

    @Test
    fun `a time predicate refuses a value that is not a number`() = parity.checkValues(
        TIMES.flatMap { time ->
            time.refuses(
                "", "+", "-", "1 ", "abc", "1k", "1,5", "0x", "1e400", "1e", "1.5f",
                message = invalidTime(time.long),
            ) + time.refuses("nan", "NaN", message = { "find: invalid not-a-number argument: `$it'" })
        },
    )

    @Test
    fun `--links reads a whole count with its comparison`() = parity.checkValues(
        LINKS.reads(
            "1" to links('=', 1), "+1" to links('+', 1), "-1" to links('-', 1), "++1" to links('+', 1),
            "-+1" to links('-', 1), "0" to links('=', 0), "-0" to links('-', 0), "+0" to links('+', 0),
            "00" to links('=', 0), "010" to links('=', 10), "080" to links('=', 80),
            "9223372036854775807" to links('=', Long.MAX_VALUE),
        ),
    )

    @Test
    fun `--links refuses a value that is not a decimal count`() = parity.checkValues(
        LINKS.refuses(
            "", "+", "-", "0x1", "1e1", "1.5", "1 ", "abc", "1k", "99999999999999999999",
            "18446744073709551616",
            message = ::nonNumericLinks,
        ),
    )

    @Test
    fun `--perm binds each mode find reads with its match kind`() = parity.checkValues(
        PERM.reads(
            "644" to FindPerm('=', "644"), "0644" to FindPerm('=', "0644"), "00644" to FindPerm('=', "00644"),
            "7755" to FindPerm('=', "7755"), "755" to FindPerm('=', "755"), "-644" to FindPerm('-', "644"),
            "/644" to FindPerm('/', "644"), "-022" to FindPerm('-', "022"), "=644" to FindPerm('=', "=644"),
            "u+x" to FindPerm('=', "u+x"), "-u+w" to FindPerm('-', "u+w"), "/u+w" to FindPerm('/', "u+w"),
            "u=rwx,g=rx" to FindPerm('=', "u=rwx,g=rx"), "a+X" to FindPerm('=', "a+X"), "u=g" to FindPerm('=', "u=g"),
            "g+s" to FindPerm('=', "g+s"), "o-w" to FindPerm('=', "o-w"), "+u+x" to FindPerm('=', "+u+x"),
            "+x" to FindPerm('=', "+x"), "u+r-w" to FindPerm('=', "u+r-w"), "=" to FindPerm('=', "="),
            "=,u+x" to FindPerm('=', "=,u+x"), "--" to FindPerm('-', "-"),
            // `find . -perm /-` warns that /000 now matches every file, and exits 0.
            "/-" to FindPerm('/', "-"),
        ),
    )

    @Test
    fun `--perm refuses a match kind with no mode after it`() = parity.checkValues(
        PERM.refuses("", "-", "/", message = ::invalidMode),
    )

    @Test
    fun `--type binds each letter and comma list find reads`() = parity.checkValues(
        TYPE.reads(
            "b" to "b", "c" to "c", "d" to "d", "p" to "p", "f" to "f", "l" to "l", "s" to "s", "f,l" to "f,l",
            "b,c,d,p,f,l,s" to "b,c,d,p,f,l,s",
        ),
    )

    @Test
    fun `--type refuses an unknown letter and a malformed list`() = parity.checkValues(
        TYPE.refuses("x", "F", "f,x", ",f", "f,,l", ",", "f, l", " f", message = {
            "find: Unknown argument to -type: ..."
        }) +
            TYPE.refuses("", message = { "find: Arguments to -type should contain at least one letter" }) +
            TYPE.refuses("fl", "f ", message = { "find: Must separate multiple arguments to -type using: ','" }) +
            TYPE.refuses("f,", message = { "find: Last file type in list argument to -type is missing" }),
    )

    @Test
    fun `a depth binds a decimal count`() = parity.checkValues(
        DEPTHS.flatMap { depth ->
            depth.reads("0" to 0, "1" to 1, "3" to 3, "01" to 1, "08" to 8, "2147483647" to Int.MAX_VALUE)
        },
    )

    @Test
    fun `a depth refuses a value that is not a decimal count`() = parity.checkValues(
        DEPTHS.flatMap { depth ->
            depth.refuses("-1", " 1", "1 ", "0x1", "1.5", "", "abc", "1e1", message = notPositiveDecimal(depth.long)) +
                depth.refuses(
                    "2147483648",
                    "99999999999999999999",
                    message = { "find: $it: Numerical result out of range" },
                )
        },
    )

    @Test
    fun `-O binds every decimal level up to 65535`() = parity.checkValues(
        // `find -O4 .`, `-O9`, `-O10` and `-O65535` exit 0, though the man page describes levels 0 to 3 only;
        // `-O65536` is "Optimisation level 65536 is too high".
        optimiseBinds(
            "0" to 0, "1" to 1, "2" to 2, "3" to 3, "01" to 1, "00" to 0, "4" to 4, "9" to 9, "10" to 10,
            "65535" to 65535,
        ),
    )

    @Test
    fun `-O refuses a level that is not a decimal number`() = parity.checkValues(
        optimiseRefuses(
            "-1" to "find: Please specify a decimal number immediately after -O",
            "x" to "find: Please specify a decimal number immediately after -O",
            "1x" to "find: Invalid optimisation level 1x",
            "0x1" to "find: Invalid optimisation level 0x1",
            "1.5" to "find: Invalid optimisation level 1.5",
            "65536" to "find: Optimisation level 65536 is too high",
            "99999999999999999999" to "find: Invalid optimisation level 99999999999999999999",
        ) + refusedLine("-O", because = "find: The -O option must be immediately followed by a decimal integer"),
    )

    @Test
    fun `-D binds its comma list and skips empty keys`() = parity.checkValues(
        // find warns "Ignoring unrecognised debug flag" for an unknown key, a key in another case, a key with a
        // blank and a dash-led word such as `--`, and exits 0.
        debugBinds(
            "tree" to listOf("tree"), "search,tree" to listOf("search", "tree"), "tree," to listOf("tree"),
            ",tree" to listOf("tree"), "tree,,search" to listOf("tree", "search"), "all" to listOf("all"),
            "tree,tree" to listOf("tree", "tree"), "bogus" to listOf("bogus"), "TREE" to listOf("TREE"),
            "tree, search" to listOf("tree", " search"), "--" to listOf("--"),
        ) + refusedLine("-D", because = "find: Missing argument after the -D option."),
    )

    @Test
    fun `--regextype binds every name find has`() = parity.checkValues(
        // `find . -regextype help` lists the thirteen.
        REGEXTYPE.reads(*FIND_REGEX_TYPES.toTypedArray()),
    )

    @Test
    fun `--regextype refuses a name find does not have and an abbreviation`() = parity.checkValues(
        REGEXTYPE.refuses("posix-e", "posix", "emac", "", "bogus", " emacs", "help", message = ::unknownRegexType),
    )

    @Test
    fun `--regextype refuses a name in another case`() = parity.checkValues(
        REGEXTYPE.refuses("POSIX-EGREP", "Posix-Egrep", "EMACS", "Findutils-Default", message = ::unknownRegexType),
    )

    @Test
    fun `a pattern binds its word verbatim`() = parity.checkValues(
        PATTERNS.flatMap { it.verbatim("", "*.kt", "a/b", "[", "-foo", " ", "--", "-", "a=b", "a\nb", "=x") },
    )

    @Test
    fun `--regex binds a regular expression find compiles verbatim`() =
        parity.checkValues(REGEX.verbatim("", "(", "*", ".*[.]kt", "a\\{2\\}"))

    @Test
    fun `--printf binds a format find reads verbatim`() = parity.checkValues(
        // find warns about `%Q` and `\q` and exits 0.
        PRINTF.reads(*listOf("", "%p\\n", "%Q", "\\q", "%-5p", "-x", "a=b").map { it to it }.toTypedArray()),
    )

    @Test
    fun `--user and --group bind a name or number verbatim`() = parity.checkValues(
        // `find . -user bogus` is "invalid user name or UID argument", which is the password database's answer.
        listOf(USER, GROUP).flatMap { owner ->
            owner.reads(*listOf("root", "0", "99999", "+0", "bogus").map { it to it }.toTypedArray())
        },
    )

    @Test
    fun `--newer binds its file verbatim`() = parity.checkValues(
        // `find . -newer nofile` is "'nofile': No such file or directory", the file system's answer.
        NEWER.reads(*listOf("a", "sub", "sub/", "nofile").map { it to CliPath(it) }.toTypedArray()),
    )

    @Test
    fun `--files0-from binds its file verbatim`() = parity.checkValues(files0From("list0", "nofile", "-"))

    @Test
    fun `the starting points bind verbatim and in order`() = parity.checkValues(
        // `find ''` is "'': No such file or directory" when find walks it.
        listOf(
            boundLine("a", "sub", expected = walking("a", "sub")),
            boundLine("sub", "a", expected = walking("sub", "a")),
            boundLine("sub/", "./a", expected = walking("sub/", "./a")),
            boundLine("", because = "parses", expected = walking("")),
            boundLine(expected = NOTHING_BOUND, because = "find with no starting point walks ."),
        ),
    )

    @Test
    fun `an invalid earlier value refuses the line`() = parity.checkValues(
        // find reads each value as it meets it, so a bad one anywhere on the line is refused.
        listOf(
            refusedLine(".", "--size", "1Q", "--size", "1", because = "find: invalid -size type `Q'"),
            refusedLine(".", "--mtime", "x", "--mtime", "1", because = "find: invalid argument `x' to `-mtime'"),
            refusedLine(".", "--links", "x", "--links", "1", because = "find: non-numeric argument to -links"),
            refusedLine(".", "--type", "x", "--type", "f", because = "find: Unknown argument to -type: x"),
            refusedLine(".", "--maxdepth", "abc", "--maxdepth", "1", because = "find: Expected a positive decimal"),
            refusedLine(".", "--mindepth", "abc", "--mindepth", "1", because = "find: Expected a positive decimal"),
            refusedLine(".", "--regextype", "bogus", "--regextype", "emacs", because = "find: Unknown regex type"),
            refusedLine("-Ox", "-O1", ".", because = "find: Please specify a decimal number immediately after -O"),
        ),
    )

    @Test
    fun `a depth given twice binds the later value`() = parity.checkValues(
        // `find . -maxdepth 0 -maxdepth 1` lists the entries one level down; `-mindepth 1 -mindepth 0 -maxdepth 0`
        // lists `.`.
        listOf(
            boundLine(".", "--maxdepth", "0", "--maxdepth", "1", expected = AT_DOT.copy(maxDepth = 1)),
            boundLine(".", "--maxdepth=0", "--maxdepth=1", expected = AT_DOT.copy(maxDepth = 1)),
            boundLine(".", "--mindepth", "1", "--mindepth", "0", expected = AT_DOT.copy(minDepth = 0)),
        ),
    )

    @Test
    fun `--regextype given twice binds the later name`() = parity.checkValues(
        // `find . -regextype posix-basic -regextype posix-extended -regex '.*/a+'` matches ./a, as extended syntax.
        listOf(
            boundLine(
                ".", "--regextype", "posix-basic", "--regextype", "posix-extended",
                expected = AT_DOT.copy(regextype = RegexType.PosixExtended),
            ),
        ),
    )

    @Test
    fun `-O given twice binds the later level`() = parity.checkValues(
        // `find -O3 -O0 -D opt . -name x -type f` reports its work as -O0, and `-O0 -O3` as -O3.
        listOf(
            boundLine("-O3", "-O0", ".", expected = AT_DOT.copy(optimise = 0), because = "-D opt prints -O0"),
            boundLine("-O0", "-O3", ".", expected = AT_DOT.copy(optimise = 3), because = "-D opt prints -O3"),
        ),
    )

    @Test
    fun `--size takes blanks before the count`() = parity.checkValues(
        // `find . -size ' 1'`, `-size $'\t1'` and `-size '+ 1'` exit 0.
        SIZE.reads(" 1" to size('=', 1), "\t1" to size('=', 1), "+ 1" to size('+', 1), " 1k" to size('=', 1, 'k')),
    )

    @Test
    fun `--size refuses a minus after the comparison`() = parity.checkValues(
        // `find . -size +-1` and `-size --1`: "Invalid argument `+-1' to -size"; only one sign is a comparison.
        SIZE.refuses("+-1", "+-1k", "--1", message = ::invalidSize),
    )

    @Test
    fun `a count refuses non-ASCII digits`() = parity.checkValues(
        // `find . -size ١`, `-links ١٢` and `-mtime ١٢` (Arabic-Indic digits) are refused.
        SIZE.refuses("١", "١٢", "+١", "１２", message = ::invalidSizeType) +
            LINKS.refuses("١٢", "+١", "１２", message = ::nonNumericLinks) +
            TIMES.flatMap { it.refuses("١٢", "+١", "１２", message = invalidTime(it.long)) },
    )

    @Test
    fun `--size reads a count beyond Long up to the unsigned 64-bit limit`() = parity.checkValues(
        // `find . -size 9223372036854775808` and `-size 18446744073709551615` exit 0; one more is refused.
        SIZE.reads(
            "9223372036854775808" to FindSize('=', 9223372036854775808u, 'b'),
            "18446744073709551615" to FindSize('=', ULong.MAX_VALUE, 'b'),
        ),
    )

    @Test
    fun `a time predicate takes blanks before the number`() = parity.checkValues(
        // `find . -mtime ' 1'`, `$'\t1'`, `'+ 1'` and `'- 1'` exit 0; `-mtime '1 '` is refused.
        TIMES.flatMap {
            it.reads(" 1" to count('=', 1), "\t1" to count('=', 1), "+ 1" to count('+', 1), "- 1" to count('-', 1))
        },
    )

    @Test
    fun `a time predicate reads a fractional exponent or hexadecimal count`() = parity.checkValues(
        // `find . -mtime 1.5`, `.5`, `5.`, `1e1`, `0x1`, `0x1p3`, `+1.5` and `-0.5` exit 0: find reads N with strtod.
        TIMES.flatMap { time ->
            time.acceptsReal('=', "1.5", ".5", "5.", "1e1", "1.5e1", "0x1", "0x1p3") +
                time.acceptsReal('+', "+1.5") +
                time.acceptsReal('-', "-0.5")
        },
    )

    @Test
    fun `a time predicate reads infinity and a count beyond Long`() = parity.checkValues(
        // `find . -mtime inf`, `-inf`, `+inf`, `Infinity` and `99999999999999999999` exit 0; `nan` and `1e400` do not.
        TIMES.flatMap { time ->
            time.acceptsReal('=', "inf", "Infinity", "99999999999999999999", "9223372036854775808") +
                time.acceptsReal('-', "-inf") +
                time.acceptsReal('+', "+inf")
        },
    )

    @Test
    fun `--links takes blanks before the count`() = parity.checkValues(
        // `find . -links ' 1'`, `$'\t1'` and `'+ 1'` exit 0.
        LINKS.reads(" 1" to links('=', 1), "\t1" to links('=', 1), "+ 1" to links('+', 1)),
    )

    @Test
    fun `--links refuses a minus after the comparison`() = parity.checkValues(
        // `find . -links +-1` and `-links --1`: "non-numeric argument to -links".
        LINKS.refuses("+-1", "--1", message = ::nonNumericLinks),
    )

    @Test
    fun `--links reads a count beyond Long up to the unsigned 64-bit limit`() = parity.checkValues(
        // `find . -links 9223372036854775808` and `18446744073709551615` exit 0; 18446744073709551616 is refused.
        LINKS.reads(
            "9223372036854775808" to FindNumber('=', 9223372036854775808u),
            "18446744073709551615" to FindNumber('=', ULong.MAX_VALUE),
        ),
    )

    @Test
    fun `--perm refuses a mode find cannot compile`() = parity.checkValues(
        // `find . -perm 17755`, `-perm -w` and the rest: "invalid file mode", while parsing.
        PERM.refuses(
            "17755", "8", "9", "+644", ",u+x", "u+x,", "//", "-/", "x", "0x1ff", " 755", "755 ", "u+w,,g+w", "-w",
            message = ::invalidMode,
        ),
    )

    @Test
    fun `--type refuses the door letter on this platform`() = parity.checkValues(
        // `find . -type D` is "-type D is not supported because Solaris doors are not supported on the platform find
        // was compiled on", here on Linux; findutils accepts D where doors exist (Solaris).
        TYPE.refuses("D", "f,D", "b,c,d,p,f,l,s,D", message = { "find: -type D is not supported" }),
    )

    @Test
    fun `--type refuses a letter given twice`() = parity.checkValues(
        TYPE.refuses("f,f", "f,l,f", message = { "find: Duplicate file type 'f' in the argument list to -type." }),
    )

    @Test
    fun `a depth refuses a sign and non-ASCII digits`() = parity.checkValues(
        // `find . -maxdepth +1`, `-maxdepth -0` and `-maxdepth ١`: "Expected a positive decimal integer argument".
        DEPTHS.flatMap { it.refuses("+1", "-0", "١", "١٢", "１２", message = notPositiveDecimal(it.long)) },
    )

    @Test
    fun `-O refuses a plus sign and non-ASCII digits`() = parity.checkValues(
        optimiseRefuses(
            "+1" to "find: Please specify a decimal number immediately after -O",
            "١" to "find: Please specify a decimal number immediately after -O",
            "１" to "find: Please specify a decimal number immediately after -O",
        ),
    )

    @Test
    fun `-D refuses a list with no key`() = parity.checkValues(
        // `find -D , .`: "Empty argument to the -D option.", as for "" and ",,".
        listOf("", ",", ",,").map { refusedLine("-D", it, ".", because = "find: Empty argument to the -D option.") },
    )

    @Test
    fun `-D help binds the help key whose action prints the debug keys`() = parity.checkValues(
        // `find -D help .` and `find -D tree,help .` print "Valid arguments for -D:" and the keys, and exit 0
        // without searching. find's own parser reads the list as any other and its action prints the keys, so
        // the line binds and the fixture's action does the printing.
        listOf(
            boundLine("-D", "help", ".", expected = AT_DOT.copy(debug = listOf("help"))),
            boundLine("-D", "tree,help", ".", expected = AT_DOT.copy(debug = listOf("tree", "help"))),
        ),
    )

    @Test
    fun `-D given twice keeps the keys of both`() = parity.checkValues(
        // `find -D tree -D search . -maxdepth 0` prints both the predicate list and the search trace.
        listOf(boundLine("-D", "tree", "-D", "search", ".", expected = AT_DOT.copy(debug = listOf("tree", "search")))),
    )

    @Test
    fun `--printf refuses a lone percent at the end`() = parity.checkValues(
        PRINTF.refuses("%", "%p%", message = { "find: error: % at end of format string" }),
    )

    @Test
    fun `--user and --group refuse an empty name`() = parity.checkValues(
        // `find . -user ''` is "invalid user name or UID argument to -user: ''", on any password database.
        USER.refuses("", message = { "find: invalid user name or UID argument to -user: ''" }) +
            GROUP.refuses("", message = { "find: invalid group name or GID argument to -group: ''" }),
    )
}

/**
 * The typed find values the fixture does not read as find does, each kept as a record of the owner's decision.
 */
class FindConverterTargetsTest {

    private val parity = ParitySuite(findCli())

    /** A record, not a target: find compiles the pattern while parsing, with an engine the fixture lacks. */
    @Test
    @Ignore("Will not do (todo 72): Kotlin has no findutils regex engine to compile the pattern with")
    fun `--regex refuses a pattern find cannot compile`() = parity.checkValues(
        // `find . -regex '['` is "failed to compile regular expression '[': Invalid regular expression" and
        // `-regex '\'` is "Trailing backslash", both while parsing.
        listOf("[", "\\").flatMap { pattern ->
            spellings("--regex", pattern).map { argv ->
                ConverterCell(argv, Reading.Refused, "find: failed to compile regular expression '$pattern'")
            }
        },
    )
}
