package com.fromwau.klap.fixture.pulse

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Fake service catalogue. Stands in for a config service / service registry over the network. */
class Repository(private val services: List<ServiceConfig> = DEFAULT_SERVICES) {

    suspend fun listServices(): List<ServiceConfig> {
        delay(30.milliseconds) // simulated round trip
        return services
    }

    suspend fun findService(name: String): ServiceConfig? {
        delay(20.milliseconds)
        return services.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** Fake health prober. Every check simulates latency; some services are wired to fail or hang. */
class HealthChecker {

    suspend fun check(service: ServiceConfig): Result<HealthStatus, ServiceError> {
        val started = TimeSource.Monotonic.markNow()
        val checkedAt = Clock.System.now()
        return when (service.failureMode) {
            FailureMode.NONE -> {
                delay(service.baseLatency)
                Ok(HealthStatus(service.name, healthy = true, latency = started.elapsedNow(), checkedAt = checkedAt))
            }

            FailureMode.DOWN -> {
                delay(service.baseLatency / 2)
                Err(ServiceError.Down(service.name))
            }

            FailureMode.FLAKY -> {
                delay(service.baseLatency)
                Err(ServiceError.Flaked(service.name, "connection reset by peer"))
            }

            FailureMode.SLOW -> {
                // Bracketed on purpose: slow enough that `--timeout 1s` has something to cut off, quick
                // enough to finish inside `--timeout`'s 5s default, so a bare `check` reaches its rows.
                delay(service.baseLatency * 20)
                Ok(HealthStatus(service.name, healthy = true, latency = started.elapsedNow(), checkedAt = checkedAt))
            }
        }
    }
}

/** Fake outbound notifier, e.g. a chat webhook. */
class Notifier {

    suspend fun notify(channel: String, message: String): Result<Unit, NotifyError> {
        delay(50.milliseconds)
        return if (channel == "broken") {
            Err(NotifyError.ChannelDown(channel))
        } else {
            Ok(Unit)
        }
    }
}
