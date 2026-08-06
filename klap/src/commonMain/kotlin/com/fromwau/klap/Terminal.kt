package com.fromwau.klap

/**
 * Where klap writes. Your platform's real stdout/stderr is used unless you pass one of these instead, which
 * is how a test drives a CLI without touching the process streams:
 *
 * ```kotlin
 * class Recorder : Terminal {
 *     val text = StringBuilder()
 *     override fun out(text: String) { this.text.append(text) }
 *     override fun err(text: String) {}
 * }
 * assertEquals(0, app.run(arrayOf("greet", "ada"), Recorder()))
 * ```
 */
public interface Terminal {
    /** Writes [text] to standard output. klap adds no newline of its own, so include one when you want one. */
    public fun out(text: String)

    /** Writes [text] to standard error, where klap sends usage errors and failures. */
    public fun err(text: String)

    /** Usable terminal width, or 0 when unknown (no wrapping). */
    public val columns: Int get() = 0

    /** Whether ANSI color is appropriate for this terminal. */
    public val ansi: Boolean get() = false

    /**
     * Whether a write has failed, typically because a downstream `| head` closed the pipe. `run` asks after
     * rendering and reports [BROKEN_PIPE_EXIT] rather than a false success. The default `false` is right for
     * any terminal whose writes cannot fail quietly.
     */
    public fun writeErrored(): Boolean = false
}
