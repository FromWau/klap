package com.fromwau.klap.fixture.dd

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.mapError
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Builtin
import com.fromwau.klap.CliError
import com.fromwau.klap.CompletionScope
import com.fromwau.klap.ConversionError
import com.fromwau.klap.Help
import com.fromwau.klap.TypedCli
import com.fromwau.klap.Version
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * GNU coreutils 9.12 `dd`, reproduced as a command-line surface only.
 *
 * Real usage: `dd [OPERAND]...`
 *         or: `dd OPTION`
 *
 * dd has no flags and no options but `--help` and `--version`. Every operand is a bare `key=value` word with no
 * leading dash, in any order, all optional, and a key given twice binds its last occurrence:
 *
 *   bs=BYTES cbs=BYTES conv=CONVS count=N ibs=BYTES if=FILE iflag=FLAGS
 *   obs=BYTES of=FILE oflag=FLAGS seek=N (oseek=N) skip=N (iseek=N) status=LEVEL
 */
public fun ddCli(): TypedCli<DdInputs> = cliOf("dd") {
    // dd declares no long option of its own, but real dd still abbreviates --help and --version.
    abbreviation = Abbreviation.Options
    // `dd --json if=in`: "unrecognized option '--json'", and `dd __complete if=`: "unrecognized operand".
    builtins {
        help = Help.ByCommand(description = "display this help and exit")
        json = Builtin.Off
        color = Builtin.Off
        completion = Builtin.Off
        docs = Builtin.Off
    }
    help = "Copy a file, converting and formatting according to the operands."
    version = Version.Of("9.12", description = "output version information and exit", text = DD_VERSION_TEXT)
    epilogue = DD_EPILOGUE

    example("dd if=/dev/zero of=out.img bs=4M count=10 status=progress", "write a 40 MiB file, with progress")
    example("dd if=disk.img of=/dev/sdb bs=1M conv=fsync oflag=direct", "restore an image with direct I/O")
    example("dd if=in.txt of=out.txt conv=ucase,notrunc skip=1 seek=1", "several operands, in any order")

    // klap binds positionals by place and has no named operand, while dd takes its keys in any order: so one
    // repeated positional takes every operand, and the projection folds the list as dd reads it.
    val operands = argument("operand", "an operand of the form key=value")
        .convert { raw -> ddOperand(raw).mapError { ConversionError.Domain(it, it.detail()) } }
        .multiple()
    operands.completeWith { offerOperand { operands() } }

    validateInputs {
        val settings = operands()
        convConflict(settings.convs(), settings.cbsGiven())?.let { CliError.Usage(it.detail) }
    }

    action<String>(human = { it }) {
        val inputs = operands().toInputs()
        Ok("would copy ${inputs.count} from ${inputs.input} to ${inputs.output}")
    }

    projection { operands().toInputs() }
}

/** One key of dd's operands, its `dd --help` [placeholder] (null for a spelling with no row of its own) and row. */
private data class Key(
    val name: String,
    val placeholder: String?,
    val help: String,
)

private const val SEEK_HELP = "(or oseek=N) skip N obs sized output blocks"
private const val SKIP_HELP = "(or iseek=N) skip N ibs sized input blocks"

private val STATUS_HELP = "The LEVEL of information to print to standard error; " +
    LEVELS.joinToString(", ") { "'${it.name}' ${it.help}" }

/** Every key dd takes, in `dd --help` order, `oseek=` and `iseek=` each beside the row that names it. */
private val KEYS: List<Key> = listOf(
    Key("bs", "BYTES", "read and write up to BYTES bytes at a time (default: 512); overrides ibs and obs"),
    Key("cbs", "BYTES", "convert BYTES bytes at a time"),
    Key("conv", "CONVS", "convert the file as per the comma separated symbol list"),
    Key("count", "N", "copy only N input blocks"),
    Key("ibs", "BYTES", "read up to BYTES bytes at a time (default: 512)"),
    Key("if", "FILE", "read from FILE instead of standard input"),
    Key("iflag", "FLAGS", "read as per the comma separated symbol list"),
    Key("obs", "BYTES", "write BYTES bytes at a time (default: 512)"),
    Key("of", "FILE", "write to FILE instead of standard output"),
    Key("oflag", "FLAGS", "write as per the comma separated symbol list"),
    Key("seek", "N", SEEK_HELP),
    Key("oseek", null, SEEK_HELP),
    Key("skip", "N", SKIP_HELP),
    Key("iseek", null, SKIP_HELP),
    Key("status", "LEVEL", STATUS_HELP),
)

private fun table(rows: List<Pair<String, String>>): String =
    rows.joinToString("\n") { (name, help) -> "  ${name.padEnd(14)}  $help" }

private val DD_EPILOGUE: String = listOf(
    "Operands:\n" + table(KEYS.mapNotNull { key -> key.placeholder?.let { "${key.name}=$it" to key.help } }),
    """
    N and BYTES may be followed by the following multiplicative suffixes: c=1, w=2, b=512, kB=1000, K=1024,
    MB=1000*1000, M=1024*1024, xM=M, GB=1000*1000*1000, G=1024*1024*1024, and so on for T, P, E, Z, Y, R, Q.
    Binary prefixes can be used, too: KiB=K, MiB=M, and so on. If N ends in 'B', it counts bytes not blocks.
    """.trimIndent(),
    "Each CONV symbol may be:\n" + table(CONVS.map { it.name to it.help }),
    "Each FLAG symbol may be:\n" + table(IFLAGS.map { it.name to it.help }),
).joinToString("\n\n")

/**
 * Tab on one operand word: the keys before its `=`, then the path or the symbols its key still takes, [operands]
 * being the other words of the line.
 */
private fun CompletionScope.offerOperand(operands: () -> List<Setting>) {
    if ('=' !in current) {
        KEYS.forEach { candidate("${it.name}=", it.help) }
        return
    }
    val key = current.substringBefore('=')
    if (key == "if" || key == "of") {
        completeFiles(nonPathPrefix = "$key=")
        return
    }
    val value = current.substringAfter('=')
    val head = if (',' in value) value.substringBeforeLast(',') + "," else ""
    // A list whose finished symbols dd refuses has nothing to continue with.
    val given = if (head.isEmpty()) null else ddOperand("$key=${head.dropLast(1)}").getOrNull() ?: return
    val offered = when (key) {
        "conv" -> {
            val line = operands() + listOfNotNull(given)
            CONVS.filter { convConflict(line.convs() + it.value, line.cbsGiven()) == null }
        }
        "iflag" -> IFLAGS
        "oflag" -> OFLAGS
        "status" -> LEVELS
        else -> emptyList()
    }
    offered.forEach { candidate("$key=$head${it.name}", it.help) }
}

/** Every `conv=` symbol of a line: dd ORs them all, so `dd conv=ucase conv=lcase` is refused as the pair. */
private fun List<Setting>.convs(): Set<Conv> =
    filterIsInstance<Setting.Convs>().flatMapTo(mutableSetOf()) { it.symbols }

private fun List<Setting>.cbsGiven(): Boolean = any { it is Setting.CbsSize }

/**
 * What `dd --version` prints for GNU coreutils 9.12, the version installed when it was pinned
 * (2026-10-10).
 */
internal val DD_VERSION_TEXT: String = """dd (coreutils) 9.12
Copyright (C) 2026 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>.
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Written by Paul Rubin, David MacKenzie, and Stuart Kemp."""
