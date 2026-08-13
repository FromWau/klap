package com.fromwau.klap.fixture.head

import com.fromwau.kern.result.Ok
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.TypedCli
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * GNU coreutils 9.11 `head`, reproduced as a command-line surface only.
 *
 * Real usage: `head [OPTION]... [FILE]...`
 *   -c, --bytes=[-]NUM     print the first NUM bytes of each file; with the leading '-',
 *                          print all but the last NUM bytes of each file
 *   -n, --lines=[-]NUM     print the first NUM lines instead of the first 10; with the leading '-',
 *                          print all but the last NUM lines of each file
 *   -q, --quiet, --silent  never print headers giving file names
 *   -v, --verbose          always print headers giving file names
 *   -z, --zero-terminated  line delimiter is NUL, not newline
 *       --help / --version
 *
 * Real head also honours the obsolete `head -NUM` form, which is why this tool is in the suite; see the
 * digit-short note below. NUM may carry a multiplier suffix: b 512, kB 1000, K 1024, MB 1000*1000,
 * M 1024*1024, and so on for G, T, P, E, Z, Y, R, Q, plus the binary spellings KiB, MiB, ...
 */

/** `head --help`: NUM is a count with an optional leading '-' and an optional multiplier suffix. */
private val HEAD_NUM = Regex("""-?\d+(?:b|[kKMGTPEZYRQ](?:B|iB)?)?""")

public fun headCli(): TypedCli<HeadInputs> = cliOf("head") {
    // GNU head takes any unambiguous prefix of a long option: `head --ver f` is ambiguous.
    abbreviation = Abbreviation.Options
    description = "Print the first 10 lines of each FILE to standard output."
    version = "9.11"
    epilogue = "With no FILE, or when FILE is -, read standard input. " +
        "NUM may have a multiplier suffix: b 512, kB 1000, K 1024, MB 1000*1000, M 1024*1024, and so on."

    example("head -n 20 notes.txt", "print the first twenty lines")
    example("head -c 512 image.bin", "print the first 512 bytes")
    example("head -n -5 notes.txt", "print all but the last five lines")
    example("head -qv a.txt b.txt", "two operands; the later of -q/-v decides whether headers print")

    // A dash-led NUM reaches this option's value slot in both forms: `-n -5` and `--lines -5`.
    val named = option("--lines", "-n", help = "print the first NUM lines instead of the first 10")
        .validate("invalid number of lines") { HEAD_NUM.matches(it) }
        .placeholder("[-]NUM")

    // Real head's obsolete `head -5 f` is shorthand for `-n 5`, not a flag named 5. Its own help text
    // rather than a copy of `--lines`': the two render as two rows, and repeating the sentence would
    // read as two settings that happen to be described identically.
    val direct = numberOption(help = "same as -n NUM; the obsolete form real head still accepts")

    // The two spellings of one quantity, folded so the action reads a line count without knowing which was
    // written; the fold then takes part in the unit override below as one member.
    val lines = lastOneWins(named, direct)

    val bytes = option("--bytes", "-c", help = "print the first NUM bytes of each file")
        .validate("invalid number of bytes") { HEAD_NUM.matches(it) }
        .placeholder("[-]NUM")

    // Real head lets the LAST of -c/-n decide the unit: `head -c 5 -n 3 f` prints three lines and
    // `head -n 3 -c 5 f` prints five bytes, both verified against coreutils 9.11. That is an override
    // rule, so requireAtMostOne would be wrong here: it would reject lines real head accepts.
    lastWins(lines, bytes)

    // One holder carries all three of real head's spellings, so `-q`, `--quiet` and `--silent` are the
    // one input GNU head documents rather than three that an action has to OR back together.
    val quiet = flag("--quiet", "-q", "--silent", help = "never print headers giving file names")

    val verbose = flag("--verbose", "-v", help = "always print headers giving file names")

    val zeroTerminated = flag("--zero-terminated", "-z", help = "line delimiter is NUL, not newline")

    // Deliberately NOT `dashLed()`, unlike chmod's mode: real head rejects a dash-led FILE, so `head -x f`
    // must stay an unknown option here too. Only `-NUM` slips through, claimed by the number input above.
    val files = argument("file", "file to print the head of; '-' means standard input")
        .file()
        .multiple()

    action<String>(human = { it }) {
        val unit = bytes()?.let { "$it byte(s)" } ?: lines()?.let { "$it line(s)" } ?: "10 line(s)"
        val headers = when {
            verbose() -> "always"
            quiet() -> "never"
            else -> "when more than one operand"
        }
        val operands = files().ifEmpty { listOf("-") }
        val delimiter = if (zeroTerminated()) "NUL" else "newline"
        Ok("would print $unit of ${operands.size} operand(s), headers=$headers, delimiter=$delimiter")
    }

    projection { HeadInputs(lines(), bytes(), quiet(), verbose(), zeroTerminated(), files()) }
}

/**
 * What one `head` line binds, as values rather than as the handles that read them.
 *
 * Being a plain data class is the point: a parity case asserts a whole invocation with one `assertEquals`
 * against a `copy()` of [NOTHING_BOUND], so every field it does *not* name is pinned to its default too.
 */
public data class HeadInputs(
    val lines: String?,
    val bytes: String?,
    val quiet: Boolean,
    val verbose: Boolean,
    val zeroTerminated: Boolean,
    val files: List<String>,
)

/** `head` with no arguments at all: every field at the default the declaration gives it. */
public val NOTHING_BOUND: HeadInputs = HeadInputs(
    lines = null,
    bytes = null,
    quiet = false,
    verbose = false,
    zeroTerminated = false,
    files = emptyList(),
)
