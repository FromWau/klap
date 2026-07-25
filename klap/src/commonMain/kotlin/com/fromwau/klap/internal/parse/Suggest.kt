package com.fromwau.klap.internal.parse

/** Levenshtein edit distance between [a] and [b]: two-row DP over insert/delete/substitute, pure Kotlin. */
internal fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(
                current[j - 1] + 1,
                previous[j] + 1,
                previous[j - 1] + cost,
            )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}

/**
 * Nearest entry in [candidates] to [token] within a conservative bound; null when nothing is close
 * enough. An exact match (distance 0) is never suggested: if the token equals a candidate it is not
 * really unknown, so "did you mean <the same word>" must never be produced. [ignoreCase] lowers both
 * sides before measuring distance (used for choice-restricted values, whose matching is itself
 * case-insensitive) while leaving every other call site's case-sensitive comparison untouched.
 */
internal fun suggest(token: String, candidates: List<String>, ignoreCase: Boolean = false): String? {
    val needle = if (ignoreCase) token.lowercase() else token
    return candidates
        .map { it to levenshtein(needle, if (ignoreCase) it.lowercase() else it) }
        .filter { (candidate, distance) ->
            @Suppress("SpellCheckingInspection")
            // Within a conservative absolute bound, AND not a wholesale rewrite: when the distance equals the
            // longer length every character is an edit, so the two share nothing. A short 2-char alias no
            // longer suggests for an unrelated 2-char token, while genuine near-misses like `sctp`->`tcp` and
            // `biuld`->`build` (distance 2, but shorter than the word) still pass.
            distance in 1..maxOf(2, candidate.length / 3) &&
                distance < maxOf(needle.length, candidate.length)
        }
        .minByOrNull { it.second }
        ?.first
}
