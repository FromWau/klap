package com.fromwau.klap.internal.platform

import kotlinx.cinterop.*
import platform.windows.*

@OptIn(ExperimentalForeignApi::class)
internal actual fun terminalWidth(): Int? = memScoped {
    val info = alloc<CONSOLE_SCREEN_BUFFER_INFO>()
    // A redirected/piped stdout fails the console query; try stderr too before giving up.
    for (stream in listOf(STD_OUTPUT_HANDLE, STD_ERROR_HANDLE)) {
        val handle = GetStdHandle(stream)
        if (handle != null && handle != INVALID_HANDLE_VALUE &&
            GetConsoleScreenBufferInfo(handle, info.ptr) != 0
        ) {
            val width = info.srWindow.Right - info.srWindow.Left + 1
            if (width > 0) return@memScoped width
        }
    }
    null
}

// The classic console host renders escape bytes literally until VT processing is opted into per-handle.
@OptIn(ExperimentalForeignApi::class)
internal actual fun ansiSupported(): Boolean = memScoped {
    val handle = GetStdHandle(STD_OUTPUT_HANDLE)
    if (handle == null || handle == INVALID_HANDLE_VALUE) return@memScoped false
    val mode = alloc<DWORDVar>()
    if (GetConsoleMode(handle, mode.ptr) == 0) return@memScoped false
    val vtFlag: UInt = ENABLE_VIRTUAL_TERMINAL_PROCESSING.convert()
    if ((mode.value and vtFlag) != 0u) return@memScoped true
    SetConsoleMode(handle, mode.value or vtFlag) != 0
}
