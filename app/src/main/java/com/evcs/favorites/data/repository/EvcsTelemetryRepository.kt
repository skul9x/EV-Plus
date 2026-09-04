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
 * Composite telemetry snapshot containing tokens, live status, derived ports, and 24h statistics.
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
     * Step 3: Fetches 24-hour time-series history points via Socket.io.
     */
    open suspend fun fetch24hHistory(
        stationId: String,
        apiToken: String
    ): Result<List<Pair<Long, Int>>> {
        return dataSource.fetch24hHistory(stationId, apiToken)
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
     * Fetches 24h history and calculates statistics.
     * Enforces graceful degradation: if Socket.io times out or errors, returns success with null
     * stats to ensure port availability and charging details remain responsive.
     */
    open suspend fun fetch24hStats(
        stationId: String,
        apiToken: String,
        totalPorts: Int
    ): Result<Station24hStats?> = withContext(ioDispatcher) {
        val historyResult = dataSource.fetch24hHistory(stationId, apiToken)
        if (historyResult.isSuccess) {
            val points = historyResult.getOrNull().orEmpty()
            val stats = statsCalculator.calculate(points, totalPorts)
            Result.success(stats)
        } else {
            // Graceful fallback: return null stats without failing or crashing
            Result.success(null)
        }
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
     * Orchestrates token retrieval, live charging telemetry, port status derivation,
     * and 24h statistics calculation into a unified snapshot.
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

            val effectiveTotalPorts = if (totalPorts > 0) totalPorts else portStatuses.sumOf { it.totalPorts }
            val stats24h = fetch24hStats(station.id, tokens.apiToken, effectiveTotalPorts).getOrNull()

            Result.success(
                StationDetailTelemetrySnapshot(
                    tokens = tokens,
                    telemetry = telemetry,
                    portStatuses = portStatuses,
                    stats24h = stats24h
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
