package com.fromwau.klap.fixture.find

import com.fromwau.kern.result.Ok
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Builtin
import com.fromwau.klap.CommandBuilder
import com.fromwau.klap.Flag
import com.fromwau.klap.Help
import com.fromwau.klap.Opt
import com.fromwau.klap.ShortOptions
import com.fromwau.klap.TypedCli
import com.fromwau.klap.ValueScope
import com.fromwau.klap.Version
import com.fromwau.klap.cliOf
import com.fromwau.klap.io.CliPath
import com.fromwau.klap.io.path
import com.fromwau.klap.projection

/**
 * find(1) as a klap surface study.
 *
 * find's argv is an expression grammar (predicates joined by `-a`/`-o`/`!`/parentheses), while klap
 * binds a fixed, unordered set of named holders. So this file reproduces the option-shaped subset
 * natively (the pre-path switches `-P -L -H`, `-D`, `-O`) and transliterates the predicates to `--`
 * spellings, which keeps each predicate's arity and value grammar but drops boolean structure.
 * Everywhere klap's surface diverges from real find, the comment is marked `KLAP-GAP:`.
 */

// find's value grammars, each read as findutils reads it while parsing the line. `.map { }` turns a thrown
// exception into a clean BadValue, so each grammar is a plain function named at its declaration.
//
// The result types are public only because the inputs holder at the foot of this file names them; nothing
// else about them is API.

/** `-mtime +7` / `-mtime -7` / `-mtime 7`: the leading sign is a COMPARISON, and the count a real number. */
public data class FindCount(val comparison: Char, val value: Double)

/** `-links +2`, `-inum 5`: a comparison and a count up to 2^64-1. */
public data class FindNumber(val comparison: Char, val value: ULong)

/** `-size +1M`: comparison, magnitude, and a case-SENSITIVE unit suffix (`b c w k M G`). */
public data class FindSize(val comparison: Char, val value: ULong, val unit: Char)

/** `-perm 644` (exact) / `-perm -644` (all bits) / `-perm /644` (any bit). */
public data class FindPerm(val match: Char, val mode: String)

/** `-newerac FILE`, `-newermt DATE`: which [times] compare (`ac`, `mt`, ...) and what they compare against. */
public sealed interface FindNewer {
    public val times: String

    /** `-newerXY FILE`, Y of `a`, `c` or `m`: a time of the [reference] file. */
    public data class ThanFile(override val times: String, val reference: CliPath) : FindNewer

    /** `-newerXt DATE`: the [date] itself, free text find reads as a time. */
    public data class ThanDate(override val times: String, val date: String) : FindNewer
}

/** The comparison a leading `+` or `-` names, `=` without one, and the rest of the word. */
private fun comparisonOf(raw: String): Pair<Char, String> {
    val first = raw.firstOrNull()
    return if (first == '+' || first == '-') first to raw.drop(1) else '=' to raw
}

/** C's isspace in the C locale: what strtod and strtoumax skip before a number. */
private fun Char.isCSpace(): Boolean = this == ' ' || this in '\t'..'\r'

private fun String.isAsciiDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

/** [text] as xstrtoumax reads it: blanks, one optional plus, then ASCII digits up to 2^64-1. */
private fun unsignedCount(text: String): ULong? {
    val digits = text.dropWhile { it.isCSpace() }.removePrefix("+")
    // toULongOrNull alone would also take any Unicode digit.
    return if (digits.isAsciiDigits()) digits.toULongOrNull() else null
}

private val STRTOD_DECIMAL = Regex("[+-]?([0-9]+[.]?[0-9]*|[.][0-9]+)([eE][+-]?[0-9]+)?")
private val STRTOD_HEX = Regex("[+-]?0[xX]([0-9a-fA-F]+[.]?[0-9a-fA-F]*|[.][0-9a-fA-F]+)([pP][+-]?[0-9]+)?")
private val STRTOD_INFINITY = Regex("[+-]?(inf|infinity)", RegexOption.IGNORE_CASE)

/** [text] as strtod reads it, the whole word consumed: a fraction, an exponent, a hex float or infinity. */
private fun strtod(text: String): Double? {
    val number = text.dropWhile { it.isCSpace() }
    return when {
        STRTOD_INFINITY.matches(number) ->
            if (number.startsWith('-')) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        STRTOD_DECIMAL.matches(number) -> number.toDouble().takeIf { it.isFinite() }
        // The JVM reads a hex float only with its binary exponent.
        STRTOD_HEX.matches(number) ->
            (if ('p' in number || 'P' in number) number else "${number}p0").toDouble().takeIf { it.isFinite() }
        else -> null
    }
}

private fun findCount(raw: String): FindCount {
    val (comparison, rest) = comparisonOf(raw)
    val value = strtod(rest) ?: throw IllegalArgumentException("expected [+-]N, got '$raw'")
    return FindCount(comparison, value)
}

private fun findNumber(raw: String): FindNumber {
    val (comparison, rest) = comparisonOf(raw)
    val value = unsignedCount(rest) ?: throw IllegalArgumentException("expected [+-]N, got '$raw'")
    return FindNumber(comparison, value)
}

private const val FIND_SIZE_UNITS = "bcwkMG"

private fun findSize(raw: String): FindSize {
    val (comparison, rest) = comparisonOf(raw)
    val unit = rest.lastOrNull()?.takeIf { it in FIND_SIZE_UNITS }
    val digits = if (unit == null) rest else rest.dropLast(1)
    val value = unsignedCount(digits)
        ?: throw IllegalArgumentException("expected [+-]N[$FIND_SIZE_UNITS], got '$raw'")
    return FindSize(comparison, value, unit ?: 'b')
}

/** `-maxdepth 3`: ASCII digits only, no sign and no blank, up to INT_MAX. */
private fun findDepth(raw: String): Int =
    raw.takeIf { it.isAsciiDigits() }?.toIntOrNull()
        ?: throw IllegalArgumentException("expected a non-negative decimal integer, got '$raw'")

/** `-O3`: ASCII digits only, up to 65535, the level find's own parser takes. */
private fun findOptimisationLevel(raw: String): Int =
    raw.takeIf { it.isAsciiDigits() }?.toIntOrNull()?.takeIf { it <= 65535 }
        ?: throw IllegalArgumentException("expected a decimal level up to 65535, got '$raw'")

private const val MODE_BITS = 0b111_111_111_111

/** Octal [digits] that fit a file mode, 07777 at most. */
private fun isOctalMode(digits: String): Boolean {
    var mode = 0
    for (digit in digits) {
        if (digit !in '0'..'7') return false
        mode = mode * 8 + (digit - '0')
        if (mode > MODE_BITS) return false
    }
    return digits.isNotEmpty()
}

/**
 * Whether gnulib's mode_compile takes [mode]: octal, or comma-separated clauses of `[ugoa]*` and one or more
 * operators, each followed by permission letters, one of `u g o`, or (in a clause naming no one) octal digits
 * that end the clause.
 */
private fun compilesAsMode(mode: String): Boolean {
    if (mode.firstOrNull() in '0'..'7') return isOctalMode(mode)
    var at = 0
    while (true) {
        val clause = at
        while (at < mode.length && mode[at] in "ugoa") at++
        val namesWho = at > clause
        if (at == mode.length || mode[at] !in "=+-") return false
        while (at < mode.length && mode[at] in "=+-") {
            at++
            when {
                at < mode.length && mode[at] in '0'..'7' -> {
                    if (namesWho) return false
                    val end = (at until mode.length).firstOrNull { mode[it] !in '0'..'7' } ?: mode.length
                    if (!isOctalMode(mode.substring(at, end))) return false
                    at = end
                    if (at < mode.length && mode[at] != ',') return false
                }
                at < mode.length && mode[at] in "ugo" -> at++
                else -> while (at < mode.length && mode[at] in "rwxXst") at++
            }
        }
        if (at == mode.length) return true
        if (mode[at] != ',') return false
        at++
    }
}

private fun findPerm(raw: String): FindPerm {
    val match = if (raw.firstOrNull() == '-' || raw.firstOrNull() == '/') raw.first() else '='
    val mode = if (match == '=') raw else raw.drop(1)
    // findutils dropped the old `-perm +MODE` and refuses it, though `+644` is a mode mode_compile takes.
    val oldPlusForm = raw.length > 1 && raw[0] == '+' && raw[1] in '0'..'7'
    if (oldPlusForm || !compilesAsMode(mode)) throw IllegalArgumentException("invalid file mode '$raw'")
    return FindPerm(match, mode)
}

/** Whether `-printf` [format] ends in a `%` that starts no directive, which find refuses while parsing. */
private fun endsInLonePercent(format: String): Boolean {
    var at = 0
    while (at < format.length) {
        when (format[at]) {
            '\\' -> {
                // `\c` ends the format: nothing after it is read.
                if (format.getOrNull(at + 1) == 'c') return false
                at += 2
            }
            '%' -> {
                if (at + 1 == format.length) return true
                at++
                while (at < format.length && format[at] in "#-+ 0123456789.") at++
                val directive = format.getOrNull(at) ?: return false
                at += if (directive in "ABCT") 2 else 1
            }
            else -> at++
        }
    }
    return false
}

private const val FIND_TYPE_LETTERS = "bcdpfls"

/**
 * `-type f`, `-type d`, and GNU's comma list `-type f,l`, each letter once. `D` (a Solaris door) is refused,
 * as find refuses it on a system without doors.
 */
private fun isFindTypeList(raw: String): Boolean {
    val letters = raw.split(",")
    return letters.all { it.length == 1 && it[0] in FIND_TYPE_LETTERS } && letters.distinct().size == letters.size
}

/** A `-type`-shaped test, completing the letters a list can go on with after its last comma, each once. */
private fun CommandBuilder.typeList(
    long: String,
    help: String,
): Opt<List<String>> = option(long = long, help = help)
    .validate("must be one or more of $FIND_TYPE_LETTERS, comma-separated, each once") { isFindTypeList(it) }
    .multiple()
    .completeWith {
        val done = current.substringBeforeLast(',', missingDelimiterValue = "")
        val head = if (current.contains(',')) "$done," else ""
        if (head.isNotEmpty() && !isFindTypeList(done)) return@completeWith
        val named = done.split(',')
        FIND_TYPE_LETTERS.map { it.toString() }.filterNot { it in named }.forEach { candidate(head + it) }
    }

/** `find -D help`, in its order. */
private val DEBUG_KEYS: List<String> = listOf("exec", "opt", "rates", "search", "stat", "time", "tree", "all", "help")

/** `-D tree,search`: the keys of one list, an empty one refused as find refuses it. */
private fun debugKeys(raw: String): List<String> =
    raw.split(",").filter { it.isNotEmpty() }.ifEmpty { throw IllegalArgumentException("empty argument to -D") }

/** find's regular expression types, each typed as its [cli] name, in the order `find . -regextype help` lists. */
public enum class RegexType(public val cli: String) {
    FindutilsDefault("findutils-default"),
    Awk("awk"),
    Ed("ed"),
    Egrep("egrep"),
    Emacs("emacs"),
    GnuAwk("gnu-awk"),
    Grep("grep"),
    PosixAwk("posix-awk"),
    PosixBasic("posix-basic"),
    PosixEgrep("posix-egrep"),
    PosixExtended("posix-extended"),
    PosixMinimalBasic("posix-minimal-basic"),
    Sed("sed"),
}

/** The times `-newerXY` compares: X of the file, Y of the reference, `t` naming a date instead of a file. */
private val NEWER_TIMES: List<String> = "acm".flatMap { x -> "acmt".map { y -> "$x$y" } }

private val NEWER_TIME_WORDS: Map<Char, String> = mapOf(
    'a' to "last accessed",
    'c' to "status last changed",
    'm' to "last modified",
)

private fun newerHelp(times: String): String {
    val reference = if (times[1] == 't') "DATE" else "FILE was ${NEWER_TIME_WORDS.getValue(times[1])}"
    return "${NEWER_TIME_WORDS.getValue(times[0])} more recently than $reference"
}

/**
 * The `find` command-line surface, as far as klap's model reaches.
 *
 * Shape: a single-command root (its own `action`, no user subcommands), with none of klap's built-ins but the
 * `--help` and `--version` find has.
 */
public fun findCli(): TypedCli<FindInputs> = cliOf("find") {
    // find matches every predicate whole: `find . -maxdep 1` is "unknown predicate `-maxdep'".
    abbreviation = Abbreviation.None
    builtins {
        help = Help.ByCommand(description = "display this help and exit")
        json = Builtin.Off
        color = Builtin.Off
        completion = Builtin.Off
        docs = Builtin.Off
    }
    help = "Search for files in a directory hierarchy"
    version = Version.Of("4.11.0", description = "output version information and exit", text = FIND_VERSION_TEXT)
    shortOptions = ShortOptions.Alone
    epilogue =
        "This is a klap surface study, not findutils. Predicates are spelled with two dashes; " +
            "pass a real find expression verbatim after `--` and parse it yourself."

    example("find . --name '*.kt' --type f --print", "the conjunction-only subset klap can bind")
    example("find . -- -name '*.kt' -o -size +1M -print", "the raw-token escape hatch, `--` mandatory")
    example("find -L -O3 /srv --maxdepth 3 --print0", "the only tokens spelled exactly as find spells them")
    example("find --type f", "no starting point: the operand list binds empty and the action applies `.`")

    // KLAP-GAP: find spells every predicate with a single dash and a multi-character name (`-name`,
    // `-type`, `-mtime`, ...), a shape klap refuses for an option, so every predicate below is respelled
    // with two dashes instead.

    // The pre-path switches `-P -L -H -D -Olevel` are single-dash single-char, klap's short form and the only
    // part of find that reproduces as find spells it. find has no long name for any of them.
    //
    // `shortOptions = ShortOptions.Alone` keeps each switch a word of its own, so `-LP` and `-Dtree` are refused.
    //
    // KLAP-GAP: klap reads the switches anywhere on the line, where find takes them only before the first starting
    // point.
    val physical = flag(short = 'P', help = "never follow symbolic links (default)")
    val logical = flag(short = 'L', help = "follow symbolic links")
    val followArgs = flag(short = 'H', help = "follow symlinks only for command-line arguments")
    lastWins(physical, logical, followArgs)

    // `-D tree,search`, its list in the next word; each -D adds its keys to the earlier ones.
    val debug = option(short = 'D', help = "print diagnostics (comma-separated)")
        .map(::debugKeys)
        .multiple()
        .completeWith {
            val head = if (current.contains(',')) current.substringBeforeLast(',') + "," else ""
            DEBUG_KEYS.forEach { candidate(head + it) }
        }

    // `-O3`, the level glued: find refuses a bare `-O` and `-O 3` alike.
    val optimise = option(short = 'O', help = "query optimisation level")
        .gluedValue()
        .map(::findOptimisationLevel)
        .multiple()
        .completeWith { (0..3).forEach { candidate("$it") } }

    // find's "global options" (-maxdepth/-mindepth/-depth/-xdev/...) are the one family that
    // genuinely is position-independent in find, so klap's unordered binding is the right model for
    // them.
    val maxDepth: Opt<Int?>
    val minDepth: Opt<Int?>
    val depth: Flag
    val xdev: Flag
    val mount: Flag
    val noleaf: Flag
    val ignoreReaddirRace: Flag
    val noignoreReaddirRace: Flag
    val files0From: Opt<CliPath?>
    group("Traversal options (find's own position-independent set)") {
        maxDepth = option(long = "maxdepth", help = "descend at most N directory levels").map(::findDepth)
        minDepth = option(long = "mindepth", help = "do not apply any tests at levels less than N").map(::findDepth)

        // `-d` is find's synonym for `-depth`.
        depth = flag(long = listOf("depth", "d"), help = "process a directory's contents before the directory itself")
        xdev = flag(long = "xdev", help = "don't descend directories on other filesystems")
        mount = flag(long = "mount", help = "ignore files on other devices")

        noleaf = flag(long = "noleaf", help = "do not optimise assuming Unix directory link counts")
        ignoreReaddirRace = flag(
            long = "ignore_readdir_race",
            help = "do not error on files removed during the scan",
        )
        noignoreReaddirRace = flag(
            long = "noignore_readdir_race",
            help = "turn off the effect of --ignore_readdir_race",
        )
        lastWins(ignoreReaddirRace, noignoreReaddirRace)

        // `find -files0-from ''`: "cannot open '' for reading".
        files0From = option(long = "files0-from", help = "read NUL-separated starting points from FILE")
            .path()
            .validate("must name a file") { it.text.isNotEmpty() }
    }

    // KLAP-GAP (order): find's positional options act on the tests after them (`-daystart` on the later time
    // tests, `-follow` on the later -newer, `-regextype` on the later -regex); klap binds them unordered.
    val regextype = option(long = "regextype", help = "syntax used by --regex").enum<RegexType>(spelling = { it.cli })
    val daystart = flag(long = "daystart", help = "measure times from the beginning of today")
    val follow = flag(long = "follow", help = "deprecated; dereference symbolic links, as -L does")
    val warn = flag(long = "warn", help = "turn warning messages on")
    val nowarn = flag(long = "nowarn", help = "turn warning messages off")
    lastWins(warn, nowarn)

    // Tests: arity and value grammar reproduce; spelling and position do not. Each binds
    // independently, so the tree below expresses exactly one implicit conjunction of predicates and
    // nothing else; see the closing KLAP-GAP block.
    //
    // Every test with a value is `.multiple()`, as find ANDs a test given twice (`-type f -type d` matches
    // nothing), but the interleaving of `--name a --size +1M --name b` across tests is not recoverable.
    fun pattern(long: String, help: String) = option(long = long, help = help).multiple()

    val namePattern = pattern("name", "base name matches shell pattern")
    val inamePattern = pattern("iname", "like --name, case-insensitive")
    val pathPattern = pattern("path", "whole path matches shell pattern")
    val ipathPattern = pattern("ipath", "like --path, case-insensitive")
    val wholenamePattern = pattern("wholename", "whole path matches shell pattern, as --path does")
    val iwholenamePattern = pattern("iwholename", "like --wholename, case-insensitive")
    // find compiles the pattern while parsing (`-regex '['` is refused); the fixture has no findutils regex
    // engine to compile it with, so any word binds.
    val regexPattern = pattern("regex", "whole path matches regular expression")
    val iregexPattern = pattern("iregex", "like --regex, case-insensitive")
    val lnamePattern = pattern("lname", "symbolic link's contents match shell pattern")
    val ilnamePattern = pattern("ilname", "like --lname, case-insensitive")
    val fstype = pattern("fstype", "file is on a filesystem of TYPE")

    val typeTest = typeList("type", "file type: one or more of b c d p f l s (comma-separated)")
    val xtype = typeList("xtype", "like --type, for the file a symbolic link points to")

    // `-size +1M` / `-size -100c` / `-size 3`. A `-`-led token binds in the space form, because klap reads a
    // dash-led word as the value a value-taking option is waiting for.
    val sizeTest = option(long = "size", help = "file uses N units of space ([+-]N[$FIND_SIZE_UNITS])")
        .map(::findSize)
        .multiple()

    fun count(long: String, help: String) = option(long = long, help = help).map(::findCount).multiple()

    val mtime = count("mtime", "data last modified [+-]N*24h ago")
    val mmin = count("mmin", "data last modified [+-]N minutes ago")
    val ctime = count("ctime", "status last changed [+-]N*24h ago")
    val cmin = count("cmin", "status last changed [+-]N minutes ago")
    val atime = count("atime", "last accessed [+-]N*24h ago")
    val amin = count("amin", "last accessed [+-]N minutes ago")
    val used = count("used", "last accessed [+-]N days after its status last changed")

    fun number(long: String, help: String) = option(long = long, help = help).map(::findNumber).multiple()

    val linkCount = number("links", "file has [+-]N hard links")
    val inum = number("inum", "file has inode number [+-]N")
    val uid = number("uid", "file's numeric user ID is [+-]N")
    val gid = number("gid", "file's numeric group ID is [+-]N")

    val permTest = option(long = "perm", help = "permission bits ([-/]MODE)").map(::findPerm).multiple()

    // `find . -newer ''` is "'': No such file or directory", refused while parsing, as every reference file is.
    fun reference(long: String, help: String) = option(long = long, help = help)
        .path()
        .validate("must name a file") { it.text.isNotEmpty() }
        .multiple()

    val newerThan = reference("newer", "modified more recently than FILE")
    val anewer = reference("anewer", "accessed more recently than FILE was modified")
    val cnewer = reference("cnewer", "status changed more recently than FILE was modified")
    val samefile = reference("samefile", "file refers to the same inode as FILE")
    // `-newermt ''` runs, a date find reads as now, so a date is free text; `-newerBt` and the other birth-time
    // forms are left out, as this system's find refuses them. One list keeps the forms in find's order for help.
    val newer = NEWER_TIMES.map<String, ValueScope.() -> List<FindNewer>> { times ->
        if (times[1] == 't') {
            val dates = option(long = "newer$times", help = newerHelp(times)).multiple()
            return@map { dates().map { FindNewer.ThanDate(times, it) } }
        }
        val files = reference("newer$times", newerHelp(times))
        return@map { files().map { FindNewer.ThanFile(times, it) } }
    }

    // `find . -user ''` is "invalid user name or UID argument", whatever the password database holds.
    val ownerUser = option(long = "user", help = "owned by USER")
        .validate("must name a user") { it.isNotEmpty() }
        .multiple()
    val ownerGroup = option(long = "group", help = "owned by GROUP")
        .validate("must name a group") { it.isNotEmpty() }
        .multiple()

    val emptyTest = flag(long = "empty", help = "file or directory is empty")
    val readableTest = flag(long = "readable", help = "readable by the current user")
    val writableTest = flag(long = "writable", help = "writable by the current user")
    val executableTest = flag(long = "executable", help = "executable/searchable by the current user")
    val nouserTest = flag(long = "nouser", help = "no user corresponds to the file's numeric uid")
    val nogroupTest = flag(long = "nogroup", help = "no group corresponds to the file's numeric gid")
    val trueTest = flag(long = "true", help = "always true")
    val falseTest = flag(long = "false", help = "always false")

    // KLAP-GAP (order): in find, an action's position is its meaning (`-print` fires per match,
    // `-prune` stops descent where it is reached, `-quit` stops immediately). klap binds actions as
    // unordered booleans, so all positional meaning is lost.
    val printAction = flag(long = "print", help = "print the full file name, then a newline")
    val print0Action = flag(long = "print0", help = "print the full file name, then a NUL")
    val printfAction = option(long = "printf", help = "print FORMAT, interpreting `%` directives")
        .validate("must not end in a lone %") { !endsInLonePercent(it) }
        .multiple()
    val fprintAction = reference("fprint", "print the full file name into FILE")
    val fprint0Action = reference("fprint0", "like --fprint, ending each name with a NUL")
    val flsAction = reference("fls", "list the file in `ls -dils` format into FILE")
    val lsAction = flag(long = "ls", help = "list the file in `ls -dils` format")
    val deleteAction = flag(long = "delete", help = "delete the file")
    val pruneAction = flag(long = "prune", help = "do not descend into this directory")
    val quitAction = flag(long = "quit", help = "exit immediately")

    // KLAP-GAP (terminator-delimited variadic): real `-exec COMMAND [ARG...] ;` (or `+`) swallows every
    // remaining token until the sentinel, which no klap option can hold, so the closest expressible thing is
    // one word per occurrence with no terminator: `-exec rm -rf {} ;` becomes
    // `--exec rm --exec=-rf --exec '{}'`. The same goes for `-execdir`, `-ok` and `-okdir`; `-fprintf FILE
    // FORMAT` takes two words, which no klap option holds either, so it is left out.
    val execAction = option(long = "exec", help = "one word of the command to run; repeat for each word")
        .multiple()
    val execdirAction = option(
        long = "execdir",
        help = "one word of the command to run from the file's directory; repeat for each word",
    ).multiple()
    val okAction = option(long = "ok", help = "like --exec, asking first").multiple()
    val okdirAction = option(long = "okdir", help = "like --execdir, asking first").multiple()

    // klap allows at most one variadic argument, so find's PATH... list and its post-`--` raw expression
    // share this one list, split by hand in the action. A literal `--` ends option parsing and hands the rest
    // over verbatim and in order, so `find . -- -name '*.kt' -o -size +1M -print` is recoverable by a
    // hand-written parser, at the cost of that tail being opted out of help, completion, docs and typed errors.
    // `optionsEndAtFirstOperand` could drop the mandatory `--` real find never wants, but it would end the
    // predicates at the first starting point too. An empty list lets bare `find` default to `.` in the action.
    val operand = argument(
        "operand",
        "starting points, then (after a literal `--`) a raw find expression",
    ).file().multiple()

    // `find . -files0-from a`: "file operands cannot be combined with -files0-from".
    atMostOne(operand, files0From)

    // KLAP-GAP (the whole expression grammar): find's boolean operators (`-o`/`-a`), grouping
    // (`\( \)`) and negation (`!`/`-not`) have no representation in klap's unordered-holder model, so
    // the precise boundary is a single flat conjunction of two-dash predicates with no operators, no
    // grouping and no negation. Everything above is that conjunction, ANDed together in the action
    // below as an accidental stand-in for find's implicit `-a`.

    action<String>(human = { it }) {
        // find's own code: `-D help` lists the keys and exits before reading the rest of the line.
        if ("help" in debug().flatten()) return@action Ok(DEBUG_HELP_TEXT)

        val (boundStartingPoints, rawExpression) = splitOperands(operand())
        val startingPoints = boundStartingPoints.map { it.text }.ifEmpty { listOf(".") }

        // `lastWins` already collapsed the set to its winner, so this reads one true flag at most and
        // needs no precedence of its own: the rule find documents is the rule the handles carry.
        val symlinkMode = when {
            logical() -> "-L"
            followArgs() -> "-H"
            else -> "-P"
        }

        val tests = listOf(
            namePattern(), inamePattern(), pathPattern(), ipathPattern(), wholenamePattern(), iwholenamePattern(),
            regexPattern(), iregexPattern(), lnamePattern(), ilnamePattern(), fstype(), typeTest(), xtype(),
            sizeTest(), mtime(), mmin(), ctime(), cmin(), atime(), amin(), used(), linkCount(), inum(), uid(),
            gid(), permTest(), newerThan(), anewer(), cnewer(), samefile(), ownerUser(), ownerGroup(),
        ).sumOf { it.size } +
            newer.sumOf { it().size } +
            listOf(
                emptyTest(), readableTest(), writableTest(), executableTest(), nouserTest(), nogroupTest(),
                trueTest(), falseTest(),
            ).count { it }

        val actions = listOf(
            printAction(), print0Action(), lsAction(), deleteAction(), pruneAction(), quitAction(),
        ).count { it } +
            listOf(printfAction(), fprintAction(), fprint0Action(), flsAction()).sumOf { it.size } +
            listOf(execAction(), execdirAction(), okAction(), okdirAction()).count { it.isNotEmpty() }

        Ok(
            "would walk ${startingPoints.joinToString(" ")} " +
                "(symlink mode: $symlinkMode, maxdepth: ${maxDepth() ?: "unlimited"}, " +
                "debug keys: ${debug().flatten().size}, -O${optimise().lastOrNull() ?: 1}, " +
                "$tests tests, $actions actions, " +
                "${rawExpression.size} unparsed expression tokens)",
        )
    }

    projection {
        val (startingPoints, expression) = splitOperands(operand())
        FindInputs(
            physical = physical(),
            logical = logical(),
            followArgs = followArgs(),
            debug = debug().flatten().ifEmpty { null },
            optimise = optimise().lastOrNull(),
            maxDepth = maxDepth(),
            minDepth = minDepth(),
            depth = depth(),
            xdev = xdev(),
            mount = mount(),
            noleaf = noleaf(),
            ignoreReaddirRace = ignoreReaddirRace(),
            noignoreReaddirRace = noignoreReaddirRace(),
            regextype = regextype(),
            files0From = files0From(),
            daystart = daystart(),
            follow = follow(),
            warn = warn(),
            nowarn = nowarn(),
            namePattern = namePattern(),
            inamePattern = inamePattern(),
            pathPattern = pathPattern(),
            ipathPattern = ipathPattern(),
            wholenamePattern = wholenamePattern(),
            iwholenamePattern = iwholenamePattern(),
            regexPattern = regexPattern(),
            iregexPattern = iregexPattern(),
            lnamePattern = lnamePattern(),
            ilnamePattern = ilnamePattern(),
            fstype = fstype(),
            typeTest = typeTest(),
            xtype = xtype(),
            sizeTest = sizeTest(),
            mtime = mtime(),
            mmin = mmin(),
            ctime = ctime(),
            cmin = cmin(),
            atime = atime(),
            amin = amin(),
            used = used(),
            linkCount = linkCount(),
            inum = inum(),
            uid = uid(),
            gid = gid(),
            permTest = permTest(),
            newerThan = newerThan(),
            anewer = anewer(),
            cnewer = cnewer(),
            samefile = samefile(),
            newerXY = newer.flatMap { it() },
            ownerUser = ownerUser(),
            ownerGroup = ownerGroup(),
            emptyTest = emptyTest(),
            readableTest = readableTest(),
            writableTest = writableTest(),
            executableTest = executableTest(),
            nouserTest = nouserTest(),
            nogroupTest = nogroupTest(),
            trueTest = trueTest(),
            falseTest = falseTest(),
            printAction = printAction(),
            print0Action = print0Action(),
            printfAction = printfAction(),
            fprintAction = fprintAction(),
            fprint0Action = fprint0Action(),
            flsAction = flsAction(),
            lsAction = lsAction(),
            deleteAction = deleteAction(),
            pruneAction = pruneAction(),
            quitAction = quitAction(),
            execAction = execAction(),
            execdirAction = execdirAction(),
            okAction = okAction(),
            okdirAction = okdirAction(),
            startingPoints = startingPoints,
            expression = expression,
        )
    }
}

/**
 * What one `find` line binds, as values rather than as the handles that read them.
 *
 * Being a plain data class is the point: a parity case asserts a whole invocation with one `assertEquals`
 * against a `copy()` of [NOTHING_BOUND], so every field it does *not* name is pinned to its default too.
 */
public data class FindInputs(
    val physical: Boolean,
    val logical: Boolean,
    val followArgs: Boolean,
    val debug: List<String>?,
    val optimise: Int?,
    val maxDepth: Int?,
    val minDepth: Int?,
    val depth: Boolean,
    val xdev: Boolean,
    val mount: Boolean,
    val noleaf: Boolean,
    val ignoreReaddirRace: Boolean,
    val noignoreReaddirRace: Boolean,
    val regextype: RegexType?,
    val files0From: CliPath?,
    val daystart: Boolean,
    val follow: Boolean,
    val warn: Boolean,
    val nowarn: Boolean,
    val namePattern: List<String>,
    val inamePattern: List<String>,
    val pathPattern: List<String>,
    val ipathPattern: List<String>,
    val wholenamePattern: List<String>,
    val iwholenamePattern: List<String>,
    val regexPattern: List<String>,
    val iregexPattern: List<String>,
    val lnamePattern: List<String>,
    val ilnamePattern: List<String>,
    val fstype: List<String>,
    val typeTest: List<String>,
    val xtype: List<String>,
    val sizeTest: List<FindSize>,
    val mtime: List<FindCount>,
    val mmin: List<FindCount>,
    val ctime: List<FindCount>,
    val cmin: List<FindCount>,
    val atime: List<FindCount>,
    val amin: List<FindCount>,
    val used: List<FindCount>,
    val linkCount: List<FindNumber>,
    val inum: List<FindNumber>,
    val uid: List<FindNumber>,
    val gid: List<FindNumber>,
    val permTest: List<FindPerm>,
    val newerThan: List<CliPath>,
    val anewer: List<CliPath>,
    val cnewer: List<CliPath>,
    val samefile: List<CliPath>,
    val newerXY: List<FindNewer>,
    val ownerUser: List<String>,
    val ownerGroup: List<String>,
    val emptyTest: Boolean,
    val readableTest: Boolean,
    val writableTest: Boolean,
    val executableTest: Boolean,
    val nouserTest: Boolean,
    val nogroupTest: Boolean,
    val trueTest: Boolean,
    val falseTest: Boolean,
    val printAction: Boolean,
    val print0Action: Boolean,
    val printfAction: List<String>,
    val fprintAction: List<CliPath>,
    val fprint0Action: List<CliPath>,
    val flsAction: List<CliPath>,
    val lsAction: Boolean,
    val deleteAction: Boolean,
    val pruneAction: Boolean,
    val quitAction: Boolean,
    val execAction: List<String>,
    val execdirAction: List<String>,
    val okAction: List<String>,
    val okdirAction: List<String>,
    val startingPoints: List<CliPath>,
    val expression: List<String>,
)

/**
 * find's operands as find reads them: its starting points, then from the first word that opens an expression (one
 * led by a dash but a lone `-`, or `(` or `!`) the expression. klap hands both to one variadic slot, so the fixture
 * splits them here. `find link/` walks the directory, `find link` names the link alone, so a starting point keeps
 * its text as typed.
 */
internal fun splitOperands(words: List<String>): Pair<List<CliPath>, List<String>> {
    val points = words.takeWhile { !(it.startsWith("-") && it != "-") && it != "(" && it != "!" }
    return points.map(::CliPath) to words.drop(points.size)
}

/** `find` with no arguments at all: every field at the default its declaration gives it. */
public val NOTHING_BOUND: FindInputs = FindInputs(
    physical = false,
    logical = false,
    followArgs = false,
    debug = null,
    optimise = null,
    maxDepth = null,
    minDepth = null,
    depth = false,
    xdev = false,
    mount = false,
    noleaf = false,
    ignoreReaddirRace = false,
    noignoreReaddirRace = false,
    regextype = null,
    files0From = null,
    daystart = false,
    follow = false,
    warn = false,
    nowarn = false,
    namePattern = emptyList(),
    inamePattern = emptyList(),
    pathPattern = emptyList(),
    ipathPattern = emptyList(),
    wholenamePattern = emptyList(),
    iwholenamePattern = emptyList(),
    regexPattern = emptyList(),
    iregexPattern = emptyList(),
    lnamePattern = emptyList(),
    ilnamePattern = emptyList(),
    fstype = emptyList(),
    typeTest = emptyList(),
    xtype = emptyList(),
    sizeTest = emptyList(),
    mtime = emptyList(),
    mmin = emptyList(),
    ctime = emptyList(),
    cmin = emptyList(),
    atime = emptyList(),
    amin = emptyList(),
    used = emptyList(),
    linkCount = emptyList(),
    inum = emptyList(),
    uid = emptyList(),
    gid = emptyList(),
    permTest = emptyList(),
    newerThan = emptyList(),
    anewer = emptyList(),
    cnewer = emptyList(),
    samefile = emptyList(),
    newerXY = emptyList(),
    ownerUser = emptyList(),
    ownerGroup = emptyList(),
    emptyTest = false,
    readableTest = false,
    writableTest = false,
    executableTest = false,
    nouserTest = false,
    nogroupTest = false,
    trueTest = false,
    falseTest = false,
    printAction = false,
    print0Action = false,
    printfAction = emptyList(),
    fprintAction = emptyList(),
    fprint0Action = emptyList(),
    flsAction = emptyList(),
    lsAction = false,
    deleteAction = false,
    pruneAction = false,
    quitAction = false,
    execAction = emptyList(),
    execdirAction = emptyList(),
    okAction = emptyList(),
    okdirAction = emptyList(),
    startingPoints = emptyList(),
    expression = emptyList(),
)

/** What `find -D help` prints. */
internal val DEBUG_HELP_TEXT: String = """Valid arguments for -D:
exec       Show diagnostic information relating to -exec, -execdir, -ok and -okdir
opt        Show diagnostic information relating to optimisation
rates      Indicate how often each predicate succeeded
search     Navigate the directory tree verbosely
stat       Trace calls to stat(2) and lstat(2)
time       Show diagnostic information relating to time-of-day and timestamp comparisons
tree       Display the expression tree
all        Set all of the debug flags (but help)
help       Explain the various -D options"""

/**
 * What `find --version` prints for GNU findutils 4.11.0, the version installed when it was pinned
 * (2026-10-10).
 */
internal val FIND_VERSION_TEXT: String = """find (GNU findutils) 4.11.0-modified
Copyright (C) 2026 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>.
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Written by Eric B. Decker, James Youngman, Bernhard Voelker,
and Kevin Dalley.
Features enabled: D_TYPE O_NOFOLLOW(enabled) LEAF_OPTIMISATION FTS(FTS_CWDFD) CBO(level=1)${" "}"""
