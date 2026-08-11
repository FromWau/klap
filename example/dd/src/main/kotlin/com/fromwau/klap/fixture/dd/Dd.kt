package com.fromwau.klap.fixture.dd

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.mapError
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.ConversionError
import com.fromwau.klap.TypedCli
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * GNU coreutils 9.11 `dd`, reproduced as a command-line surface only.
 *
 * Real usage: `dd [OPERAND]...`
 *         or: `dd OPTION`
 *
 * dd is the odd one out among the coreutils: it has NO flags and NO options. Every operand is a bare
 * `key=value` token with no leading dash, order-independent, all optional, and a repeated key silently
 * takes its last occurrence. The only dash-led tokens it accepts are `--help` and `--version`.
 *
 *   bs=BYTES cbs=BYTES conv=CONVS count=N ibs=BYTES if=FILE iflag=FLAGS
 *   obs=BYTES of=FILE oflag=FLAGS seek=N (oseek=N) skip=N (iseek=N) status=LEVEL
 */
public fun ddCli(): TypedCli<DdInputs> = cliOf("dd") {
    // dd declares no long option of its own, but real dd still abbreviates klap's injected --help/--version.
    abbreviation = Abbreviation.Options
    description = "Copy a file, converting and formatting according to the operands"
    version = "9.11"

    example("dd if=/dev/zero of=out.img bs=4M count=10 status=progress", "write a 40 MiB file, with progress")
    example("dd if=disk.img of=/dev/sdb bs=1M conv=fsync oflag=direct", "restore an image with direct I/O")
    example("dd if=in.txt of=out.txt conv=ucase,notrunc skip=1 seek=1", "several operands, in any order")

    // dd's 14 documented operands cannot be declared individually: klap binds positionals strictly by
    // declaration order, but dd's operands are order-independent and every one is optional, so
    // `dd count=1 if=x` would bind "count=1" to an `if` slot. klap also has no concept of a NAMED
    // operand — a token is either dash-led (option/flag) or an anonymous positional — so a third input
    // kind would be needed to serve this one tool, which is not worth the parser surface it would add.
    //
    // So all 14 operands collapse into ONE repeated positional plus a hand-written key=value converter,
    // at the cost of per-operand help rows/defaults/choices (recovered only as prose in `epilogue`) and
    // dd's own error wording. Bare `dd` (zero operands) still parses: `.multiple()` defaults to min = 0.
    val operands = argument("operand", "an operand of the form key=value")
        .convert { raw -> ddOperand(raw).mapError { ConversionError.Domain(it, it.detail()) } }
        .multiple()
        .completeWith {
            val eq = current.indexOf('=')
            if (eq < 0) {
                DD_KEY_HELP.forEach { (key, help) -> candidate("$key=", help) }
            } else {
                val key = current.take(eq)
                if (key in DD_FILE_KEYS) {
                    completeFiles(nonPathPrefix = "$key=")
                } else {
                    ddValueCandidates(key).forEach { value -> candidate("$key=$value") }
                }
            }
        }

    // KLAP-GAP: klap accepts built-ins that real dd rejects as operands (`--json`, `--color`, `--help-all`,
    // `--completion`, `--docs`); `builtins { }` can decline all but `--help`/`--help-all`, but this
    // fixture declines nothing.
    // KLAP-GAP: klap has no declarative form for dd's `name=value` operands, so this fixture documents
    // them in the epilogue instead of declaring them.
    epilogue = """
        Operands:
          bs=BYTES      read and write up to BYTES bytes at a time (default: 512); overrides ibs and obs
          cbs=BYTES     convert BYTES bytes at a time
          conv=CONVS    convert the file as per the comma separated symbol list
          count=N       copy only N input blocks
          ibs=BYTES     read up to BYTES bytes at a time (default: 512)
          if=FILE       read from FILE instead of standard input
          iflag=FLAGS   read as per the comma separated symbol list
          obs=BYTES     write BYTES bytes at a time (default: 512)
          of=FILE       write to FILE instead of standard output
          oflag=FLAGS   write as per the comma separated symbol list
          seek=N        (or oseek=N) skip N obs sized output blocks
          skip=N        (or iseek=N) skip N ibs sized input blocks
          status=LEVEL  the LEVEL of information to print to stderr: none, noxfer, progress

        N and BYTES may be followed by a multiplicative suffix (c=1, w=2, b=512, kB=1000, K=1024, MB, M,
        GB, G, and so on for T, P, E, Z, Y, R, Q; KiB=K, MiB=M, ...) and may be an xN product. A trailing
        B counts bytes rather than blocks.

        CONV symbols: ${DD_CONV_SYMBOLS.joinToString(", ")}
        FLAG symbols: ${DD_IFLAG_SYMBOLS.joinToString(", ")} (fullblock is iflag-only, append is oflag-only)
    """.trimIndent()

    action<String>(human = { it }) {
        // dd lets a key repeat and keeps the last occurrence; associate() has the same last-wins rule.
        val settings = operands().associate { it.key to it.value }
        val input = settings["if"] ?: "standard input"
        val output = settings["of"] ?: "standard output"
        val count = settings["count"] ?: "all"
        Ok("would copy $count block(s) of ${settings["bs"] ?: "512"} bytes from $input to $output")
    }

    projection { DdInputs(operands()) }
}

// --- the hand-written operand grammar klap cannot express declaratively ---

public data class DdOperand(val key: String, val value: String)

private val DD_FILE_KEYS = setOf("if", "of")
private val DD_BYTE_KEYS = setOf("bs", "ibs", "obs", "cbs")
private val DD_COUNT_KEYS = setOf("count", "seek", "oseek", "skip", "iseek")

private val DD_STATUS_LEVELS = listOf("none", "noxfer", "progress")

private val DD_CONV_SYMBOLS = listOf(
    "ascii", "ebcdic", "ibm", "block", "unblock", "lcase", "ucase", "sparse", "swab", "sync",
    "excl", "nocreat", "notrunc", "noerror", "fdatasync", "fsync",
)

private val DD_IFLAG_SYMBOLS = listOf(
    "append", "direct", "directory", "dsync", "sync", "fullblock", "nonblock", "noatime", "nocache",
    "noctty", "nofollow",
)

// dd accepts the same symbol table on both sides and only rejects the misplaced ones at run time
// ("fullblock" is iflag-only, "append" oflag-only), so the two lists are deliberately identical here.
private val DD_OFLAG_SYMBOLS = DD_IFLAG_SYMBOLS

/** Multiplicative suffixes accepted after a dd number, including the empty (plain-count) case. */
private val DD_SIZE_SUFFIXES = setOf(
    "", "B", "c", "w", "b",
    "kB", "K", "KiB", "MB", "M", "MiB", "GB", "G", "GiB", "TB", "T", "TiB",
    "PB", "P", "PiB", "EB", "E", "EiB", "ZB", "Z", "ZiB", "YB", "Y", "YiB",
    "RB", "R", "RiB", "QB", "Q", "QiB",
)

private val DD_KEY_HELP: List<Pair<String, String>> = listOf(
    "bs" to "read and write up to BYTES bytes at a time (default: 512)",
    "cbs" to "convert BYTES bytes at a time",
    "conv" to "convert the file as per the comma separated symbol list",
    "count" to "copy only N input blocks",
    "ibs" to "read up to BYTES bytes at a time (default: 512)",
    "if" to "read from FILE instead of standard input",
    "iflag" to "read as per the comma separated symbol list",
    "obs" to "write BYTES bytes at a time (default: 512)",
    "of" to "write to FILE instead of standard output",
    "oflag" to "write as per the comma separated symbol list",
    "seek" to "skip N obs sized output blocks",
    "skip" to "skip N ibs sized input blocks",
    "status" to "the LEVEL of information to print to standard error",
)

/** `4M`, `512`, `2x1024`, `10B`: digits with an optional multiplicative suffix, optionally an xN product. */
private fun ddNumberIsValid(raw: String): Boolean =
    raw.isNotEmpty() && raw.split("x").all { term ->
        val digits = term.takeWhile { it.isDigit() }
        digits.isNotEmpty() && term.substring(digits.length) in DD_SIZE_SUFFIXES
    }

/**
 * dd's operand grammar has five distinct ways to be wrong. Each is a case carrying what went wrong, with
 * no wording on it: `parse()` callers branch on the case, and [detail] below picks the English once.
 */
private sealed interface DdOperandError : IError {
    data object NotKeyValue : DdOperandError

    data class UnknownKey(val key: String) : DdOperandError

    data class NotANumber(val value: String) : DdOperandError

    data class UnknownSymbol(val symbol: String, val key: String, val allowed: List<String>) : DdOperandError

    data class BadStatusLevel(val value: String) : DdOperandError
}

/** dd's own wording, chosen at the boundary rather than baked into [DdOperandError]. */
private fun DdOperandError.detail(): String = when (this) {
    DdOperandError.NotKeyValue -> "unrecognized operand: every dd operand is written key=value"
    is DdOperandError.UnknownKey -> "unrecognized operand key '$key'"
    is DdOperandError.NotANumber ->
        "'$value' is not a dd number (digits, an optional multiplicative suffix, and optional xN products)"

    is DdOperandError.UnknownSymbol -> "unknown $key symbol '$symbol' (choose from ${allowed.joinToString(", ")})"
    is DdOperandError.BadStatusLevel ->
        "invalid status level '$value' (choose from ${DD_STATUS_LEVELS.joinToString(", ")})"
}

/** The first unknown symbol in a comma separated list, or null when every symbol is known. */
private fun ddBadSymbol(value: String, allowed: List<String>, label: String): DdOperandError.UnknownSymbol? =
    value.split(",").firstOrNull { it !in allowed }
        ?.let { DdOperandError.UnknownSymbol(it, label, allowed) }

/**
 * The whole of dd's operand grammar, re-implemented because klap has no per-key hook for a `key=value`
 * token: it splits the token, then applies the check the corresponding `.int()`/`.choice()`/`.enum<E>()`
 * converter would have applied had the key been declarable as its own input.
 */
private fun ddOperand(raw: String): Result<DdOperand, DdOperandError> {
    val eq = raw.indexOf('=')
    if (eq <= 0) return Err(DdOperandError.NotKeyValue)
    val key = raw.take(eq)
    val value = raw.substring(eq + 1)
    val operand = DdOperand(key, value)
    return when {
        key in DD_FILE_KEYS -> Ok(operand)

        key in DD_BYTE_KEYS || key in DD_COUNT_KEYS ->
            if (ddNumberIsValid(value)) {
                Ok(operand)
            } else {
                Err(DdOperandError.NotANumber(value))
            }

        key == "conv" -> {
            val bad = ddBadSymbol(value, DD_CONV_SYMBOLS, "conv")
            if (bad != null) Err(bad) else Ok(operand)
        }

        key == "iflag" -> {
            val bad = ddBadSymbol(value, DD_IFLAG_SYMBOLS, "iflag")
            if (bad != null) Err(bad) else Ok(operand)
        }

        key == "oflag" -> {
            val bad = ddBadSymbol(value, DD_OFLAG_SYMBOLS, "oflag")
            if (bad != null) Err(bad) else Ok(operand)
        }

        key == "status" ->
            if (value in DD_STATUS_LEVELS) {
                Ok(operand)
            } else {
                Err(DdOperandError.BadStatusLevel(value))
            }

        else -> Err(DdOperandError.UnknownKey(key))
    }
}

/** Completion candidates for the value half of `key=`, empty for the keys whose values are unbounded. */
private fun ddValueCandidates(key: String): List<String> = when (key) {
    "conv" -> DD_CONV_SYMBOLS
    "iflag" -> DD_IFLAG_SYMBOLS
    "oflag" -> DD_OFLAG_SYMBOLS
    "status" -> DD_STATUS_LEVELS
    else -> emptyList()
}

/**
 * What one `dd` line binds, as values rather than as the handles that read them.
 *
 * Being a plain data class is the point: a parity case asserts a whole invocation with one `assertEquals`
 * against a `copy()` of [NOTHING_BOUND], so every field it does *not* name is pinned to its default too.
 */
public data class DdInputs(val operands: List<DdOperand>)

/** `dd` with no operands at all: every field at the default the declaration gives it. */
public val NOTHING_BOUND: DdInputs = DdInputs(operands = emptyList())
