package com.fromwau.klap.internal.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import platform.posix.__environ
import platform.posix.free
import platform.posix.getcwd

@OptIn(ExperimentalForeignApi::class)
internal actual fun processEnvironment(): Map<String, String> = environmentOf(entriesOf(__environ) { it.toKString() })

@OptIn(ExperimentalForeignApi::class)
internal actual fun processWorkingDirectory(): String {
    // No buffer: glibc allocates one as long as the path, so no length is too long.
    val path = getcwd(null, 0.convert()) ?: return UNNAMED_WORKING_DIRECTORY
    return try {
        path.toKString()
    } finally {
        free(path)
    }
}
