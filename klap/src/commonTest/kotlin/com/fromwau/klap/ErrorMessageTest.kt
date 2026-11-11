package com.fromwau.klap

import com.fromwau.kern.result.ResultError
import com.fromwau.kern.terminal.test.RecordingTerminal
import com.fromwau.klap.internal.render.renderError
import kotlin.test.Test
import kotlin.test.assertEquals

private data object OwnError : ResultError

class ErrorMessageTest {

    private val cliErrors: List<Pair<CliError, String>> = listOf(
        CliError.UnknownSubcommand("app", "confi", "config") to
            "unknown subcommand 'confi' for 'app'. Did you mean config?",
        CliError.MissingSubcommand("app") to "missing subcommand for 'app'",
        CliError.AmbiguousSubcommand("app", "st", listOf("stash", "status")) to
            "subcommand 'st' is ambiguous; possibilities: 'stash' 'status'",
        CliError.UnknownOption("-1", "--one", cluster = "-1m") to
            "unknown option '-1' (in '-1m'). Did you mean --one?",
        CliError.AmbiguousOption("--re", listOf("--recursive", "--reference")) to
            "option '--re' is ambiguous; possibilities: '--recursive' '--reference'",
        CliError.SubcommandAfterSeparator("build", "app") to
            "'build' is a command of 'app', but '--' ends command parsing; put '--' after the command",
        CliError.UnroutedSubcommand("build", "app") to
            "'build' is a command of 'app', but the tokens before it already ended command parsing; " +
            "run 'app build' on its own",
        CliError.MissingArgument("add", "text") to "missing required argument <text> for 'add'",
        CliError.MissingRequiredOption("--host") to "missing required option --host",
        CliError.MissingOptionValue("--host") to "option --host requires a value",
        CliError.FlagTakesNoValue("--color", "no-color") to
            "flag '--color' does not take a value; use --no-color to turn it off",
        CliError.BadValue("--port", "abc", "not an integer", ConversionError.NotAnInteger) to
            "invalid value 'abc' for --port: not an integer",
        CliError.InvalidChoice("level", "x", listOf("low", "high")) to
            "invalid value 'x' for level (choose from low, high)",
        CliError.AmbiguousValue("--priority", "h", listOf("high", "highest")) to
            "value 'h' for --priority is ambiguous; possibilities: 'high' 'highest'",
        CliError.TooManyArguments("app", listOf("biuld"), "build") to
            "unexpected extra argument: biuld. Did you mean the 'build' command?",
        CliError.MixedClusterAfterOperands("-vx", "-v") to
            "'-vx' mixes the global '-v' with a local option after the first operand; write '-v' before the operands",
        CliError.TooFewOccurrences("header", 2, 1) to "'header' must be given at least 2 times (got 1)",
        CliError.ExactlyOneRequired(listOf("--create", "--extract")) to
            "exactly one of --create, --extract is required",
        CliError.MutuallyExclusive(listOf("--create", "--extract", "--list")) to
            "--create, --extract and --list are mutually exclusive",
        CliError.RepeatedCommand("--query") to "'--query' may be given only once",
        CliError.RepeatedOption("--target-directory") to "'--target-directory' may be given only once",
        CliError.Usage("--from and --to must differ") to "--from and --to must differ",
        CliError.Failure("out of space") to "out of space",
        CliError.Domain(OwnError, "no task 3") to "no task 3",
    )

    private val conversionErrors: List<Pair<ConversionError, String>> = listOf(
        ConversionError.NotAnInteger to "not an integer",
        ConversionError.NotALong to "not a long",
        ConversionError.NotADouble to "not a number",
        ConversionError.NotABoolean to "not a boolean (true/false)",
        ConversionError.NotADuration to "not a duration of zero or more, such as 90, 1h30m or PT1H30M",
        ConversionError.NotAnInstant to "not an ISO 8601 instant with an offset, such as 2026-01-31T12:00:00Z",
        ConversionError.NotAUuid to "not a UUID (32 hex digits, dashed as 8-4-4-4-12 or not)",
        ConversionError.NotOneOf(listOf("c", "f", "k")) to "not one of c, f, k",
        ConversionError.Threw(IllegalStateException("too big")) to "too big",
        ConversionError.Threw(IllegalStateException()) to "conversion failed",
        ConversionError.Domain(OwnError, "must be in 1..65535") to "must be in 1..65535",
    )

    private fun printed(error: CliError): String {
        val terminal = RecordingTerminal()
        renderError(error, json = false, terminal = terminal)
        return terminal.err
    }

    @Test
    fun `every CliError case has the message klap prints for it`() {
        for ((error, words) in cliErrors) {
            assertEquals(words, error.message)
            assertEquals("error: ${error.message}\n", printed(error))
        }
    }

    @Test
    fun `every ConversionError case has the reason a bad value prints for it`() {
        for ((cause, words) in conversionErrors) {
            assertEquals(words, cause.message)
            val error = CliError.BadValue("--port", "abc", cause.message, cause)
            assertEquals("error: invalid value 'abc' for --port: $words\n", printed(error))
        }
    }
}
