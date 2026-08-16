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
 */
internal class Sifted(
    val flags: Map<FlagSpec, Int>,
    val negations: Map<FlagSpec, Boolean>,
    val options: Map<OptionSpec, List<String>>,
    val positionals: List<String>,
    val error: CliError? = null,
    // Where each flag/option was LAST seen, for the one rule that needs order between two different inputs
    // ([ConstraintArity.LastWins]).
    val flagPositions: Map<FlagSpec, ClusterPosition> = emptyMap(),
    val optionPositions: Map<OptionSpec, ClusterPosition> = emptyMap(),
    // Indices into [positionals] that arrived through a `dashLed()` slot: a single-dash token that resolved
    // to no option. Only these are refusable at bind time, so a `--`-escaped operand keeps binding in any
    // slot exactly as it does today.
    val dashLedAdmitted: Set<Int> = emptySet(),
)

/**
 * Where one occurrence sat: its token, then its character within that token's short cluster. Compared as
 * the pair it is rather than packed into one number, so no cluster is ever long enough to reach the next
 * token. `-if` and `-i -f` order the same way, which is what makes `lastWins` mean one thing in both.
 */
internal data class ClusterPosition(val token: Int, val char: Int = 0) : Comparable<ClusterPosition> {
    override fun compareTo(other: ClusterPosition): Int =
        compareValuesBy(this, other, ClusterPosition::token, ClusterPosition::char)
}
