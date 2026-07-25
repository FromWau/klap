package com.fromwau.klap.internal.platform

import com.fromwau.klap.Terminal
import kotlin.system.exitProcess

internal actual fun platformExit(code: Int): Nothing = exitProcess(code)

// JVM/Android has no ioctl without JNI; width comes from the COLUMNS env var handled by resolveColumns.
internal actual fun terminalWidth(): Int? = null

/** Shared JVM/Android terminal; only the [isTty] probe differs per platform (isTerminal() vs console presence). */
internal fun jvmTerminal(isTty: Boolean): Terminal {
    val env: (String) -> String? = { System.getenv(it) }
    // PrintStream.checkError() latches to true on the first write failure and cannot be reset (clearError is
    // protected, never called), so in a long-lived JVM process that reuses run(), one broken-pipe write would
    // otherwise make every later, fully successful run() report a write error and return the broken-pipe exit
    // code. Snapshot the latch at construction and report only errors that appear during THIS terminal's
    // lifetime, so a fresh run() is never tainted by a prior one. (In a normal one-shot CLI both snapshots are
    // false, so behavior is unchanged.)
    val outAlreadyErrored = System.out.checkError()
    val errAlreadyErrored = System.err.checkError()
    return object : Terminal {
        override fun out(text: String) = print(text)
        override fun err(text: String) = System.err.print(text)
        override val columns: Int = resolveColumns(env, terminalWidth())
        override val ansi: Boolean = ansiEnabled(isTty, env)
        override fun writeErrored(): Boolean =
            (System.out.checkError() && !outAlreadyErrored) || (System.err.checkError() && !errAlreadyErrored)
    }
}
