package com.evcs.favorites.domain

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Ephemeral session and telemetry access tokens obtained from `POST /{slug}.html` with `X-Partial: user`.
 */
@Immutable
@Serializable
data class StationAccessTokens(
    val chargeToken: String,
    val apiToken: String,
    val rating: StationRating? = null,
    val ratingCsrf: String? = null,
    val hasGo: Boolean = false,
    val hasBiz: Boolean = false,
    val historyToken7: String? = null,
    val historyToken30: String? = null
)

/**
 * Community star rating metrics and user review state for an EVCS station.
 */
@Immutable
@Serializable
data class StationRating(
    val avg: Double = 0.0,
    val count: Int = 0,
    val mine: Int = 0
)

/**
 * Live charging telemetry payload parsed from `POST /charging`.
 */
@Immutable
@Serializable
data class StationTelemetry(
    val busyByKw: Map<Int, Int> = emptyMap(),
    val rawTicker: String? = null,
    val cleanForecast: String? = null,
    val isLocked: Boolean = false
)

/**
 * Real-time port availability per kW charging tier.
 */
@Immutable
@Serializable
data class StationPortStatus(
    val kw: Int,
    val availablePorts: Int,
    val totalPorts: Int,
    val busyCount: Int
)

/**
 * 24-hour historical usage statistics calculated from Socket.io time-series data.
 */
@Immutable
@Serializable
data class Station24hStats(
    val peakUsage: Int,
    val avgUsage: Int,
    val peakHour: String,
    val fillRate: Int
)
