package com.fromwau.klap

import com.fromwau.kern.terminal.Style
import com.fromwau.klap.internal.spec.HolderSpec

/**
 * Receiver of an `action { }` block: read this run's parsed values by invoking the handles the builder
 * handed you, wrap text in a [Style] to colour it when colour is on, and read [json] when your action
 * writes as it goes.
 *
 * ```kotlin
 * val name = argument("name")
 * action { Ok("hello, ${bold(name())}") }
 * ```
 */
@KlapDsl
public class ActionScope internal constructor(
    override val values: Map<HolderSpec, Any?>,
    internal val colorEnabled: Boolean = false,
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
     * does. It is always `false` for a CLI that declined the built-in with `builtins { json = false }`.
     */
    public val json: Boolean = false,
) : ValueScope(), ColorScope {
    override fun Style.invoke(block: () -> String): String = render(block(), colorEnabled)
    override fun Style.invoke(text: String): String = render(text, colorEnabled)

    /** Rebuilds this scope carrying the same bound values but the resolved color switch from run(). */
    internal fun withColorEnabled(enabled: Boolean): ActionScope = ActionScope(values, enabled, json)

    override fun unbound(spec: HolderSpec): Nothing =
        error(
            "input '${spec.name}' was read but not bound by the current command; an accessor is only " +
                "readable inside the action { } of the command that declares it",
        )
}
