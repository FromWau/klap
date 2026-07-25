package com.fromwau.example

import com.fromwau.klap.CliError
import com.fromwau.klap.Err
import com.fromwau.klap.Ok
import com.fromwau.klap.Result
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val json = Json { prettyPrint = true }

internal class TaskStore(private val file: Path) {
    fun load(): Result<List<Task>, CliError> {
        return try {
            if (!SystemFileSystem.exists(file)) return Ok(emptyList())
            val text = SystemFileSystem.source(file).buffered().use { it.readString() }
            Ok(json.decodeFromString(text))
        } catch (e: IOException) {
            Err(storeIoError(file, e))
        } catch (_: SerializationException) {
            Err(corruptStore(file))
        }
    }

    fun save(tasks: List<Task>): Result<Unit, CliError> {
        // Write to a sibling temp file (same directory, so same filesystem) then atomically
        // swap it into place, so an interrupted write can never truncate the existing store.
        val tmp = Path("${file}.tmp")
        return try {
            SystemFileSystem.sink(tmp).buffered().use { it.writeString(json.encodeToString(tasks)) }
            SystemFileSystem.atomicMove(tmp, file)
            Ok(Unit)
        } catch (e: IOException) {
            Err(storeIoError(file, e))
        }
    }

    fun nextId(tasks: List<Task>): Int = (tasks.maxOfOrNull { it.id } ?: 0) + 1
}

private fun corruptStore(file: Path) =
    CliError.Failure("task store $file is not valid JSON", exitCode = EXIT_CORRUPT_STORE)

private fun storeIoError(file: Path, cause: IOException) =
    CliError.Failure("could not access task store $file: ${cause.message}", exitCode = EXIT_IO_ERROR)


