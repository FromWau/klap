package com.fromwau.klap.internal.platform

import com.fromwau.klap.Terminal
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

internal actual fun platformExit(code: Int): Nothing = exitProcess(code)

// JVM/Android has no ioctl without JNI; width comes from the COLUMNS env var handled by resolveColumns.
internal actual fun terminalWidth(): Int? = null

internal actual fun ansiSupported(): Boolean = true

/**
 * Shared JVM/Android terminal; only the [isTty] probe differs per platform (isTerminal() vs console
 * presence). [outSink]/[errSink] default to the process's real file descriptors and exist so a test can
 * inject a stream that fails writes, which is otherwise unreachable from here.
 */
internal fun jvmTerminal(
    isTty: Boolean,
    outSink: OutputStream = FileOutputStream(FileDescriptor.out),
    errSink: OutputStream = FileOutputStream(FileDescriptor.err),
): Terminal {
    val env: (String) -> String? = { System.getenv(it) }
    // A dedicated stream per Terminal rather than System.out/System.err: PrintStream.checkError() latches
    // on the first write failure and can never reset, so Terminals sharing one process-wide stream cannot
    // tell a new error from an older run's. A fresh stream scopes the latch to this Terminal's own writes.
    val outStream = PrintStream(outSink, true)
    val errStream = PrintStream(errSink, true)
    return object : Terminal {
        override fun out(text: String) = outStream.print(text)
        override fun err(text: String) = errStream.print(text)
        override val columns: Int = resolveColumns(env, terminalWidth())
        override val ansi: Boolean = ansiEnabled(isTty, env, ::ansiSupported)
        override fun writeErrored(): Boolean = outStream.checkError() || errStream.checkError()
    }
}
