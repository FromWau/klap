package com.fromwau.klap.internal.platform

/** This process's working directory, or [UNNAMED_WORKING_DIRECTORY] when the system cannot name it. */
internal expect fun processWorkingDirectory(): String

/** The working directory of a process whose folder the system cannot name, as after it was deleted. */
internal const val UNNAMED_WORKING_DIRECTORY: String = "."

/** This process's environment variables, by name. */
internal expect fun processEnvironment(): Map<String, String>

/**
 * The variables of a process's `NAME=value` [entries], as `getenv` answers them: the first of a name set twice, and
 * no variable for an entry without `=`.
 */
internal fun environmentOf(entries: Sequence<String>): Map<String, String> {
    val variables = mutableMapOf<String, String>()
    for (entry in entries) {
        val equals = entry.indexOf('=')
        if (equals < 0) continue
        val name = entry.substring(0, equals)
        if (name !in variables) variables[name] = entry.substring(equals + 1)
    }
    return variables
}
