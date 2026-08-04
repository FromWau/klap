package com.fromwau.klap

/** POSIX convention: a command-line usage error exits 2. */
public const val USAGE_ERROR_EXIT: Int = 2

/** A structured, message-free parse/usage failure. The single renderer in ErrorRendering.kt owns the words. */
public sealed interface CliError {
    public val exitCode: Int get() = USAGE_ERROR_EXIT

    public data class UnknownSubcommand(
        val parent: String,
        val token: String,
        val suggestion: String? = null,
    ) : CliError

    /**
     * An abbreviated subcommand naming more than one declared spelling. [candidates] are the full spellings
     * it reached, in declaration order, and a command's aliases are among them: an alias is just another
     * spelling in the pool.
     */
    public data class AmbiguousSubcommand(
        val parent: String,
        val token: String,
        val candidates: List<String>,
    ) : CliError

    public data class UnknownOption(val token: String, val suggestion: String? = null) : CliError

    /**
     * An abbreviated long option that names more than one declared spelling. [token] is what the user
     * typed, dashes included; [candidates] are the full spellings it reached, also dashes included, in
     * declaration order.
     */
    public data class AmbiguousOption(val token: String, val candidates: List<String>) : CliError

    /** A real subcommand typed after `--`, which ends command parsing so the name is read as an operand. */
    public data class SubcommandAfterSeparator(val command: String, val parent: String) : CliError

    public data class MissingArgument(val command: String, val argument: String) : CliError

    /** [option] already carries its own `-`/`--` prefix, rendered verbatim: an input's primary spelling may be a short. */
    public data class MissingRequiredOption(val option: String) : CliError

    /** [option] already carries its own `-`/`--` prefix, rendered verbatim: an input's primary spelling may be a short. */
    public data class MissingOptionValue(val option: String) : CliError

    /**
     * [flag] is the exact token the user typed (already carrying its own `-`/`--` prefix), rendered verbatim.
     * [negationHint], unlike [flag], is the negated long spelling with dashes stripped, since the renderer
     * supplies the `--` itself; null when the flag has no negated form.
     */
    public data class FlagTakesNoValue(val flag: String, val negationHint: String? = null) : CliError

    public data class BadValue(val name: String, val value: String, val reason: String) : CliError

    public data class InvalidChoice(
        val name: String,
        val value: String,
        val choices: List<String>,
        val suggestion: String? = null,
    ) : CliError

    /**
     * An abbreviated choice value naming more than one of an input's declared choices. [name] carries its
     * own `--`/`<>` form, like [InvalidChoice.name]; [candidates] are the full choices it reached, in
     * declaration order.
     */
    public data class AmbiguousValue(
        val name: String,
        val value: String,
        val candidates: List<String>,
    ) : CliError

    public data class TooManyArguments(
        val command: String,
        val extras: List<String>,
        val suggestion: String? = null,
    ) : CliError

    public data class TooFewOccurrences(val option: String, val min: Int, val actual: Int) : CliError

    /**
     * No member of a [CommandBuilder.requireExactlyOne] set was supplied. [inputs] lists the whole set in
     * declaration order; like [FlagTakesNoValue.flag], each entry already carries its own `--`/`<>` form,
     * since a set can mix options, flags and positionals, which render differently.
     */
    public data class ExactlyOneRequired(val inputs: List<String>) : CliError

    /**
     * Two or more members of a mutually exclusive set were supplied. [inputs] names only the ones GIVEN,
     * in declaration order, prefixed the same way [ExactlyOneRequired.inputs] is.
     */
    public data class MutuallyExclusive(val inputs: List<String>) : CliError

    /**
     * A usage error the command detected itself, for a rule klap cannot express as a constraint. Exits
     * [USAGE_ERROR_EXIT] like every parse-level variant above, so a hand-written rule reports the same
     * code as a built-in one instead of the runtime-failure code; that is the whole reason it exists
     * alongside [Failure], which is otherwise identical. It carries no `exitCode` parameter, since a
     * usage error that exits anything else is not a usage error.
     */
    public data class Usage(val detail: String) : CliError

    /** A command's own runtime failure, reported from an action. Edge-level: the handler owns the message. */
    public data class Failure(val detail: String, override val exitCode: Int = 1) : CliError

    /**
     * A consumer's own typed error, carried through klap's error path with its payload intact. klap renders
     * [detail] and exits [exitCode], exactly as it does for [Failure]; what it adds is that a `parse()`
     * caller can recover [error] and match on it, so a domain hierarchy survives the trip instead of being
     * flattened to a sentence at the boundary.
     *
     * [error] is deliberately `Any`: klap never inspects it, and typing it would force a klap-owned
     * supertype onto a hierarchy that already has its own root.
     */
    public data class Domain(
        val error: Any,
        val detail: String,
        override val exitCode: Int = 1,
    ) : CliError
}
