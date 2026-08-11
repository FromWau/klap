package com.fromwau.example

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The counting global `-v`/`-vv`: each rung reveals one more field of the same rendered line. */
class VerbosityTest {

    @Test
    fun `repeated global verbose reveals more of each task`() = withTempStore { path ->
        val cli = taskManagerCli()
        cli.captureWithFile(path, "add", "Ship it", "--due", "2026-01-01", "--tag", "release")

        val quiet = cli.captureWithFile(path, "list").out
        assertFalse("2026-01-01" in quiet, quiet)
        assertFalse("release" in quiet, quiet)

        val onceVerbose = cli.captureWithFile(path, "-v", "list").out
        assertContains(onceVerbose, "2026-01-01")
        assertFalse("release" in onceVerbose, onceVerbose)

        val twiceVerbose = cli.captureWithFile(path, "-vv", "list").out
        assertContains(twiceVerbose, "2026-01-01")
        assertContains(twiceVerbose, "release")
    }
}
