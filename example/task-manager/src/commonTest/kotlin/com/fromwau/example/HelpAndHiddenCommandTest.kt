package com.fromwau.example

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** `--help`'s own contract: exits 0, names every real subcommand, and the hidden `where` stays hidden. */
class HelpAndHiddenCommandTest {

    @Test
    fun helpExitsZeroAndNamesEverySubcommandButNotTheHiddenOne() {
        val result = taskManagerCli().capture("--help")
        assertEquals(0, result.exitCode, result.err)
        for (name in listOf("add", "list", "done", "rm", "tag")) {
            assertContains(result.out, name)
        }
        // Not a bare "where" substring check: the epilogue's own "elsewhere" would false-positive on that.
        // The command's own description text is what must never leak into --help.
        assertFalse("print the configured task-store path" in result.out, result.out)
    }

    @Test
    fun addsHelpClosesWithItsOwnEpilogueNotTheRoots() {
        val result = taskManagerCli().capture("add", "--help")
        assertEquals(0, result.exitCode, result.err)
        assertContains(result.out, "Repeat --tag to attach several labels")
        assertFalse("point --file elsewhere" in result.out, result.out)
    }

    @Test
    fun whereRunsAndPrintsTheConfiguredPathEvenThoughHelpNeverMentionsIt() = withTempStore { path ->
        val result = taskManagerCli().captureWithFile(path, "where")
        assertEquals(0, result.exitCode, result.err)
        assertEquals(path, result.out.trim())
    }
}
