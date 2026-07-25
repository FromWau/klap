package com.fromwau.example

import com.fromwau.klap.Cli
import com.fromwau.klap.Terminal
import com.fromwau.klap.run
import kotlin.random.Random
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/** Collects written text so a test can assert on rendered output and exit code, never real stdio. */
internal class RecordingTerminal : Terminal {
    val out = StringBuilder()
    val err = StringBuilder()
    override fun out(text: String) { out.append(text) }
    override fun err(text: String) { err.append(text) }
}

/** What one `run` produced: the exit code plus everything written to each stream. */
internal data class RunResult(val exitCode: Int, val out: String, val err: String)

/** Runs [argv] against a fresh [RecordingTerminal] and captures the result; never exits the process. */
internal fun Cli.capture(argv: List<String>): RunResult {
    val terminal = RecordingTerminal()
    val code = run(argv, terminal)
    return RunResult(code, terminal.out.toString(), terminal.err.toString())
}

internal fun Cli.capture(vararg argv: String): RunResult = capture(argv.toList())

/** [capture], with `--file path` prefixed: the shape almost every test needs, since that is the store. */
internal fun Cli.captureWithFile(path: String, vararg argv: String): RunResult =
    capture(listOf("--file", path) + argv)

/**
 * Runs [block] against a store path unique to this call, then deletes whatever `TaskStore` left behind
 * (the file itself and its atomic-move `.tmp` sibling), so tests never share mutable state on disk.
 */
internal fun withTempStore(block: (path: String) -> Unit) {
    val path = Path(SystemTemporaryDirectory, "klap-task-manager-test-${Random.nextInt(Int.MAX_VALUE)}.json")
    try {
        block(path.toString())
    } finally {
        SystemFileSystem.delete(path, mustExist = false)
        SystemFileSystem.delete(Path("$path.tmp"), mustExist = false)
    }
}
