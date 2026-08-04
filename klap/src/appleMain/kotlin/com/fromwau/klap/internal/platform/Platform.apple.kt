package com.fromwau.klap.internal.platform

import kotlinx.cinterop.*
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
internal actual fun terminalWidth(): Int? = memScoped {
    val ws = alloc<winsize>()
    // A redirected/piped stdout fails ioctl; try stderr too before giving up.
    for (fd in intArrayOf(STDOUT_FILENO, STDERR_FILENO)) {
        if (ioctl(fd, TIOCGWINSZ.convert(), ws.ptr) == 0 && ws.ws_col.toInt() > 0) {
            return@memScoped ws.ws_col.toInt()
        }
    }
    null
}

internal actual fun ansiSupported(): Boolean = true
