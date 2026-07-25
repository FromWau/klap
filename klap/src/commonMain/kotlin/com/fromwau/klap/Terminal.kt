package com.fromwau.klap

/** The sole I/O seam: production uses the platform default, tests a recording fake. */
public interface Terminal {
    public fun out(text: String)
    public fun err(text: String)

    /** Usable terminal width, or 0 when unknown (no wrapping). */
    public val columns: Int get() = 0

    /** Whether ANSI color is appropriate for this terminal. */
    public val ansi: Boolean get() = false

    /**
     * True if a write failed, e.g. a downstream `| head` closed the pipe. [run] checks it after rendering
     * to exit 141 rather than report a false success, since the JVM's `PrintStream` swallows the `EPIPE`.
     */
    public fun writeErrored(): Boolean = false
}
