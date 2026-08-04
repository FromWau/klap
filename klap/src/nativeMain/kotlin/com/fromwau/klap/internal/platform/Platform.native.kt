package com.fromwau.klap.internal.platform

import com.fromwau.klap.Terminal
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.fileno
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.isatty
import platform.posix.stderr
import platform.posix.stdout
import kotlin.system.exitProcess

internal actual fun platformExit(code: Int): Nothing = exitProcess(code)

@OptIn(ExperimentalForeignApi::class)
internal actual fun defaultTerminal(): Terminal {
    val env: (String) -> String? = { getenv(it)?.toKString() }
    val isTty = isatty(fileno(stdout)) != 0
    return object : Terminal {
        override fun out(text: String) = print(text)
        override fun err(text: String) { fputs(text, stderr) }
        override val columns: Int = resolveColumns(env, terminalWidth())
        override val ansi: Boolean = ansiEnabled(isTty, env, ::ansiSupported)
    }
}
