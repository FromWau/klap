package com.fromwau.klap

/** POSIX convention: a command-line usage error exits 2. */
public const val USAGE_ERROR_EXIT: Int = 2

/**
 * Why a command line was refused, or why an action failed, carried as data rather than as a sentence: klap
 * writes the words. Return [Usage], [Failure] or [Domain] from an action; the rest describe parse failures
 * you can match on after calling `parse`.
 */
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

    /** [option] is the primary spelling, dashes included, which may be a short one. */
    public data class MissingRequiredOption(val option: String) : CliError

    /** [option] is the primary spelling, dashes included, which may be a short one. */
    public data class MissingOptionValue(val option: String) : CliError

    /**
     * [flag] is the exact token typed, dashes included. [negationHint] is the negated long spelling without
     * dashes, or null when the flag has no negated form.
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
     * No member of a [CommandBuilder.requireExactlyOne] set was supplied. [inputs] is the whole set in
     * declaration order, each entry in its own `--option` or `<operand>` form, since a set can mix both.
     */
    public data class ExactlyOneRequired(val inputs: List<String>) : CliError

    /**
     * Two or more members of a mutually exclusive set were supplied. [inputs] names only the ones GIVEN,
     * in declaration order, prefixed the same way [ExactlyOneRequired.inputs] is.
     */
    public data class MutuallyExclusive(val inputs: List<String>) : CliError

    /**
     * A usage error your command detected itself, for a rule klap cannot express as a constraint. It exits
     * [USAGE_ERROR_EXIT], the same code klap's own usage errors use, which is what separates it from
     * [Failure]. There is no exit code to choose: a usage error that exits anything else is not one.
     */
    public data class Usage(val detail: String) : CliError

    /** A command's own runtime failure, reported from an action. Edge-level: the handler owns the message. */
    public data class Failure(val detail: String, override val exitCode: Int = 1) : CliError

    /**
     * Your own typed error, carried through with its payload intact. klap renders [detail] and exits
     * [exitCode] exactly as for [Failure]; what it adds is that a `parse` caller can recover [error] and
     * match on it, so your error hierarchy survives instead of flattening to a sentence.
     *
     * [error] is `Any` because klap never looks inside it; your hierarchy keeps its own root.
     */
    public data class Domain(
        val error: Any,
        val detail: String,
        override val exitCode: Int = 1,
    ) : CliError
}
