package com.fromwau.klap

import com.fromwau.kern.terminal.Style

/**
 * The ability to apply a [Style], which [ActionScope] has. Take this as a receiver instead of the whole
 * action scope when a helper only formats text:
 *
 * ```kotlin
 * private fun ColorScope.warn(text: String) = (bold + yellow)("warning: $text")
 * ```
 *
 * The palette itself is [com.fromwau.kern.terminal]'s, so `bold` and `yellow` are imported from there.
 * What this adds is klap's colour switch: styling resolves against the same decision as klap's own help, so
 * a piped or `--color=never` run prints plain text without your action branching on it.
 */
@KlapDsl
public sealed interface ColorScope {
    public operator fun Style.invoke(block: () -> String): String
    public operator fun Style.invoke(text: String): String
}

/** The chrome's [ColorScope]: klap's own help and error output resolves a [Style] through this. */
internal class Palette(private val enabled: Boolean) : ColorScope {
    override fun Style.invoke(block: () -> String): String = render(block(), enabled)
    override fun Style.invoke(text: String): String = render(text, enabled)
}
