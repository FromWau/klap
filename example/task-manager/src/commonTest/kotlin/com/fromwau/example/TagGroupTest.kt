package com.fromwau.example

import com.fromwau.klap.USAGE_ERROR_EXIT
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/** The nested `tag add` / `tag rm` group: does the grandchild command bind and persist a real change? */
class TagGroupTest {

    @Test
    fun `tag add then tag rm round trips through the nested group`() = withTempStore { path ->
        val cli = taskManagerCli()
        cli.captureWithFile(path, "add", "Ship it")

        val added = cli.captureWithFile(path, "tag", "add", "1", "urgent")
        assertEquals(0, added.exitCode, added.err)
        assertContains(added.out, "urgent")

        val afterAdd = Json.decodeFromString<List<Task>>(cli.captureWithFile(path, "list", "--json").out.trim())
        assertEquals(listOf("urgent"), afterAdd.single().tags)

        val removed = cli.captureWithFile(path, "tag", "rm", "1", "urgent")
        assertEquals(0, removed.exitCode, removed.err)

        val afterRemove = Json.decodeFromString<List<Task>>(cli.captureWithFile(path, "list", "--json").out.trim())
        assertEquals(emptyList(), afterRemove.single().tags)
    }

    @Test
    fun `removing a tag the task never carried is refused`() = withTempStore { path ->
        val cli = taskManagerCli()
        cli.captureWithFile(path, "add", "Ship it")
        cli.captureWithFile(path, "tag", "add", "1", "urgent")

        val result = cli.captureWithFile(path, "tag", "rm", "1", "later")
        assertEquals(USAGE_ERROR_EXIT, result.exitCode, result.out)
        assertContains(result.err, "later")
        assertContains(result.err, "does not carry that tag")

        // Refused means unchanged: the tag it does carry is still there.
        val after = Json.decodeFromString<List<Task>>(cli.captureWithFile(path, "list", "--json").out.trim())
        assertEquals(listOf("urgent"), after.single().tags)
    }
}
