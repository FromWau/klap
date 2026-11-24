package com.fromwau.klap

import com.fromwau.klap.internal.platform.processEnvironment
import com.fromwau.klap.internal.platform.processWorkingDirectory

/**
 * Who a run is for: the folder it was started in and the environment it was started with. Your action reads it as
 * `caller`, and so does a `.completeWith { }` provider, to resolve a path the user typed against the folder they
 * typed it in, and to hand the environment to whatever resolves directories from it, such as kern's `dirs`:
 *
 * ```kotlin
 * val file = argument("file").path()   // klap-io
 * action {
 *     val config = file().resolvedFor(caller)
 *     val dirs = BaseDirs.fromXdg(caller.environment, accountHome = null)
 *         .getOrElse { return@action Err(CliError.Failure("no home directory")) }
 *     // ...
 * }
 * ```
 *
 * A run is this process's own unless you pass another, so a program run from a shell needs nothing. A daemon that
 * runs lines for a client passes the client's: `runSuspending(argv, terminal, Caller(cwd, environment))`.
 *
 * @property workingDirectory the folder a relative path is read from, as the caller's shell names it.
 * @property environment the caller's environment variables, by name.
 */
public data class Caller(
    val workingDirectory: String,
    val environment: Map<String, String>,
) {
    /** The folder and the variable names only: a value can be a secret, and this lands in logs and test failures. */
    override fun toString(): String =
        "Caller(workingDirectory=$workingDirectory, environment=${environment.keys.sorted()})"

    public companion object {
        /**
         * This process's own working directory and environment, as a program run from a shell sees them. When the
         * system cannot name the folder, as after it was deleted, [workingDirectory] is `.`, so a relative path still
         * reads from wherever the process is.
         */
        public fun process(): Caller = Caller(processWorkingDirectory(), processEnvironment())
    }
}
