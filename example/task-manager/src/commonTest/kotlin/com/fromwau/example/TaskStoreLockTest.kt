package com.fromwau.example

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.klap.CliError
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `a writer cannot enter while another holds the lock`() = withTempStore { path ->
        val store = TaskStore(Path(path), lockTimeout = 50.milliseconds)
        holdLock(store.lockPath)

        val result = store.withLock { Ok(Unit) }

        assertEquals(EXIT_STORE_BUSY, result.assertError<CliError.Failure>().exitCode)
    }

    @Test
    fun `the lock is released once the block succeeds`() = withTempStore { path ->
        val store = TaskStore(Path(path))

        store.withLock { store.save(listOf(Task(id = 1, title = "Buy milk"))) }.assertSuccess()
        assertEquals(false, SystemFileSystem.exists(store.lockPath))
    }

    @Test
    fun `the lock is released when the block returns an error`() = withTempStore { path ->
        val store = TaskStore(Path(path))

        store.withLock { Err(CliError.Failure("boom", exitCode = EXIT_NOT_FOUND)) }.assertError<CliError.Failure>()
        assertEquals(false, SystemFileSystem.exists(store.lockPath))
    }

    @Test
    fun `a later writer enters once the earlier one has finished`() = withTempStore { path ->
        val store = TaskStore(Path(path), lockTimeout = 50.milliseconds)

        store.withLock { store.save(listOf(Task(id = 1, title = "first"))) }.assertSuccess()
        store.withLock { store.save(listOf(Task(id = 2, title = "second"))) }.assertSuccess()
    }
}
