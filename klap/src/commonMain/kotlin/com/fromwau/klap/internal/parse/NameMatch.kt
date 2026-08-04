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
 * The longs that answer to their full spelling only, never to an abbreviation of it. The carve-out is inert
 * when nothing infers: with abbreviation off entirely, there is no shared prefix space left to protect.
 *
 * `--help-all` is injected by klap into every tree rather than written by the author, so letting it claim
 * the shared prefix space would take `--h`, `--he` and `--hel` away from every CLI in exchange for a name
 * nobody declared. `--help` keeps them, and `--help-all` stays reachable by spelling it out.
 *
 * A carve-out for klap's own injected name, NOT the general rule "a candidate that is a strict prefix of
 * another owns the space they share": GNU has no such rule, and `ls --so` really does report an ambiguity
 * between `--sort` and `--show-control-chars`.
 */
internal val EXACT_ONLY_LONGS: Set<String> = setOf("help-all")

/**
 * Resolve [typed] against [candidates] the way GNU's getopt_long does: an exact spelling wins outright,
 * otherwise a prefix matching exactly one candidate resolves and a prefix matching several is an error.
 * A candidate in [exactOnly] takes part in the exact half alone, so it is neither reachable by abbreviation
 * nor listed among an abbreviation's possibilities.
 *
 * The exact half always runs; [infer] gates only the prefix half, so a tree that infers nothing still binds
 * every full spelling. The exact-first rule is what makes a pool containing both `--sort` and `--sort-by`
 * usable at all, and it is the same reasoning that keeps `list` reachable beside `listen`. A blank [typed]
 * never matches, since every candidate would carry it as a prefix.
 *
 * Duplicates are collapsed before the count, because one spelling can reach the pool from two sources and
 * two entries naming ONE candidate are not two possibilities.
 */
internal fun resolveName(
    typed: String,
    candidates: List<String>,
    infer: Boolean,
    exactOnly: Set<String> = emptySet(),
): NameMatch {
    if (typed.isEmpty()) return NameMatch.None
    if (candidates.any { it == typed }) return NameMatch.Exact(typed)
    if (!infer) return NameMatch.None
    val hits = candidates.filter { it.startsWith(typed) && it !in exactOnly }.distinct()
    return when (hits.size) {
        0 -> NameMatch.None
        1 -> NameMatch.Prefix(hits.first())
        else -> NameMatch.Ambiguous(hits)
    }
}

/** [resolveName] over a long-option pool, where klap's own injected names are the exact-only carve-out. */
internal fun resolveLong(typed: String, candidates: List<String>, infer: Boolean): NameMatch =
    resolveName(typed, candidates, infer, EXACT_ONLY_LONGS)

/**
 * The [candidates] entry the `--`-led [token] names, exactly or as an unambiguous abbreviation, or null.
 * A `--<name>=value` token resolves on its name half, so a value-taking spelling matches too.
 *
 * An ambiguous abbreviation answers null rather than reporting: the callers are the position-independent
 * scans that run before the walk knows which command it reaches, and answering there would short-circuit
 * past the full-pool diagnostic that command's own sift raises for the same token.
 */
internal fun matchedLong(token: String, candidates: List<String>, infer: Boolean): String? {
    if (!token.startsWith("--")) return null
    return when (val match = resolveLong(token.removePrefix("--").substringBefore('='), candidates, infer)) {
        is NameMatch.Exact -> match.name
        is NameMatch.Prefix -> match.name
        is NameMatch.Ambiguous, NameMatch.None -> null
    }
}
