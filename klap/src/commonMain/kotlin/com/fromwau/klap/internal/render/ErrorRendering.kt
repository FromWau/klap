package com.fromwau.klap.internal.render

import com.fromwau.kern.terminal.Terminal
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

/**
 * Escapes ASCII control bytes (0x00-0x1F, 0x7F) as `\xNN` so text embedded in an `error: ...` line cannot
 * inject raw ANSI/control sequences into the terminal, or forge a second line in whatever reads stderr.
 * Applied on both the human and `--json` paths so every [com.fromwau.klap.CliError] variant is neutralized
 * consistently; on the `--json` path kotlinx then escapes the resulting text normally. It is applied there
 * too because kotlinx escapes 0x00-0x1F but not 0x7F/DEL, so the paths would otherwise diverge.
 *
 * [allowWhitespace] keeps a literal newline and tab, and [renderError] sets it only for the two variants
 * whose sentence the consumer writes ([CliError.Usage], [CliError.Failure]), where a `hint:` continuation
 * line is a deliberate layout choice rather than something echoed out of argv. Nothing else survives there,
 * not even an ESC the consumer embedded on purpose: such a detail almost always interpolates an argv token,
 * so klap cannot tell a color the author applied from one the caller injected.
 */
private fun stripTerminalEscapes(text: String, allowWhitespace: Boolean = false): String = buildString {
    for (ch in text) {
        val code = ch.code
        val control = code in 0x00..0x1F || code == 0x7F
        if (control && !(allowWhitespace && (ch == '\n' || ch == '\t'))) {
            append("\\x")
            append(code.toString(16).padStart(2, '0').uppercase())
        } else {
            append(ch)
        }
    }
}

/** The one place a CliError becomes human text; nothing upstream produces user-facing strings. */
internal fun CliError.message(): String = when (this) {
    is CliError.UnknownSubcommand -> "unknown subcommand '$token' for '$parent'" + suggestion.didYouMean()
    is CliError.AmbiguousSubcommand ->
        "subcommand '$token' is ambiguous; possibilities: ${candidates.joinToString(" ") { "'$it'" }}"

    is CliError.UnknownOption ->
        "unknown option '$token'" + cluster?.let { " (in '$it')" }.orEmpty() + suggestion.didYouMean()
    is CliError.AmbiguousOption ->
        "option '$token' is ambiguous; possibilities: ${candidates.joinToString(" ") { "'$it'" }}"

    is CliError.UnroutedSubcommand ->
        "'$command' is a command of '$parent', but the tokens before it already ended command parsing; " +
            "run '$parent $command' on its own"

    is CliError.SubcommandAfterSeparator ->
        "'$command' is a command of '$parent', but '--' ends command parsing; put '--' after the command"

    is CliError.MissingArgument -> "missing required argument <$argument> for '$command'"
    is CliError.MissingRequiredOption -> "missing required option $option"
    is CliError.MissingOptionValue -> "option $option requires a value"
    is CliError.FlagTakesNoValue ->
        "flag '$flag' does not take a value" + (negationHint?.let { "; use --$it to turn it off" } ?: "")

    is CliError.BadValue -> "invalid value '$value' for $name: $reason"
    is CliError.InvalidChoice ->
        "invalid value '$value' for $name (choose from ${choices.joinToString(", ")})" + suggestion.didYouMean()

    is CliError.AmbiguousValue ->
        "value '$value' for $name is ambiguous; possibilities: ${candidates.joinToString(" ") { "'$it'" }}"

    is CliError.MixedClusterAfterOperands ->
        "'$cluster' mixes the global '$global' with a local option after the first operand; " +
            "write '$global' before the operands"

    is CliError.TooManyArguments ->
        "unexpected extra argument${if (extras.size > 1) "s" else ""}: ${extras.joinToString(" ")}" +
            (suggestion?.let { ". Did you mean the '$it' command?" } ?: "")

    is CliError.TooFewOccurrences ->
        // Noun-neutral: this error covers both a repeated option (`--h a --h b`) and a repeated positional
        // `argument(...).multiple(min)`, so it names the input by its bare name rather than assuming `--`.
        "'$option' must be given at least $min time${if (min > 1) "s" else ""} (got $actual)"

    is CliError.ExactlyOneRequired -> "exactly one of ${inputs.joinToString(", ")} is required"
    is CliError.MutuallyExclusive -> "${inputs.andJoined()} are mutually exclusive"

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
            terminal.err(
                jsonErrorEnvelope(
                    "--json is not available: the command's return type is not @Serializable",
                    1,
                ) + "\n"
            )

        is ActionError.EncodeFailed ->
            terminal.err(jsonErrorEnvelope(stripTerminalEscapes("--json encoding failed: ${error.message}"), 1) + "\n")

        is ActionError.RenderFailed ->
            terminal.err("error: ${stripTerminalEscapes("could not render output: ${error.message}")}\n")
    }
    return 1
}

internal fun renderError(error: CliError, json: Boolean, terminal: Terminal): Int {
    // A CliError.Failure's exitCode is caller-supplied and unvalidated (0 would read as success; an
    // out-of-range value would wrap on the OS), so it is clamped here at the render boundary, and the
    // JSON envelope's `code` field uses the same clamped value so it always matches the actual exit.
    val code = error.exitCode.coerceIn(1, 255)
    // Who wrote the sentence picks the sanitizer, and the same choice holds on both output paths. A Usage,
    // Failure or Domain detail is the consumer's own prose, so its newlines and tabs survive; every other
    // variant echoes a token straight from argv, where a newline would let a caller forge a second
    // `error:` line.
    val authored = error is CliError.Usage || error is CliError.Failure || error is CliError.Domain
    val rendered = stripTerminalEscapes(error.message(), allowWhitespace = authored)
    terminal.err(if (json) jsonErrorEnvelope(rendered, code) + "\n" else "error: $rendered\n")
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
    is ConversionError.NotOneOf -> "not one of ${choices.joinToString(", ")}"
    // Kotlin/Native's toInt() throws with a null message; the fallback must not say "invalid value"
    // again, since BadValue's rendering above already prefixes it.
    is ConversionError.Threw -> thrown.message?.takeIf { it.isNotBlank() } ?: "conversion failed"
    is ConversionError.Domain -> detail
}
