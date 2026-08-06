package com.fromwau.klap

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

    /** Fails the read of an input this scope has no value for, in this scope's own terms; see each subclass. */
    internal abstract fun unbound(spec: HolderSpec): Nothing

    public operator fun <T> Arg<T>.invoke(): T = read(spec)
    public operator fun <T> Opt<T>.invoke(): T = read(spec)
    public operator fun Flag.invoke(): Boolean = read(spec)
    public operator fun CountFlag.invoke(): Int = read(spec)

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
