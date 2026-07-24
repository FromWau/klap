package com.fromwau.example

import com.fromwau.klap.CliError
import com.fromwau.klap.Err
import com.fromwau.klap.Ok
import com.fromwau.klap.Result
import com.fromwau.klap.action
import com.fromwau.klap.cli
import com.fromwau.klap.enum
import com.fromwau.klap.file
import com.fromwau.klap.main
import com.fromwau.klap.multiple
import kotlinx.serialization.Serializable
import java.io.File

/** Per-file counts. `@Serializable` so `--json` emits the full record for every file. */
@Serializable
data class FileStats(
    val file: String,
    val lines: Int,
    val words: Int,
    val chars: Int,
)

/** Which single column the human table shows; `--only` is a view projection — `--json` always carries the full record. */
enum class Metric { LINES, WORDS, CHARS }

fun main(args: Array<String>) {
    val tally = cli("tally") {
        description = "Count lines, words, and characters in text files"
        version = "1.0.0"

        // A single-command tool: the root itself acts, so `tally <files>` needs no subcommand.
        val files = argument("files", "Files to count").file().multiple(min = 1)
        val only = option("only", "o", "Show only one metric in the table").enum<Metric>()

        action(human = { stats -> renderTable(stats, only()) }) {
            countFiles(files())
        }
    }

    tally.main(args)
}

/** Read and count each file, mapping the first I/O failure to a typed [CliError] at the boundary. */
private fun countFiles(paths: List<String>): Result<List<FileStats>, CliError> {
    val stats = mutableListOf<FileStats>()
    for (path in paths) {
        val f = File(path)
        if (!f.isFile) return Err(CliError.Failure("no such file: $path"))
        val text = try {
            f.readText()
        } catch (e: Exception) {
            return Err(CliError.Failure("cannot read $path: ${e.message}"))
        }
        stats += FileStats(
            file = path,
            lines = text.count { it == '\n' },
            words = text.split(Regex("\\s+")).count { it.isNotBlank() },
            chars = text.length,
        )
    }
    return Ok(stats)
}

private const val COL = 7

private fun Int.cell(): String = toString().padStart(COL)

private fun FileStats.metric(metric: Metric): Int = when (metric) {
    Metric.LINES -> lines
    Metric.WORDS -> words
    Metric.CHARS -> chars
}

/** The `--json`-free view: a full table, or one column when `--only` is set, with a totals row for multiple files. */
private fun renderTable(stats: List<FileStats>, only: Metric?): String {
    val rows = mutableListOf<String>()
    if (only != null) {
        stats.forEach { rows += "${it.metric(only).cell()}  ${it.file}" }
        if (stats.size > 1) rows += "${stats.sumOf { it.metric(only) }.cell()}  total"
    } else {
        rows += "${"LINES".padStart(COL)}  ${"WORDS".padStart(COL)}  ${"CHARS".padStart(COL)}  FILE"
        stats.forEach { rows += "${it.lines.cell()}  ${it.words.cell()}  ${it.chars.cell()}  ${it.file}" }
        if (stats.size > 1) {
            rows += "${stats.sumOf { it.lines }.cell()}  ${stats.sumOf { it.words }.cell()}  ${stats.sumOf { it.chars }.cell()}  total"
        }
    }
    return rows.joinToString("\n")
}
