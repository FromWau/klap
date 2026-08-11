package com.fromwau.example

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json

/**
 * A zero-byte or whitespace-only store file means the same thing as a missing one: no tasks yet,
 * most often left behind by a write interrupted before the atomic swap in `TaskStore.save`.
 * Genuinely malformed JSON is a different, still-fatal case and must keep failing exactly as before.
 */
class EmptyStoreFileTest {

    private fun writeRawStore(path: String, content: String) {
        SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(content) }
    }

    @Test
    fun `a zero byte store loads as an empty list`() = withTempStore { path ->
        writeRawStore(path, "")

        val result = taskManagerCli().captureWithFile(path, "list")
        assertEquals(0, result.exitCode, result.err)
        assertEquals("no tasks", result.out.trim())
    }

    @Test
    fun `a whitespace only store loads as an empty list`() = withTempStore { path ->
        writeRawStore(path, "  \n\t  ")

        val result = taskManagerCli().captureWithFile(path, "list")
        assertEquals(0, result.exitCode, result.err)
        assertEquals("no tasks", result.out.trim())
    }

    @Test
    fun `adding to a zero byte store recovers a well formed store`() = withTempStore { path ->
        writeRawStore(path, "")

        val added = taskManagerCli().captureWithFile(path, "add", "Buy milk")
        assertEquals(0, added.exitCode, added.err)

        val text = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
        val tasks = Json.decodeFromString<List<Task>>(text)
        val task = tasks.single()
        assertEquals(1, task.id)
        assertEquals("Buy milk", task.title)
    }

    @Test
    fun `genuinely invalid json store still fails with the same message and exit code`() = withTempStore { path ->
        writeRawStore(path, "not json at all")

        val result = taskManagerCli().captureWithFile(path, "list")
        assertEquals(EXIT_CORRUPT_STORE, result.exitCode, result.err)
        assertContains(result.err, "task store ${Path(path)} is not valid JSON")
    }
}
