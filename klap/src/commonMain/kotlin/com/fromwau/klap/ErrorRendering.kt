package com.fromwau.klap

/** The one place a CliError becomes human text; nothing upstream produces user-facing strings. */
internal fun CliError.message(): String = when (this) {
    is CliError.UnknownSubcommand -> "unknown subcommand '$token' for '$parent'"
    is CliError.UnknownOption -> "unknown option '$token'"
    is CliError.MissingArgument -> "missing required argument <$argument> for '$command'"
    is CliError.MissingRequiredOption -> "missing required option --$option"
    is CliError.MissingOptionValue -> "option --$option requires a value"
    is CliError.BadValue -> "invalid value '$value' for $name: $reason"
    is CliError.InvalidChoice -> "invalid value '$value' for $name (choose from ${choices.joinToString(", ")})"
    is CliError.TooManyArguments -> "unexpected extra argument${if (extras.size > 1) "s" else ""}: ${extras.joinToString(" ")}"
    is CliError.Failure -> detail
}
