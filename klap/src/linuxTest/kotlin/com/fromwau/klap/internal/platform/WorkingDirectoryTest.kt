package com.fromwau.klap.internal.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.chdir
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class WorkingDirectoryTest {

    @Test
    fun `a working directory the system can name is absolute`() {
        assertTrue(processWorkingDirectory().startsWith("/"), processWorkingDirectory())
    }

    @Test
    fun `a deleted working directory is the unnamed one`() {
        val home = processWorkingDirectory()
        val folder = "/tmp/klap-cwd-${getpid()}"
        assertEquals(0, mkdir(folder, 0x1C0u))
        try {
            assertEquals(0, chdir(folder))
            assertEquals(0, rmdir(folder))
            assertEquals(UNNAMED_WORKING_DIRECTORY, processWorkingDirectory())
        } finally {
            chdir(home)
        }
    }
}
