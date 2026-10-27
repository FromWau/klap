package com.fromwau.klap

import com.fromwau.kern.terminal.Terminal
import com.fromwau.klap.internal.parse.Line
import com.fromwau.klap.internal.spec.HolderSpec

/**
 * Receiver of an `action { }` or `actionSuspending { }` block: read this run's parsed values by invoking the
 * handles the builder handed you, and write through [terminal] and read [json] when your action writes as it goes.
 *
 * ```kotlin
 * val name = argument("name")
 * action { Ok("hello, ${name()}") }
 * ```
 */
@KlapDsl
public class ActionScope internal constructor(
    override val values: Map<HolderSpec, Any?>,
    /**
     * Whether this run renders structured output, because the caller passed `--json`.
     *
     * You never need this to produce the JSON: klap serializes whatever you return either way. Read it
     * when your action *also* writes as it goes, to keep its human-facing half out of a machine
     * caller's stream:
     *
     * ```kotlin
     * action {
     *     if (!json) ticks.forEach { println(it) }
     *     Ok(Summary(ticks.size))
     * }
     * ```
     *
     * Output mode belongs to the whole program, never to one command: every command renders JSON or none
     * does. It is always `false` for a CLI that declined the built-in with `builtins { json = Builtin.Off }`.
     */
    public val json: Boolean,
    override val line: Line,
    /**
     * Where this run writes: the terminal you passed to `run` or `runSuspending`, with any `--color` choice
     * applied, the same one klap writes help and your returned value to; under `runAction` and
     * `runActionSuspending`, the terminal you passed there, as given. Styled text you write through it
     * follows that colour decision, so it is plain when piped or under `--color=never`:
     *
     * ```kotlin
     * action {
     *     terminal.out(yellow("tick") + "\n")
     *     Ok("done")
     * }
     * ```
     *
     * What you write lands ahead of the line your returned value prints.
     */
    public val terminal: Terminal,
) : ValueScope() {
    override fun unbound(spec: HolderSpec): Nothing = unboundInCommand(spec)
}

/** One parse's bound inputs, read by `validateInputs` and [Invocation.Execute.inputs] before any terminal exists. */
internal class BoundInputs(
    override val values: Map<HolderSpec, Any?>,
    val json: Boolean,
    override val line: Line,
) : ValueScope() {
    /** The scope an action runs in, writing to [terminal]. */
    fun actionScope(terminal: Terminal): ActionScope = ActionScope(values, json, line, terminal)

    override fun unbound(spec: HolderSpec): Nothing = unboundInCommand(spec)
}

private fun unboundInCommand(spec: HolderSpec): Nothing =
    error(
        "input '${spec.name}' was read but not bound by the current command; an accessor is only " +
            "readable inside the action of the command that declares it",
    )
