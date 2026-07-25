package com.fromwau.klap

import com.fromwau.klap.internal.spec.ArgumentSpec
import com.fromwau.klap.internal.spec.FlagSpec
import com.fromwau.klap.internal.spec.HolderSpec
import com.fromwau.klap.internal.spec.OptionSpec

/**
 * Anything declared on a command that a user can supply on the command line.
 *
 * The one type the four handle kinds share, so a rule relating several of them
 * ([CommandBuilder.requireExactlyOne], [CommandBuilder.requireAtMostOne]) can take a mixed set. It carries
 * no members of its own: an `internal` member is illegal in an interface, so the spec behind a handle is
 * reached through the top-level [holderSpec] instead.
 */
public sealed interface Input

/**
 * The primary spelling this input was declared under: `--host` for `option("--host", "-H")`, `file` for
 * `argument("file")`. The same string klap's own errors name it by, so a consumer's hand-written rule can
 * say `--host` without repeating the literal and drifting from the declaration.
 */
public val Input.name: String get() = holderSpec().name

/** The spec behind a handle. A `when` over the sealed subtypes, since [Input] itself cannot host an internal member. */
internal fun Input.holderSpec(): HolderSpec = when (this) {
    is Arg<*> -> spec
    is Opt<*> -> spec
    is Flag -> spec
    is CountFlag -> spec
}

/** Typed handle to a declared argument; its parsed value is read inside `action { }` (see [ActionScope], [CompletionScope]). */
public class Arg<T> @PublishedApi internal constructor(@PublishedApi internal val spec: ArgumentSpec) : Input

/** Typed handle to a declared option; its parsed value is read inside `action { }` (see [ActionScope], [CompletionScope]). */
public class Opt<T> @PublishedApi internal constructor(@PublishedApi internal val spec: OptionSpec) : Input

/** Typed handle to a declared flag; its parsed value is read inside `action { }` (see [ActionScope], [CompletionScope]). */
public class Flag internal constructor(internal val spec: FlagSpec) : Input

/** A flag whose accessor reports how many times it appeared, e.g. `-vvv` -> 3. Made via [Flag.count]. */
public class CountFlag internal constructor(internal val spec: FlagSpec) : Input
