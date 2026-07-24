package com.fromwau.klap

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fputs
import platform.posix.stderr
import kotlin.system.exitProcess

internal actual fun platformExit(code: Int): Nothing = exitProcess(code)

@OptIn(ExperimentalForeignApi::class)
internal actual fun defaultTerminal(): Terminal = object : Terminal {
    override fun out(text: String) = print(text)
    override fun err(text: String) {
        fputs(text, stderr)
    }
}
