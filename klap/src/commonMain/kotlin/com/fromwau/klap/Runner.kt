package com.fromwau.klap

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.fold
import com.fromwau.kern.terminal.BROKEN_PIPE_EXIT
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.defaultTerminal
import com.fromwau.klap.internal.platform.platformExit
import com.fromwau.klap.internal.render.Candidate
import com.fromwau.klap.internal.render.HelpStyle
import com.fromwau.klap.internal.render.completeCandidates
import com.fromwau.klap.internal.render.helpText
import com.fromwau.klap.internal.render.helpTextAll
import com.fromwau.klap.internal.render.renderActionError
import com.fromwau.klap.internal.render.renderError
import com.fromwau.klap.internal.spec.ActionError
import com.fromwau.klap.internal.spec.completeWithoutSuspending

/**
 * Parses [argv], runs whatever it resolved to, writes the result to [terminal] and hands back the exit
 * code. Nothing is thrown and the process is not terminated, which is what makes it the entry point to use
 * from a test or from inside a larger program.
 *
 * ```kotlin
 * assertEquals(0, app.run(listOf("greet", "ada"), recorder))
 * ```
 *
 * A CLI declaring any `actionSuspending { }` is refused here; use [runSuspending].
 */
public fun Cli.run(argv: Collection<String>, terminal: Terminal): Int {
    val path = suspendingPath
    require(path == null) {
        // A root-level actionSuspending puts the CLI's own name in `path`, so naming it again as a
        // "command" would be both wrong and redundant.
        val subject = if (path == name) "cli '$name'" else "cli '$name': command '$path'"
        "$subject uses actionSuspending { }, which the synchronous entry points cannot drive; call " +
            "runSuspending(argv, terminal) from a coroutine instead"
    }
    return runCore(argv, terminal) { it.executeAndRender(terminal) }
}

/**
 * [run] for a CLI whose actions suspend. The caller supplies the scope, so cancellation and timeouts stay
 * theirs, and the exit code comes back as a value rather than ending the process:
 *
 * ```kotlin
 * fun main(args: Array<String>) {
 *     val code = runBlocking { app.runSuspending(args, defaultTerminal()) }
 *     exitProcess(code)
 * }
 * ```
 */
public suspend fun Cli.runSuspending(argv: Collection<String>, terminal: Terminal): Int =
    runCore(argv, terminal) { it.executeAndRenderSuspending(terminal) }

/**
 * The shared body of [run] and [runSuspending]. `inline`, so [execute] may suspend when the caller does;
 * that is what lets one implementation serve both without the parse and render work being written twice.
 */
private inline fun Cli.runCore(
    argv: Collection<String>,
    terminal: Terminal,
    execute: (Invocation.Execute) -> Int,
): Int {
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
    // A local `when` rather than kern's `fold`, because `execute` suspends through this lambda and so every
    // frame it crosses must stay inline. kern's `fold` is inline today, but keeping the chain in klap's own
    // code means a change over there cannot break `runSuspending`'s compilation over here.
    val code = when (val parsed = parse(argList)) {
        is Result.Error -> renderError(parsed.error, json, terminal)
        is Result.Success -> {
            val invocation = parsed.value
            val resolved = if (invocation is Invocation.Execute) {
                // The action palette follows the same switch as chrome, but off under --json so machine
                // output stays free of escape codes. Resolved here because parse() had no terminal.
                invocation.copy(scope = invocation.scope.withColorEnabled(effectiveColor && !invocation.globals.json))
            } else {
                invocation
            }
            resolved.render(style, terminal, execute)
        }
    }

    return if (code == 0 && terminal.writeErrored()) BROKEN_PIPE_EXIT else code
}

/**
 * Render a resolved [Invocation] to [terminal] and return its exit code. [execute] is the only branch that
 * differs between the synchronous and suspending paths, so the other branches are written once.
 *
 * `inline` with a bare (non-`crossinline`) [execute] is load-bearing here too: this is the second link in
 * the inline chain (after [runCore]) that lets [execute]'s suspend calls compile through, so dropping
 * `inline` breaks compilation with an error pointing at [runCore], not here.
 */
private inline fun Invocation.render(
    style: HelpStyle,
    terminal: Terminal,
    execute: (Invocation.Execute) -> Int,
): Int = when (this) {
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

    is Invocation.Execute -> execute(this)
}

/** Turn a finished action outcome into output on [terminal] and an exit code. Shared by both paths. */
private fun Invocation.Execute.renderOutcome(outcome: Result<String, ActionError>, terminal: Terminal): Int =
    outcome.fold(
        onError = { renderActionError(it, globals.json, terminal) },
        onSuccess = { text ->
            if (text.isNotEmpty()) terminal.out(text + "\n")
            0
        },
    )

private fun Invocation.Execute.executeAndRender(terminal: Terminal): Int =
    renderOutcome(completeWithoutSuspending { action.renderOutput(scope, globals.json) }, terminal)

/** [executeAndRender] for the suspending path, where the action is awaited rather than bridged. */
private suspend fun Invocation.Execute.executeAndRenderSuspending(terminal: Terminal): Int =
    renderOutcome(action.renderOutput(scope, globals.json), terminal)

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
 *
 * The resolved action declaring `actionSuspending { }` is refused here; use [runActionSuspending]. Unlike
 * [run], which refuses on any suspending action anywhere in the tree, this checks only the action that was
 * resolved, so a mixed tree still drives a synchronous command through `parse` + `runAction`.
 */
public fun Invocation.Execute.runAction(): Result<Any?, CliError> {
    // The resolved action, not the tree: on a mixed tree a sync command is drivable even though some
    // sibling suspends, and the root's aggregate cannot tell the difference.
    require(!action.suspending) {
        "command '${command.name}' uses actionSuspending { }, which runAction() cannot drive; " +
            "call runActionSuspending() from a coroutine instead"
    }
    return completeWithoutSuspending { action.evaluate(scope) }
}

/**
 * [runAction] for an action that suspends. Writes nothing and exits nothing; the value arrives as `Any?`,
 * so cast it to what your action returns.
 */
public suspend fun Invocation.Execute.runActionSuspending(): Result<Any?, CliError> = action.evaluate(scope)

/** [run] over the `Array` that Kotlin's own `main` hands you. */
public fun Cli.run(argv: Array<String>, terminal: Terminal): Int = run(argv.toList(), terminal)

/** [runSuspending] over the `Array` that Kotlin's own `main` hands you. */
public suspend fun Cli.runSuspending(argv: Array<String>, terminal: Terminal): Int =
    runSuspending(argv.toList(), terminal)

/**
 * Runs the CLI and exits the process with its code, the one-line way to wire up a `main`:
 *
 * ```kotlin
 * fun main(args: Array<String>) = app.main(args)
 * ```
 *
 * Use `run` instead when you need the exit code back rather than a terminated process.
 *
 * A CLI declaring any `actionSuspending { }` is refused here, and there is deliberately no suspending
 * `main`: exiting from inside a caller's scope would skip every `finally` and kill sibling coroutines. Call
 * [runSuspending] from a coroutine instead, and exit with `exitProcess` yourself.
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
