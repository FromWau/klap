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
    // ([ConstraintArity.LastWins]). Encoded so a position inside a short cluster is comparable with a
    // whole-token one: see [clusterPosition].
    val flagPositions: Map<FlagSpec, Int> = emptyMap(),
    val optionPositions: Map<OptionSpec, Int> = emptyMap(),
    // Indices into [positionals] that arrived through a `dashLed()` slot: a single-dash token that resolved
    // to no option. Only these are refusable at bind time, so a `--`-escaped operand keeps binding in any
    // slot exactly as it does today.
    val dashLedAdmitted: Set<Int> = emptySet(),
)

/**
 * A comparable position for a flag occurrence: the token's own index, times a stride wide enough that the
 * character index within a short cluster orders inside it without ever reaching the next token. So `-if`
 * and `-i -f` compare the same way, which is what makes `lastWins` mean the same thing in both spellings.
 */
internal fun clusterPosition(tokenIndex: Int, charIndex: Int = 0): Int = tokenIndex * 1000 + charIndex
