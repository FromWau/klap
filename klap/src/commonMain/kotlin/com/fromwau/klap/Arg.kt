package com.fromwau.klap

import com.fromwau.klap.internal.spec.ArgumentSpec
import com.fromwau.klap.internal.spec.FlagSpec
import com.fromwau.klap.internal.spec.HolderSpec
import com.fromwau.klap.internal.spec.OptionSpec

/** Anything declared on a command that a user can supply on the command line. */
public sealed interface Input

/** The primary spelling this input was declared under, the one klap's error messages name it by. */
public val Input.name: String get() = holderSpec().name

internal fun Input.holderSpec(): HolderSpec = when (this) {
    is Arg<*> -> spec
    is Opt<*> -> spec
    is Flag -> spec
    is CountFlag -> spec
}

/** Typed handle to a declared argument; its parsed value is read inside the action (see [ActionScope], [CompletionScope]). */
public class Arg<T> @PublishedApi internal constructor(@PublishedApi internal val spec: ArgumentSpec) :
    Input

/** Typed handle to a declared option; its parsed value is read inside the action (see [ActionScope], [CompletionScope]). */
public class Opt<T> @PublishedApi internal constructor(@PublishedApi internal val spec: OptionSpec) :
    Input

/** Typed handle to a declared flag; its parsed value is read inside the action (see [ActionScope], [CompletionScope]). */
public class Flag internal constructor(internal val spec: FlagSpec) : Input

/** A flag whose accessor reports how many times it appeared, e.g. `-vvv` -> 3. Made via [Flag.count]. */
public class CountFlag internal constructor(internal val spec: FlagSpec) : Input