package com.fromwau.klap

import com.fromwau.klap.internal.parse.suggest as internalSuggest

/**
 * The nearest entry in [candidates] to [token], or null when nothing is close enough — klap's own
 * did-you-mean, so a consumer's hand-written suggestion is phrased and thresholded identically to the
 * ones the parser produces for an unknown subcommand or option.
 *
 * Reuses klap's threshold rather than exposing it: an exact match never suggests (a token equal to a
 * candidate is not unknown), and a wholesale rewrite never suggests either, so a two-character alias
 * stops suggesting for an unrelated two-character token. [ignoreCase] measures with both sides lowered,
 * for a value set whose own matching is case-insensitive.
 */
public fun suggest(
    token: String,
    candidates: List<String>,
    ignoreCase: Boolean = false,
): String? = internalSuggest(token, candidates, ignoreCase)
