package com.fromwau.klap.fixture.head

import com.fromwau.kern.result.Ok
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Builtin
import com.fromwau.klap.Help
import com.fromwau.klap.io.CliPath
import com.fromwau.klap.io.path
import com.fromwau.klap.TypedCli
import com.fromwau.klap.ValueScope
import com.fromwau.klap.Version
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * GNU coreutils 9.12 `head`, reproduced as a command-line surface only.
 *
 * Real usage: `head [OPTION]... [FILE]...`
 * ```
 * -c, --bytes=[-]NUM     print the first NUM bytes of each file; with the leading '-', print all but the last
 *                        NUM bytes of each file
 * -n, --lines=[-]NUM     print the first NUM lines instead of the first 10; with the leading '-', print all but
 *                        the last NUM lines of each file
 * -q, --quiet, --silent  never print headers giving file names
 * -v, --verbose          always print headers giving file names
 * -z, --zero-terminated  line delimiter is NUL, not newline
 *     --help             display this help and exit
 *     --version          output version information and exit
 * ```
 *
 * Real head also honours the obsolete `head -NUM` form, which is why this tool is in the suite; see the
 * obsolete count below. NUM may carry a multiplier suffix: b 512, kB 1000, K 1024, MB 1000*1000,
 * M 1024*1024, and so on for G, T, P, E, Z, Y, R, Q, plus the binary spellings KiB, MiB, ...
 */
public fun headCli(): TypedCli<HeadInputs> = cliOf("head") {
    // GNU head takes any unambiguous prefix of a long option: `head --ver f` is ambiguous.
    abbreviation = Abbreviation.Options
    // GNU head has none of klap's built-ins but --help, which has no short (`head -h` is "invalid option -- 'h'").
    builtins {
        help = Help.ByCommand(description = "display this help and exit")
        json = Builtin.Off
        color = Builtin.Off
        completion = Builtin.Off
        docs = Builtin.Off
    }
    help = "Print the first 10 lines of each FILE to standard output."
    version = Version.Of("9.12", description = "output version information and exit", text = HEAD_VERSION_TEXT)
    epilogue = "With no FILE, or when FILE is -, read standard input. " +
        "NUM may have a multiplier suffix: b 512, kB 1000, K 1024, MB 1000*1000, M 1024*1024, and so on."

    example("head -n 20 notes.txt", "print the first twenty lines")
    example("head -c 512 image.bin", "print the first 512 bytes")
    example("head -n -5 notes.txt", "print all but the last five lines")
    example("head -qv a.txt b.txt", "two operands; the later of -q/-v decides whether headers print")

    // A dash-led NUM reaches this option's value slot in both forms: `-n -5` and `--lines -5`.
    val lines = option(
        short = 'n',
        long = "lines",
        help = "print the first NUM lines instead of the first 10; with the leading '-', print all but the last " +
            "NUM lines of each file",
    )
        .placeholder("[-]NUM")
        .map<Count> { Count.Lines(it) }
        .validate("invalid number of lines") { HEAD_NUM.matches(it.num) }

    val bytes = option(
        short = 'c',
        long = "bytes",
        help = "print the first NUM bytes of each file; with the leading '-', print all but the last NUM bytes of " +
            "each file",
    )
        .placeholder("[-]NUM")
        .map<Count> { Count.Bytes(it) }
        .validate("invalid number of bytes") { HEAD_NUM.matches(it.num) }

    // Real head's obsolete first word: `head -5 f` is `-n 5` and `head -5c f` is `-c 5`, not a flag named 5.
    val obsolete = numberOption(
        help = "same as -n NUM, or -c NUM after a b, c, k or m; the obsolete form real head still accepts",
        firstWordOnly = true,
        suffixes = "bcklm",
    )
        .map(::obsoleteCount)

    // Real head lets the last of -c, -n and the obsolete count decide the count: `head -c 5 -n 3 f` prints three
    // lines and `head -n 3 -c 5 f` five bytes. That is an override rule, so atMostOne would be wrong here.
    val count = lastOf(lines, bytes, obsolete)

    // One holder carries all three of real head's spellings, so `-q`, `--quiet` and `--silent` are the
    // one input GNU head documents rather than three that an action has to OR back together.
    val quiet = flag(
        short = 'q',
        long = listOf("quiet", "silent"),
        help = "never print headers giving file names",
    )

    val verbose = flag(short = 'v', long = "verbose", help = "always print headers giving file names")

    // `head -vq a b` prints no headers and `head -qv a b` prints them.
    lastWins(quiet, verbose)

    val zeroTerminated = flag(short = 'z', long = "zero-terminated", help = "line delimiter is NUL, not newline")

    // Deliberately NOT `dashLed()`, unlike chmod's mode: real head rejects a dash-led FILE, so `head -x f`
    // must stay an unknown option here too. Only `-NUM` slips through, claimed by the number input above.
    val files = argument("file", "file to print the head of; '-' means standard input")
        .path()
        .multiple()

    val read: ValueScope.() -> HeadInputs = {
        HeadInputs(
            count = count() ?: Count.Lines("10"),
            headers = when {
                quiet() -> Headers.Never
                verbose() -> Headers.Always
                else -> Headers.WhenSeveral
            },
            zeroTerminated = zeroTerminated(),
            files = files(),
        )
    }

    action<String>(human = { it }) {
        val bound = read()
        val unit = when (val count = bound.count) {
            is Count.Lines -> "${count.num} line(s)"
            is Count.Bytes -> "${count.num} byte(s)"
        }
        val operands = bound.files.ifEmpty { listOf(CliPath("-")) }
        val delimiter = if (bound.zeroTerminated) "NUL" else "newline"
        Ok("would print $unit of ${operands.size} operand(s), headers=${bound.headers}, delimiter=$delimiter")
    }

    projection(read)
}

/** How many lines or bytes head prints of each file, NUM kept as typed: `-n NUM` or `-c NUM`. */
public sealed interface Count {
    /** NUM as typed, its sign and multiplier suffix included. */
    public val num: String

    /** `-n NUM`: "print the first NUM lines instead of the first 10". */
    public data class Lines(override val num: String) : Count

    /** `-c NUM`: "print the first NUM bytes of each file". */
    public data class Bytes(override val num: String) : Count
}

/** When head prints a header giving a file's name: `-q` never, `-v` always, the last of them winning. */
public enum class Headers {
    /** Neither given: "With more than one FILE, precede each with a header giving the file name." */
    WhenSeveral,

    /** `-q`: "never print headers giving file names". */
    Never,

    /** `-v`: "always print headers giving file names". */
    Always,
}

/**
 * What one `head` line binds, as head reads it rather than as the inputs that spell it: `-n`, `-c` and the
 * obsolete `-NUM` are one [count], and `-q` and `-v` are one [headers].
 *
 * A parity case asserts a whole invocation against a `copy()` of [NOTHING_BOUND], so every field it does not name
 * is pinned to its default too.
 */
public data class HeadInputs(
    val count: Count,
    val headers: Headers,
    val zeroTerminated: Boolean,
    val files: List<CliPath>,
)

/** `head` with no arguments at all: every field at the default head gives it. */
public val NOTHING_BOUND: HeadInputs = HeadInputs(
    count = Count.Lines("10"),
    headers = Headers.WhenSeveral,
    zeroTerminated = false,
    files = emptyList(),
)

private const val UNIT = """(?:b|[kKmMGTPEZYRQ](?:B|iB)?)"""

/**
 * NUM as head's `xstrtoumax` reads it after one leading '-': blanks, an optional '+', then decimal digits with an
 * optional multiplier, or a multiplier alone, which counts one of it.
 */
private val HEAD_NUM = Regex("""-?[ \t\n\u000B\f\r]*\+?(?:[0-9]+$UNIT?|$UNIT)""")

/**
 * Reads the obsolete `-[NUM][bkm][cqv]` count, digits then unit letters in the order typed, into a [Count] spelled
 * as `-n` or `-c` would take it. The last unit letter wins: `b`, `k` and `m` count bytes in that multiplier, `c`
 * bytes in ones, and `l` lines in the multiplier so far, so `3bl` is 1536 lines and `3bc` three bytes.
 */
internal fun obsoleteCount(value: String): Count {
    val digits = value.takeWhile { it in '0'..'9' }
    var countsBytes = false
    var multiplier = ""
    for (letter in value.drop(digits.length)) {
        when (letter) {
            'b', 'k', 'm' -> {
                countsBytes = true
                multiplier = letter.toString()
            }
            'c' -> {
                countsBytes = true
                multiplier = ""
            }
            'l' -> countsBytes = false
            else -> throw IllegalArgumentException("'$letter' is no unit letter of head's obsolete count")
        }
    }
    val num = digits + multiplier
    return if (countsBytes) Count.Bytes(num) else Count.Lines(num)
}

/**
 * What `head --version` prints for GNU coreutils 9.12, the version installed when it was pinned
 * (2026-10-10).
 */
internal val HEAD_VERSION_TEXT: String = """head (GNU coreutils) 9.12
Copyright (C) 2026 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>.
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Written by David MacKenzie and Jim Meyering."""
