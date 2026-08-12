package com.fromwau.klap.fixture.pulse

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class CancellationTest {

    @Test
    fun `cancelling a run of the real watch command through runSuspending stops it before the full duration`() = runTest {
        var completedNormally = false
        val job = launch {
            pulseCli().captureSuspending("watch", "--interval", "1s", "--duration", "300s")
            completedNormally = true
        }

        // Let it get into the loop, then cancel from outside, the way the shutdown hook in main() does.
        delay(50.milliseconds)
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse(completedNormally, "the 300s watch resumed past a cancelled scope instead of being cut off")
    }
}
