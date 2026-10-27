package com.fromwau.klap.fixture.pulse

import com.fromwau.kern.terminal.test.RecordingTerminal
import com.fromwau.klap.Cli
import com.fromwau.klap.runSuspending

/** What one run produced: the exit code plus everything written to each stream. */
internal data class RunResult(val exitCode: Int, val out: String, val err: String)

/** Runs [argv] against a fresh [RecordingTerminal] through the suspending entry point; never exits the process. */
internal suspend fun Cli.captureSuspending(vararg argv: String): RunResult {
    val terminal = RecordingTerminal()
    val code = runSuspending(argv.toList(), terminal)
    return RunResult(code, terminal.out, terminal.err)
}
