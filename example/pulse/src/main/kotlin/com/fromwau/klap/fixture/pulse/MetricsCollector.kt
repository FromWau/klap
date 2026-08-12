package com.fromwau.klap.fixture.pulse

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

private val TICK_INTERVAL = 200.milliseconds

/**
 * A long-running background loop that ticks independently of whatever CLI command is running.
 * Stands in for a metrics scraper / connection-pool keepalive: something a real daemon-ish CLI
 * would want alive for the whole invocation, not scoped to one action.
 */
class MetricsCollector {
    private var tickCount = 0

    suspend fun startLoop() {
        try {
            while (true) {
                delay(TICK_INTERVAL)
                tickCount++
            }
        } finally {
            // Proves the caller's cancelAndJoin() pattern (guide.md "Suspending actions") actually
            // lets cleanup run, instead of the process being killed out from under it.
            System.err.println("[metrics] loop shut down cleanly after $tickCount tick(s)")
        }
    }
}
