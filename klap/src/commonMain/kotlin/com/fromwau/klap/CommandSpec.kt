package com.fromwau.klap

/**
 * A subcommand defined apart from the tree it joins, so another module or library can hand you a command, or a whole
 * subtree, for your CLI to mount with [CommandBuilder.command]:
 *
 * ```kotlin
 * object Greet : CommandSpec {
 *     override val name = "greet"
 *
 *     override fun CommandBuilder.build() {
 *         help = "say hello"
 *         val who = argument("name")
 *         action { Ok("hello, ${who()}") }
 *     }
 * }
 *
 * val app = cli("app") { command(Greet) }   // app greet Ada
 * ```
 *
 * Everything else about the command, its `help` and `aliases` among it, is set inside [build], as in a
 * `command(name) { }` block.
 */
public interface CommandSpec {
    /** The word the command is reached by, as in `command(name) { }`. */
    public val name: String

    /**
     * Declares the command's inputs, settings, subcommands and action. It runs once each time the spec is mounted, so
     * a spec mounted under two parents gives each its own inputs.
     */
    public fun CommandBuilder.build()
}
