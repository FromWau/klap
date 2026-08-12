package com.fromwau.klap.fixture.pulse

import com.fromwau.klap.Invocation
import com.fromwau.klap.parse
import com.fromwau.klap.run
import com.fromwau.klap.runAction
import com.fromwau.kern.result.Result
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `pulse`'s tree mixes a plain `action { }` (`info`) with several `actionSuspending { }` commands
 * (`services`, `check`, `watch`, `notify`). docs/specs/2026-08-12-suspending-actions-design.md describes
 * two different guards for this shape: `run`/`main` refuse the WHOLE tree if anything in it suspends
 * (checked before parsing, so only the aggregate is available), while `parse` + `runAction` refuses only
 * the resolved action in hand. These tests pin exactly that from a consumer's side of the API, against a
 * tree that was declared for its own sake rather than to exercise a guard.
 */
class MixedTreeGuardTest {

    @Test
    fun `the sync run refuses even a sync-only invocation, because the guard is tree-wide`() {
        val terminal = RecordingTerminal()
        val error = assertFailsWith<IllegalArgumentException> {
            pulseCli().run(listOf("info"), terminal)
        }
        assertTrue("actionSuspending" in error.message.orEmpty(), error.message)
        assertTrue("runSuspending" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `parse + runAction drives the sync command directly, unaffected by suspending siblings`() {
        val execute = assertIs<Invocation.Execute>(
            assertIs<Result.Success<Invocation>>(pulseCli().parse(listOf("info"))).value,
        )
        val outcome = assertIs<Result.Success<*>>(execute.runAction())
        val info = assertIs<BuildInfo>(outcome.value)
        assertEquals(6, info.serviceCount)
    }

    @Test
    fun `runSuspending drives the sync command fine too, since the direction is allowed`() = runTest {
        val result = pulseCli().captureSuspending("info")
        assertEquals(0, result.exitCode)
        assertTrue("pulse v0.1.0" in result.out)
    }
}
