package com.fromwau.klap.fixture.pulse

import kotlinx.serialization.Serializable

@Serializable
data class CheckReport(val results: List<HealthStatus>) {
    val healthyCount: Int get() = results.count { it.healthy }
    val total: Int get() = results.size
}

@Serializable
data class WatchSummary(val ticks: Int, val finalHealthy: Int, val finalTotal: Int)

@Serializable
data class BuildInfo(val name: String, val version: String, val serviceCount: Int)
