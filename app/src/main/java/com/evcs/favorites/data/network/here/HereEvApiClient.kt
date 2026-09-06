package com.evcs.favorites.data.network.here

import com.evcs.favorites.data.logging.AppDebugLogger
import com.evcs.favorites.data.logging.DebugLogLevel
import com.evcs.favorites.data.logging.DebugLogTag
import com.evcs.favorites.data.logging.DebugLoggingInterceptor
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.AppOkHttpClientProvider
import com.evcs.favorites.data.network.here.model.HereEvStationsResponse
import com.evcs.favorites.data.network.here.model.HereModelMapper
import com.evcs.favorites.domain.location.DistanceCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp Client managing Tier 1 zero-login live telemetry requests against HERE Maps EV API:
 * `https://ev-v2.cc.api.here.com/ev/stations.json`
 *
 * Supports:
 * - OAuth 1.0a Bearer token authentication via [HereOAuthManager] (or direct apiKey).
 * - Bounded 5-second network timeout.
 * - Dynamic prox queries (`prox={lat},{lon},{radius}`).
 * - Parsing multi-gun EVSE connector statuses (AVAILABLE vs OCCUPIED).
 * - DC-only port filtering (>= 30kW) and mapping into domain [Station] models.
 */
open class HereEvApiClient(
    private val oauthManager: HereOAuthManager? = null,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val defaultApiKey: String? = VINFAST_EXTRACTED_API_KEY
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://ev-v2.cc.api.here.com/ev"
        const val VINFAST_EXTRACTED_API_KEY = "q26XVMcERlPYt5JghvY04zwUxpS0Pef0xwnB_c7DE-I"

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private fun defaultClient(): OkHttpClient {
            return AppOkHttpClientProvider.getSharedClient().newBuilder()
                .addInterceptor(DebugLoggingInterceptor())
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Executes raw query against `ev/stations.json`.
     *
     * @param latitude Target latitude.
     * @param longitude Target longitude.
     * @param radiusMeters Search radius in meters (default 5000m).
     * @param maxResults Maximum results to return (default 50).
     * @return Result containing parsed [HereEvStationsResponse].
     */
    open suspend fun fetchStationsRaw(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int = 5000,
        maxResults: Int = 50
    ): Result<HereEvStationsResponse> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = baseUrl.trimEnd('/')
            val endpointUrl = "$cleanBase/stations.json".toHttpUrlOrNull()
                ?: return@withContext Result.failure(IOException("Invalid HERE EV base URL: $baseUrl"))

            val urlBuilder = endpointUrl.newBuilder()
                .addQueryParameter("prox", "$latitude,$longitude,$radiusMeters")
                .addQueryParameter("maxresults", maxResults.toString())

            val requestBuilder = Request.Builder()
                .get()
                .addHeader("Accept", "application/json")

            // Attach Bearer token from OAuth Manager if provided; otherwise fallback to apiKey if set
            var tokenAttached = false
            if (oauthManager != null) {
                val tokenResult = oauthManager.getAccessToken()
                if (tokenResult.isSuccess) {
                    requestBuilder.addHeader("Authorization", "Bearer ${tokenResult.getOrThrow()}")
                    tokenAttached = true
                }
            }

            if (!tokenAttached && !defaultApiKey.isNullOrBlank()) {
                urlBuilder.addQueryParameter("apiKey", defaultApiKey)
            }

            requestBuilder.url(urlBuilder.build())

            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HERE EV stations request failed with HTTP ${response.code}: $bodyString")
                    )
                }

                val parsed = json.decodeFromString<HereEvStationsResponse>(bodyString)
                AppDebugLogger.log(
                    tag = DebugLogTag.FOCUS_MODE,
                    level = DebugLogLevel.INFO,
                    message = "HERE EV API trả về ${parsed.allStations.size} trạm quanh ($latitude, $longitude, r=${radiusMeters}m)"
                )
                Result.success(parsed)
            }
        } catch (e: Exception) {
            AppDebugLogger.log(
                tag = DebugLogTag.FOCUS_MODE,
                level = DebugLogLevel.ERROR,
                message = "HERE EV API lỗi phân giải: ${e.message}",
                errorDetails = e.stackTraceToString()
            )
            Result.failure(e)
        }
    }

    /**
     * Fetches nearby stations and maps them directly to [Station] domain models.
     *
     * @param latitude Target latitude.
     * @param longitude Target longitude.
     * @param radiusMeters Search radius in meters (default 5000m).
     * @param dcOnly When true, strictly includes DC fast charging ports (>= 30kW).
     * @param maxResults Maximum results to return.
     * @return Result containing domain [Station] models.
     */
    open suspend fun fetchNearbyStations(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int = 5000,
        dcOnly: Boolean = true,
        maxResults: Int = 50
    ): Result<List<Station>> = withContext(Dispatchers.IO) {
        val rawResult = fetchStationsRaw(latitude, longitude, radiusMeters, maxResults)
        if (rawResult.isFailure) {
            return@withContext Result.failure(rawResult.exceptionOrNull()!!)
        }

        val hereResponse = rawResult.getOrThrow()
        val stations = hereResponse.allStations.map { hereStation ->
            HereModelMapper.mapStationToDomain(hereStation, dcOnly = dcOnly)
        }

        Result.success(stations)
    }

    /**
     * Finds a specific station by ID within the specified search radius.
     * Optimized to filter directly on raw responses and map only the matched station,
     * eliminating garbage-collection churn and payload overhead during polling.
     */
    open suspend fun fetchStationById(
        stationId: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Int = 1000,
        dcOnly: Boolean = true
    ): Result<Station?> = withContext(Dispatchers.IO) {
        val rawResult = fetchStationsRaw(latitude, longitude, radiusMeters, maxResults = 10)
        if (rawResult.isFailure) {
            return@withContext Result.failure(rawResult.exceptionOrNull()!!)
        }

        val stations = rawResult.getOrThrow().allStations
        val cleanTargetId = stationId.trim().lowercase()
        val targetWithoutPrefix = cleanTargetId.removePrefix("c.")

        // 1. Match by ID or cpoId on raw models
        var matchedHereStation = stations.firstOrNull { st ->
            val cleanId = st.id.trim().lowercase()
            val cleanCpo = st.cpoId?.trim()?.lowercase()
            cleanId == cleanTargetId ||
                    cleanId.removePrefix("c.") == targetWithoutPrefix ||
                    cleanCpo == cleanTargetId ||
                    cleanCpo?.removePrefix("c.") == targetWithoutPrefix
        }

        // 2. Proximity fallback: find nearest station within 300 meters
        if (matchedHereStation == null && latitude != 0.0 && longitude != 0.0) {
            val closest = stations.minByOrNull { st ->
                val stLat = st.position?.latitude ?: 0.0
                val stLon = st.position?.longitude ?: 0.0
                DistanceCalculator.calculateDistanceKm(latitude, longitude, stLat, stLon)
            }
            if (closest != null) {
                val stLat = closest.position?.latitude ?: 0.0
                val stLon = closest.position?.longitude ?: 0.0
                val distKm = DistanceCalculator.calculateDistanceKm(latitude, longitude, stLat, stLon)
                if (distKm <= 0.3) {
                    matchedHereStation = closest
                }
            }
        }

        val match = matchedHereStation?.let { HereModelMapper.mapStationToDomain(it, dcOnly = dcOnly) }

        if (match != null) {
            AppDebugLogger.log(
                tag = DebugLogTag.FOCUS_MODE,
                level = DebugLogLevel.SUCCESS,
                message = "Đã khớp trạm: ${match.name} [id=${match.id}] (DC khả dụng: ${match.totalAvailablePlugs}/${match.totalPlugs})"
            )
        } else {
            val sampleIds = stations.take(4).map { "${it.name}(${it.id})" }
            AppDebugLogger.log(
                tag = DebugLogTag.FOCUS_MODE,
                level = DebugLogLevel.WARN,
                message = "Không tìm thấy mã trạm $stationId trong bán kính HERE! Mẫu các trạm xung quanh: $sampleIds"
            )
        }

        Result.success(match)
    }
}
