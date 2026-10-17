package com.fromwau.klap.fixture.dd

import com.fromwau.klap.testing.Suggestion
import kotlin.test.fail

/*
 * A table of what the installed GNU dd 9.12 takes, from which the matrix tests generate their cells. The wording is
 * `dd --help` (bold and hyperlink escapes stripped), each entry's lines joined with one space and otherwise verbatim;
 * `iseek=` and `oseek=` have no row of their own and carry the row that names them. What each operand accepts was
 * checked with real dd runs inside a throwaway directory holding a small file `in`, every `if=` and `of=` a relative
 * name there; `info coreutils 'dd invocation'` is quoted where a comment says so.
 */

/** How dd reads the value after an operand's `=`. */
internal enum class ValueKind {
    /** A file name, any text: `dd if==in` opens the file `=in`. */
    FILE,

    /** A block size: a dd number that must not be zero (`dd bs=0`: "invalid number: '0'"). */
    BYTES,

    /** A block count: a dd number, zero included (`dd count=0` copies nothing). */
    COUNT,

    /** A comma separated list of symbols from the operand's own table. */
    SYMBOLS,
}

/**
 * One operand of real dd: its [key], the `dd --help` row describing it ([help]), how it reads its value ([kind]), a
 * value dd accepts ([sample]) and another for a repeat ([second]).
 */
internal data class Operand(
    val key: String,
    val help: String,
    val kind: ValueKind,
    val sample: String,
    val second: String,
) {
    /** The word that names this operand before its value: `if=`. */
    val head: String get() = "$key="

    override fun toString(): String = head
}

internal const val BS_HELP = "read and write up to BYTES bytes at a time (default: 512); overrides ibs and obs"
internal const val CBS_HELP = "convert BYTES bytes at a time"
internal const val CONV_HELP = "convert the file as per the comma separated symbol list"
internal const val COUNT_HELP = "copy only N input blocks"
internal const val IBS_HELP = "read up to BYTES bytes at a time (default: 512)"
internal const val IF_HELP = "read from FILE instead of standard input"
internal const val IFLAG_HELP = "read as per the comma separated symbol list"
internal const val OBS_HELP = "write BYTES bytes at a time (default: 512)"
internal const val OF_HELP = "write to FILE instead of standard output"
internal const val OFLAG_HELP = "write as per the comma separated symbol list"
internal const val SEEK_HELP = "(or oseek=N) skip N obs sized output blocks"
internal const val SKIP_HELP = "(or iseek=N) skip N ibs sized input blocks"
internal const val STATUS_HELP = "The LEVEL of information to print to standard error; 'none' suppresses everything " +
    "but error messages, 'noxfer' suppresses the final transfer statistics, 'progress' shows periodic transfer " +
    "statistics"
internal const val HELP_HELP = "display this help and exit"
internal const val VERSION_HELP = "output version information and exit"

/** Every operand of real dd, in `dd --help` order, each spelling of `seek=` and `skip=` beside the row naming it. */
internal val OPERANDS: List<Operand> = listOf(
    Operand("bs", BS_HELP, ValueKind.BYTES, sample = "4M", second = "1K"),
    Operand("cbs", CBS_HELP, ValueKind.BYTES, sample = "80", second = "40"),
    Operand("conv", CONV_HELP, ValueKind.SYMBOLS, sample = "ucase,notrunc", second = "sync"),
    Operand("count", COUNT_HELP, ValueKind.COUNT, sample = "10", second = "2"),
    Operand("ibs", IBS_HELP, ValueKind.BYTES, sample = "1024", second = "512"),
    Operand("if", IF_HELP, ValueKind.FILE, sample = "in", second = "in2"),
    Operand("iflag", IFLAG_HELP, ValueKind.SYMBOLS, sample = "fullblock,nocache", second = "direct"),
    Operand("obs", OBS_HELP, ValueKind.BYTES, sample = "2048", second = "512"),
    Operand("of", OF_HELP, ValueKind.FILE, sample = "out", second = "out2"),
    Operand("oflag", OFLAG_HELP, ValueKind.SYMBOLS, sample = "append", second = "dsync"),
    Operand("seek", SEEK_HELP, ValueKind.COUNT, sample = "1", second = "2"),
    Operand("oseek", SEEK_HELP, ValueKind.COUNT, sample = "3", second = "4"),
    Operand("skip", SKIP_HELP, ValueKind.COUNT, sample = "2", second = "1"),
    Operand("iseek", SKIP_HELP, ValueKind.COUNT, sample = "4", second = "3"),
    Operand("status", STATUS_HELP, ValueKind.SYMBOLS, sample = "progress", second = "none"),
)

/** The operands with a row of their own in `dd --help`, in its order. */
internal val DOCUMENTED: List<Operand> = OPERANDS.filter { it.key != "oseek" && it.key != "iseek" }

/** One symbol of a CONV, FLAG or LEVEL list, and the words `dd --help` gives it. */
internal data class Symbol(
    val name: String,
    val help: String,
)

/** `dd --help`'s "Each CONV symbol may be" table, in its order. */
internal val CONV_SYMBOLS: List<Symbol> = listOf(
    Symbol("ascii", "from EBCDIC to ASCII"),
    Symbol("ebcdic", "from ASCII to EBCDIC"),
    Symbol("ibm", "from ASCII to alternate EBCDIC"),
    Symbol("block", "pad newline-terminated records with spaces to cbs-size"),
    Symbol("unblock", "replace trailing spaces in cbs-size records with newline"),
    Symbol("lcase", "change upper case to lower case"),
    Symbol("ucase", "change lower case to upper case"),
    Symbol("sparse", "try to seek rather than write all-NUL output blocks"),
    Symbol("swab", "swap every pair of input bytes"),
    Symbol(
        "sync",
        "pad every input block with NULs to ibs-size; when used with block or unblock, pad with spaces rather " +
            "than NULs",
    ),
    Symbol("excl", "fail if the output file already exists"),
    Symbol("nocreat", "do not create the output file"),
    Symbol("notrunc", "do not truncate the output file"),
    Symbol("noerror", "continue after read errors"),
    Symbol("fdatasync", "physically write output file data before finishing"),
    Symbol("fsync", "likewise, but also write metadata"),
)

/** `dd --help`'s "Each FLAG symbol may be" table, in its order. */
internal val FLAG_SYMBOLS: List<Symbol> = listOf(
    Symbol("append", "append mode (makes sense only for output; conv=notrunc suggested)"),
    Symbol("direct", "use direct I/O for data"),
    Symbol("directory", "fail unless a directory"),
    Symbol("dsync", "use synchronized I/O for data"),
    Symbol("sync", "likewise, but also for metadata"),
    Symbol("fullblock", "accumulate full blocks of input (iflag only)"),
    Symbol("nonblock", "use non-blocking I/O"),
    Symbol("noatime", "do not update access time"),
    Symbol("nocache", "Request to drop cache.  See also oflag=sync"),
    Symbol("noctty", "do not assign controlling terminal from file"),
    Symbol("nofollow", "do not follow symlinks"),
)

/** The input flags: every FLAG symbol (`dd iflag=append` runs). */
internal val IFLAG_SYMBOLS: List<Symbol> = FLAG_SYMBOLS

/** The output flags: every FLAG symbol but fullblock (`dd oflag=fullblock`: "invalid output flag: 'fullblock'"). */
internal val OFLAG_SYMBOLS: List<Symbol> = FLAG_SYMBOLS.filter { it.name != "fullblock" }

/** The status LEVELs, each with the clause of `status=`'s row that describes it. */
internal val STATUS_LEVELS: List<Symbol> = listOf(
    Symbol("none", "suppresses everything but error messages"),
    Symbol("noxfer", "suppresses the final transfer statistics"),
    Symbol("progress", "shows periodic transfer statistics"),
)

/**
 * Flags real dd accepts on both sides though `dd --help` lists none of them: `dd iflag=count_bytes count=0` and the
 * other two run, each without a warning. The info page names neither.
 */
internal val UNDOCUMENTED_FLAGS: List<String> = listOf("count_bytes", "skip_bytes", "seek_bytes")

/**
 * Flags the info page lists that this dd refuses on both sides, as not supported on this system: `dd iflag=nolinks`:
 * "invalid input flag: 'nolinks'", and the same for cio, binary and text.
 */
internal val UNSUPPORTED_FLAGS: List<String> = listOf("nolinks", "cio", "binary", "text")

/**
 * The CONV symbols dd refuses together: `dd conv=ascii,ebcdic`: "cannot combine any two of {ascii,ebcdic,ibm}", `dd
 * conv=lcase,ucase`: "cannot combine lcase and ucase", `dd conv=excl,nocreat`: "cannot combine excl and nocreat".
 */
internal val CONV_EXCLUSIVE: List<Set<String>> = listOf(
    setOf("ascii", "ebcdic", "ibm"),
    setOf("lcase", "ucase"),
    setOf("excl", "nocreat"),
)

/**
 * The info page calls block and unblock mutually exclusive, and dd holds them so only once `cbs=` is on the line, with
 * ascii counting as unblock and ebcdic and ibm as block: `dd conv=block,unblock cbs=4`, `conv=ascii,block cbs=4` and
 * `conv=ebcdic,unblock cbs=4`: "cannot combine block and unblock", while `dd conv=block,unblock`, `conv=ebcdic,unblock`
 * and `conv=ebcdic,block cbs=4` run.
 */
private val BLOCKING: Set<String> = setOf("block", "ebcdic", "ibm")
private val UNBLOCKING: Set<String> = setOf("unblock", "ascii")

/** Whether dd refuses the CONV symbols [names] on one line, [cbs] telling whether `cbs=` is on it too. */
internal fun refusedTogether(
    names: Set<String>,
    cbs: Boolean,
): Boolean = CONV_EXCLUSIVE.any { set -> names.count { it in set } > 1 } ||
    cbs && names.any { it in BLOCKING } && names.any { it in UNBLOCKING }

/** The symbol table `key=` takes its list from. */
internal fun symbolsOf(key: String): List<Symbol> = when (key) {
    "conv" -> CONV_SYMBOLS
    "iflag" -> IFLAG_SYMBOLS
    "oflag" -> OFLAG_SYMBOLS
    "status" -> STATUS_LEVELS
    else -> fail("$key= takes no symbol list")
}

/**
 * The symbols dd still takes in [key]'s list after [given]: every one dd does not refuse beside them, a repeat
 * included (`dd conv=ucase,ucase` runs), [cbs] telling whether `cbs=` is on the line.
 */
internal fun symbolsAfter(
    key: String,
    given: List<String>,
    cbs: Boolean = false,
): List<Symbol> = symbolsOf(key).filter { key != "conv" || !refusedTogether(given.toSet() + it.name, cbs) }

/** `key=` as Tab offers it on a word with no `=` yet: the key and its value's head, described by its row. */
internal fun keySuggestion(operand: Operand): Suggestion = Suggestion(operand.head, operand.help)

/** Every key dd takes, as Tab should offer it on an empty operand word, in `dd --help` order. */
internal val KEYS: List<Suggestion> = OPERANDS.map(::keySuggestion)

/** The keys with a row of their own in `dd --help`. */
internal val DOCUMENTED_KEYS: List<Suggestion> = DOCUMENTED.map(::keySuggestion)

/** The keys real dd takes that start with [typed]. */
internal fun keysStarting(typed: String): List<Suggestion> = KEYS.filter { it.value.startsWith(typed) }

/** `--help` and `--version` as Tab should offer them, in `dd --help` words. */
internal val DD_OPTIONS: List<Suggestion> =
    listOf(Suggestion("--help", HELP_HELP), Suggestion("--version", VERSION_HELP))

/** klap's built-in long names, which real dd refuses (`dd --json if=in`: "unrecognized option '--json'"). */
internal val KLAP_ONLY_LONGS: List<String> = listOf("--json", "--color", "--completion", "--docs")

/**
 * What dd reads each number the cells type as, by `dd --help`: "c=1, w=2, b=512, kB=1000, K=1024, MB=1000*1000,
 * M=1024*1024, xM=M ... KiB=K, MiB=M"; a `D` after a multiplier is decimal like a `B` (`dd count=1kD` at ibs=2 copies
 * 2000 bytes), a multiplier alone counts one (`bs=K`), blanks and a `+` may open it, and `010` is decimal ten.
 */
private val NUMBERS: Map<String, Long> = mapOf(
    "0" to 0, "0B" to 0, "2x0" to 0, "00x10" to 0, "0x10" to 0,
    "1" to 1, "1B" to 1, "1c" to 1, "c" to 1, "1cB" to 1, " 5" to 5, "\t5" to 5, "\n5" to 5, "+5" to 5, " +5" to 5,
    "1w" to 2, "1wB" to 2, "2" to 2, "2B" to 2, "1x2B" to 2, "2Bx1" to 2, "3" to 3, "3B" to 3, "4" to 4,
    "2x3" to 6, "2x+3" to 6, "2x 3" to 6, "010" to 10,
    "10" to 10, "2x3x4" to 24, "40" to 40, "80" to 80, "512" to 512, "1b" to 512, "1bB" to 512,
    "1kB" to 1000, "1KB" to 1000, "1KD" to 1000, "1kD" to 1000,
    "1K" to 1024, "1k" to 1024, "1KiB" to 1024, "1kiB" to 1024, "K" to 1024, "1024" to 1024,
    "2048" to 2048, "Kx2" to 2048, "2xK" to 2048, "1MB" to 1_000_000, "1MD" to 1_000_000,
    "1M" to 1_048_576, "1MiB" to 1_048_576, "1GD" to 1_000_000_000,
    "4M" to 4_194_304, "1E" to 1_152_921_504_606_846_976, "9223372036854775807" to Long.MAX_VALUE,
)

private fun number(value: String): Long = NUMBERS[value] ?: fail("no reading of the dd number '$value' in NUMBERS")

/**
 * A count or an offset: "If N ends in 'B', it counts bytes not blocks", wherever the B stands (`dd ibs=10 count=1Bx2`
 * copies two bytes, `dd ibs=2 count=1kB` 1000).
 */
private fun amount(value: String): Amount =
    if ('B' in value) Amount.Bytes(number(value)) else Amount.Blocks(number(value))

private inline fun <reified E : Enum<E>> symbol(name: String): E =
    enumValues<E>().singleOrNull { it.name.lowercase() == name } ?: fail("no ${E::class.simpleName} named $name")

/** The FLAG symbols of a list; the undocumented byte flags bind no flag, and are checked on their own. */
private fun flags(value: String): List<Flag> = value.split(',').filter { it !in UNDOCUMENTED_FLAGS }.map(::symbol)

/**
 * What a line binds, as dd reads it: the last of a key wins (`dd count=1 count=2` copies two blocks), conv=, iflag=
 * and oflag= add up, a status list binds its last level, and bs= sets ibs and obs wherever it stands (`dd bs=10
 * ibs=4` and `dd ibs=4 bs=10` read 10 bytes at a time).
 */
internal fun bound(vararg words: String): DdInputs {
    var inputs = NOTHING_BOUND
    var bs: Long? = null
    for (word in words) {
        val value = word.substringAfter('=')
        inputs = when (word.substringBefore('=')) {
            "if" -> inputs.copy(input = Stream.File(value))
            "of" -> inputs.copy(output = Stream.File(value))
            "bs" -> inputs.also { bs = number(value) }
            "ibs" -> inputs.copy(ibs = number(value))
            "obs" -> inputs.copy(obs = number(value))
            "cbs" -> inputs.copy(cbs = Cbs.Bytes(number(value)))
            "count" -> inputs.copy(count = amount(value))
            "skip", "iseek" -> inputs.copy(skip = amount(value))
            "seek", "oseek" -> inputs.copy(seek = amount(value))
            "conv" -> inputs.copy(conv = inputs.conv + value.split(',').map { symbol<Conv>(it) })
            "iflag" -> inputs.copy(iflag = inputs.iflag + flags(value))
            "oflag" -> inputs.copy(oflag = inputs.oflag + flags(value))
            "status" -> inputs.copy(status = symbol(value.substringAfterLast(',')))
            else -> fail("$word is no operand dd binds")
        }
    }
    return bs?.let { inputs.copy(ibs = it, obs = it) } ?: inputs
}

/** The symbols whose names start with [typed]. */
internal fun List<Symbol>.startingWith(typed: String): List<Symbol> = filter { it.name.startsWith(typed) }
