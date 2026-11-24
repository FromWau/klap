package com.fromwau.klap.internal.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import platform.Foundation.NSProcessInfo
import platform.posix.free
import platform.posix.getcwd

// Apple's headers declare no `environ` on every platform, so Foundation answers instead.
internal actual fun processEnvironment(): Map<String, String> = NSProcessInfo.processInfo.environment
    .entries
    .associate { (name, value) -> name.toString() to value.toString() }

@OptIn(ExperimentalForeignApi::class)
internal actual fun processWorkingDirectory(): String {
    // No buffer: Darwin allocates one as long as the path, so no length is too long.
    val path = getcwd(null, 0.convert()) ?: return UNNAMED_WORKING_DIRECTORY
    return try {
        path.toKString()
    } finally {
        free(path)
    }
}
