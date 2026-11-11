package com.fromwau.klap

import com.fromwau.kern.terminal.BROKEN_PIPE_EXIT

/**
 * A code the process exits with and what it means. Declare a command's own with [CommandBuilder.exits], so help,
 * the man page, the markdown page and `--help --json` list it, and return the same object where it happens: from an
 * action as `action(exit = { … })`, or carried by a [CliError.Failure] or [CliError.Domain].
 *
 * ```kotlin
 * enum class StoreExit(
 *     override val code: Int,
 *     override val meaning: String,
 *     override val success: Boolean = false,
 * ) : Exit {
 *     NotFound(3, "no task has that id"),
 *     Differ(4, "the stores differ", success = true),
 * }
 *
 * command("show") {
 *     exits(StoreExit.NotFound)
 *     action {
 *         store.find(id())?.let { Ok(it) } ?: Err(CliError.Failure("no task ${id()}", exit = StoreExit.NotFound))
 *     }
 * }
 * ```
 */
public interface Exit {
    /** The process exit status, from 0 to 255. */
    public val code: Int

    /** What the code means, as help prints it: `no line matched the pattern`. */
    public val meaning: String

    /** Whether the code answers rather than fails, as `diff` exits 1 when the files differ; shown `(not a failure)`. */
    public val success: Boolean get() = false

    /** klap's own exits, which every run can give: help lists all but [BrokenPipe], the man and markdown pages all. */
    public companion object {
        public val Success: Exit = KlapExit(0, "success", success = true)
        public val Failure: Exit = KlapExit(1, "a failure")
        public val Usage: Exit =
            KlapExit(USAGE_ERROR_EXIT, "a usage error, such as an unknown option or a value that does not parse")
        public val BrokenPipe: Exit =
            KlapExit(BROKEN_PIPE_EXIT, "standard output closed before everything was written, as by `head`")
    }
}

private data class KlapExit(
    override val code: Int,
    override val meaning: String,
    override val success: Boolean = false,
) : Exit
