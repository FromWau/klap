package com.fromwau.klap

/**
 * Which of klap's built-ins this CLI offers. Every one defaults to on; setting one to `false` in the
 * root's `builtins { }` block removes it entirely — its name stops being reserved (so the app can declare
 * its own input under it), the parser stops recognizing it, and `--help`, the generated docs and tab
 * completion stop advertising it.
 *
 * ```kotlin
 * cli("curl") {
 *     builtins { json = false }
 *     option("--json", "-j", help = "post this JSON body")
 * }
 * ```
 */
@KlapDsl
public class BuiltinsBuilder internal constructor() {
    /** The `--json` output switch, and the `Globals.json` an action reads it back through. */
    public var json: Boolean = true

    /** The `--color <auto|always|never>` rendering modifier. */
    public var color: Boolean = true

    /** The `completion <shell>` subcommand (a dispatcher) or `--completion <shell>` option (a single-command root). */
    public var completion: Boolean = true

    /** The `docs <format>` subcommand (a dispatcher) or `--docs <format>` option (a single-command root). */
    public var docs: Boolean = true

    /** The `-h` alias for `--help`. `--help` itself is always offered and cannot be disabled. */
    public var helpShort: Boolean = true
}

/**
 * The resolved, immutable form of a [BuiltinsBuilder], carried by the [Cli] and threaded to every parse and
 * render seam that would otherwise inject a built-in unconditionally. [DEFAULT] is what a CLI that never
 * calls `builtins { }` resolves to, and the fallback wherever a seam is reached without a root to ask.
 */
internal data class Builtins(
    val json: Boolean = true,
    val color: Boolean = true,
    val completion: Boolean = true,
    val docs: Boolean = true,
    val helpShort: Boolean = true,
) {
    companion object {
        val DEFAULT: Builtins = Builtins()
    }
}

internal fun BuiltinsBuilder.resolve(): Builtins = Builtins(
    json = json,
    color = color,
    completion = completion,
    docs = docs,
    helpShort = helpShort,
)
