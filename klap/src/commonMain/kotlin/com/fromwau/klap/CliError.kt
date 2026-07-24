package com.fromwau.klap

/** POSIX convention: a command-line usage error exits 2. */
const val USAGE_ERROR_EXIT: Int = 2

/** A structured, message-free parse/usage failure. The single renderer in ErrorRendering.kt owns the words. */
sealed interface CliError {
    val exitCode: Int get() = USAGE_ERROR_EXIT

    data class UnknownSubcommand(val parent: String, val token: String) : CliError
    data class UnknownOption(val token: String) : CliError
    data class MissingArgument(val command: String, val argument: String) : CliError
    data class MissingRequiredOption(val option: String) : CliError
    data class MissingOptionValue(val option: String) : CliError
    data class BadValue(val name: String, val value: String, val reason: String) : CliError
    data class InvalidChoice(val name: String, val value: String, val choices: List<String>) : CliError
    data class TooManyArguments(val command: String, val extras: List<String>) : CliError

    /** A command's own runtime failure, reported from an action. Edge-level: the handler owns the message. */
    data class Failure(val detail: String, override val exitCode: Int = 1) : CliError
}
