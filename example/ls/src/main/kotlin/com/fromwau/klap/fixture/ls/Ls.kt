package com.fromwau.klap.fixture.ls

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Builtin
import com.fromwau.klap.ConversionError
import com.fromwau.klap.Help
import com.fromwau.klap.io.CliPath
import com.fromwau.klap.io.path
import com.fromwau.klap.TypedCli
import com.fromwau.klap.ValueScope
import com.fromwau.klap.Version
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * GNU coreutils 9.12 `ls`, reproduced as a command-line surface only: every input of `ls --help`, each described in
 * its words. The action lists nothing.
 *
 * Real usage: `ls [OPTION]... [FILE]...`. Several inputs set one value, the last given winning, and the record holds
 * that value: the layout (`-l -g -o -n --full-time -C -x -m -1 --zero --format`), the sort (`-t -S -U -X -v -f
 * --sort`), the timestamp (`-c -u --time`), which entries show (`-a -A -f`), the size units (`-h --si
 * --block-size`) and the indicator style (`-p --file-type --indicator-style -F --classify`).
 *
 * [terminal] stands in for whether standard output is a terminal, where `--classify=auto` appends indicators; klap
 * does not look, so a test says.
 */
public fun lsCli(terminal: Boolean = false): TypedCli<LsInputs> = cliOf("ls") {
    // GNU ls takes any unambiguous prefix of a long option or a value: `ls --rec` is --recursive.
    abbreviation = Abbreviation.Options
    // GNU ls has none of klap's built-ins but --help, which has no short: `-h` is --human-readable.
    builtins {
        help = Help.ByCommand(description = "display this help and exit")
        json = Builtin.Off
        color = Builtin.Off
        completion = Builtin.Off
        docs = Builtin.Off
    }
    help = "List information about the FILEs (the current directory by default). Sort entries alphabetically if " +
        "none of -cftuvSUX nor --sort is specified."
    version = Version.Of("9.12", description = "output version information and exit", text = LS_VERSION_TEXT)
    epilogue = "The WHEN argument defaults to 'always' and can also be 'auto' or 'never'."

    example("ls -la", "long format, including dotfiles")
    example("ls -lhS", "long format, human-readable sizes, largest first")
    example("ls --color=never --format=single-column", "one entry per line, never coloured")
    example("ls -R --ignore '*.o' src", "recurse, skipping object files")

    val all = flag(short = 'a', long = "all", help = "do not ignore entries starting with .")
    val almostAll = flag(short = 'A', long = "almost-all", help = "do not list implied . and ..")
    val author = flag(long = "author", help = "with -l, print the author of each file")
    val escape = flag(short = 'b', long = "escape", help = "print C-style escapes for nongraphic characters")
    val blockSize = option(
        long = "block-size",
        help = "with -l, scale sizes by SIZE when printing them; e.g., '--block-size=M'; see SIZE format below",
    ).placeholder("SIZE")
    val ignoreBackups = flag(short = 'B', long = "ignore-backups", help = "do not list implied entries ending with ~")
    val ctime = flag(
        short = 'c',
        help = "with -lt: sort by, and show, ctime (time of last change of file status information); with -l: show " +
            "ctime and sort by name; otherwise: sort by ctime, newest first",
    )
    val byColumns = flag(short = 'C', help = "list entries by columns")
    val color = option(long = "color", help = "color the output WHEN; more info below")
        .enum<When>(spellings = { it.words })
        .optionalValue("always")
    val directory = flag(short = 'd', long = "directory", help = "list directories themselves, not their contents")
    val dired = flag(short = 'D', long = "dired", help = "generate output designed for Emacs' dired mode")
    val unsortedAll = flag(short = 'f', help = "same as -a -U")
    // `ls -Fnever` is "invalid option -- 'e'": only the long takes a WHEN, so the two are declared apart.
    val classifyShort = flag(short = 'F', help = CLASSIFY_HELP)
    val classify = option(long = "classify", help = CLASSIFY_HELP)
        .enum<When>(spellings = { it.words })
        .optionalValue("always")
    val fileType = flag(long = "file-type", help = "like -F, except do not append '*'")
    val format = option(
        long = "format",
        help = "across,horizontal (-x), commas (-m), long (-l), single-column (-1), verbose (-l), vertical (-C)",
    ).enum<Format>(spellings = { it.words })
    val fullTime = flag(long = "full-time", help = "like -l --time-style=full-iso")
    val omitOwner = flag(short = 'g', help = "like -l, but do not list owner")
    val groupDirectoriesFirst = flag(long = "group-directories-first", help = "group directories before files")
    val noGroup = flag(short = 'G', long = "no-group", help = "in a long listing, don't print group names")
    val human = flag(
        short = 'h',
        long = "human-readable",
        help = "with -l and -s, print sizes like 1K 234M 2G etc.",
    )
    val si = flag(long = "si", help = "likewise, but use powers of 1000 not 1024")
    val dereferenceCommandLine = flag(
        short = 'H',
        long = "dereference-command-line",
        help = "follow symbolic links listed on the command line",
    )
    val dereferenceCommandLineSymlinkToDir = flag(
        long = "dereference-command-line-symlink-to-dir",
        help = "follow each command line symbolic link that points to a directory",
    )
    val hide = option(
        long = "hide",
        help = "do not list implied entries matching shell PATTERN (overridden by -a or -A)",
    ).multiple().placeholder("PATTERN")
    val hyperlink = option(long = "hyperlink", help = "hyperlink file names WHEN")
        .enum<When>(spellings = { it.words })
        .optionalValue("always")
    val indicatorStyle = option(
        long = "indicator-style",
        help = "append indicator with style WORD to entry names: none (default), slash (-p), file-type " +
            "(--file-type), classify (-F)",
    ).enum<IndicatorStyle>(spellings = { it.words })
    val inode = flag(short = 'i', long = "inode", help = "print the index number of each file")
    val ignore = option(short = 'I', long = "ignore", help = "do not list implied entries matching shell PATTERN")
        .multiple()
        .placeholder("PATTERN")
    val kibibytes = flag(
        short = 'k',
        long = "kibibytes",
        help = "default to 1024-byte blocks for file system usage; used only with -s and per directory totals",
    )
    val longFormat = flag(short = 'l', help = "use a long listing format")
    val dereference = flag(
        short = 'L',
        long = "dereference",
        help = "when showing file information for a symbolic link, show information for the file the link " +
            "references rather than for the link itself",
    )
    val commas = flag(short = 'm', help = "fill width with a comma separated list of entries")
    val numericUidGid = flag(
        short = 'n',
        long = "numeric-uid-gid",
        help = "like -l, but list numeric user and group IDs",
    )
    val literal = flag(short = 'N', long = "literal", help = "print entry names without quoting")
    val omitGroup = flag(short = 'o', help = "like -l, but do not list group information")
    val slash = flag(short = 'p', help = "append / indicator to directories")
    val hideControlChars = flag(
        short = 'q',
        long = "hide-control-chars",
        help = "print ? instead of nongraphic characters",
    )
    val showControlChars = flag(
        long = "show-control-chars",
        help = "show nongraphic characters as-is; the default, unless program is 'ls' and output is a terminal",
    )
    val quoteName = flag(short = 'Q', long = "quote-name", help = "enclose entry names in double quotes")
    val quotingStyle = option(
        long = "quoting-style",
        help = "use quoting style WORD for entry names: literal, locale, shell, shell-always, shell-escape, " +
            "shell-escape-always, c, escape (overrides QUOTING_STYLE environment variable)",
    ).enum<QuotingStyle>(spellings = { it.words })
    val reverse = flag(short = 'r', long = "reverse", help = "reverse order while sorting")
    val recursive = flag(short = 'R', long = "recursive", help = "list subdirectories recursively")
    val size = flag(short = 's', long = "size", help = "print the allocated size of each file, in blocks")
    val bySize = flag(short = 'S', help = "sort by file size, largest first")
    val sort = option(
        long = "sort",
        help = "change default 'name' sort to WORD: none (-U), size (-S), time (-t), version (-v), extension (-X), " +
            "name, width",
    ).enum<Sort>(spellings = { it.words })
    val time = option(
        long = "time",
        help = "select which timestamp used to display or sort; access time (-u): atime, access, use; metadata " +
            "change time (-c): ctime, status; modified time (default): mtime, modification; birth time: birth, " +
            "creation; with -l, WORD determines which time to show; with --sort=time, sort by WORD (newest first)",
    ).enum<TimeWord>(spellings = { it.words })
    // ls checks TIME_STYLE only when it lists long (`ls --time-style=bogus a` runs), and accepts any +FORMAT.
    val timeStyle = option(long = "time-style", help = "time/date format with -l; see TIME_STYLE below")
        .placeholder("TIME_STYLE")
        .completeWith { candidates(TIME_STYLES) }
    val byTime = flag(short = 't', help = "sort by time, newest first; see --time")
    val tabSize = option(short = 'T', long = "tabsize", help = "assume tab stops at each COLS instead of 8")
        .convert { cols(it, overflow = null, refusal = "invalid tab size") }
        .placeholder("COLS")
    val atime = flag(
        short = 'u',
        help = "with -lt: sort by, and show, access time; with -l: show access time and sort by name; otherwise: " +
            "sort by access time, newest first",
    )
    val unsorted = flag(short = 'U', help = "do not sort directory entries")
    val byVersion = flag(short = 'v', help = "natural sort of (version) numbers within text")
    // `ls -w 99999999999999999999` runs: a width past the largest number is no limit.
    val width = option(short = 'w', long = "width", help = "set output width to COLS.  0 means no limit")
        .convert { cols(it, overflow = ULong.MAX_VALUE, refusal = "invalid line width") }
        .placeholder("COLS")
    val byLines = flag(short = 'x', help = "list entries by lines instead of by columns")
    val byExtension = flag(short = 'X', help = "sort alphabetically by entry extension")
    val context = flag(short = 'Z', long = "context", help = "print any security context of each file")
    val zero = flag(long = "zero", help = "end each output line with NUL, not newline")
    val singleColumn = flag(short = '1', help = "list one file per line")

    // `ls -S -t` sorts by time and `ls --sort=time -S` by size; `ls -m -f -S` by size and `-S -f` not at all.
    lastWins(byTime, bySize, unsorted, byExtension, byVersion, unsortedAll, sort)
    // `ls -c --time=atime` shows atime and `ls --time=atime -c` ctime.
    lastWins(ctime, atime, time)
    // `ls -s -h --si big` prints 8.2k and `-s --si -h` 8.0K; `-h --block-size=1` prints 5001216.
    lastWins(human, si, blockSize)

    // `[FILE]...` is zero or more: bare `ls` lists the current directory.
    val files = argument("file", "the files or directories to list").path().multiple()

    // ls's option loop sets the layout one option at a time, and -1 or --zero leaves a long format as it is.
    fun ValueScope.replayLayout(): Layout {
        var layout = Layout.ByTerminal
        val written = inOrder(
            longFormat, omitOwner, omitGroup, numericUidGid, fullTime, byColumns, byLines, commas, singleColumn, zero,
            format,
        )
        for ((input, value) in written) {
            layout = when (input) {
                byColumns -> Layout.Vertical
                byLines -> Layout.Across
                commas -> Layout.Commas
                singleColumn, zero -> if (layout == Layout.Long) layout else Layout.SingleColumn
                format -> (value as Format).layout
                else -> Layout.Long
            }
        }
        return layout
    }

    // `ls -m -a -A` hides . and .., and `ls -m -A -f` shows them.
    fun ValueScope.replayHidden(): Hidden {
        var hidden = Hidden.Skip
        for ((input, _) in inOrder(all, almostAll, unsortedAll)) {
            hidden = if (input == almostAll) Hidden.AlmostAll else Hidden.All
        }
        return hidden
    }

    // `ls -d -p --classify=never sub` prints sub/: a WHEN that resolves to no takes no part.
    fun ValueScope.replayIndicator(): IndicatorStyle {
        var indicator = IndicatorStyle.None
        for ((input, value) in inOrder(slash, fileType, indicatorStyle, classifyShort, classify)) {
            indicator = when (input) {
                slash -> IndicatorStyle.Slash
                fileType -> IndicatorStyle.FileType
                indicatorStyle -> value as IndicatorStyle
                classify -> if ((value as When).appliesOn(terminal)) IndicatorStyle.Classify else indicator
                else -> IndicatorStyle.Classify
            }
        }
        return indicator
    }

    fun ValueScope.timeWord(): TimeWord = when {
        ctime() -> TimeWord.Ctime
        atime() -> TimeWord.Atime
        else -> time() ?: TimeWord.Mtime
    }

    // `ls -c` sorts by ctime, `ls -l -c` by name and `ls -c -S` by size: a timestamp sorts only where nothing else
    // sorts and the listing is not long.
    fun ValueScope.sortOrder(layout: Layout): Sort = when {
        byTime() -> Sort.Time
        bySize() -> Sort.Size
        unsorted() || unsortedAll() -> Sort.None
        byExtension() -> Sort.Extension
        byVersion() -> Sort.Version
        else -> sort() ?: if ((ctime() || atime() || time() != null) && layout != Layout.Long) Sort.Time else Sort.Name
    }

    fun ValueScope.units(): SizeUnits = when {
        human() -> SizeUnits.Human
        si() -> SizeUnits.Si
        else -> blockSize()?.let(SizeUnits::Block) ?: SizeUnits.Default
    }

    val read: ValueScope.() -> LsInputs = {
        val layout = replayLayout()
        LsInputs(
            hidden = replayHidden(),
            author = author(),
            escape = escape(),
            units = units(),
            ignoreBackups = ignoreBackups(),
            time = timeWord(),
            layout = layout,
            color = color(),
            directory = directory(),
            dired = dired(),
            indicator = replayIndicator(),
            fullTime = fullTime(),
            omitOwner = omitOwner(),
            groupDirectoriesFirst = groupDirectoriesFirst(),
            noGroup = noGroup(),
            dereferenceCommandLine = dereferenceCommandLine(),
            dereferenceCommandLineSymlinkToDir = dereferenceCommandLineSymlinkToDir(),
            hide = hide(),
            hyperlink = hyperlink(),
            inode = inode(),
            ignore = ignore(),
            kibibytes = kibibytes(),
            dereference = dereference(),
            numericUidGid = numericUidGid(),
            literal = literal(),
            omitGroup = omitGroup(),
            hideControlChars = hideControlChars(),
            showControlChars = showControlChars(),
            quoteName = quoteName(),
            quotingStyle = quotingStyle(),
            reverse = reverse(),
            recursive = recursive(),
            size = size(),
            sort = sortOrder(layout),
            timeStyle = timeStyle(),
            tabSize = tabSize(),
            width = width(),
            context = context(),
            zero = zero(),
            files = files(),
        )
    }

    action<String>(human = { it }) {
        val bound = read()
        Ok("would list ${bound.files.ifEmpty { listOf(".") }.size} operand(s) [$bound]")
    }

    projection(read)
}

private const val CLASSIFY_HELP = "append indicator (one of */=>@|) to entries WHEN"

/** The TIME_STYLE words `ls -l --time-style=bogus` lists: "[posix-]full-iso, [posix-]long-iso, ...". */
private val TIME_STYLES = listOf("full-iso", "long-iso", "iso", "locale")

/** A WHEN of `--color`, `--classify` and `--hyperlink`, each named by any of its [words] (`ls --color=bogus`). */
public enum class When(public val words: List<String>) {
    Always(listOf("always", "yes", "force")),
    Never(listOf("never", "no", "none")),
    Auto(listOf("auto", "tty", "if-tty")),
    ;

    /** Whether this WHEN turns its feature on: auto only when standard output is a [terminal]. */
    public fun appliesOn(terminal: Boolean): Boolean = this == Always || (this == Auto && terminal)
}

/** A `--format` WORD, named by any of its [words] (`ls --format=bogus`), and the [layout] it sets. */
public enum class Format(
    public val words: List<String>,
    public val layout: Layout,
) {
    Long(listOf("verbose", "long"), Layout.Long),
    Commas(listOf("commas"), Layout.Commas),
    Across(listOf("horizontal", "across"), Layout.Across),
    Vertical(listOf("vertical"), Layout.Vertical),
    SingleColumn(listOf("single-column"), Layout.SingleColumn),
}

/** How ls lays out its entries once every layout option is read. */
public enum class Layout {
    /** No layout given: in columns on a terminal, one per line otherwise. */
    ByTerminal,

    /** `-l`, `-g`, `-o`, `-n`, `--full-time`, `--format=long`. */
    Long,

    /** `-m`, `--format=commas`. */
    Commas,

    /** `-x`, `--format=across`. */
    Across,

    /** `-C`, `--format=vertical`. */
    Vertical,

    /** `-1`, `--zero`, `--format=single-column`. */
    SingleColumn,
}

/** A `--sort` WORD (`ls --sort=bogus`), and what the sort shorts set: "none (-U), size (-S), time (-t), ...". */
public enum class Sort(public val words: List<String>) {
    None(listOf("none")),
    Size(listOf("size")),
    Time(listOf("time")),
    Version(listOf("version")),
    Extension(listOf("extension")),
    Name(listOf("name")),
    Width(listOf("width")),
}

/** A `--time` WORD, named by any of its [words] (`ls --time=bogus`), and what `-c` and `-u` set. */
public enum class TimeWord(public val words: List<String>) {
    Atime(listOf("atime", "access", "use")),
    Ctime(listOf("ctime", "status")),
    Mtime(listOf("mtime", "modification")),
    Birth(listOf("birth", "creation")),
}

/** A `--quoting-style` WORD (`ls --quoting-style=bogus`). */
public enum class QuotingStyle(public val words: List<String>) {
    Literal(listOf("literal")),
    Shell(listOf("shell")),
    ShellAlways(listOf("shell-always")),
    ShellEscape(listOf("shell-escape")),
    ShellEscapeAlways(listOf("shell-escape-always")),
    C(listOf("c")),
    CMaybe(listOf("c-maybe")),
    Escape(listOf("escape")),
    Locale(listOf("locale")),
    Clocale(listOf("clocale")),
}

/** An `--indicator-style` WORD, and what `-p`, `--file-type` and `-F` set: "none (default), slash (-p), ...". */
public enum class IndicatorStyle(public val words: List<String>) {
    None(listOf("none")),
    Slash(listOf("slash")),
    FileType(listOf("file-type")),
    Classify(listOf("classify")),
}

/** Which entries ls lists besides the ones not starting with a dot. */
public enum class Hidden {
    /** No `-a`, `-A` or `-f`. */
    Skip,

    /** `-A`: "do not list implied . and ..". */
    AlmostAll,

    /** `-a` or `-f`: "do not ignore entries starting with .". */
    All,
}

/** How ls prints sizes: `-h`, `--si` and `--block-size`, the last given winning. */
public sealed interface SizeUnits {
    /** None of the three given. */
    public data object Default : SizeUnits

    /** `-h`: "print sizes like 1K 234M 2G etc.". */
    public data object Human : SizeUnits

    /** `--si`: "likewise, but use powers of 1000 not 1024". */
    public data object Si : SizeUnits

    /** `--block-size=SIZE`, its SIZE as typed. */
    public data class Block(val size: String) : SizeUnits
}

/**
 * What one `ls` line binds, as ls reads it rather than as the inputs that spell it: the settings several inputs share
 * are one field each ([hidden], [units], [time], [layout], [indicator], [sort]), while `-g`, `-o`, `-n`,
 * `--full-time` and `--zero` also keep their own.
 *
 * A parity case asserts a whole invocation against a `copy()` of [NOTHING_BOUND], so every field it does not name is
 * pinned to its default too.
 */
public data class LsInputs(
    val hidden: Hidden,
    val author: Boolean,
    val escape: Boolean,
    val units: SizeUnits,
    val ignoreBackups: Boolean,
    val time: TimeWord,
    val layout: Layout,
    /** The `--color` WHEN as given, `auto` unresolved: ls decides it against its output when it lists. */
    val color: When?,
    val directory: Boolean,
    val dired: Boolean,
    val indicator: IndicatorStyle,
    val fullTime: Boolean,
    /** `-g`: "like -l, but do not list owner". */
    val omitOwner: Boolean,
    val groupDirectoriesFirst: Boolean,
    val noGroup: Boolean,
    val dereferenceCommandLine: Boolean,
    val dereferenceCommandLineSymlinkToDir: Boolean,
    val hide: List<String>,
    val hyperlink: When?,
    val inode: Boolean,
    val ignore: List<String>,
    val kibibytes: Boolean,
    val dereference: Boolean,
    val numericUidGid: Boolean,
    val literal: Boolean,
    /** `-o`: "like -l, but do not list group information". */
    val omitGroup: Boolean,
    val hideControlChars: Boolean,
    val showControlChars: Boolean,
    val quoteName: Boolean,
    val quotingStyle: QuotingStyle?,
    val reverse: Boolean,
    val recursive: Boolean,
    val size: Boolean,
    val sort: Sort,
    val timeStyle: String?,
    val tabSize: ULong?,
    val width: ULong?,
    val context: Boolean,
    val zero: Boolean,
    val files: List<CliPath>,
)

/** `ls` with no arguments at all: every field at the default ls gives it. */
public val NOTHING_BOUND: LsInputs = LsInputs(
    hidden = Hidden.Skip,
    author = false,
    escape = false,
    units = SizeUnits.Default,
    ignoreBackups = false,
    time = TimeWord.Mtime,
    layout = Layout.ByTerminal,
    color = null,
    directory = false,
    dired = false,
    indicator = IndicatorStyle.None,
    fullTime = false,
    omitOwner = false,
    groupDirectoriesFirst = false,
    noGroup = false,
    dereferenceCommandLine = false,
    dereferenceCommandLineSymlinkToDir = false,
    hide = emptyList(),
    hyperlink = null,
    inode = false,
    ignore = emptyList(),
    kibibytes = false,
    dereference = false,
    numericUidGid = false,
    literal = false,
    omitGroup = false,
    hideControlChars = false,
    showControlChars = false,
    quoteName = false,
    quotingStyle = null,
    reverse = false,
    recursive = false,
    size = false,
    sort = Sort.Name,
    timeStyle = null,
    tabSize = null,
    width = null,
    context = false,
    zero = false,
    files = emptyList(),
)

/** A COLS ls refuses, as typed. */
private data class ColsRefused(val typed: String) : ResultError

/**
 * COLS as gnulib's `xstrtoumax` reads it in base 0: leading blanks and a `+` skipped, a minus refused, octal after a
 * `0`, hex after `0x`, and nothing after the digits. A number past 2^64-1 is [overflow], or refused when that is null,
 * as is one past 2^63-1 then: `ls -T 9223372036854775808` is "Value too large for defined data type".
 */
private fun cols(
    typed: String,
    overflow: ULong?,
    refusal: String,
): Result<ULong, ConversionError> {
    val refused = Err(ConversionError.Domain(ColsRefused(typed), refusal))
    val tooLarge = Err(ConversionError.Domain(ColsRefused(typed), "$refusal: Value too large for defined data type"))
    val number = typed.trimStart(' ', '\t', '\n', '\u000B', '\u000C', '\r').removePrefix("+")
    val hex = number.length > 2 && number[0] == '0' && number[1] in "xX" && number[2].digitToIntOrNull(16) != null
    val (digits, radix) = when {
        hex -> number.drop(2) to 16
        number.startsWith("0") -> number to 8
        else -> number to 10
    }
    // digitToIntOrNull reads every Unicode digit, and strtoumax only ASCII ones: `ls -w ١٢` is an invalid width.
    if (digits.isEmpty() || digits.any { it.code > 127 || it.digitToIntOrNull(radix) == null }) return refused
    val value = digits.toULongOrNull(radix)
    return when {
        value == null -> overflow?.let(::Ok) ?: tooLarge
        overflow == null && value > Long.MAX_VALUE.toULong() -> tooLarge
        else -> Ok(value)
    }
}

/**
 * What `ls --version` prints for GNU coreutils 9.12, the version installed when it was pinned
 * (2026-10-10).
 */
internal val LS_VERSION_TEXT: String = """ls (GNU coreutils) 9.12
Copyright (C) 2026 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>.
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Written by Richard M. Stallman and David MacKenzie."""
