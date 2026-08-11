package com.fromwau.klap

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.fold
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

/**
 * Parses [argv], runs whatever it resolved to, writes the result to [terminal] and hands back the exit
 * code. Nothing is thrown and the process is not terminated, which is what makes it the entry point to use
 * from a test or from inside a larger program.
 *
 * ```kotlin
 * assertEquals(0, app.run(listOf("greet", "ada"), recorder))
 * ```
 */
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
    val mode = argList.colorMode(builtins, builtinPool, scan.valueSlots, abbreviation != Abbreviation.None)
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
        // Each candidate renders as one line, `value` or `value\tdescription`, and the per-shell scripts
        // decode the tab; a value that would break that format is dropped (Candidate.toCompletionLine()).
        val text = cli.completeCandidates(words).mapNotNull { it.toCompletionLine() }.joinToString("\n")
        if (text.isNotEmpty()) terminal.out(text + "\n")
        0
    }

    is Invocation.Execute -> executeAndRender(terminal)
}

/** Run the resolved action against its parsed [scope] and render the outcome. */
private fun Invocation.Execute.executeAndRender(terminal: Terminal): Int =
    action.renderOutput(scope, globals.json).fold(
        onError = { renderActionError(it, globals.json, terminal) },
        onSuccess = { text ->
            if (text.isNotEmpty()) terminal.out(text + "\n")
            0
        },
    )

/** Write [text] as a line to stdout; the exit code for a successful print is 0. */
private fun Terminal.outputLine(text: String): Int {
    out(text + "\n")
    return 0
}

/**
 * Runs the resolved action and hands back its own `Ok(value)` or typed failure, instead of the rendered
 * output and exit code `run` produces. Writes nothing and exits nothing.
 *
 * The value arrives as `Any?`, so cast it to what your action returns.
 */
public fun Invocation.Execute.runAction(): Result<Any?, CliError> = action.evaluate(scope)

/** [run] over the `Array` that Kotlin's own `main` hands you. */
public fun Cli.run(argv: Array<String>, terminal: Terminal): Int = run(argv.toList(), terminal)

/**
 * Runs the CLI and exits the process with its code, the one-line way to wire up a `main`:
 *
 * ```kotlin
 * fun main(args: Array<String>) = app.main(args)
 * ```
 *
 * Use `run` instead when you need the exit code back rather than a terminated process.
 */
public fun Cli.main(argv: Collection<String>) {
    platformExit(run(argv, defaultTerminal()))
}

/** [main] over the `Array` that Kotlin's own `main` hands you, which is the usual spelling. */
public fun Cli.main(argv: Array<String>) {
    main(argv.toList())
}

/**
 * Encode this candidate as one `__complete` wire line — bare [Candidate.value], or `value\t<description>`
 * — or null to drop it. A value carrying a tab, newline, or carriage return is dropped rather than
 * cleaned: a shell inserts the value back into the command line verbatim, so cleaning it would offer a
 * candidate that no longer matches the data it came from. A description has no such contract, so it is
 * cleaned (tab/newline/CR to a space, then trimmed) and a blank one encodes as the bare value.
 */
internal fun Candidate.toCompletionLine(): String? {
    if (value.any { it == '\t' || it == '\n' || it == '\r' }) return null
    val cleaned = description
        ?.replace('\t', ' ')
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.trim()
        .orEmpty()
    return if (cleaned.isEmpty()) value else "$value\t$cleaned"
}
