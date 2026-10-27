package com.fromwau.klap.fixture.ls

import com.fromwau.klap.io.CliPath
import kotlin.test.fail

/*
 * A table of the inputs the installed GNU ls 9.12 documents, from which the matrix tests generate their cells. HELP is
 * `ls --help` (bold and hyperlink escapes stripped), each entry's lines joined with one space and otherwise verbatim.
 * How each option reads its value is the help's own `--opt[=X]` notation, checked with real ls runs inside a throwaway
 * directory with ls's environment unset (LS_COLORS, TIME_STYLE, QUOTING_STYLE, COLUMNS, TABSIZE, BLOCK_SIZE,
 * LS_BLOCK_SIZE, POSIXLY_CORRECT); the word lists are the "Valid arguments" ls prints for a bogus value.
 */

/** How an input reads the words after its name. */
internal sealed interface Kind {
    data object Flag : Kind

    /**
     * A value: [groups] the words argmatch reads, one group per value in ls's own order (null for a pattern, a number
     * or a size), [optional] when the value goes attached only and the bare name means `always`, as `--color[=WHEN]`,
     * and [checked] false where ls takes any word and its groups are only what Tab offers (`ls --time-style=bogus a`
     * runs). [second] is another value ls accepts, for a repeat.
     */
    data class Value(
        val sample: String,
        val groups: List<List<String>>? = null,
        val optional: Boolean = false,
        val checked: Boolean = true,
        val second: String = sample + "0",
    ) : Kind {
        val words: List<String>? get() = groups?.flatten()
    }
}

/**
 * One input of real ls: its [long] name (null for a short-only flag), its [shorts], ls's own [help], and how it binds
 * into the fixture's record ([bind]). A value input's [bind] is handed the value as ls means it: the first word of its
 * argmatch group, a number, or a pattern.
 */
internal data class Input(
    val long: String?,
    val shorts: List<Char>,
    val help: String,
    val kind: Kind,
    val bind: LsInputs.(String?) -> LsInputs,
) {
    val name: String get() = long ?: "-${shorts.single()}"

    override fun toString(): String = name
}

private fun flag(
    long: String?,
    vararg shorts: Char,
    help: String,
    bind: LsInputs.() -> LsInputs,
) = Input(long, shorts.toList(), help, Kind.Flag) { bind() }

private fun value(
    long: String,
    vararg shorts: Char,
    help: String,
    kind: Kind.Value,
    bind: LsInputs.(String) -> LsInputs,
) = Input(long, shorts.toList(), help, kind) { bind(it!!) }

/** `--color`, `--classify` and `--hyperlink`'s WHEN words (`ls --color=bogus`). */
internal val WHEN_GROUPS: List<List<String>> = listOf(
    listOf("always", "yes", "force"),
    listOf("never", "no", "none"),
    listOf("auto", "tty", "if-tty"),
)

/** `--format`'s words (`ls --format=bogus`): verbose and long are one format, horizontal and across another. */
internal val FORMAT_GROUPS: List<List<String>> = listOf(
    listOf("verbose", "long"),
    listOf("commas"),
    listOf("horizontal", "across"),
    listOf("vertical"),
    listOf("single-column"),
)

internal val SORT_GROUPS: List<List<String>> =
    listOf("none", "size", "time", "version", "extension", "name", "width").map { listOf(it) }

private val TIME_GROUPS: List<List<String>> = listOf(
    listOf("atime", "access", "use"),
    listOf("ctime", "status"),
    listOf("mtime", "modification"),
    listOf("birth", "creation"),
)

// `ls -l --time-style=bogus a`: "Valid arguments are: [posix-]full-iso, [posix-]long-iso, [posix-]iso, [posix-]locale".
private val TIME_STYLE_GROUPS: List<List<String>> =
    listOf("full-iso", "long-iso", "iso", "locale").map { listOf(it) }

private val QUOTING_GROUPS: List<List<String>> = listOf(
    "literal", "shell", "shell-always", "shell-escape", "shell-escape-always", "c", "c-maybe", "escape", "locale",
    "clocale",
).map { listOf(it) }

private val INDICATOR_GROUPS: List<List<String>> =
    listOf("none", "slash", "file-type", "classify").map { listOf(it) }

/** The entry of [E] whose words, as [words] gives them, hold [word]: the value ls reads it as. */
private inline fun <reified E : Enum<E>> read(
    word: String,
    words: (E) -> List<String>,
): E = enumValues<E>().first { word in words(it) }

/** `-1` and `--zero` leave a long format as it is (`ls -l -1 a` lists long). */
private fun LsInputs.oneLine(): LsInputs = if (layout == Layout.Long) this else copy(layout = Layout.SingleColumn)

/**
 * `-c`, `-u` or `--time` sets the timestamp and, with no sort and no long format given before it, sorts by it: `ls -c`
 * sorts by ctime, `ls -l -c` and `ls -c -S` do not. ls decides on the whole line; a cell gives these inputs alone or
 * after `-r`, where the record so far tells.
 */
private fun LsInputs.timed(word: TimeWord): LsInputs =
    copy(time = word, sort = if (sort == Sort.Name && layout != Layout.Long) Sort.Time else sort)

/*
 * `ls -l -C a b` lists in columns and `ls -C -l a b` long, `--format` taking part the same way: the last layout given
 * wins. `ls -l -1 a b` and `ls --format=long -1 a b` still list long, while `ls -l --format=single-column a b` and
 * `ls -l -C -1 a b` list one per line: -1 has no effect after a long format, and `--zero` none either (`ls -C --zero`
 * lists one per line, `ls -l --zero` long). `ls -a -A` hides . and .., `ls -A -a` shows them; `ls -s -h --si big`
 * prints 4.1k and `ls -s --si -h big` 4.0K: each pair is one setting. `ls -1 -S -v` sorts by version and `-v -S` by
 * size: ls's help counts -v among the sorts ("if none of -cftuvSUX nor --sort is specified").
 */

/** Every input of real ls other than `--help` and `--version`, in `ls --help` order. */
internal val INPUTS: List<Input> = listOf(
    flag("--all", 'a', help = "do not ignore entries starting with .") { copy(hidden = Hidden.All) },
    flag("--almost-all", 'A', help = "do not list implied . and ..") { copy(hidden = Hidden.AlmostAll) },
    flag("--author", help = "with -l, print the author of each file") { copy(author = true) },
    flag("--escape", 'b', help = "print C-style escapes for nongraphic characters") { copy(escape = true) },
    value(
        "--block-size",
        help = "with -l, scale sizes by SIZE when printing them; e.g., '--block-size=M'; see SIZE format below",
        kind = Kind.Value(sample = "1K", second = "M"),
    ) { copy(units = SizeUnits.Block(it)) },
    flag("--ignore-backups", 'B', help = "do not list implied entries ending with ~") { copy(ignoreBackups = true) },
    flag(
        null,
        'c',
        help = "with -lt: sort by, and show, ctime (time of last change of file status information); with -l: show " +
            "ctime and sort by name; otherwise: sort by ctime, newest first",
    ) { timed(TimeWord.Ctime) },
    flag(null, 'C', help = "list entries by columns") { copy(layout = Layout.Vertical) },
    value(
        "--color",
        help = "color the output WHEN; more info below",
        kind = Kind.Value(sample = "never", groups = WHEN_GROUPS, optional = true),
    ) { copy(color = read(it, When::words)) },
    flag("--directory", 'd', help = "list directories themselves, not their contents") { copy(directory = true) },
    flag("--dired", 'D', help = "generate output designed for Emacs' dired mode") { copy(dired = true) },
    flag(null, 'f', help = "same as -a -U") { copy(hidden = Hidden.All, sort = Sort.None) },
    // `ls -d -F sub` and `ls -d --classify sub` print sub/; `ls -Fnever` is "invalid option -- 'e'": the short takes no
    // value. `ls -d -p --classify=never sub` and `ls -d --classify=always --classify=never sub` print sub/, and `ls -d
    // --classify=auto sub` sub: a WHEN that resolves to no, off a terminal for auto, leaves the indicator as it was.
    value(
        "--classify",
        help = "append indicator (one of */=>@|) to entries WHEN",
        kind = Kind.Value(sample = "always", groups = WHEN_GROUPS, optional = true),
    ) { if (read(it, When::words) == When.Always) copy(indicator = IndicatorStyle.Classify) else this },
    flag("--file-type", help = "like -F, except do not append '*'") { copy(indicator = IndicatorStyle.FileType) },
    value(
        "--format",
        help = "across,horizontal (-x), commas (-m), long (-l), single-column (-1), verbose (-l), vertical (-C)",
        kind = Kind.Value(sample = "commas", groups = FORMAT_GROUPS),
    ) { copy(layout = read(it, Format::words).layout) },
    flag("--full-time", help = "like -l --time-style=full-iso") { copy(layout = Layout.Long, fullTime = true) },
    flag(null, 'g', help = "like -l, but do not list owner") { copy(layout = Layout.Long, omitOwner = true) },
    flag("--group-directories-first", help = "group directories before files") { copy(groupDirectoriesFirst = true) },
    flag("--no-group", 'G', help = "in a long listing, don't print group names") { copy(noGroup = true) },
    flag("--human-readable", 'h', help = "with -l and -s, print sizes like 1K 234M 2G etc.") {
        copy(units = SizeUnits.Human)
    },
    flag("--si", help = "likewise, but use powers of 1000 not 1024") { copy(units = SizeUnits.Si) },
    flag("--dereference-command-line", 'H', help = "follow symbolic links listed on the command line") {
        copy(dereferenceCommandLine = true)
    },
    flag(
        "--dereference-command-line-symlink-to-dir",
        help = "follow each command line symbolic link that points to a directory",
    ) { copy(dereferenceCommandLineSymlinkToDir = true) },
    value(
        "--hide",
        help = "do not list implied entries matching shell PATTERN (overridden by -a or -A)",
        kind = Kind.Value(sample = "*.o"),
    ) { copy(hide = hide + it) },
    value(
        "--hyperlink",
        help = "hyperlink file names WHEN",
        kind = Kind.Value(sample = "never", groups = WHEN_GROUPS, optional = true),
    ) { copy(hyperlink = read(it, When::words)) },
    value(
        "--indicator-style",
        help = "append indicator with style WORD to entry names: none (default), slash (-p), " +
            "file-type (--file-type), classify (-F)",
        kind = Kind.Value(sample = "slash", groups = INDICATOR_GROUPS),
    ) { copy(indicator = read(it, IndicatorStyle::words)) },
    flag("--inode", 'i', help = "print the index number of each file") { copy(inode = true) },
    value(
        "--ignore",
        'I',
        help = "do not list implied entries matching shell PATTERN",
        kind = Kind.Value(sample = "*.o"),
    ) { copy(ignore = ignore + it) },
    flag(
        "--kibibytes",
        'k',
        help = "default to 1024-byte blocks for file system usage; used only with -s and per directory totals",
    ) { copy(kibibytes = true) },
    flag(null, 'l', help = "use a long listing format") { copy(layout = Layout.Long) },
    flag(
        "--dereference",
        'L',
        help = "when showing file information for a symbolic link, show information for the file the link references " +
            "rather than for the link itself",
    ) { copy(dereference = true) },
    flag(null, 'm', help = "fill width with a comma separated list of entries") { copy(layout = Layout.Commas) },
    flag("--numeric-uid-gid", 'n', help = "like -l, but list numeric user and group IDs") {
        copy(layout = Layout.Long, numericUidGid = true)
    },
    flag("--literal", 'N', help = "print entry names without quoting") { copy(literal = true) },
    flag(null, 'o', help = "like -l, but do not list group information") {
        copy(layout = Layout.Long, omitGroup = true)
    },
    flag(null, 'p', help = "append / indicator to directories") { copy(indicator = IndicatorStyle.Slash) },
    flag("--hide-control-chars", 'q', help = "print ? instead of nongraphic characters") {
        copy(hideControlChars = true)
    },
    flag(
        "--show-control-chars",
        help = "show nongraphic characters as-is; the default, unless program is 'ls' and output is a terminal",
    ) { copy(showControlChars = true) },
    flag("--quote-name", 'Q', help = "enclose entry names in double quotes") { copy(quoteName = true) },
    value(
        "--quoting-style",
        help = "use quoting style WORD for entry names: literal, locale, shell, shell-always, shell-escape, " +
            "shell-escape-always, c, escape (overrides QUOTING_STYLE environment variable)",
        kind = Kind.Value(sample = "shell", groups = QUOTING_GROUPS),
    ) { copy(quotingStyle = read(it, QuotingStyle::words)) },
    flag("--reverse", 'r', help = "reverse order while sorting") { copy(reverse = true) },
    flag("--recursive", 'R', help = "list subdirectories recursively") { copy(recursive = true) },
    flag("--size", 's', help = "print the allocated size of each file, in blocks") { copy(size = true) },
    flag(null, 'S', help = "sort by file size, largest first") { copy(sort = Sort.Size) },
    value(
        "--sort",
        help = "change default 'name' sort to WORD: none (-U), size (-S), time (-t), version (-v), extension (-X), " +
            "name, width",
        kind = Kind.Value(sample = "size", groups = SORT_GROUPS),
    ) { copy(sort = read(it, Sort::words)) },
    value(
        "--time",
        help = "select which timestamp used to display or sort; access time (-u): atime, access, use; metadata " +
            "change time (-c): ctime, status; modified time (default): mtime, modification; birth time: birth, " +
            "creation; with -l, WORD determines which time to show; with --sort=time, sort by WORD (newest first)",
        kind = Kind.Value(sample = "atime", groups = TIME_GROUPS),
    ) { timed(read(it, TimeWord::words)) },
    value(
        "--time-style",
        help = "time/date format with -l; see TIME_STYLE below",
        kind = Kind.Value(sample = "iso", groups = TIME_STYLE_GROUPS, checked = false),
    ) { copy(timeStyle = it) },
    flag(null, 't', help = "sort by time, newest first; see --time") { copy(sort = Sort.Time) },
    value(
        "--tabsize",
        'T',
        help = "assume tab stops at each COLS instead of 8",
        kind = Kind.Value(sample = "4"),
    ) { copy(tabSize = it.toULong()) },
    flag(
        null,
        'u',
        help = "with -lt: sort by, and show, access time; with -l: show access time and sort by name; otherwise: " +
            "sort by access time, newest first",
    ) { timed(TimeWord.Atime) },
    flag(null, 'U', help = "do not sort directory entries") { copy(sort = Sort.None) },
    flag(null, 'v', help = "natural sort of (version) numbers within text") { copy(sort = Sort.Version) },
    value("--width", 'w', help = "set output width to COLS.  0 means no limit", kind = Kind.Value(sample = "40")) {
        copy(width = it.toULong())
    },
    flag(null, 'x', help = "list entries by lines instead of by columns") { copy(layout = Layout.Across) },
    flag(null, 'X', help = "sort alphabetically by entry extension") { copy(sort = Sort.Extension) },
    flag("--context", 'Z', help = "print any security context of each file") { copy(context = true) },
    flag("--zero", help = "end each output line with NUL, not newline") { oneLine().copy(zero = true) },
    flag(null, '1', help = "list one file per line") { oneLine() },
)

/** `-F`: one row of `ls --help` with `--classify`, though the short takes no value (`ls -Fnever`: invalid option). */
internal val CLASSIFY_SHORT: Input = Input(
    long = null,
    shorts = listOf('F'),
    help = "append indicator (one of */=>@|) to entries WHEN",
    kind = Kind.Flag,
    bind = { copy(indicator = IndicatorStyle.Classify) },
)

internal const val HELP_HELP = "display this help and exit"
internal const val VERSION_HELP = "output version information and exit"

/** Every input of real ls, -F among them, other than `--help` and `--version`. */
internal val ALL_INPUTS: List<Input> = INPUTS + CLASSIFY_SHORT

/** Every long name real ls has (`ls --=x` lists the same 44). */
internal val LS_LONGS: List<String> = INPUTS.mapNotNull { it.long } + listOf("--help", "--version")

/** klap's built-in long names, which real ls refuses (`ls --json a`: "unrecognized option"). */
internal val KLAP_ONLY_LONGS: List<String> = listOf("--json", "--completion", "--docs")

internal fun input(name: String): Input = ALL_INPUTS.firstOrNull { it.name == name } ?: fail("no ls input $name")

internal fun inputFor(short: Char): Input? = ALL_INPUTS.firstOrNull { short in it.shorts }

/** The shortest prefix of [long] getopt reads as [long] alone among ls's longs, or null when only the whole does. */
internal fun abbreviationOf(long: String): String? = (3 until long.length)
    .map { long.take(it) }
    .firstOrNull { prefix -> LS_LONGS.count { it.startsWith(prefix) } == 1 }

/** The long name getopt reads [spelled] as: itself, or the one long it is the unique prefix of. */
private fun resolveLong(spelled: String): String? =
    LS_LONGS.firstOrNull { it == spelled } ?: LS_LONGS.filter { it.startsWith(spelled) }.singleOrNull()

/** Whether getopt calls [spelled] ambiguous among ls's longs: a prefix of two or more and none of them whole. */
internal fun isAmbiguousLong(spelled: String): Boolean =
    spelled !in LS_LONGS && LS_LONGS.count { it.startsWith(spelled) } > 1

/** The input real ls reads the long name [spelled] as, abbreviated or not; null for --help, --version and the rest. */
internal fun inputOfLong(spelled: String): Input? =
    resolveLong(spelled)?.let { long -> INPUTS.firstOrNull { it.long == long } }

/**
 * The value argmatch reads [typed] as among [groups]: the first word of the one group an exact word or every word
 * the prefix reaches belongs to, or null when ls refuses it (`ls --color=n` is never, `--color=a` ambiguous).
 */
internal fun argmatch(
    typed: String,
    groups: List<List<String>>,
): String? {
    groups.firstOrNull { typed in it }?.let { return it.first() }
    if (typed.isEmpty()) return null
    return groups.filter { group -> group.any { it.startsWith(typed) } }.singleOrNull()?.first()
}

/** What a line listing `a` and `b` binds before any option: the record every cell starts from. */
internal val A_AND_B: LsInputs = NOTHING_BOUND.copy(files = listOf(CliPath("a"), CliPath("b")))
