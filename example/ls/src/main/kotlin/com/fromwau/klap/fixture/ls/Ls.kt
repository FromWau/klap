package com.fromwau.klap.fixture.ls

import com.fromwau.kern.result.Ok
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Builtin
import com.fromwau.klap.TypedCli
import com.fromwau.klap.Version
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * GNU coreutils 9.11 `ls`, reproduced as a command-line surface only.
 *
 * Real usage: `ls [OPTION]... [FILE]...`. Real ls has over fifty options; the subset below is what the
 * tool is about (which entries, in what layout, in what order) plus everything that touches a known gap:
 *   -a, --all / -A, --almost-all / -d, --directory / -R, --recursive
 *   -I, --ignore=PATTERN / --hide=PATTERN
 *   -l / -C / -x / -m / -1 / --format=WORD
 *   -r, --reverse / -t / -S / -U / -X / --sort=WORD
 *   -h, --human-readable / --si / -i, --inode / -s, --size
 *   -w, --width=COLS / -T, --tabsize=COLS
 *   --color[=WHEN] / -F, --classify[=WHEN] / -Z, --context
 *       --help / --version
 *
 * Exit status: 0 if OK, 1 on minor problems, 2 on serious trouble — including every usage error, which is
 * why the suite never compares klap's exit code with a real tool's (mkdir answers the same mistake with 1).
 */
public fun lsCli(): TypedCli<LsInputs> = cliOf("ls") {
    // GNU ls takes any unambiguous prefix of a long option or a choice value: `ls --rec` is --recursive.
    abbreviation = Abbreviation.Options
    help = "List information about the FILEs (the current directory by default)."
    version = Version.Of("9.12", text = LS_VERSION_TEXT)
    epilogue = "Sort entries alphabetically if none of -tSUX nor --sort is specified. " +
        "The WHEN argument defaults to 'always' and can also be 'auto' or 'never'."

    // klap's --color claims a name real ls already uses, and declining it hands the name back. Without
    // `color = Builtin.Off`, validateReservedNames rejects `option(long = "color")`, and parse() would strip
    // `--color` and its value before ls ever saw them, silently swallowing an option the tool documents.
    // klap's --help has no short, so `-h` is free for --human-readable.
    builtins { color = Builtin.Off }

    example("ls -la", "long format, including dotfiles")
    example("ls -lhS", "long format, human-readable sizes, largest first")
    example("ls --color=never --format=single-column", "one entry per line, never coloured")
    example("ls -R --ignore '*.o' src", "recurse, skipping object files")

    val all = flag(short = 'a', long = "all", help = "do not ignore entries starting with .")
    val almostAll = flag(short = 'A', long = "almost-all", help = "do not list implied . and ..")
    val directory = flag(short = 'd', long = "directory", help = "list directories themselves, not their contents")
    val recursive = flag(short = 'R', long = "recursive", help = "list subdirectories recursively")

    // Both patterns repeat in real ls, so .multiple() is the faithful cardinality; absent binds an empty
    // list rather than erroring, since neither is mandatory.
    val ignore = option(short = 'I', long = "ignore", help = "do not list implied entries matching shell PATTERN")
        .multiple()
        .placeholder("PATTERN")
    val hide = option(long = "hide", help = "like --ignore, but overridden by -a or -A")
        .multiple()
        .placeholder("PATTERN")

    val outputFormat = group("Output format") {
        // Real ls spells these four with no long form at all; a one-character name is a short-only
        // declaration, so `-l` is `-l` and nothing else — no invented `--l` comes with it.
        val longFormat = flag(short = 'l', help = "use a long listing format")
        val byColumns = flag(short = 'C', help = "list entries by columns")
        val byLines = flag(short = 'x', help = "list entries by lines instead of by columns")
        val commaSeparated = flag(short = 'm', help = "fill width with a comma separated list of entries")

        // This fixture is deliberately the mirror of `head`, and the pair is the point: real ls REJECTS
        // every OTHER digit (`ls -5` exits 2 with "invalid option -- '5'", verified) where real head
        // ACCEPTS `-5` as a count. Neither is a global rule, so each tool declares what it means: ls
        // declares this one short and no number input, which is exactly what makes `ls -5` unknown.
        val singleColumn = flag(short = '1', help = "list one file per line")

        val format = option(
            long = "format",
            help = "across/horizontal (-x), commas (-m), long (-l), single-column (-1), verbose (-l), vertical (-C)",
        ).choice("across", "horizontal", "commas", "long", "single-column", "verbose", "vertical")

        Pair(Triple(longFormat, byColumns, byLines), Triple(commaSeparated, singleColumn, format))
    }
    val (longFormat, byColumns, byLines) = outputFormat.first
    val (commaSeparated, singleColumn, format) = outputFormat.second

    val sorting = group("Sorting") {
        val reverse = flag(short = 'r', long = "reverse", help = "reverse order while sorting")
        val sortByTime = flag(short = 't', help = "sort by time, newest first")
        val sortBySize = flag(short = 'S', help = "sort by file size, largest first")
        val unsorted = flag(short = 'U', help = "do not sort directory entries")
        val sortByExtension = flag(short = 'X', help = "sort alphabetically by entry extension")

        val sort = option(long = "sort", help = "change default 'name' sort to WORD")
            .choice("none", "size", "time", "version", "extension", "name", "width")

        // Real ls treats the sort shorts and --sort as ONE setting whose last mention wins ("sort entries
        // alphabetically if none of -cftuvSUX nor --sort is specified"): `ls -S -t` sorts by time,
        // `ls -S --sort=time` by time and `ls --sort=time -S` by size, all verified against coreutils
        // 9.11. atMostOne would be the wrong rule, since it is precedence rather than exclusivity:
        // it would reject lines real ls accepts.
        lastWins(sortByTime, sortBySize, unsorted, sortByExtension, sort)

        Pair(Triple(reverse, sortByTime, sortBySize), Triple(unsorted, sortByExtension, sort))
    }
    val (reverse, sortByTime, sortBySize) = sorting.first
    val (unsorted, sortByExtension, sort) = sorting.second

    val humanReadable = flag(short = 'h', long = "human-readable", help = "with -l and -s, print sizes like 1K 234M 2G")
    val si = flag(long = "si", help = "likewise, but use powers of 1000 not 1024")
    val inode = flag(short = 'i', long = "inode", help = "print the index number of each file")
    val size = flag(short = 's', long = "size", help = "print the allocated size of each file, in blocks")

    val width = option(short = 'w', long = "width", help = "set output width to COLS; 0 means no limit")
        .int()
        .placeholder("COLS")
    val tabSize = option(short = 'T', long = "tabsize", help = "assume tab stops at each COLS instead of 8")
        .int()
        .placeholder("COLS")

    // `--color[=WHEN]` is optional-value via `.optionalValue("always")` — a bare occurrence binds "always"
    // and `--color=never` binds its own value, matching real ls exactly. The space form never binds (GNU's
    // own rule, not a klap limit): `ls --color auto f` colours `f` and leaves "auto" as a second file
    // operand (see `known divergence from real ls`).
    val color = option(long = "color", help = "color the output WHEN: always, auto or never")
        .choice("always", "auto", "never")
        .optionalValue("always")

    // Real ls's `-F` never takes an attached value — only the long spelling does (verified: `ls -Fnever`
    // fails "invalid option -- 'e'", while `ls -Fla` and `ls -FZ` both succeed, reading the rest of the
    // cluster as separate shorts). `-F` is declared short-only here and `--classify` long-only with its
    // own optional value, mirroring the `-u`/`--update` split in Mv.kt for the same reason.
    val classifyShort = flag(short = 'F', help = "append indicator (one of */=>@|) to entries")
    val classify = option(long = "classify", help = "append indicator (one of */=>@|) to entries WHEN")
        .choice("always", "auto", "never")
        .optionalValue("always")
        .placeholder("WHEN")

    val context = flag(short = 'Z', long = "context", help = "print any security context of each file")

    // `[FILE]...` really is zero-or-more: bare `ls` lists the current directory, so min = 0 is the honest
    // cardinality and help renders `[file...]`, matching real ls's own synopsis.
    val files = argument("file", "the files or directories to list").file().multiple()

    action<String>(human = { it }) {
        val layout = when {
            longFormat() -> "long"
            commaSeparated() -> "commas"
            byLines() -> "across"
            byColumns() -> "columns"
            else -> format() ?: "vertical"
        }
        val order = when {
            unsorted() -> "none"
            sortBySize() -> "size"
            sortByTime() -> "time"
            sortByExtension() -> "extension"
            else -> sort() ?: "name"
        }
        val notes = listOfNotNull(
            "all".takeIf { all() },
            "almost-all".takeIf { almostAll() },
            "self".takeIf { directory() },
            "recursive".takeIf { recursive() },
            "reversed".takeIf { reverse() },
            "human-readable".takeIf { humanReadable() },
            "si".takeIf { si() },
            "inode".takeIf { inode() },
            "blocks".takeIf { size() },
            "context".takeIf { context() },
            "classify".takeIf { classifyShort() || (classify() ?: "never") != "never" },
            color()?.let { "color=$it" },
            width()?.let { "width=$it" },
            tabSize()?.let { "tabsize=$it" },
            "ignoring ${ignore().size + hide().size} pattern(s)"
                .takeIf { ignore().isNotEmpty() || hide().isNotEmpty() },
        )
        val operands = files().ifEmpty { listOf(".") }
        Ok("would list ${operands.size} operand(s) as $layout, sorted by $order [${notes.joinToString(", ")}]")
    }

    projection {
        LsInputs(
            all = all(),
            almostAll = almostAll(),
            directory = directory(),
            recursive = recursive(),
            ignore = ignore(),
            hide = hide(),
            longFormat = longFormat(),
            byColumns = byColumns(),
            byLines = byLines(),
            commaSeparated = commaSeparated(),
            singleColumn = singleColumn(),
            format = format(),
            reverse = reverse(),
            sortByTime = sortByTime(),
            sortBySize = sortBySize(),
            unsorted = unsorted(),
            sortByExtension = sortByExtension(),
            sort = sort(),
            humanReadable = humanReadable(),
            si = si(),
            inode = inode(),
            size = size(),
            width = width(),
            tabSize = tabSize(),
            color = color(),
            classifyShort = classifyShort(),
            classify = classify(),
            context = context(),
            files = files(),
        )
    }
}

/**
 * What one `ls` line binds, as values rather than as the handles that read them.
 *
 * Being a plain data class is the point: a parity case asserts a whole invocation with one `assertEquals`
 * against a `copy()` of [NOTHING_BOUND], so every field it does *not* name is pinned to its default too.
 */
public data class LsInputs(
    val all: Boolean,
    val almostAll: Boolean,
    val directory: Boolean,
    val recursive: Boolean,
    val ignore: List<String>,
    val hide: List<String>,
    val longFormat: Boolean,
    val byColumns: Boolean,
    val byLines: Boolean,
    val commaSeparated: Boolean,
    val singleColumn: Boolean,
    val format: String?,
    val reverse: Boolean,
    val sortByTime: Boolean,
    val sortBySize: Boolean,
    val unsorted: Boolean,
    val sortByExtension: Boolean,
    val sort: String?,
    val humanReadable: Boolean,
    val si: Boolean,
    val inode: Boolean,
    val size: Boolean,
    val width: Int?,
    val tabSize: Int?,
    val color: String?,
    val classifyShort: Boolean,
    val classify: String?,
    val context: Boolean,
    val files: List<String>,
)

/** `ls` with no arguments at all: every field at the default the declaration gives it. */
public val NOTHING_BOUND: LsInputs = LsInputs(
    all = false,
    almostAll = false,
    directory = false,
    recursive = false,
    ignore = emptyList(),
    hide = emptyList(),
    longFormat = false,
    byColumns = false,
    byLines = false,
    commaSeparated = false,
    singleColumn = false,
    format = null,
    reverse = false,
    sortByTime = false,
    sortBySize = false,
    unsorted = false,
    sortByExtension = false,
    sort = null,
    humanReadable = false,
    si = false,
    inode = false,
    size = false,
    width = null,
    tabSize = null,
    color = null,
    classifyShort = false,
    classify = null,
    context = false,
    files = emptyList(),
)

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
