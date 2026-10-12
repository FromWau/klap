package com.fromwau.klap

import com.fromwau.klap.internal.render.completeCandidates

/** The values Tab offers for the last of [words], as `__complete` answers them. */
internal fun Cli.offered(vararg words: String): List<String> = offeredAt(words.lastIndex, *words)

/** The values Tab offers for the word at [cursor], with the words after it on the line. */
internal fun Cli.offeredAt(
    cursor: Int,
    vararg words: String,
): List<String> = completeCandidates(words.toList(), cursor).map { it.value }

/** Runs [args] through [Cli.run] and returns stdout without the trailing newline every run appends. */
internal fun Cli.bindText(vararg args: String): String {
    val terminal = RecordingTerminal()
    run(arrayOf(*args), terminal)
    return terminal.out.toString().removeSuffix("\n")
}

/** This tree's own `--help` output, without the trailing newline. */
internal fun Cli.helpText(): String = bindText("--help")
