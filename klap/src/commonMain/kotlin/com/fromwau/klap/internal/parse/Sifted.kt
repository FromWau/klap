package com.fromwau.klap.internal.parse

import com.fromwau.klap.CliError
import com.fromwau.klap.internal.spec.FlagSpec
import com.fromwau.klap.internal.spec.OptionSpec

/**
 * Collected option/flag occurrences plus leftover positionals for a command segment.
 * [flags] counts occurrences per spec (a plain flag binds `count > 0`, a count flag binds the count itself).
 * [negations] carries the last-seen polarity per negatable flag: true for `--x`, false for `--no-x`.
 * [error] is the first hard syntax error hit while walking, mirroring [GlobalSift.error]: [bind] raises it
 * before binding anything, while the completion planner ignores it and uses whatever the walk did collect.
 * [errorAt] is where that error stood.
 */
internal data class Sifted(
    val flags: Map<FlagSpec, Int>,
    val negations: Map<FlagSpec, Boolean>,
    // A null value is a bare occurrence of an `.optionalValue()` option, which names no value.
    val options: Map<OptionSpec, List<String?>>,
    val positionals: List<String>,
    val error: CliError? = null,
    // Where each flag/option was LAST seen, for the one rule that needs order between two different inputs
    // ([ConstraintArity.LastWins]).
    val flagPositions: Map<FlagSpec, ClusterPosition> = emptyMap(),
    val optionPositions: Map<OptionSpec, ClusterPosition> = emptyMap(),
    // Indices into [positionals] that arrived through a `dashLed()` slot: a single-dash token that resolved
    // to no option. Only these are refusable at bind time, so a `--`-escaped operand binds in any slot.
    val dashLedAdmitted: Set<Int> = emptySet(),
    val errorAt: ClusterPosition? = null,
    // Where each of [options]' values was written, parallel to its list there.
    val optionValuePositions: Map<OptionSpec, List<ClusterPosition>> = emptyMap(),
    // Every flag occurrence in line order, for [com.fromwau.klap.ValueScope.inOrder].
    val flagHits: List<FlagHit> = emptyList(),
    // The dash words the command's `dashLed(gather) { }` slot took, in line order.
    val gathered: List<String> = emptyList(),
) {
    /** [error] with where it stood. */
    val refusal: Refusal? get() = error?.let { e -> errorAt?.let { Refusal(e, it) } }
}

/** One flag occurrence: [on] is false for a negative spelling; a null [at] is a position no pass tracked. */
internal class FlagHit(val spec: FlagSpec, val on: Boolean, val at: ClusterPosition?)

/** A refusal and where on the line it stood, for ordering it against the built-in the line also asks for. */
internal class Refusal(val error: CliError, val at: ClusterPosition)

/**
 * Where one occurrence sat: its token, then its character within that token's short cluster. Compared as
 * the pair it is rather than packed into one number, so no cluster is ever long enough to reach the next
 * token. `-if` and `-i -f` order the same way, which is what makes `lastWins` mean one thing in both.
 */
internal data class ClusterPosition(val token: Int, val char: Int = 0) : Comparable<ClusterPosition> {
    override fun compareTo(other: ClusterPosition): Int =
        compareValuesBy(this, other, ClusterPosition::token, ClusterPosition::char)
}
