package com.fromwau.klap

import com.fromwau.klap.internal.parse.suggest as internalSuggest

/**
 * The nearest entry in [candidates] to [token], or null when nothing is close enough — klap's own
 * did-you-mean, so a suggestion you write by hand reads exactly like the ones klap produces for an
 * unknown subcommand or option.
 */
public fun suggest(
    token: String,
    candidates: List<String>,
    ignoreCase: Boolean = false,
): String? = internalSuggest(token, candidates, ignoreCase)
