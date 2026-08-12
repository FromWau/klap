package com.fromwau.klap.fixture.pulse

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheckCommandTest {

    @Test
    fun `check fans out concurrently and reports both healthy and failing services`() = runTest {
        val result = pulseCli().captureSuspending("check", "--timeout", "10s")
        assertEquals(0, result.exitCode)
        assertTrue("[ok]" in result.out, result.out)
        assertTrue("[fail]" in result.out, result.out)
        assertTrue("4/6 healthy" in result.out, result.out)
    }

    @Test
    fun `check with no --timeout finishes inside the default budget`() = runTest {
        // Names no --timeout on purpose: every other case here supplies one, so nothing else covers the
        // default's own margin over the deliberately slow service.
        val result = pulseCli().captureSuspending("check")
        assertEquals(0, result.exitCode)
        assertTrue("4/6 healthy" in result.out, result.out)
    }

    @Test
    fun `check --json returns a structured report with one row per service`() = runTest {
        val result = pulseCli().captureSuspending("check", "--timeout", "10s", "--json")
        assertEquals(0, result.exitCode)
        assertTrue("\"results\":[" in result.out, result.out)
        assertEquals(6, Regex(""""service":"""").findAll(result.out).count())
    }

    @Test
    fun `check --service filters to the requested services only`() = runTest {
        val result = pulseCli().captureSuspending("check", "--timeout", "5s", "--service", "api", "--service", "cache")
        assertEquals(0, result.exitCode)
        assertTrue("api" in result.out)
        assertTrue("cache" in result.out)
        assertTrue("database" !in result.out)
    }

    @Test
    fun `check --service rejects a name that matches nothing, as a usage error`() = runTest {
        val result = pulseCli().captureSuspending("check", "--service", "bogus")
        assertEquals(2, result.exitCode)
        assertTrue("no known service matches bogus" in result.err, result.err)
    }

    @Test
    fun `check surfaces a typed batch timeout when the slow service can't finish in time`() = runTest {
        val result = pulseCli().captureSuspending("check", "--timeout", "1s")
        assertEquals(4, result.exitCode)
        assertTrue("timed out after 1s" in result.err, result.err)
    }

    @Test
    fun `check --timeout --json renders the same typed timeout as a json error envelope`() = runTest {
        val result = pulseCli().captureSuspending("check", "--timeout", "1s", "--json")
        assertEquals(4, result.exitCode)
        assertTrue("\"code\":4" in result.err, result.err)
    }

    @Test
    fun `check --timeout rejects a malformed duration as a usage error`() = runTest {
        val result = pulseCli().captureSuspending("check", "--timeout", "abc")
        assertEquals(2, result.exitCode)
        assertTrue("not a valid duration" in result.err, result.err)
    }
}
