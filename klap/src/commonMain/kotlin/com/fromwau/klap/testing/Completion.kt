package com.fromwau.klap.testing

import com.fromwau.klap.COMPLETE_FILES
import com.fromwau.klap.Caller
import com.fromwau.klap.Cli
import com.fromwau.klap.internal.render.Candidate
import com.fromwau.klap.internal.render.completeCandidates
import com.fromwau.klap.internal.render.forShell
import com.fromwau.klap.internal.render.isNativeFileRequest

/**
 * One thing Tab offers for a line: a [Word] the shell puts in place of the word under the cursor, or [Files], the
 * shell's own path completion.
 *
 * ```kotlin
 * assertEquals(listOf(Suggestion.Word("-rf", "ignore nonexistent files")), rm.suggestionsFor("rm -r"))
 * assertEquals(listOf(Suggestion.Files("--out=")), app.suggestionsFor("app --out="))
 * ```
 */
public sealed interface Suggestion {

    /**
     * A word that replaces the one under the cursor, with the [description] the shell shows beside it, when it
     * shows one. Whether the shell adds a space after [value] is not reported.
     *
     * ```kotlin
     * Suggestion.Word("--force", "ignore nonexistent files")
     * ```
     */
    public data class Word(
        val value: String,
        val description: String? = null,
    ) : Suggestion

    /**
     * The shell completes a path itself here, keeping [head], the part of the word that is not the path, in
     * front of it: `--out=` for `--out=<TAB>`, `-O` for a path glued to `-O`, dd's `if=`, or `""` when the whole
     * word is the path.
     *
     * ```kotlin
     * Suggestion.Files("--out=")
     * ```
     */
    public data class Files(
        val head: String = "",
    ) : Suggestion
}

private fun List<Candidate>.toSuggestions(): List<Suggestion> =
    if (isNativeFileRequest()) {
        listOf(Suggestion.Files(single().value.removePrefix(COMPLETE_FILES)))
    } else {
        map { Suggestion.Word(it.value, it.description) }
    }

/** Where in [line] the word under [cursor] starts: right after the whitespace before it. */
private fun wordStart(
    line: String,
    cursor: Int,
): Int = line.lastIndexOfAny(charArrayOf(' ', '\t'), cursor - 1) + 1

/**
 * What Tab offers for [line] with the cursor at [cursor], its end by default, the way every generated shell
 * script asks klap: the words after the program name, the word under the cursor as far as it is typed, and the
 * words after it. A cursor right after whitespace is on no word. The words are split at whitespace only, as a
 * shell hands them over once it has taken their quoting off. [caller] is who the Tab press is for, as a provider
 * reads it; this process by default.
 */
public fun Cli.suggestionsFor(
    line: String,
    cursor: Int = line.length,
    caller: Caller = Caller.process(),
): List<Suggestion> {
    val start = wordStart(line, cursor)
    val before = line.take(start).split(' ', '\t').filter { it.isNotEmpty() }
    val after = line.drop(cursor).dropWhile { !it.isWhitespace() }.split(' ', '\t').filter { it.isNotEmpty() }
    val words = before.drop(1) + line.substring(start, cursor) + after
    return completeCandidates(words, before.size - 1, caller)
        .mapNotNull { it.forShell() }
        .toSuggestions()
}

/**
 * [line] once [suggestion] is taken with the cursor at [cursor]: a [Suggestion.Word] replaces the word under the
 * cursor as far as that is typed, and the rest of the line stays as it is. [Suggestion.Files] leaves the line as it
 * is, since the shell completes the path itself.
 */
public fun acceptSuggestion(
    line: String,
    suggestion: Suggestion,
    cursor: Int = line.length,
): String = when (suggestion) {
    is Suggestion.Word -> line.take(wordStart(line, cursor)) + suggestion.value + line.drop(cursor)
    is Suggestion.Files -> line
}

/**
 * [line] after one Tab press with the cursor at [cursor]: the one suggestion taken, or what all of them share
 * put in place of what is typed, as every shell does. Where [Suggestion.Files] is offered the path is the shell's
 * own to complete, so the line stays as it is. [caller] is who the Tab press is for, as a provider reads it; this
 * process by default.
 */
public fun Cli.tab(
    line: String,
    cursor: Int = line.length,
    caller: Caller = Caller.process(),
): String {
    val suggestions = suggestionsFor(line, cursor, caller)
    val words = suggestions.filterIsInstance<Suggestion.Word>()
    if (words.isEmpty() || words.size < suggestions.size) return line
    val shared = words.map { it.value }.reduce { shared, value -> shared.commonPrefixWith(value) }
    val typed = line.substring(wordStart(line, cursor), cursor)
    return if (shared.length > typed.length) acceptSuggestion(line, Suggestion.Word(shared), cursor) else line
}

/** What [Cli.suggestionsFor] gives where the shell completes a path itself after [head]. */
public fun filesAfter(head: String = ""): Suggestion.Files = Suggestion.Files(head)
