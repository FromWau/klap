package com.fromwau.klap.fixture

import com.fromwau.kern.terminal.Terminal
import com.fromwau.klap.Cli
import com.fromwau.klap.run
import com.fromwau.klap.testing.Suggestion

/**
 * What a shell script reads back for [line] with the cursor at [cursor]: the words split as `suggestionsFor`
 * splits them, sent through `__complete` the way every generated script calls it, and each printed line
 * decoded into a [Suggestion].
 */
public fun Cli.wireSuggestions(
    line: String,
    cursor: Int = line.length,
): List<Suggestion> {
    val start = line.lastIndexOfAny(charArrayOf(' ', '\t'), cursor - 1) + 1
    val before = line.take(start).split(' ', '\t').filter { it.isNotEmpty() }
    val after = line.drop(cursor).dropWhile { !it.isWhitespace() }.split(' ', '\t').filter { it.isNotEmpty() }
    val words = before.drop(1) + line.substring(start, cursor) + after
    val terminal = WireTerminal()
    run(listOf("__complete", "--cursor", "${before.size - 1}", "--") + words, terminal)
    return terminal.out
        .lines()
        .filter { it.isNotEmpty() }
        .map { wire ->
            val tab = wire.indexOf('\t')
            if (tab < 0) Suggestion(wire) else Suggestion(wire.take(tab), wire.drop(tab + 1))
        }
}

private class WireTerminal : Terminal {
    val out = StringBuilder()

    override fun out(text: String) {
        out.append(text)
    }

    override fun err(text: String) = Unit
}
