package com.fromwau.klap.internal.parse

/**
 * What a typed spelling matched in a candidate pool.
 *
 * [Exact] and [Prefix] both mean "bind this one" and are kept apart only so a caller can tell an
 * abbreviation from a full spelling, which the ambiguity rule below depends on.
 */
internal sealed interface NameMatch {
    data class Exact(val name: String) : NameMatch
    data class Prefix(val name: String) : NameMatch

    /** [candidates] is every distinct pool entry the typed prefix reached, in pool order. */
    data class Ambiguous(val candidates: List<String>) : NameMatch
    data object None : NameMatch
}

/**
 * The longs that never claim the prefixes of a shorter long they extend, each beside the long it yields to:
 * `--help-all` takes no part in `--h` to `--help`, which stay `--help`'s, and is reached by `--help-` to `--help-al`.
 *
 * A carve-out for klap's own injected name, NOT the general rule "a candidate that is a strict prefix of another
 * owns the space they share": GNU has no such rule, and `ls --so` really does report an ambiguity between `--sort`
 * and `--show-control-chars`.
 */
private val YIELDING_LONGS: Map<String, String> = mapOf("help-all" to "help")

/**
 * Resolve [typed] against [candidates] the way GNU's getopt_long does: an exact spelling wins outright,
 * otherwise a prefix matching exactly one candidate resolves and a prefix matching several is an error.
 * A candidate in [yielding] takes no part while [typed] is also a prefix of the long it yields to.
 *
 * The exact half always runs; [infer] gates only the prefix half, so a tree that infers nothing still binds
 * every full spelling. The exact-first rule is what makes a pool containing both `--sort` and `--sort-by`
 * usable at all, and it is the same reasoning that keeps `list` reachable beside `listen`. A blank [typed]
 * matches only where [blankIsPrefix], since every candidate carries it as a prefix. A prefix reaching only a
 * candidate in [whole] reaches nothing, as rm refuses `--no-p` for `--no-preserve-root`; among others it still
 * makes the prefix ambiguous, as getopt_long reports it before rm's own check runs.
 *
 * Duplicates are collapsed before the count, because one spelling can reach the pool from two sources and
 * two entries naming ONE candidate are not two possibilities.
 */
internal fun resolveName(
    typed: String,
    candidates: List<String>,
    infer: Boolean,
    yielding: Map<String, String> = emptyMap(),
    blankIsPrefix: Boolean = false,
    whole: Set<String> = emptySet(),
): NameMatch {
    if (typed.isEmpty() && !blankIsPrefix) return NameMatch.None
    if (candidates.any { it == typed }) return NameMatch.Exact(typed)
    if (!infer) return NameMatch.None
    val hits = candidates
        .filter { it.startsWith(typed) && !yields(it, typed, yielding) }
        .distinct()
    return when (hits.size) {
        0 -> NameMatch.None
        1 -> if (hits.first() in whole) NameMatch.None else NameMatch.Prefix(hits.first())
        else -> NameMatch.Ambiguous(hits)
    }
}

/** Whether [candidate] steps aside for [typed], a non-empty prefix of the long it yields to. */
private fun yields(candidate: String, typed: String, yielding: Map<String, String>): Boolean =
    typed.isNotEmpty() && yielding[candidate]?.startsWith(typed) == true

/** The longs a token resolves against, dashes stripped; a `.spelledInFull()` input's longs are also in [whole]. */
internal class LongPool(names: List<String>, val whole: Set<String> = emptySet()) : List<String> by names

/**
 * [resolveName] over a long-option pool, where klap's own injected names yield, and where the empty name of
 * `--=x` is a prefix of every long, as getopt_long reads it (`ls --=x` is ambiguous among all of ls's longs).
 */
internal fun resolveLong(typed: String, candidates: LongPool, infer: Boolean): NameMatch =
    resolveName(typed, candidates, infer, YIELDING_LONGS, blankIsPrefix = true, whole = candidates.whole)

/**
 * The [candidates] entry the `--`-led [token] names, exactly or as an unambiguous abbreviation, or null.
 * A `--<name>=value` token resolves on its name half, so a value-taking spelling matches too.
 *
 * An ambiguous abbreviation answers null rather than reporting: the callers are the position-independent
 * scans that run before the walk knows which command it reaches, and answering there would short-circuit
 * past the full-pool diagnostic that command's own sift raises for the same token.
 */
internal fun matchedLong(token: String, candidates: LongPool, infer: Boolean): String? {
    if (!token.startsWith("--")) return null
    return when (val match = resolveLong(token.removePrefix("--").substringBefore('='), candidates, infer)) {
        is NameMatch.Exact -> match.name
        is NameMatch.Prefix -> match.name
        is NameMatch.Ambiguous, NameMatch.None -> null
    }
}
