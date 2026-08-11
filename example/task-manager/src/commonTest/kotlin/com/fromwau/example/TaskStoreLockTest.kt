package com.fromwau.example

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.klap.CliError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

/** Stands in for another invocation holding the lock; the marker inside is what makes a rename onto it fail. */
private fun holdLock(lockPath: Path) {
    SystemFileSystem.createDirectories(lockPath)
    SystemFileSystem.sink(Path(lockPath, "owner")).buffered().use { it.writeString("held") }
}

/**
 * A unique temp path stops two writers corrupting each other mid-write, but not the lost update behind it:
 * each invocation loads a snapshot, appends, and saves, so without exclusion the last writer silently
 * discards the rest. Every load-modify-save runs under the store lock.
 */
class TaskStoreLockTest {

    @Test
    fun aWriterCannotEnterWhileAnotherHoldsTheLock() = withTempStore { path ->
        val store = TaskStore(Path(path), lockTimeout = 50.milliseconds)
        holdLock(store.lockPath)

        val result = store.withLock { Ok(Unit) }

        val error = assertIs<Result.Error<CliError>>(result).error
        assertEquals(EXIT_STORE_BUSY, assertIs<CliError.Failure>(error).exitCode)
    }

    @Test
    fun theLockIsReleasedOnceTheBlockSucceeds() = withTempStore { path ->
        val store = TaskStore(Path(path))

        assertIs<Result.Success<Unit>>(store.withLock { store.save(listOf(Task(id = 1, title = "Buy milk"))) })
        assertEquals(false, SystemFileSystem.exists(store.lockPath))
    }

    @Test
    fun theLockIsReleasedWhenTheBlockReturnsAnError() = withTempStore { path ->
        val store = TaskStore(Path(path))

        assertIs<Result.Error<CliError>>(store.withLock { Err(CliError.Failure("boom", exitCode = EXIT_NOT_FOUND)) })
        assertEquals(false, SystemFileSystem.exists(store.lockPath))
    }

    @Test
    fun aLaterWriterEntersOnceTheEarlierOneHasFinished() = withTempStore { path ->
        val store = TaskStore(Path(path), lockTimeout = 50.milliseconds)

        assertIs<Result.Success<Unit>>(store.withLock { store.save(listOf(Task(id = 1, title = "first"))) })
        assertIs<Result.Success<Unit>>(store.withLock { store.save(listOf(Task(id = 2, title = "second"))) })
    }
}
