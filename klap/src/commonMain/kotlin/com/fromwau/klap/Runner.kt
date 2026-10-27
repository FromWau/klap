package com.fromwau.klap

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.fold
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.defaultTerminal
import com.fromwau.kern.terminal.out
import com.fromwau.kern.terminal.plain
import com.fromwau.kern.terminal.withAnsi
import com.fromwau.klap.internal.parse.builtinScan
import com.fromwau.klap.internal.parse.hoistCommands
import com.fromwau.klap.internal.platform.platformExit
import com.fromwau.klap.internal.render.Candidate
import com.fromwau.klap.internal.render.HelpStyle
import com.fromwau.klap.internal.render.completeCandidates
import com.fromwau.klap.internal.render.forShell
import com.fromwau.klap.internal.render.helpJsonText
import com.fromwau.klap.internal.render.jsonVersionEnvelope
import com.fromwau.klap.internal.render.renderActionError
import com.fromwau.klap.internal.render.renderError
import com.fromwau.klap.internal.render.renderWriteFailed
import com.fromwau.klap.internal.render.styledHelp
import com.fromwau.klap.internal.render.styledHelpAll
import com.fromwau.klap.internal.spec.ActionError
import com.fromwau.klap.internal.spec.Rendered
import com.fromwau.klap.internal.spec.completeWithoutSuspending

/**
 * Parses [argv], runs whatever it resolved to, writes the result to [terminal] and hands back the exit
 * code. A parse or action failure is rendered and reported as that code rather than thrown, and the process
 * is not terminated, which is what makes it the entry point to use from a test or from inside a larger
 * program.
 *
 * ```kotlin
 * assertEquals(0, app.run(listOf("greet", "ada"), recorder))
 * ```
 *
 * A CLI declaring any `actionSuspending { }` is refused here with an [IllegalArgumentException], since this
 * entry point cannot drive one; use [runSuspending].
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
    return runCore(argv, terminal) { invocation, output -> invocation.executeAndRender(output) }
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
    runCore(argv, terminal) { invocation, output -> invocation.executeAndRenderSuspending(output) }

/**
 * The shared body of [run] and [runSuspending]. `inline`, so [execute] may suspend when the caller does;
 * that is what lets one implementation serve both without the parse and render work being written twice.
 * [execute] writes to the terminal it is handed, which carries this line's colour decision.
 */
private inline fun Cli.runCore(
    argv: Collection<String>,
    terminal: Terminal,
    execute: (Invocation.Execute, Terminal) -> Int,
): Int {
    val argList = argv.toList()
    // The parse's own reading of each token and the value slots, read once and shared by both reads below, so an
    // abbreviated --json or --col resolves here — and a built-in in an option's value slot stays out of
    // reach here — exactly as in parse(), over the line parse() scans, its flag-spelled commands hoisted.
    val line = hoistCommands(argList).tokens
    val scan = builtinScan(line)
    val json = builtins.json && scan.names("json")
    // --color, bare or =always/never, beats the terminal's own reading; auto (absent/invalid) leaves the caller's
    // terminal untouched, so a terminal that computes ansi per read keeps doing so. A bad --color value is
    // reported by parse() itself, not here.
    val forced = when (line.colorMode(builtins, abbreviation, scan)) {
        ColorMode.ALWAYS -> true
        ColorMode.NEVER -> false
        ColorMode.AUTO -> null
    }
    val output = if (forced == null) terminal else terminal.withAnsi(forced, forced)
    val style = HelpStyle(columns = terminal.columns)
    // A local `when` rather than kern's `fold`, because `execute` suspends through this lambda and so every
    // frame it crosses must stay inline. kern's `fold` is inline today, but keeping the chain in klap's own
    // code means a change over there cannot break `runSuspending`'s compilation over here.
    val code = when (val parsed = parse(argList)) {
        is Result.Error -> renderError(parsed.error, json, output)
        is Result.Success -> parsed.value.render(style, output, execute)
    }

    if (code != 0) return code
    return when (val written = output.writeResult()) {
        is Result.Success -> code
        is Result.Error -> renderWriteFailed(written.error, json, output)
    }
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
    execute: (Invocation.Execute, Terminal) -> Int,
): Int = when (this) {
    is Invocation.ShowHelp -> {
        val text = when {
            builtins.helpPage != null && !json -> builtins.helpPage
            json -> command.helpJsonText(
                qualifiedName,
                globalSpecs,
                rootVersioned,
                rootMetaOptions,
                builtins,
                recursive,
            )

            recursive -> command.styledHelpAll(
                qualifiedName,
                globalSpecs,
                style,
                rootVersioned,
                rootMetaOptions,
                builtins,
            )

            else -> command.styledHelp(qualifiedName, globalSpecs, style, rootVersioned, rootMetaOptions, builtins)
        }
        terminal.outputLine(text)
    }

    is Invocation.ShowVersion -> {
        // One expression for both shapes: `version` is non-null for anything parse() produces, but this
        // class is publicly constructible, so the two branches must not disagree about a null one.
        val version = cli.version.orEmpty()
        val line = cli.builtins.versionPrint ?: "${cli.name} $version"
        terminal.outputLine(if (json) jsonVersionEnvelope(cli.name, version) else line)
    }
    is Invocation.ShowCompletion -> terminal.outputLine(cli.renderCompletion(shell))
    is Invocation.ShowDocs -> terminal.outputLine(cli.renderDocs(format))

    is Invocation.ShowCompleteCandidates -> {
        // No candidates prints nothing (not a blank line), matching a leaf action that returns "".
        // Each candidate renders as one line, `value` or `value\tdescription`, and the per-shell scripts
        // decode the tab; a value that would break that format is dropped (Candidate.forShell()).
        val text = cli.completeCandidates(words, cursor).mapNotNull { it.toCompletionLine() }.joinToString("\n")
        if (text.isNotEmpty()) terminal.out(text + "\n")
        0
    }

    is Invocation.Execute -> execute(this, terminal)
}

/** Turn a finished action outcome into output on [terminal] and an exit code. Shared by both paths. */
private fun Invocation.Execute.renderOutcome(outcome: Result<Rendered, ActionError>, terminal: Terminal): Int =
    outcome.fold(
        onError = { renderActionError(it, globals.json, terminal) },
        onSuccess = { rendered ->
            if (rendered.text.isNotEmpty()) terminal.out(plain(rendered.text, "\n"))
            // Clamped at the render boundary like a failure's code, but from 0 rather than 1: a success may
            // report any code, while [renderError] keeps a failure from ever claiming 0.
            rendered.exitCode.coerceIn(0, 255)
        },
    )

private fun Invocation.Execute.executeAndRender(terminal: Terminal): Int =
    renderOutcome(completeWithoutSuspending { action.renderOutput(scope.actionScope(terminal), globals.json) }, terminal)

/** [executeAndRender] for the suspending path, where the action is awaited rather than bridged. */
private suspend fun Invocation.Execute.executeAndRenderSuspending(terminal: Terminal): Int =
    renderOutcome(action.renderOutput(scope.actionScope(terminal), globals.json), terminal)

/** Write [text] as a line to stdout, coloured when it is styled and stdout takes colour; the exit code is 0. */
private fun Terminal.outputLine(text: CharSequence): Int {
    out(plain(text, "\n"))
    return 0
}

/**
 * Runs the resolved action and hands back its own `Ok(value)` or typed failure, instead of the rendered
 * output and exit code `run` produces. Exits nothing, and writes only what your action itself writes to
 * [terminal]; the returned value is not printed.
 *
 * The value arrives as `Any?`, so cast it to what your action returns.
 *
 * The resolved action declaring `actionSuspending { }` is refused here; use [runActionSuspending]. Unlike
 * [run], which refuses on any suspending action anywhere in the tree, this checks only the action that was
 * resolved, so a mixed tree still drives a synchronous command through `parse` + `runAction`.
 *
 * @param terminal what the action's [ActionScope.terminal] writes to, used as given.
 */
public fun Invocation.Execute.runAction(terminal: Terminal): Result<Any?, CliError> {
    // The resolved action, not the tree: on a mixed tree a sync command is drivable even though some
    // sibling suspends, and the root's aggregate cannot tell the difference.
    require(!action.suspending) {
        "command '${command.name}' uses actionSuspending { }, which runAction(terminal) cannot drive; " +
            "call runActionSuspending(terminal) from a coroutine instead"
    }
    return completeWithoutSuspending { action.evaluate(scope.actionScope(terminal)) }
}

/**
 * [runAction] for an action that suspends. Exits nothing, and writes only what your action itself writes to
 * [terminal]; the value arrives as `Any?`, so cast it to what your action returns.
 *
 * @param terminal what the action's [ActionScope.terminal] writes to, used as given.
 */
public suspend fun Invocation.Execute.runActionSuspending(terminal: Terminal): Result<Any?, CliError> =
    action.evaluate(scope.actionScope(terminal))

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
 * Encode this candidate as one `__complete` wire line, the value then a tab and its description when it has one,
 * or null to drop it; [forShell] decides what survives.
 */
internal fun Candidate.toCompletionLine(): String? =
    forShell()?.let { shell -> shell.description?.let { "${shell.value}\t$it" } ?: shell.value }
