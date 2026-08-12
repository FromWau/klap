package com.fromwau.klap.fixture.pulse

import com.fromwau.kern.terminal.Terminal
import com.fromwau.klap.Cli
import com.fromwau.klap.runSuspending

/** Collects written text so a test can assert on rendered output and exit code, never real stdio. */
internal class RecordingTerminal : Terminal {
    val out = StringBuilder()
    val err = StringBuilder()
    override fun out(text: String) { out.append(text) }
    override fun err(text: String) { err.append(text) }
}

/** What one run produced: the exit code plus everything written to each stream. */
internal data class RunResult(val exitCode: Int, val out: String, val err: String)

/** Runs [argv] against a fresh [RecordingTerminal] through the suspending entry point; never exits the process. */
internal suspend fun Cli.captureSuspending(vararg argv: String): RunResult {
    val terminal = RecordingTerminal()
    val code = runSuspending(argv.toList(), terminal)
    return RunResult(code, terminal.out.toString(), terminal.err.toString())
}
