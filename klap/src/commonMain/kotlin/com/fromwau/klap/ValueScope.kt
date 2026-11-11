package com.fromwau.klap

import com.fromwau.kern.result.Result
import com.fromwau.klap.internal.parse.Line
import com.fromwau.klap.internal.spec.HolderSpec

/**
 * Where you read parsed values, by invoking the [Arg]/[Opt]/[Flag]/[CountFlag] handles the builder gave you.
 *
 * Both [ActionScope] (what one run supplied) and [CompletionScope] (what a half-typed line has supplied so
 * far) are one, so a helper written against this type serves both:
 *
 * ```kotlin
 * fun ValueScope.store() = Store(path())
 * ```
 *
 * Reading a handle outside such a scope does not compile, so you cannot read a value before it exists.
 */
@KlapDsl
public sealed class ValueScope {
    internal abstract val values: Map<HolderSpec, Any?>

    internal abstract val line: Line

    /** Fails the read of an input this scope has no value for, in this scope's own terms; see each subclass. */
    internal abstract fun unbound(spec: HolderSpec): Nothing

    public operator fun <T> Arg<T>.invoke(): T = read(spec)
    public operator fun <T> Opt<T>.invoke(): T = read(spec)
    public operator fun Flag.invoke(): Boolean = read(spec)
    public operator fun CountFlag.invoke(): Int = read(spec)

    /**
     * Whether a single-dash word filled this operand as its `dashLed` lets one, before any `--`; for a variadic
     * operand, whether any of its words did, and for `dashLed(gather)`, whether it gathered one. So a command can tell
     * `set -x` from `set -- -x`, which give it the same value, and answer each in its own way:
     *
     * ```kotlin
     * val value = argument("value").dashLed()
     * action {
     *     if (value.isDashLed && !isNumber(value())) Err(CliError.UnknownOption(value())) else Ok(store(value()))
     * }
     * ```
     */
    public val Arg<*>.isDashLed: Boolean get() = spec in line.dashLedOperands

    /**
     * Every occurrence of [inputs] on the line, in the order they were written, for settings that several
     * options assign in turn, or for files given both as an option and as operands. GNU cp reads `-d` as
     * `--no-dereference --preserve=links`, so `cp -d -L` follows links and `cp -L -d` does not; replaying the
     * occurrences decides it as cp's own option loop does:
     *
     * ```kotlin
     * projection {
     *     var follow = Follow.Default
     *     for (occurrence in inOrder(noDereferencePreserveLinks, dereference, noDereference)) {
     *         when (occurrence.input) {
     *             dereference -> follow = Follow.Always
     *             noDereferencePreserveLinks, noDereference -> follow = Follow.Never
     *         }
     *     }
     *     CpArgs(follow)
     * }
     * ```
     *
     * A flag occurs each time it is written, a cluster's letters in the order they stand. Every occurrence of an
     * option is listed, a `.multiple()` one's and a single-valued one's alike, and an operand occurs once for each
     * value it took, at the place it stood; a `dashLed(gather)` operand's words are one value, at the first of them. An
     * input that last-wins overrides keeps its occurrences here, since they are what was written. In a completion
     * provider, an occurrence whose value does not convert is left out, and so is the operand being typed.
     *
     * @param inputs the flags, options and operands to list.
     * @return each occurrence with the handle you passed as its [Occurrence.input].
     */
    public fun inOrder(vararg inputs: Input): List<Occurrence> {
        val wanted = inputs.associateBy { it.holderSpec() }
        val order = line
        return order.written.mapNotNull { written ->
            val input = wanted[written.spec] ?: return@mapNotNull null
            when (val value = order.valueOf(written)) {
                is Result.Success -> Occurrence(input, value.value)
                is Result.Error -> null
            }
        }
    }

    /** The one unchecked cast behind every accessor: the heterogeneous value map erases each holder's T. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> read(spec: HolderSpec): T {
        // Read the map once: a subclass may compute it (see CompletionScope's lazy bind), and the
        // containsKey/get pair must see the same snapshot.
        val bound = values
        // containsKey, not a null fallback: a holder legitimately bound to null must read back null, not fail.
        return if (bound.containsKey(spec)) bound[spec] as T else unbound(spec)
    }
}

/**
 * One occurrence of an input on the line, as [ValueScope.inOrder] lists them.
 *
 * @property input the handle you passed to [ValueScope.inOrder]. It is typed `Any` rather than [Input] so that a
 *   `when (occurrence.input)` matching your handles needs no `else` branch, which a `when` over the sealed [Input]
 *   would.
 * @property value what this occurrence gave: `true` for a flag, `false` for a flag's negative spelling, and for an
 *   option or operand the value of this occurrence after its converters ran (an [OptionalValue] for
 *   `.optionalValue()`).
 */
public data class Occurrence(
    val input: Any,
    val value: Any?,
)
