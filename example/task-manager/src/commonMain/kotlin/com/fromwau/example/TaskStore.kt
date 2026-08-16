package com.fromwau.example

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.klap.CliError
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val json = Json { prettyPrint = true }

private val LOCK_TIMEOUT = 5.seconds

private const val LOCK_OWNER = "owner"

internal class TaskStore(private val file: Path, private val lockTimeout: Duration = LOCK_TIMEOUT) {
    private val stamp = Random.nextInt(Int.MAX_VALUE)

    // Randomized per instance: two processes racing on the same --file each get their own temp
    // path, so one process's atomicMove can never sweep away the other's write in progress.
    internal val tempPath: Path = Path("$file.$stamp.tmp")

    internal val lockPath: Path = Path("$file.lock")

    private val stagingPath: Path = Path("$file.$stamp.staging")

    /**
     * Runs [block] holding the store lock, so a load-modify-save cannot be overwritten by a concurrent
     * invocation that read the same snapshot before this one wrote.
     */
    fun <T> withLock(block: () -> Result<T, CliError>): Result<T, CliError> {
        val start = TimeSource.Monotonic.markNow()
        while (!tryAcquire()) {
            if (start.elapsedNow() > lockTimeout) return Err(storeBusy(file, lockPath))
        }
        return try {
            block()
        } finally {
            release()
        }
    }

    // Renaming a directory onto a non-empty one fails, which is what makes exactly one caller win.
    // createDirectories(mustCreate = true) looks like the primitive for this and is not: on native it can
    // return success having created nothing. Spun rather than slept on, since commonMain has no sleep.
    private fun tryAcquire(): Boolean {
        SystemFileSystem.createDirectories(stagingPath)
        SystemFileSystem.sink(Path(stagingPath, LOCK_OWNER)).buffered().use { it.writeString("$stamp") }
        return try {
            SystemFileSystem.atomicMove(stagingPath, lockPath)
            true
        } catch (_: IOException) {
            discard(stagingPath)
            false
        }
    }

    // Moved away whole rather than emptied in place: deleting the marker first would leave the lock an
    // empty directory, which a waiting writer's rename would replace while this one still holds it.
    private fun release() {
        val released = Path("$file.$stamp.released")
        try {
            SystemFileSystem.atomicMove(lockPath, released)
        } catch (_: IOException) {
            return
        }
        discard(released)
    }

    private fun discard(dir: Path) {
        SystemFileSystem.delete(Path(dir, LOCK_OWNER), mustExist = false)
        SystemFileSystem.delete(dir, mustExist = false)
    }

    fun load(): Result<List<Task>, CliError> {
        return try {
            if (!SystemFileSystem.exists(file)) return Ok(emptyList())
            val text = SystemFileSystem.source(file).buffered().use { it.readString() }
            if (text.isBlank()) return Ok(emptyList())
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
        return try {
            SystemFileSystem.sink(tempPath).buffered().use { it.writeString(json.encodeToString(tasks)) }
            SystemFileSystem.atomicMove(tempPath, file)
            Ok(Unit)
        } catch (e: IOException) {
            SystemFileSystem.delete(tempPath, mustExist = false)
            Err(storeIoError(file, e))
        }
    }

    fun nextId(tasks: List<Task>): Int = (tasks.maxOfOrNull { it.id } ?: 0) + 1
}

private fun corruptStore(file: Path) =
    CliError.Failure("task store $file is not valid JSON", exitCode = EXIT_CORRUPT_STORE)

private fun storeBusy(file: Path, lock: Path) = CliError.Failure(
    "task store $file is busy; if no other invocation is running, remove $lock",
    exitCode = EXIT_STORE_BUSY,
)

private fun storeIoError(file: Path, cause: IOException) =
    CliError.Failure("could not access task store $file: ${cause.reason()}", exitCode = EXIT_IO_ERROR)

// kotlinx-io renders these as "Failed to <op> <path> with <reason>", so keeping only the trailing reason
// stops the path printing twice; a message in any other shape has no " with " and survives whole.
private fun IOException.reason(): String =
    message?.substringAfterLast(" with ")?.takeIf { it.isNotBlank() } ?: "unknown I/O error"


