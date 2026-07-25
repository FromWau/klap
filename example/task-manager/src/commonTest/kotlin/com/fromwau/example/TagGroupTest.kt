package com.fromwau.example

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/** The nested `tag add` / `tag rm` group: does the grandchild command bind and persist a real change? */
class TagGroupTest {

    @Test
    fun tagAddThenTagRmRoundTripsThroughTheNestedGroup() = withTempStore { path ->
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
}
