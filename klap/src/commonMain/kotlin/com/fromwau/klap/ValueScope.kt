package com.fromwau.klap

import com.fromwau.klap.internal.spec.HolderSpec

/**
 * The typed read side of one parse: a snapshot of resolved values, addressed by the [Arg]/[Opt]/[Flag]/
 * [CountFlag] handles the builder handed back. Shared by [ActionScope] (an execution's inputs) and
 * [CompletionScope] (what a half-typed command line has supplied so far), so a helper written once against
 * this type serves both — `fun ValueScope.store() = Store(path())` reads the same in an `action { }` and in
 * a `.completeWith { }`.
 *
 * The accessors are members here rather than on the handles themselves so that reading one outside a scope
 * is a compile error instead of a runtime one.
 *
 * A sealed class cannot take an `internal constructor()` guard ("constructor must be private or protected
 * in sealed class") and does not need one: sealed already confines subclassing to this module and package.
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
