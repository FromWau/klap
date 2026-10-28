package com.fromwau.klap.io

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.readBytes
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import com.fromwau.kern.result.mapError
import com.fromwau.kern.terminal.ReadError
import com.fromwau.kern.terminal.Terminal
import com.fromwau.klap.Arg
import com.fromwau.klap.ConverterScope
import com.fromwau.klap.Opt

/**
 * Where an operand or option says to read from: standard input for `-`, as `cat -` and `head -` read it, and the
 * file it names otherwise.
 *
 * ```kotlin
 * val file = argument("file").input().optional()
 * action {
 *     (file() ?: CliInput.StandardInput).readBytes(terminal)
 *         .map { it.decodeToString() }
 *         .mapError { CliError.Domain(it, "cannot read: $it") }
 * }
 * ```
 */
public sealed interface CliInput {
    /** The word `-`: the process's standard input. */
    public data object StandardInput : CliInput

    /** Any other word: the file at [path]. */
    public data class File(val path: CliPath) : CliInput
}

/** Why a [CliInput] could not be read. */
public sealed interface InputError : ResultError {
    /** Standard input could not be read, as [error] says. */
    public data class StandardInput(val error: ReadError) : InputError

    /** The file at [path] could not be read, as [error] says. */
    public data class File(
        val path: CliPath,
        val error: FileError,
    ) : InputError
}

/**
 * Every byte this input holds: standard input through [terminal]'s `readIn`, or the file's contents. A FIFO or a
 * character device is read to its end too, as `<(...)` and `/dev/stdin` name one; a directory, a socket or a block
 * device is refused.
 *
 * @param terminal the action's terminal, whose standard input `-` reads; a `RecordingTerminal` feeds a test its input.
 * @param maxBytes the most bytes read; past it the read is refused with `TooLarge`. Null reads it all.
 */
public fun CliInput.readBytes(
    terminal: Terminal,
    maxBytes: Long? = null,
): Result<ByteArray, InputError> = when (this) {
    CliInput.StandardInput -> terminal.readIn(maxBytes).mapError { InputError.StandardInput(it) }
    is CliInput.File -> path.path.readBytes(maxBytes, streams = true).mapError { InputError.File(path, it) }
}

/**
 * Reads this operand as a [CliInput]: `-` is [CliInput.StandardInput] and any other word a [CliInput.File], kept as
 * typed. It completes as a file in every shell, as `.file()` does. Whether a missing operand means standard input is
 * yours to say, as `file() ?: CliInput.StandardInput`.
 */
context(scope: ConverterScope)
public fun Arg<String>.input(): Arg<CliInput> = with(scope) { file().convert { Ok(it.toCliInput()) } }

/** Reads this option's value as a [CliInput], as an operand's [input] is read, completing it as a file. */
context(scope: ConverterScope)
public fun Opt<String?>.input(): Opt<CliInput?> = with(scope) { file().convert { Ok(it.toCliInput()) } }

private fun String.toCliInput(): CliInput = if (this == "-") CliInput.StandardInput else CliInput.File(CliPath(this))
