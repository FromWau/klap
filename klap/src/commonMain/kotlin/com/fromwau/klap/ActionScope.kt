package com.fromwau.klap

import com.fromwau.klap.internal.spec.HolderSpec

/**
 * Receiver of an `action { }` block: read this run's parsed values by invoking the handles the builder
 * handed you, and wrap text in a [Style] to colour it when colour is on.
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
) : ValueScope(), ColorScope {
    override fun Style.invoke(block: () -> String): String = render(block(), colorEnabled)
    override fun Style.invoke(text: String): String = render(text, colorEnabled)

    /** Rebuilds this scope carrying the same bound values but the resolved color switch from run(). */
    internal fun withColorEnabled(enabled: Boolean): ActionScope = ActionScope(values, enabled)

    override fun unbound(spec: HolderSpec): Nothing =
        error(
            "input '${spec.name}' was read but not bound by the current command; an accessor is only " +
                "readable inside the action { } of the command that declares it",
        )
}
