package com.fromwau.klap

/**
 * What one occurrence of an option declared with `.optionalValue()` gave: the option alone, or a value attached
 * to it. GNU `cp --backup[=CONTROL]` is the shape: a bare `--backup` names no control of its own.
 *
 * ```kotlin
 * val backup = option(long = "backup").optionalValue()
 * action {
 *     when (val given = backup()) {
 *         null -> Ok("no backup")
 *         OptionalValue.Bare -> Ok("backup with the default control")
 *         is OptionalValue.Given -> Ok("backup with ${given.value}")
 *     }
 * }
 * ```
 */
public sealed interface OptionalValue<out T> {

    /** The option was given with no value attached, as `--backup`. */
    public data object Bare : OptionalValue<Nothing>

    /** The option was given with [value] attached, as `--backup=numbered`, after its converters ran. */
    public data class Given<out T>(val value: T) : OptionalValue<T>
}
