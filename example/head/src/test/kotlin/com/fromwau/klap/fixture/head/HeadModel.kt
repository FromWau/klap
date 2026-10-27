package com.fromwau.klap.fixture.head

import com.fromwau.klap.io.CliPath
import kotlin.test.fail

/*
 * A table of the inputs the installed GNU head 9.12 documents, from which the matrix tests generate their cells. The
 * help is `head --help` (bold and hyperlink escapes stripped), each entry's lines joined with one space and otherwise
 * verbatim. How each input reads its value is checked with real head runs inside a throwaway directory; the obsolete
 * `-NUM` form is read from `info coreutils 'head invocation'` and checked the same way.
 */

/** How an input reads the words after its name. */
internal sealed interface Kind {
    data object Flag : Kind

    /** A NUM in head's grammar, [sample] one head accepts and [second] another, for a repeat. */
    data class Value(
        val sample: String,
        val second: String,
    ) : Kind
}

/**
 * One input of real head: its [longs], its [short], head's own [help], and how it binds into the fixture's record
 * ([bind]), handed the value as typed for a value input.
 */
internal data class Input(
    val longs: List<String>,
    val short: Char,
    val help: String,
    val kind: Kind,
    val bind: HeadInputs.(String?) -> HeadInputs,
) {
    val name: String get() = "-$short"

    override fun toString(): String = name
}

internal const val BYTES_HELP =
    "print the first NUM bytes of each file; with the leading '-', print all but the last NUM bytes of each file"
internal const val LINES_HELP = "print the first NUM lines instead of the first 10; with the leading '-', print all " +
    "but the last NUM lines of each file"
internal const val QUIET_HELP = "never print headers giving file names"
internal const val VERBOSE_HELP = "always print headers giving file names"
internal const val ZERO_HELP = "line delimiter is NUL, not newline"
internal const val HELP_HELP = "display this help and exit"
internal const val VERSION_HELP = "output version information and exit"

/*
 * `head -c 5 -n 3 f` prints three lines and `head -n 3 -c 5 f` five bytes: the last of -c and -n sets the unit, so
 * the other is absent. `head -v -q a b` prints no headers and `head -q -v a b` prints them: the last of -q and -v sets
 * the header mode, so the other is absent too.
 */

/** Every input of real head other than `--help`, `--version` and the obsolete `-NUM`, in `head --help` order. */
internal val INPUTS: List<Input> = listOf(
    Input(listOf("--bytes"), 'c', BYTES_HELP, Kind.Value(sample = "5", second = "7")) {
        copy(count = Count.Bytes(checkNotNull(it)))
    },
    Input(listOf("--lines"), 'n', LINES_HELP, Kind.Value(sample = "3", second = "4")) {
        copy(count = Count.Lines(checkNotNull(it)))
    },
    Input(listOf("--quiet", "--silent"), 'q', QUIET_HELP, Kind.Flag) { copy(headers = Headers.Never) },
    Input(listOf("--verbose"), 'v', VERBOSE_HELP, Kind.Flag) { copy(headers = Headers.Always) },
    Input(listOf("--zero-terminated"), 'z', ZERO_HELP, Kind.Flag) { copy(zeroTerminated = true) },
)

/**
 * Every long name getopt_long is handed by real head, the undocumented `---presume-input-pipe` among them (`head --=x
 * f` lists it as a possibility).
 */
internal val HEAD_LONGS: List<String> =
    INPUTS.flatMap { it.longs } + listOf("--help", "--version", "---presume-input-pipe")

/** The long names `head --help` shows. */
internal val DOCUMENTED_LONGS: List<String> = HEAD_LONGS - "---presume-input-pipe"

/** klap's built-in long names, which real head refuses (`head --json f`: "unrecognized option '--json'"). */
internal val KLAP_ONLY_LONGS: List<String> = listOf("--json", "--color", "--completion", "--docs")

/** The letters that may follow the digits of head's obsolete first word (`-[NUM][bkm][cqv]`, plus `l` and `z`). */
internal const val OBSOLETE_LETTERS = "bcklmqvz"

internal fun input(name: String): Input =
    INPUTS.firstOrNull { name == "-${it.short}" || name in it.longs } ?: fail("no head input $name")

internal fun inputFor(short: Char): Input? = INPUTS.firstOrNull { it.short == short }

/** The shortest prefix of [long] getopt reads as [long] alone among head's longs, or null when only the whole does. */
internal fun abbreviationOf(long: String): String? = (3 until long.length)
    .map { long.take(it) }
    .firstOrNull { prefix -> HEAD_LONGS.count { it.startsWith(prefix) } == 1 }

/** Whether getopt calls [spelled] ambiguous among head's longs: a prefix of two or more and none of them whole. */
internal fun isAmbiguousLong(spelled: String): Boolean =
    spelled !in HEAD_LONGS && HEAD_LONGS.count { it.startsWith(spelled) } > 1

/** The input real head reads the long name [spelled] as, abbreviated or not; null for the built-ins and the rest. */
internal fun inputOfLong(spelled: String): Input? {
    val long = HEAD_LONGS.firstOrNull { it == spelled } ?: HEAD_LONGS.filter { it.startsWith(spelled) }.singleOrNull()
    return INPUTS.firstOrNull { long in it.longs }
}

/** What a line printing `a` and `b` binds before any option: the record most cells start from. */
internal val A_AND_B: HeadInputs = NOTHING_BOUND.copy(files = listOf(CliPath("a"), CliPath("b")))

/** What a line printing `f` binds before any option. */
internal val F: HeadInputs = NOTHING_BOUND.copy(files = listOf(CliPath("f")))

/** This record with head printing [num] lines of each file, as `-n` spells it. */
internal fun HeadInputs.lines(num: String): HeadInputs = copy(count = Count.Lines(num))

/** This record with head printing [num] bytes of each file, as `-c` spells it. */
internal fun HeadInputs.bytes(num: String): HeadInputs = copy(count = Count.Bytes(num))
