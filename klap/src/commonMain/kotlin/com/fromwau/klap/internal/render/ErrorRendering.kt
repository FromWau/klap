package com.fromwau.klap.internal.render

import com.fromwau.kern.terminal.BROKEN_PIPE_EXIT
import com.fromwau.kern.terminal.Stream
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.WriteError
import com.fromwau.kern.terminal.escapeControls
import com.fromwau.kern.terminal.shellQuoted
import com.fromwau.klap.CliError
import com.fromwau.klap.ConversionError
import com.fromwau.klap.internal.spec.ActionError
import kotlin.text.iterator

private fun String?.didYouMean(): String = if (this == null) "" else ". Did you mean $this?"

/**
 * `a and b`, `a, b and c`: only the last item joins with "and", so a three-way conflict does not chant
 * "a and b and c".
 */
private fun List<String>.andJoined(): String =
    if (size < 2) joinToString(", ") else dropLast(1).joinToString(", ") + " and " + last()

/** This token set apart in a sentence as GNU quotes one, `'--nope'`, its control characters in `$'…'`. */
private fun String.quoted(): String = shellQuoted(always = true)

/** The one place a CliError becomes human text; nothing upstream produces user-facing strings. */
internal fun CliError.message(): String = when (this) {
    is CliError.UnknownSubcommand ->
        "unknown subcommand ${token.quoted()} for ${parent.quoted()}" + suggestion.didYouMean()
    is CliError.MissingSubcommand -> "missing subcommand for ${parent.quoted()}"
    is CliError.AmbiguousSubcommand ->
        "subcommand ${token.quoted()} is ambiguous; possibilities: ${candidates.joinToString(" ") { it.quoted() }}"

    is CliError.UnknownOption ->
        "unknown option ${token.quoted()}" + cluster?.let { " (in ${it.quoted()})" }.orEmpty() +
            suggestion.didYouMean()
    is CliError.AmbiguousOption ->
        "option ${token.quoted()} is ambiguous; possibilities: ${candidates.joinToString(" ") { it.quoted() }}"

    is CliError.UnroutedSubcommand ->
        "${command.quoted()} is a command of ${parent.quoted()}, " +
            "but the tokens before it already ended command parsing; " +
            "run ${"$parent $command".quoted()} on its own"

    is CliError.SubcommandAfterSeparator ->
        "${command.quoted()} is a command of ${parent.quoted()}, but '--' ends command parsing; " +
            "put '--' after the command"

    is CliError.MissingArgument -> "missing required argument <$argument> for ${command.quoted()}"
    is CliError.MissingRequiredOption -> "missing required option $option"
    is CliError.MissingOptionValue -> "option $option requires a value"
    is CliError.FlagTakesNoValue ->
        "flag ${flag.quoted()} does not take a value" + (negationHint?.let { "; use --$it to turn it off" } ?: "")

    is CliError.BadValue -> "invalid value ${value.quoted()} for $name: ${reason.escapeControls()}"
    is CliError.InvalidChoice ->
        "invalid value ${value.quoted()} for $name (choose from ${choices.joinToString(", ")})" +
            suggestion.didYouMean()

    is CliError.AmbiguousValue ->
        "value ${value.quoted()} for $name is ambiguous; possibilities: ${candidates.joinToString(" ") { it.quoted() }}"

    is CliError.MixedClusterAfterOperands ->
        "${cluster.quoted()} mixes the global ${global.quoted()} with a local option after the first operand; " +
            "write ${global.quoted()} before the operands"

    is CliError.TooManyArguments ->
        "unexpected extra argument${if (extras.size > 1) "s" else ""}: " +
            extras.joinToString(" ") { it.shellQuoted() } +
            (suggestion?.let { ". Did you mean the ${it.quoted()} command?" } ?: "")

    is CliError.TooFewOccurrences ->
        // Noun-neutral: this error covers both a repeated option (`--h a --h b`) and a repeated positional
        // `argument(...).multiple(min)`, so it names the input by its bare name rather than assuming `--`.
        "${option.quoted()} must be given at least $min time${if (min > 1) "s" else ""} (got $actual)"

    is CliError.ExactlyOneRequired -> "exactly one of ${inputs.joinToString(", ")} is required"
    is CliError.MutuallyExclusive -> "${inputs.andJoined()} are mutually exclusive"
    is CliError.RepeatedCommand -> "${command.quoted()} may be given only once"
    is CliError.RepeatedOption -> "${option.quoted()} may be given only once"

    is CliError.Usage -> detail
    is CliError.Failure -> detail
    is CliError.Domain -> detail
}

/**
 * A `Failed` carries the action's own typed error, whose (clamped) exit code propagates; klap's own
 * render/encode failures always exit 1.
 */
internal fun renderActionError(error: ActionError, json: Boolean, terminal: Terminal): Int {
    when (error) {
        is ActionError.Failed -> return renderError(error.error, json, terminal)
        ActionError.NotSerializable ->
            terminal.err(jsonErrorEnvelope(encodeFailureJson("NotSerializable", null), 1) + "\n")

        is ActionError.EncodeFailed ->
            terminal.err(jsonErrorEnvelope(encodeFailureJson("EncodeFailed", error.message), 1) + "\n")

        is ActionError.RenderFailed ->
            terminal.err("error: ${"could not render output: ${error.message}".escapeControls()}\n")
    }
    return 1
}

/**
 * A run that succeeded but whose output was lost. A closed pipe is the reader's choice, so it exits 141 and
 * says nothing, as a tool killed by SIGPIPE would; so does a failure the platform cannot explain. A write the
 * system refused, such as on a full disk, is an error the user needs to see, named by its stream.
 */
internal fun renderWriteFailed(error: WriteError, json: Boolean, terminal: Terminal): Int = when (error) {
    is WriteError.BrokenPipe, is WriteError.Unknown -> BROKEN_PIPE_EXIT
    is WriteError.Refused -> {
        if (json) {
            terminal.err(jsonErrorEnvelope(writeFailedJson(error.stream, error.detail), 1) + "\n")
        } else {
            val name = when (error.stream) {
                Stream.Out -> "standard output"
                Stream.Err -> "standard error"
            }
            val reason = error.detail?.let { ": ${it.escapeControls()}" }.orEmpty()
            terminal.err("error: cannot write to $name$reason\n")
        }
        1
    }
}

internal fun renderError(error: CliError, json: Boolean, terminal: Terminal): Int {
    // A CliError.Failure's exitCode is caller-supplied and unvalidated (0 would read as success; an
    // out-of-range value would wrap on the OS), so it is clamped here at the render boundary, and the
    // JSON envelope's `code` field uses the same clamped value so it always matches the actual exit.
    val code = error.exitCode.coerceIn(1, 255)
    if (json) {
        terminal.err(jsonErrorEnvelope(error.toJson(), code) + "\n")
        return code
    }
    // Each piece of input is quoted or escaped where its sentence is built, klap's own here and a consumer's detail
    // by the consumer, as GNU's tools quote before they call error(); escaping the sentence again would double the
    // backslashes of what was already escaped.
    terminal.err("error: ${error.message()}\n")
    return code
}

/**
 * The words for a conversion failure. They live here rather than on [ConversionError] so the cases stay
 * data a caller can branch on, and this render layer stays the only place that picks English.
 *
 * Phrased as a fragment: [CliError.BadValue]'s rendering supplies the input's name and the offending token
 * around it, printing `invalid value 'abc' for --port: not an integer`.
 */
internal fun ConversionError.reason(): String = when (this) {
    ConversionError.NotAnInteger -> "not an integer"
    ConversionError.NotALong -> "not a long"
    ConversionError.NotADouble -> "not a number"
    ConversionError.NotABoolean -> "not a boolean (true/false)"
    ConversionError.NotADuration -> "not a duration of zero or more, such as 90, 1h30m or PT1H30M"
    ConversionError.NotAnInstant -> "not an ISO 8601 instant with an offset, such as 2026-01-31T12:00:00Z"
    ConversionError.NotAUuid -> "not a UUID (32 hex digits, dashed as 8-4-4-4-12 or not)"
    is ConversionError.NotOneOf -> "not one of ${choices.joinToString(", ")}"
    // Kotlin/Native's toInt() throws with a null message; the fallback must not say "invalid value"
    // again, since BadValue's rendering above already prefixes it.
    is ConversionError.Threw -> thrown.message?.takeIf { it.isNotBlank() } ?: "conversion failed"
    is ConversionError.Domain -> detail
}
