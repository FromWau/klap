package com.fromwau.klap.fixture.pulse

import com.fromwau.kern.result.IError
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** A service pulse watches, with a simulated failure personality so runs are deterministic, not flaky. */
@Serializable
enum class FailureMode { NONE, DOWN, SLOW, FLAKY }

@Serializable
data class ServiceConfig(
    val name: String,
    val baseLatency: Duration,
    val failureMode: FailureMode = FailureMode.NONE,
)

@Serializable
data class HealthStatus(
    val service: String,
    val healthy: Boolean,
    val latency: Duration,
    val checkedAt: Instant,
    val detail: String? = null,
)

/** Typed failures for a health check. Rooted in kern's [IError] so it composes with [com.fromwau.klap.CliError]. */
sealed interface ServiceError : IError {
    val service: String

    data class Down(override val service: String) : ServiceError
    data class Flaked(override val service: String, val reason: String) : ServiceError
}

/** The whole `check` batch outran its budget. Not a [ServiceError]: no single service is to blame. */
data class BatchTimeout(val after: Duration) : IError

sealed interface NotifyError : IError {
    data class ChannelDown(val channel: String) : NotifyError
}

/** A `--timeout`/`--interval`/`--duration` value kotlin.time could not parse, e.g. a bare number or garbage. */
sealed interface DurationError : IError {
    data class Malformed(val given: String) : DurationError
}

val DEFAULT_SERVICES: List<ServiceConfig> = listOf(
    ServiceConfig("api", baseLatency = 80.milliseconds),
    ServiceConfig("database", baseLatency = 120.milliseconds),
    ServiceConfig("cache", baseLatency = 40.milliseconds),
    ServiceConfig("queue", baseLatency = 60.milliseconds, failureMode = FailureMode.FLAKY),
    ServiceConfig("auth", baseLatency = 90.milliseconds, failureMode = FailureMode.DOWN),
    ServiceConfig("search", baseLatency = 100.milliseconds, failureMode = FailureMode.SLOW),
)
