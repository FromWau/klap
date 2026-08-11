package com.fromwau.example

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/** Pins the three abbreviations `Main.kt`'s `abbreviation = Abbreviation.All` comment claims resolve. */
class AbbreviationTest {

    @Test
    fun `an abbreviated subcommand reaches list`() = withTempStore { path ->
        val cli = taskManagerCli()
        cli.captureWithFile(path, "add", "Buy milk")

        // `lis` prefixes no other subcommand or alias, so it resolves to `list` alone.
        val result = cli.captureWithFile(path, "lis")
        assertEquals(0, result.exitCode, result.err)
        assertContains(result.out, "Buy milk")
    }

    @Test
    fun `an abbreviated long option reaches the builtin json flag`() = withTempStore { path ->
        val cli = taskManagerCli()
        cli.captureWithFile(path, "add", "Buy milk")

        // `--j` prefixes no other long option or built-in reachable from `list`, so it reaches `--json`.
        val result = cli.captureWithFile(path, "list", "-n", "1", "--j")
        assertEquals(0, result.exitCode, result.err)

        val tasks = Json.decodeFromString<List<Task>>(result.out.trim())
        assertEquals(listOf("Buy milk"), tasks.map { it.title })
    }

    @Test
    fun `an abbreviated choice value reaches high`() = withTempStore { path ->
        val cli = taskManagerCli()

        // `hi` prefixes only `high` among low/medium/high, so it resolves unambiguously.
        val added = cli.captureWithFile(path, "add", "Ship it", "--priority", "hi")
        assertEquals(0, added.exitCode, added.err)

        val tasks = Json.decodeFromString<List<Task>>(cli.captureWithFile(path, "list", "--json").out.trim())
        assertEquals(Priority.HIGH, tasks.single().priority)
    }
}
