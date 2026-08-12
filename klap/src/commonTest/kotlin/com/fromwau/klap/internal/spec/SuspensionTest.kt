package com.fromwau.klap.internal.spec

import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuspensionTest {

    @Test
    fun `a block that never suspends completes and returns its value`() {
        assertEquals(42, completeWithoutSuspending { 42 })
    }

    @Test
    fun `a block that completes with null returns null rather than failing`() {
        assertNull(completeWithoutSuspending<Int?> { null })
    }

    @Test
    fun `a block that genuinely suspends fails loudly rather than hanging`() {
        // Nothing resumes this continuation, so without the check the call would block forever.
        val error = assertFailsWith<IllegalStateException> {
            completeWithoutSuspending { suspendCoroutine<Int> { } }
        }
        assertTrue("synchronous entry point" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a throw inside the block reaches the caller unchanged`() {
        val error = assertFailsWith<IllegalArgumentException> {
            completeWithoutSuspending<Int> { throw IllegalArgumentException("boom") }
        }
        assertEquals("boom", error.message)
    }
}
