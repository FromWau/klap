package com.fromwau.klap

import com.fromwau.kern.result.IError

/** POSIX convention: a command-line usage error exits 2. */
public const val USAGE_ERROR_EXIT: Int = 2

/**
 * Why a raw token could not become a value: the case, not the sentence. klap writes the words for its own
 * cases; [Domain] is how a `.convert { }` of yours contributes both an error of its own and the words for
 * it.
 *
 * The token that failed is not repeated here, since [CliError.BadValue.value] already carries it.
 */
public sealed interface ConversionError : IError {
    /** `.int()` was given something `toIntOrNull` rejects. */
    public data object NotAnInteger : ConversionError

    /** `.long()` was given something `toLongOrNull` rejects, including an integer past `Long`'s range. */
    public data object NotALong : ConversionError

    /** `.double()` was given something `toDoubleOrNull` rejects. */
    public data object NotADouble : ConversionError

    /** `.boolean()` was given anything other than exactly `true` or `false`. */
    public data object NotABoolean : ConversionError

    /**
     * `.choice(...)` or `.enum<E>()` was given a value outside its set. [choices] are the declared spellings
     * in declaration order, lowercased for an enum, and matching ignores case.
     */
    public data class NotOneOf(val choices: List<String>) : ConversionError

    /** A `.map { }` transform threw. klap catches it rather than letting a bad value crash the parse. */
    public data class Threw(val thrown: Throwable) : ConversionError

    /**
     * Your own error from a `.convert { }`, carried through with its payload intact, plus the [detail] klap
     * should print for it. Mirrors [CliError.Domain] one level down: klap never inspects [error], so your
     * hierarchy keeps its own sealed root and a `parse` caller can match on it.
     *
     * ```kotlin
     * sealed interface PortError : IError {
     *     data class NotANumber(val given: String) : PortError
     *     data class OutOfRange(val given: Int) : PortError
     * }
     *
     * option("--port").convert { raw ->
     *     val n = raw.toIntOrNull()
     *         ?: return@convert Err(ConversionError.Domain(PortError.NotANumber(raw), "not a number"))
     *     if (n in 1..65535) Ok(n)
     *     else Err(ConversionError.Domain(PortError.OutOfRange(n), "must be in 1..65535"))
     * }
     * ```
     */
    public data class Domain(val error: IError, val detail: String) : ConversionError
}

/**
 * Why a command line was refused, or why an action failed, carried as data rather than as a sentence: klap
 * writes the words. Return [Usage], [Failure] or [Domain] from an action; the rest describe parse failures
 * you can match on after calling `parse`.
 */
public sealed interface CliError : IError {
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

    /**
     * An option spelling nothing in the tree declares.
     *
     * @param token the spelling itself, dashes included.
     * @param suggestion the declared spelling [token] most likely meant, when one is close enough.
     * @param cluster the single word [token] was read out of, when short clustering split one: typing
     *   `-1m` reports [token] `-1` with [cluster] `-1m`. Null whenever [token] is what the user typed,
     *   which is every other case. Report both, or the message names a token absent from the command line.
     */
    public data class UnknownOption(
        val token: String,
        val suggestion: String? = null,
        val cluster: String? = null,
    ) : CliError

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

    /**
     * [reason] is the text klap prints; [cause] is the case behind it, so a converter failure stays
     * matchable instead of flattening into a string. [cause] is null for the failures klap raises without
     * running a converter, notably a `.validate()`/`.range()` rejection.
     */
    public data class BadValue(
        val name: String,
        val value: String,
        val reason: String,
        val cause: ConversionError? = null,
    ) : CliError

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
     * klap never looks inside [error]; [IError] is the only thing asked of it, so your hierarchy keeps its
     * own sealed root and stays exhaustive at the `when` that unwraps it.
     */
    public data class Domain(
        val error: IError,
        val detail: String,
        override val exitCode: Int = 1,
    ) : CliError
}
