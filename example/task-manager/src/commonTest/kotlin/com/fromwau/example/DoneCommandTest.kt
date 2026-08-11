package com.fromwau.example

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** `done`'s two own failure codes (Constants.kt), not klap's generic usage-error code. */
class DoneCommandTest {

    @Test
    fun `done on an unknown id exits with the programs not found code`() = withTempStore { path ->
        val result = taskManagerCli().captureWithFile(path, "done", "42")
        assertEquals(EXIT_NOT_FOUND, result.exitCode, result.err)
        assertContains(result.err, "no task with id 42")
    }

    @Test
    fun `done on an already done task exits with the programs already done code`() = withTempStore { path ->
        val cli = taskManagerCli()
        cli.captureWithFile(path, "add", "Ship it", "--done")

        val result = cli.captureWithFile(path, "done", "1")
        assertEquals(EXIT_ALREADY_DONE, result.exitCode, result.err)
        assertContains(result.err, "task 1 is already done")
    }
}
