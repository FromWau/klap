package com.fromwau.example

import com.fromwau.klap.USAGE_ERROR_EXIT
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** A bad value against a choice-restricted converter is a usage error naming the option, not a crash. */
class EnumChoiceValidationTest {

    @Test
    fun `add rejects an unknown priority naming the option`() = withTempStore { path ->
        val result = taskManagerCli().captureWithFile(path, "add", "Ship it", "--priority", "urgent")
        assertEquals(USAGE_ERROR_EXIT, result.exitCode, result.err)
        assertContains(result.err, "--priority")
        assertContains(result.err, "urgent")
    }

    @Test
    fun `list rejects an unknown status naming the option`() = withTempStore { path ->
        val result = taskManagerCli().captureWithFile(path, "list", "--status", "archived")
        assertEquals(USAGE_ERROR_EXIT, result.exitCode, result.err)
        assertContains(result.err, "--status")
        assertContains(result.err, "archived")
    }
}
