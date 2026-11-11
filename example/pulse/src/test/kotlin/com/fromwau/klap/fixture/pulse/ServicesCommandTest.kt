package com.fromwau.klap.fixture.pulse

import kotlin.test.assertIs
import kotlin.test.assertFalse
import com.fromwau.klap.parse
import com.fromwau.klap.Invocation
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServicesCommandTest {

    @Test
    fun `services list prints every known service`() = runTest {
        val result = pulseCli().captureSuspending("services", "list")
        assertEquals(0, result.exitCode)
        DEFAULT_SERVICES.forEach { svc -> assertTrue(svc.name in result.out, "expected ${svc.name} in ${result.out}") }
    }

    @Test
    fun `services list --json emits an array with every service name`() = runTest {
        val result = pulseCli().captureSuspending("services", "list", "--json")
        assertEquals(0, result.exitCode)
        assertTrue(result.out.trim().startsWith("["))
        DEFAULT_SERVICES.forEach { svc -> assertTrue("\"${svc.name}\"" in result.out) }
    }

    @Test
    fun `services show finds a known service`() = runTest {
        val result = pulseCli().captureSuspending("services", "show", "cache")
        assertEquals(0, result.exitCode)
        assertTrue("cache" in result.out)
        assertTrue("40ms" in result.out)
    }

    @Test
    fun `services show reports a typed failure for an unknown service`() = runTest {
        val result = pulseCli().captureSuspending("services", "show", "nope")
        assertEquals(2, result.exitCode)
        assertTrue("no service named 'nope'" in result.err)
    }

    @Test
    fun `a caller of parse tells services run without a command from its help asked for`() {
        val bare = assertIs<Invocation.ShowHelp>(pulseCli().parse(listOf("services")).assertSuccess())
        assertTrue(bare.missingCommand)
        val asked = assertIs<Invocation.ShowHelp>(pulseCli().parse(listOf("services", "--help")).assertSuccess())
        assertFalse(asked.missingCommand)
    }
}
