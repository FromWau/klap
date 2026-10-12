package com.fromwau.klap.fixture.find

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.marked
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.assertEquals

/**
 * What Tab should offer on a find line, read as GNU findutils 4.11.0 `/usr/bin/find` reads the same words in its
 * own single-dash spelling (owner decisions F1, F1a, F1b): a predicate is the fixture's `--name`, matched whole and
 * never abbreviated, its `=` form completing as its spaced form; `-H -L -P -D -O` are find's own words, each alone,
 * only before the first starting point; a starting point comes before the first predicate; a literal `--` hands the
 * rest of the line to find's raw expression. Every runnable claim here was checked with `/usr/bin/find` inside a
 * throwaway directory; the evidence sits next to each table entry.
 */
internal object FindCompletionSupport {

    /** How a find input reads its value. */
    sealed interface Value {
        /** Free text or a number: nothing to offer. */
        data object Free : Value

        /** A path: the shell's file completion. */
        data object Path : Value

        /** One of [values], or with [list] a comma list of them; [unique] when find refuses a repeat in the list. */
        data class Choices(
            val values: List<String>,
            val list: Boolean = false,
            val unique: Boolean = false,
        ) : Value
    }

    /** One input of real find in the fixture's spelling: its [name], its help in the fixture, and its [value]. */
    data class Input(
        val name: String,
        val help: String,
        val value: Value? = null,
    ) {
        val sample: String
            get() = when (value) {
                is Value.Choices -> value.values.first().take(1)
                else -> "a"
            }
    }

    /** `/usr/bin/find -D help`, in its order. */
    val DEBUG_OPTIONS: List<String> = listOf("exec", "opt", "rates", "search", "stat", "time", "tree", "all", "help")

    /** `/usr/bin/find . -regextype bogus`: "valid types are ...", in its order. */
    val REGEX_TYPES: List<String> = listOf(
        "findutils-default", "awk", "ed", "egrep", "emacs", "gnu-awk", "grep", "posix-awk", "posix-basic",
        "posix-egrep", "posix-extended", "posix-minimal-basic", "sed",
    )

    /**
     * The `-type` letters this find accepts: `find . -type D` is "not supported because Solaris doors are not
     * supported on the platform", `find . -type f,f` is "Duplicate file type 'f'".
     */
    val TYPE_LETTERS: List<String> = listOf("b", "c", "d", "p", "f", "l", "s")

    private const val OPTIMISE_HELP = "query optimisation level"

    /**
     * find's own words before the starting points. `find -L -L .` and `find -L -P -H .` run, `find -LP .` is
     * "unknown predicate `-LP'", `find -Dtree .` "unknown predicate `-Dtree'", `find -O 3 .` "The -O option must be
     * immediately followed by a decimal integer"; `man find` documents `-O0` to `-O3`.
     */
    val SHORTS: List<Input> = listOf(
        Input("-P", "never follow symbolic links (default)"),
        Input("-L", "follow symbolic links"),
        Input("-H", "follow symlinks only for command-line arguments"),
        Input("-D", "print diagnostics (comma-separated)", Value.Choices(DEBUG_OPTIONS, list = true)),
    )

    /** `-O` and its level glued: the four levels `man find` documents, described by the option's help. */
    val LEVELS: List<Suggestion> = (0..3).map { Suggestion("-O$it", OPTIMISE_HELP) }

    /** `-newerXY`'s forms this system's find runs: X of a, c and m, Y of a, c, m and t (`t` a date). */
    private val NEWER_TIMES: List<String> = "acm".flatMap { x -> "acmt".map { y -> "$x$y" } }

    private val NEWER_TIME_WORDS: Map<Char, String> =
        mapOf('a' to "last accessed", 'c' to "status last changed", 'm' to "last modified")

    /**
     * Every predicate this system's find runs, in the fixture's order and help, spelled `--` plus find's own name
     * (`find --help` lists each). `-ignore_readdir_race` keeps find's underscores. Left out: `-context` and the
     * birth-time `-newerXY` forms, which this find refuses, and `-fprintf FILE FORMAT`, two words no option holds.
     */
    val PREDICATES: List<Input> = listOf(
        Input("--maxdepth", "descend at most N directory levels", Value.Free),
        Input("--mindepth", "do not apply any tests at levels less than N", Value.Free),
        Input("--depth", "process a directory's contents before the directory itself"),
        // `man find`: "-d A synonym for -depth".
        Input("--d", "process a directory's contents before the directory itself"),
        Input("--xdev", "don't descend directories on other filesystems"),
        // `man find`: "-mount Ignore files on other devices."
        Input("--mount", "ignore files on other devices"),
        Input("--noleaf", "do not optimise assuming Unix directory link counts"),
        Input("--ignore_readdir_race", "do not error on files removed during the scan"),
        Input("--noignore_readdir_race", "turn off the effect of --ignore_readdir_race"),
        Input("--regextype", "syntax used by --regex", Value.Choices(REGEX_TYPES)),
        Input("--files0-from", "read NUL-separated starting points from FILE", Value.Path),
        Input("--daystart", "measure times from the beginning of today"),
        Input("--follow", "deprecated; dereference symbolic links, as -L does"),
        Input("--warn", "turn warning messages on"),
        Input("--nowarn", "turn warning messages off"),
        Input("--name", "base name matches shell pattern", Value.Free),
        Input("--iname", "like --name, case-insensitive", Value.Free),
        Input("--path", "whole path matches shell pattern", Value.Free),
        Input("--ipath", "like --path, case-insensitive", Value.Free),
        Input("--wholename", "whole path matches shell pattern, as --path does", Value.Free),
        Input("--iwholename", "like --wholename, case-insensitive", Value.Free),
        Input("--regex", "whole path matches regular expression", Value.Free),
        Input("--iregex", "like --regex, case-insensitive", Value.Free),
        Input("--lname", "symbolic link's contents match shell pattern", Value.Free),
        Input("--ilname", "like --lname, case-insensitive", Value.Free),
        Input("--fstype", "file is on a filesystem of TYPE", Value.Free),
        Input(
            "--type",
            "file type: one or more of b c d p f l s (comma-separated)",
            Value.Choices(TYPE_LETTERS, list = true, unique = true),
        ),
        Input(
            "--xtype",
            "like --type, for the file a symbolic link points to",
            Value.Choices(TYPE_LETTERS, list = true, unique = true),
        ),
        Input("--size", "file uses N units of space ([+-]N[bcwkMG])", Value.Free),
        Input("--mtime", "data last modified [+-]N*24h ago", Value.Free),
        Input("--mmin", "data last modified [+-]N minutes ago", Value.Free),
        Input("--ctime", "status last changed [+-]N*24h ago", Value.Free),
        Input("--cmin", "status last changed [+-]N minutes ago", Value.Free),
        Input("--atime", "last accessed [+-]N*24h ago", Value.Free),
        Input("--amin", "last accessed [+-]N minutes ago", Value.Free),
        Input("--used", "last accessed [+-]N days after its status last changed", Value.Free),
        Input("--links", "file has [+-]N hard links", Value.Free),
        Input("--inum", "file has inode number [+-]N", Value.Free),
        Input("--uid", "file's numeric user ID is [+-]N", Value.Free),
        Input("--gid", "file's numeric group ID is [+-]N", Value.Free),
        Input("--perm", "permission bits ([-/]MODE)", Value.Free),
        Input("--newer", "modified more recently than FILE", Value.Path),
        Input("--anewer", "accessed more recently than FILE was modified", Value.Path),
        Input("--cnewer", "status changed more recently than FILE was modified", Value.Path),
        Input("--samefile", "file refers to the same inode as FILE", Value.Path),
    ) + NEWER_TIMES.map { times ->
        val reference = if (times[1] == 't') "DATE" else "FILE was ${NEWER_TIME_WORDS.getValue(times[1])}"
        val value = if (times[1] == 't') Value.Free else Value.Path
        Input("--newer$times", "${NEWER_TIME_WORDS.getValue(times[0])} more recently than $reference", value)
    } + listOf(
        Input("--user", "owned by USER", Value.Free),
        Input("--group", "owned by GROUP", Value.Free),
        Input("--empty", "file or directory is empty"),
        Input("--readable", "readable by the current user"),
        Input("--writable", "writable by the current user"),
        Input("--executable", "executable/searchable by the current user"),
        Input("--nouser", "no user corresponds to the file's numeric uid"),
        Input("--nogroup", "no group corresponds to the file's numeric gid"),
        Input("--true", "always true"),
        Input("--false", "always false"),
        Input("--print", "print the full file name, then a newline"),
        Input("--print0", "print the full file name, then a NUL"),
        Input("--printf", "print FORMAT, interpreting `%` directives", Value.Free),
        Input("--fprint", "print the full file name into FILE", Value.Path),
        Input("--fprint0", "like --fprint, ending each name with a NUL", Value.Path),
        Input("--fls", "list the file in `ls -dils` format into FILE", Value.Path),
        Input("--ls", "list the file in `ls -dils` format"),
        Input("--delete", "delete the file"),
        Input("--prune", "do not descend into this directory"),
        Input("--quit", "exit immediately"),
        Input("--exec", "one word of the command to run; repeat for each word", Value.Free),
        Input(
            "--execdir",
            "one word of the command to run from the file's directory; repeat for each word",
            Value.Free,
        ),
        Input("--ok", "like --exec, asking first", Value.Free),
        Input("--okdir", "like --execdir, asking first", Value.Free),
    )

    /** `find --help`: "--help display this help and exit", "--version output version information and exit". */
    val BUILTINS: List<Input> = listOf(
        Input("--help", "display this help and exit"),
        Input("--version", "output version information and exit"),
    )

    /** Every long name real find accepts in the fixture's spelling. */
    val LONGS: List<Input> = PREDICATES + BUILTINS

    /**
     * The longs the fixture invents for `-P -L -H -D -O`, and its dashed `--ignore-readdir-race`: `find --logical`
     * and `find . -ignore-readdir-race` are each "unknown predicate".
     */
    val INVENTED_LONGS: List<String> =
        listOf("--physical", "--logical", "--follow-args", "--debug", "--optimise", "--ignore-readdir-race")

    /** klap's built-ins beside `--help` and `--version`: `find --json` and the rest are each "unknown predicate". */
    val KLAP_LONGS: List<String> = listOf("--help-all", "--json", "--color", "--completion", "--docs")

    /** The shell's own file completion, offered where a path stands. */
    val FILES: Suggestion = filesAfter()

    private enum class Phase { OPTIONS, PATHS, EXPRESSION }

    /** What a run of words leaves: how far find has read, a value still due, a raw tail, and what was given. */
    private data class Scan(
        val phase: Phase,
        val pending: Input?,
        val raw: Boolean,
        val operands: Int,
        val files0From: Boolean,
    )

    /**
     * Reads [words] as find reads them. A word find refuses (an unknown or abbreviated predicate, a cluster) claims
     * no word and leaves the phase, as the decided K47 cell has it.
     */
    private fun scan(words: List<String>, from: Phase = Phase.OPTIONS): Scan {
        var phase = from
        var pending: Input? = null
        var raw = false
        var operands = 0
        var files0From = false
        for (word in words) {
            when {
                pending != null -> pending = null
                // `find -- x -files0-from b`: "file operands cannot be combined with -files0-from".
                raw -> operands++
                word == "--" -> raw = true
                word.startsWith("--") -> {
                    val input = LONGS.firstOrNull { it.name == word.substringBefore('=') } ?: continue
                    if (input in PREDICATES) phase = Phase.EXPRESSION
                    if (input.name == "--files0-from") files0From = true
                    if ('=' !in word && input.value != null) pending = input
                }
                word.startsWith("-") && word.length > 1 -> {
                    if (phase == Phase.OPTIONS && word == "-D") pending = SHORTS.last()
                }
                else -> {
                    operands++
                    if (phase == Phase.OPTIONS) phase = Phase.PATHS
                }
            }
        }
        return Scan(phase, pending, raw, operands, files0From)
    }

    private fun pool(phase: Phase, operands: Boolean): List<Suggestion> = buildList {
        if (phase == Phase.OPTIONS) {
            SHORTS.forEach { add(Suggestion(it.name, it.help)) }
            addAll(LEVELS)
        }
        LONGS
            .filterNot { operands && it.name == "--files0-from" }
            .forEach { add(Suggestion(it.name, it.help)) }
    }

    /** What real find would accept for the word [current] (as far as typed) after [head], with [after] past it. */
    fun findOffers(
        head: List<String>,
        current: String,
        after: List<String>,
    ): Set<Suggestion> {
        val before = scan(head)
        val later = scan(after, before.phase)
        // `find . -files0-from x`: "file operands cannot be combined with -files0-from", whatever x is.
        val beside = before.operands + later.operands > 0
        before.pending?.let { pending ->
            return if (pending.name == "--files0-from" && beside) emptySet() else valueOffers(pending, current, null)
        }
        if (before.raw) return if (current.startsWith("-")) emptySet() else setOf(FILES)
        if (current.startsWith("--") && '=' in current) {
            val eq = current.indexOf('=')
            val input = LONGS.firstOrNull { it.name == current.take(eq) } ?: return emptySet()
            if (input.value == null || input.name == "--files0-from" && beside) return emptySet()
            return valueOffers(input, current.drop(eq + 1), head = current.take(eq + 1))
        }
        if (current.startsWith("-")) return pool(before.phase, beside).filter { it.value.startsWith(current) }.toSet()
        val noOperand = before.phase == Phase.EXPRESSION || before.files0From || later.files0From
        return if (noOperand) emptySet() else setOf(FILES)
    }

    private fun valueOffers(
        input: Input,
        partial: String,
        head: String?,
    ): Set<Suggestion> = when (val value = input.value) {
        Value.Path -> setOf(filesAfter(head.orEmpty()))
        is Value.Choices -> {
            val done = if (value.list && ',' in partial) partial.substringBeforeLast(',') + "," else ""
            val typed = partial.drop(done.length)
            val named = done.split(',').filter { it.isNotEmpty() }
            value.values
                .filter { it.startsWith(typed) && !(value.unique && it in named) }
                .map { if (head == null) Suggestion(done + it) else Suggestion(head + done + it, input.help) }
                .toSet()
        }
        else -> emptySet()
    }

    /**
     * One Tab press: its matrix row and form ([kind]), what it completes ([subject]), the line and cursor, and what
     * real find would accept there ([find]).
     */
    data class CompletionCell(
        val kind: String,
        val subject: String,
        val line: String,
        val cursor: Int,
        val find: Set<Suggestion>,
    ) {
        override fun toString(): String =
            if (subject == kind) "$kind: ${marked(line, cursor)}" else "$kind, $subject: ${marked(line, cursor)}"
    }

    fun wordStart(
        line: String,
        cursor: Int,
    ): Int = line.lastIndexOf(' ', cursor - 1) + 1

    /** [marked] with its `|` taken out, the cursor where it stood, and what real find accepts there. */
    fun slot(
        kind: String,
        marked: String,
        subject: String = kind,
        expected: Set<Suggestion>? = null,
    ): CompletionCell {
        val cursor = marked.indexOf('|')
        require(cursor >= 0 && marked.indexOf('|', cursor + 1) < 0) { "one | marks the cursor in `$marked`" }
        val line = marked.removeRange(cursor, cursor + 1)
        val start = wordStart(line, cursor)
        val head = line.take(start).split(' ').filter { it.isNotEmpty() }.drop(1)
        val current = line.substring(start, cursor)
        val after = line.drop(cursor).dropWhile { it != ' ' }.split(' ').filter { it.isNotEmpty() }
        return CompletionCell(kind, subject, line, cursor, expected ?: findOffers(head, current, after))
    }

    /** Tab on [cell] offers find's set once each. */
    fun Cli.assertFind(cell: CompletionCell) {
        val actual = suggestionsFor(cell.line, cell.cursor)
        assertEquals(cell.find.sortedBy { it.value }, actual.sortedBy { it.value }, "$cell: offered (find's set first)")
        assertEquals(actual.size, actual.toSet().size, "$cell: a suggestion is offered twice: $actual")
    }
}
