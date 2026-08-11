package com.fromwau.example

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/** `add` then `list`: the program's core loop, on both the human path and `--json`. */
class AddListRoundTripTest {

    @Test
    fun `add then list round trips a task`() = withTempStore { path ->
        val cli = taskManagerCli()

        val added = cli.captureWithFile(path, "add", "Buy milk", "--priority", "high", "--tag", "errands")
        assertEquals(0, added.exitCode, added.err)
        assertContains(added.out, "Buy milk")

        val listed = cli.captureWithFile(path, "list")
        assertEquals(0, listed.exitCode, listed.err)
        assertContains(listed.out, "#1")
        assertContains(listed.out, "Buy milk")
        assertContains(listed.out, "(high)")
    }

    @Test
    fun `json emits the same task as real structured json`() = withTempStore { path ->
        val cli = taskManagerCli()
        cli.captureWithFile(path, "add", "Buy milk", "--priority", "high", "--tag", "errands")

        val jsonResult = cli.captureWithFile(path, "list", "--json")
        assertEquals(0, jsonResult.exitCode, jsonResult.err)

        val tasks = Json.decodeFromString<List<Task>>(jsonResult.out.trim())
        val task = tasks.single()
        assertEquals(1, task.id)
        assertEquals("Buy milk", task.title)
        assertEquals(Priority.HIGH, task.priority)
        assertEquals(listOf("errands"), task.tags)
        assertEquals(false, task.done)
        assertEquals(null, task.due)
    }

    @Test
    fun `listing an empty store says so rather than printing nothing`() = withTempStore { path ->
        val result = taskManagerCli().captureWithFile(path, "list")
        assertEquals(0, result.exitCode, result.err)
        assertEquals("no tasks", result.out.trim())
    }
}
