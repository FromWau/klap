package com.fromwau.klap.fixture

import com.fromwau.klap.testing.Suggestion

/**
 * The text a row compares this suggestion by: a word's value, or for [Suggestion.Files] its head, a tab and then
 * `<files>`. No offered word holds a tab, so no word renders like files.
 */
public val Suggestion.value: String
    get() = when (this) {
        is Suggestion.Word -> value
        is Suggestion.Files -> "$head\t<files>"
    }

/** A word's description; [Suggestion.Files] has none. */
public val Suggestion.description: String?
    get() = (this as? Suggestion.Word)?.description
