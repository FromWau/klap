package com.fromwau.klap

import com.fromwau.klap.internal.spec.HolderSpec

/**
 * The receiver of an `action { }` block: one execution's parsed values, resolved per accessor (inherited
 * from [ValueScope]). Every run constructs its own scope, so values are confined to the executing call —
 * the command tree stays immutable and concurrent runs of one tree never share state (no thread-locals,
 * no locks).
 */
@KlapDsl
public class ActionScope internal constructor(
    override val values: Map<HolderSpec, Any?>,
    // Defaults off: parse() builds this scope before a terminal exists, so effectiveColor is unknown yet;
    // run() re-threads the resolved switch afterward via withColorEnabled.
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
