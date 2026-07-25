package com.fromwau.klap

/** klap's typed-error result: an expected outcome is [Success], an expected failure is [Error]. */
public sealed interface Result<out S, out E> {
    public data class Success<out S>(val value: S) : Result<S, Nothing>
    public data class Error<out E>(val error: E) : Result<Nothing, E>
}

/** Convenience builder: wrap a value as a successful result. */
public fun <S> Ok(value: S): Result<S, Nothing> = Result.Success(value)

/** Convenience builder: wrap an error as a failed result. */
public fun <E> Err(error: E): Result<Nothing, E> = Result.Error(error)

public inline fun <S, E, T> Result<S, E>.map(transform: (S) -> T): Result<T, E> = when (this) {
    is Result.Success -> Result.Success(transform(value))
    is Result.Error -> this
}

public inline fun <S, E, F> Result<S, E>.mapError(transform: (E) -> F): Result<S, F> = when (this) {
    is Result.Success -> this
    is Result.Error -> Result.Error(transform(error))
}

public inline fun <S, E> Result<S, E>.getOrElse(fallback: (E) -> S): S = when (this) {
    is Result.Success -> value
    is Result.Error -> fallback(error)
}

public inline fun <S, E, T> Result<S, E>.fold(onSuccess: (S) -> T, onError: (E) -> T): T = when (this) {
    is Result.Success -> onSuccess(value)
    is Result.Error -> onError(error)
}

public inline fun <S, E> Result<S, E>.onSuccess(action: (S) -> Unit): Result<S, E> =
    also { if (it is Result.Success) action(it.value) }

public inline fun <S, E> Result<S, E>.onError(action: (E) -> Unit): Result<S, E> =
    also { if (it is Result.Error) action(it.error) }
