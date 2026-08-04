package com.fromwau.klap

import com.fromwau.klap.internal.platform.defaultTerminal
import com.fromwau.klap.internal.platform.platformExit
import com.fromwau.klap.internal.render.Candidate
import com.fromwau.klap.internal.render.HelpStyle
import com.fromwau.klap.internal.render.completeCandidates
import com.fromwau.klap.internal.render.helpText
import com.fromwau.klap.internal.render.helpTextAll
import com.fromwau.klap.internal.render.renderActionError
import com.fromwau.klap.internal.render.renderError

/** Exit code for a truncated output pipe: the shell's 128+N "killed by signal N" convention, N = SIGPIPE (13). */
public const val BROKEN_PIPE_EXIT: Int = 128 + 13

/** Parse, dispatch, and render to [terminal]; return the exit code. Never exits the process. */
public fun Cli.run(argv: Collection<String>, terminal: Terminal): Int {
    val argList = argv.toList()
    // The tree's own built-in pool and value slots, read once and shared by both reads below, so an
    // abbreviated --json or --col resolves here — and a built-in in an option's value slot stays out of
    // reach here — exactly as in parse().
    val builtinPool = positionIndependentLongs()
    val scan = builtinScan(argList, builtinPool)
    val json = builtins.json && scan.names("json")
    // --color=always/never beats the whole env ladder below; auto (absent/bare/invalid) defers to the
    // terminal's own reading, unchanged. A bad/missing --color value is reported by parse() itself, not here.
    val mode = argList.colorMode(builtins, builtinPool, scan.valueSlots, inference != Inference.None)
    val effectiveColor = when (mode) {
        ColorMode.ALWAYS -> true
        ColorMode.NEVER -> false
        ColorMode.AUTO -> terminal.ansi
    }
    val style = HelpStyle(columns = terminal.columns, color = effectiveColor)
    val code = parse(argList).fold(
        onError = { renderError(it, json, terminal) },
        onSuccess = { invocation ->
            val resolved = if (invocation is Invocation.Execute) {
                // The action palette follows the same switch as chrome, but off under --json so machine
                // output stays free of escape codes. Resolved here because parse() had no terminal.
                invocation.copy(scope = invocation.scope.withColorEnabled(effectiveColor && !invocation.globals.json))
            } else {
                invocation
            }
            resolved.render(style, terminal)
        },
    )

    return if (code == 0 && terminal.writeErrored()) BROKEN_PIPE_EXIT else code
}

/** Render a resolved [Invocation] to [terminal] and return its exit code. */
private fun Invocation.render(style: HelpStyle, terminal: Terminal): Int = when (this) {
    is Invocation.ShowHelp -> {
        val text = if (recursive) command.helpTextAll(qualifiedName, globalSpecs, style, rootVersioned, builtins)
        else command.helpText(qualifiedName, globalSpecs, style, rootVersioned, builtins)
        terminal.outputLine(text)
    }

    is Invocation.ShowVersion -> terminal.outputLine("${cli.name} ${cli.version}")
    is Invocation.ShowCompletion -> terminal.outputLine(cli.renderCompletion(shell))
    is Invocation.ShowDocs -> terminal.outputLine(cli.renderDocs(format))

    is Invocation.ShowCompleteCandidates -> {
        // No candidates prints nothing (not a blank line), matching a leaf action that returns "".
        // Each candidate renders as one line, `value` or `value\tdescription` (the description sanitized by
        // Candidate.toCompletionLine()); the per-shell scripts decode the tab.
        val text = cli.completeCandidates(words).joinToString("\n") { it.toCompletionLine() }
        if (text.isNotEmpty()) terminal.out(text + "\n")
        0
    }

    is Invocation.Execute -> executeAndRender(terminal)
}

/** Run the resolved action against its parsed [scope] and render the outcome; a null action is a pure group (exit 0). */
private fun Invocation.Execute.executeAndRender(terminal: Terminal): Int {
    val action = command.action ?: return 0
    return action.renderOutput(scope, globals.json).fold(
        onError = { renderActionError(it, globals.json, terminal) },
        onSuccess = { text ->
            if (text.isNotEmpty()) terminal.out(text + "\n")
            0
        },
    )
}

/** Write [text] as a line to stdout; the exit code for a successful print is 0. */
private fun Terminal.outputLine(text: String): Int {
    out(text + "\n")
    return 0
}

/**
 * Runs the resolved action and hands back its own result value: the embedding hatch for when you want
 * the action's `Ok(value)`/typed `Failure` rather than the output and exit code [run] renders. Writes
 * nothing and never exits; null when the node is a pure group with no action. The value is erased to
 * `Any?` (a [Cli] is not typed over its actions' return types), so cast it to your action's return type.
 */
public fun Invocation.Execute.runAction(): Result<Any?, CliError>? {
    val action = command.action ?: return null
    return action.evaluate(scope)
}

/**
 * Array overload of [run]: an `Array` is not a [Collection], so the `main`-shaped argv needs its own entry.
 */
public fun Cli.run(argv: Array<String>, terminal: Terminal): Int = run(argv.toList(), terminal)

/** Full drop-in entry point: parse, dispatch, render, and exit with the resulting code. */
public fun Cli.main(argv: Collection<String>) {
    platformExit(run(argv, defaultTerminal()))
}

/**
 * Array overload of [main]: the shape Kotlin's own `fun main(args: Array<String>)` hands you, and so the
 * spelling almost every program actually uses. An `Array` is not a [Collection], so it needs its own entry.
 */
public fun Cli.main(argv: Array<String>) {
    main(argv.toList())
}

/**
 * Encode this candidate as one `__complete` wire line: bare [Candidate.value] when there is no usable
 * description, else `value\t<description>`. The description is sanitized first (every tab, newline, and
 * carriage return collapsed to a single space, then trimmed) so a multi-line `help` can never break the
 * one-candidate-per-line format; a blank description encodes as the bare value. The value is still all a
 * shell inserts, so this only ever adds display text a renderer can choose to show.
 */
internal fun Candidate.toCompletionLine(): String {
    val cleaned = description
        ?.replace('\t', ' ')
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.trim()
        .orEmpty()
    return if (cleaned.isEmpty()) value else "$value\t$cleaned"
}
