package com.fromwau.klap.internal.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKStringFromUtf16
import platform.posix._wenviron
import platform.posix._wgetcwd
import platform.posix.free

// The wide forms: the ANSI ones hand back the system code page, which mangles a name outside it.
@OptIn(ExperimentalForeignApi::class)
internal actual fun processEnvironment(): Map<String, String> =
    environmentOf(entriesOf(_wenviron) { it.toKStringFromUtf16() })

@OptIn(ExperimentalForeignApi::class)
internal actual fun processWorkingDirectory(): String {
    // No buffer: the CRT allocates one as long as the path, so no length is too long.
    val path = _wgetcwd(null, 0) ?: return UNNAMED_WORKING_DIRECTORY
    return try {
        path.toKStringFromUtf16()
    } finally {
        free(path)
    }
}
