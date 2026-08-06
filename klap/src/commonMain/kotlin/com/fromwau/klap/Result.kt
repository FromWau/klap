package com.fromwau.klap

/**
 * klap's typed-error result: an expected outcome is [Success], an expected failure is [Error].
 *
 * Actions return one of these instead of throwing, so a failure reaches the user as your own error type
 * rendered by klap, rather than as a stack trace.
 */
public sealed interface Result<out S, out E> {
    /** The value the operation produced. */
    public data class Success<out S>(val value: S) : Result<S, Nothing>

    /** The failure the operation produced, typed as your own error rather than a message string. */
    public data class Error<out E>(val error: E) : Result<Nothing, E>
}

/** Shorthand for [Result.Success], the spelling klap's examples use: `Ok("done")`. */
public typealias Ok<S> = Result.Success<S>

/** Shorthand for [Result.Error]: `Err(CliError.Usage("no targets matched"))`. */
public typealias Err<E> = Result.Error<E>

/** Applies [transform] to a success value, passing an error through untouched. */
public inline fun <S, E, T> Result<S, E>.map(transform: (S) -> T): Result<T, E> = when (this) {
    is Result.Success -> Result.Success(transform(value))
    is Result.Error -> this
}

/** Applies [transform] to an error, passing a success value through untouched. */
public inline fun <S, E, F> Result<S, E>.mapError(transform: (E) -> F): Result<S, F> = when (this) {
    is Result.Success -> this
    is Result.Error -> Result.Error(transform(error))
}

/** Returns the success value, or what [fallback] makes of the error. */
public inline fun <S, E> Result<S, E>.getOrElse(fallback: (E) -> S): S = when (this) {
    is Result.Success -> value
    is Result.Error -> fallback(error)
}

/** Collapses both cases into one type: [onSuccess] for a value, [onError] for a failure. */
public inline fun <S, E, T> Result<S, E>.fold(onSuccess: (S) -> T, onError: (E) -> T): T =
    when (this) {
        is Result.Success -> onSuccess(value)
        is Result.Error -> onError(error)
    }

/** Runs [action] on a success value and returns this result unchanged, for chaining. */
public inline fun <S, E> Result<S, E>.onSuccess(action: (S) -> Unit): Result<S, E> =
    also { if (it is Result.Success) action(it.value) }

/** Runs [action] on an error and returns this result unchanged, for chaining. */
public inline fun <S, E> Result<S, E>.onError(action: (E) -> Unit): Result<S, E> =
    also { if (it is Result.Error) action(it.error) }
