package com.evcs.favorites.data.routing

import com.evcs.favorites.data.logging.DebugLoggingInterceptor
import com.evcs.favorites.data.network.AppOkHttpClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Tier 2 HTTP network client for the open-source OSRM Table Service.
 * Provides free road-network driving durations and distances without API keys.
 */
class OsrmRoutingClient(
    private val okHttpClient: OkHttpClient = defaultClient(),
    private val defaultBaseUrl: String = DEFAULT_BASE_URL
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://router.project-osrm.org/"
        const val HEADER_USER_AGENT = "User-Agent"
        const val USER_AGENT_VALUE = "EVCSFavorites-Android/1.0"

        private fun defaultClient(): OkHttpClient {
            return AppOkHttpClientProvider.getSharedClient().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(DebugLoggingInterceptor())
                .build()
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Computes driving metrics matrix from origin to destinations using OSRM Table Service.
     *
     * @param originLat Latitude of origin point.
     * @param originLng Longitude of origin point.
     * @param destinations List of destination points with station IDs.
     * @param customBaseUrl Optional custom OSRM server URL configured by the user.
     * @return Result wrapping Map of stationId -> DrivingMetrics on success, or failure exception.
     */
    suspend fun computeTable(
        originLat: Double,
        originLng: Double,
        destinations: List<RoutingDestination>,
        customBaseUrl: String? = null
    ): Result<Map<String, DrivingMetrics>> = withContext(Dispatchers.IO) {
        if (destinations.isEmpty()) {
            return@withContext Result.success(emptyMap())
        }

        // Group destination stations by coordinate pair (lon, lat)
        val groupedByCoord: Map<Pair<Double, Double>, List<RoutingDestination>> =
            destinations.groupBy { Pair(it.longitude, it.latitude) }
        val uniqueCoords = groupedByCoord.keys.toList()

        // Coordinate format: {lon},{lat} semicolon-delimited
        val coordinatesBuilder = StringBuilder()
        coordinatesBuilder.append("$originLng,$originLat")

        for (coord in uniqueCoords) {
            coordinatesBuilder.append(";${coord.first},${coord.second}")
        }

        val baseUrl = if (!customBaseUrl.isNullOrBlank()) customBaseUrl else defaultBaseUrl
        val endpointUrl = "${baseUrl.trimEnd('/')}/table/v1/driving/${coordinatesBuilder}?sources=0&annotations=duration,distance"

        val request = Request.Builder()
            .url(endpointUrl)
            .addHeader(HEADER_USER_AGENT, USER_AGENT_VALUE)
            .get()
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        RoutingApiException(
                            statusCode = response.code,
                            message = "OSRM API error HTTP ${response.code}: $responseBody"
                        )
                    )
                }

                val tableResponse = json.decodeFromString<OsrmTableResponse>(responseBody)

                if (!tableResponse.code.equals("Ok", ignoreCase = true)) {
                    return@withContext Result.failure(
                        RoutingApiException(
                            statusCode = response.code,
                            message = "OSRM returned status '${tableResponse.code}': ${tableResponse.message.orEmpty()}"
                        )
                    )
                }

                val durationsRow = tableResponse.durations?.firstOrNull()
                val distancesRow = tableResponse.distances?.firstOrNull()

                val resultMap = mutableMapOf<String, DrivingMetrics>()

                // Destination coordinate at index i corresponds to matrix column i + 1 (column 0 is origin -> origin)
                for ((index, coord) in uniqueCoords.withIndex()) {
                    val columnIndex = index + 1

                    val durationSec = durationsRow?.getOrNull(columnIndex)
                    val distanceM = distancesRow?.getOrNull(columnIndex)

                    // Safely ignore unreachable or null cells
                    if (durationSec == null || distanceM == null) {
                        continue
                    }

                    val durationLong = durationSec.roundToLong()
                    val distanceLong = distanceM.roundToLong()

                    val trafficCondition = TrafficCondition.computeCondition(
                        durationSeconds = durationLong,
                        staticDurationSeconds = null,
                        distanceMeters = distanceLong
                    )

                    val metrics = DrivingMetrics(
                        distanceMeters = distanceLong,
                        durationSeconds = durationLong,
                        staticDurationSeconds = null,
                        trafficCondition = trafficCondition,
                        engineUsed = RoutingEngineType.OSRM
                    )

                    for (dest in groupedByCoord[coord].orEmpty()) {
                        resultMap[dest.id] = metrics
                    }
                }

                Result.success(resultMap)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }
}
