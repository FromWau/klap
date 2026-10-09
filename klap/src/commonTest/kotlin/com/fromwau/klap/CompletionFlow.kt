package com.fromwau.klap

import com.fromwau.klap.internal.render.completeCandidates

/** One thing Tab offers: the word that replaces the one under the cursor, and what the shell shows beside it. */
internal data class Suggestion(
    val value: String,
    val description: String? = null,
)

/** Where in [line] the word under [cursor] starts: right after the whitespace before it. */
private fun wordStart(
    line: String,
    cursor: Int,
): Int = line.lastIndexOfAny(charArrayOf(' ', '\t'), cursor - 1) + 1

/**
 * What Tab offers for [line] with the cursor at [cursor], its end by default: the words the line holds after
 * the program name, as a shell hands them over unquoted, with the word under the cursor as far as it is typed.
 * Quoting is each shell's own, so a line here has none.
 */
internal fun Cli.suggestionsFor(
    line: String,
    cursor: Int = line.length,
): List<Suggestion> {
    val start = wordStart(line, cursor)
    val before = line.take(start).split(' ', '\t').filter { it.isNotEmpty() }
    val rest = line.drop(cursor).dropWhile { !it.isWhitespace() }
    val after = rest.split(' ', '\t').filter { it.isNotEmpty() }
    val words = before.drop(1) + line.substring(start, cursor) + after
    return completeCandidates(words, before.size - 1).map { Suggestion(it.value, it.description) }
}

/**
 * [line] once [suggestion] is taken with the cursor at [cursor]: it replaces the word under the cursor as far
 * as that is typed, and the rest of the line stays as it is.
 */
internal fun accept(
    line: String,
    suggestion: Suggestion,
    cursor: Int = line.length,
): String = line.take(wordStart(line, cursor)) + suggestion.value + line.drop(cursor)

/**
 * [line] after one Tab press with the cursor at [cursor]: the one suggestion taken, or what all of them share put
 * in place of what is typed, as every shell does. A file directive is the shell's own to complete.
 */
internal fun Cli.tab(
    line: String,
    cursor: Int = line.length,
): String {
    val suggestions = suggestionsFor(line, cursor)
    if (suggestions.isEmpty() || suggestions.first().value.startsWith(COMPLETE_FILES)) return line
    val shared = suggestions.map { it.value }.reduce { shared, value -> shared.commonPrefixWith(value) }
    val typed = line.substring(wordStart(line, cursor), cursor)
    return if (shared.length > typed.length) accept(line, Suggestion(shared), cursor) else line
}

/** The file directive with [head] the part of the word that is not the path: the shell completes the rest. */
internal fun filesAfter(head: String = ""): Suggestion = Suggestion(COMPLETE_FILES + head)
