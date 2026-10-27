package com.fromwau.klap.testing

import com.fromwau.klap.COMPLETE_FILES
import com.fromwau.klap.Cli
import com.fromwau.klap.internal.render.completeCandidates
import com.fromwau.klap.internal.render.forShell

/**
 * One thing Tab offers for a line: [value] is the word that replaces the one under the cursor, and
 * [description] what the shell shows beside it, when it shows one.
 */
public data class Suggestion(
    val value: String,
    val description: String? = null,
)

/** Where in [line] the word under [cursor] starts: right after the whitespace before it. */
private fun wordStart(
    line: String,
    cursor: Int,
): Int = line.lastIndexOfAny(charArrayOf(' ', '\t'), cursor - 1) + 1

/**
 * What Tab offers for [line] with the cursor at [cursor], its end by default, the way every generated shell
 * script asks klap: the words after the program name, the word under the cursor as far as it is typed, and the
 * words after it. A cursor right after whitespace is on no word. The words are split at whitespace only, as a
 * shell hands them over once it has taken their quoting off.
 */
public fun Cli.suggestionsFor(
    line: String,
    cursor: Int = line.length,
): List<Suggestion> {
    val start = wordStart(line, cursor)
    val before = line.take(start).split(' ', '\t').filter { it.isNotEmpty() }
    val after = line.drop(cursor).dropWhile { !it.isWhitespace() }.split(' ', '\t').filter { it.isNotEmpty() }
    val words = before.drop(1) + line.substring(start, cursor) + after
    return completeCandidates(words, before.size - 1)
        .mapNotNull { it.forShell() }
        .map { Suggestion(it.value, it.description) }
}

/**
 * [line] once [suggestion] is taken with the cursor at [cursor]: it replaces the word under the cursor as far
 * as that is typed, and the rest of the line stays as it is.
 */
public fun acceptSuggestion(
    line: String,
    suggestion: Suggestion,
    cursor: Int = line.length,
): String = line.take(wordStart(line, cursor)) + suggestion.value + line.drop(cursor)

/**
 * [line] after one Tab press with the cursor at [cursor]: the one suggestion taken, or what all of them share
 * put in place of what is typed, as every shell does. A file directive is the shell's own to complete, so the
 * line stays as it is.
 */
public fun Cli.tab(
    line: String,
    cursor: Int = line.length,
): String {
    val suggestions = suggestionsFor(line, cursor)
    if (suggestions.isEmpty() || suggestions.first().value.startsWith(COMPLETE_FILES)) return line
    val shared = suggestions.map { it.value }.reduce { shared, value -> shared.commonPrefixWith(value) }
    val typed = line.substring(wordStart(line, cursor), cursor)
    return if (shared.length > typed.length) acceptSuggestion(line, Suggestion(shared), cursor) else line
}

/**
 * The suggestion that hands a path to the shell's own file completion, [head] being the part of the word that
 * is not the path (`--file=`, dd's `if=`).
 */
public fun filesAfter(head: String = ""): Suggestion = Suggestion(COMPLETE_FILES + head)
