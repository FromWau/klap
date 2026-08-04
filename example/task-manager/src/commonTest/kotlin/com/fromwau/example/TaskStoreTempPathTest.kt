package com.fromwau.example

import com.fromwau.klap.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Two processes racing on the same `--file` must not share a temp path: one process's atomicMove would
 * otherwise rename the file out from under the other mid-write. The path is unique per store instance,
 * and a completed save leaves none behind.
 */
class TaskStoreTempPathTest {

    @Test
    fun twoStoresForTheSameFileNeverShareATempPath() = withTempStore { path ->
        val first = TaskStore(Path(path))
        val second = TaskStore(Path(path))

        assertNotEquals(first.tempPath, second.tempPath)
    }

    @Test
    fun aCompletedSaveLeavesNoTempFileBehind() = withTempStore { path ->
        val store = TaskStore(Path(path))

        val result = store.save(listOf(Task(id = 1, title = "Buy milk")))

        assertIs<Result.Success<Unit>>(result)
        assertEquals(false, SystemFileSystem.exists(store.tempPath))
    }
}
