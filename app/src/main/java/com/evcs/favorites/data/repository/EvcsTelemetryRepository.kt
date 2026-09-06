package com.evcs.favorites.data.repository

import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.telemetry.EvcsTelemetryDataSource
import com.evcs.favorites.domain.Station24hStats
import com.evcs.favorites.domain.Station24hStatsCalculator
import com.evcs.favorites.domain.StationAccessTokens
import com.evcs.favorites.domain.StationPortStatus
import com.evcs.favorites.domain.StationTelemetry
import com.evcs.favorites.domain.StationTelemetryParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Composite telemetry snapshot containing tokens, live status, derived ports, and optional 24h statistics.
 */
data class StationDetailTelemetrySnapshot(
    val tokens: StationAccessTokens,
    val telemetry: StationTelemetry,
    val portStatuses: List<StationPortStatus>,
    val stats24h: Station24hStats?
)

/**
 * Repository orchestrating on-demand EVCS station detail telemetry and usage statistics.
 */
open class EvcsTelemetryRepository(
    private val dataSource: EvcsTelemetryDataSource,
    private val statsCalculator: Station24hStatsCalculator = Station24hStatsCalculator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Step 1: Fetches ephemeral tokens (chargeToken, apiToken, rating).
     */
    open suspend fun fetchStationTokens(station: Station): Result<StationAccessTokens> {
        return dataSource.fetchStationTokens(station)
    }

    /**
     * Step 2: Fetches live charging telemetry and forecast ticker.
     */
    open suspend fun fetchLiveCharging(
        stationId: String,
        chargeToken: String,
        isVin: Boolean = true
    ): Result<StationTelemetry> {
        return dataSource.fetchLiveCharging(stationId, chargeToken, isVin)
    }

    /**
     * Step 3: Deprecated stub for 24-hour time-series history points.
     * Retained for test suite compilation safety; always returns success with empty list.
     */
    @Deprecated("Removed Socket.io 24h history in favor of fast HTTP telemetry")
    open suspend fun fetch24hHistory(
        stationId: String,
        apiToken: String
    ): Result<List<Pair<Long, Int>>> {
        return Result.success(emptyList())
    }

    /**
     * Computes 24h usage metrics using deterministic formulas matching EVCS production.
     */
    open fun calculate24hStats(
        points: List<Pair<Long, Int>>,
        totalPorts: Int
    ): Station24hStats {
        return statsCalculator.calculate(points, totalPorts)
    }

    /**
     * Deprecated stub for 24h stats calculation.
     * Retained for test suite compilation safety; always returns success with null.
     */
    @Deprecated("Removed Socket.io 24h history stats in favor of fast HTTP telemetry")
    open suspend fun fetch24hStats(
        stationId: String,
        apiToken: String,
        totalPorts: Int
    ): Result<Station24hStats?> = withContext(ioDispatcher) {
        Result.success(null)
    }

    /**
     * Step 4: Dispatches fire-and-forget sync ping.
     */
    open suspend fun sendTelemetryUpdate(
        stationId: String,
        apiToken: String,
        totalBusy: Int
    ): Result<Unit> {
        return dataSource.sendTelemetryUpdate(stationId, apiToken, totalBusy)
    }

    /**
     * Orchestrates token retrieval, live charging telemetry, and port status derivation
     * into a unified snapshot with zero Socket.io overhead.
     */
    open suspend fun fetchStationTelemetrySnapshot(
        station: Station,
        totalPorts: Int = station.totalPlugs
    ): Result<StationDetailTelemetrySnapshot> = withContext(ioDispatcher) {
        try {
            val tokensResult = fetchStationTokens(station)
            if (tokensResult.isFailure) {
                return@withContext Result.failure(tokensResult.exceptionOrNull()!!)
            }
            val tokens = tokensResult.getOrThrow()

            val chargingResult = fetchLiveCharging(station.id, tokens.chargeToken)
            if (chargingResult.isFailure) {
                return@withContext Result.failure(chargingResult.exceptionOrNull()!!)
            }
            val telemetry = chargingResult.getOrThrow()

            val portStatuses = if (station.powers.isNotEmpty()) {
                StationTelemetryParser.derivePortStatuses(station.powers, telemetry.busyByKw)
            } else {
                StationTelemetryParser.derivePortStatuses(station.connectors, telemetry.busyByKw)
            }

            Result.success(
                StationDetailTelemetrySnapshot(
                    tokens = tokens,
                    telemetry = telemetry,
                    portStatuses = portStatuses,
                    stats24h = null
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
