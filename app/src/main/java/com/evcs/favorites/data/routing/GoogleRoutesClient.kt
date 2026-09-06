package com.evcs.favorites.data.routing

import com.evcs.favorites.data.network.AppOkHttpClientProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Tier 1 HTTP network client for Google Routes API v2 (computeRouteMatrix).
 * Supports real-time traffic condition detection and secure Android package validation.
 */
class GoogleRoutesClient(
    private val okHttpClient: OkHttpClient = AppOkHttpClientProvider.getSharedClient().newBuilder().build(),
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://routes.googleapis.com/"
        const val DEFAULT_TIMEOUT_MS = 3500L
        const val HEADER_API_KEY = "X-Goog-Api-Key"
        const val HEADER_FIELD_MASK = "X-Goog-FieldMask"
        const val HEADER_ANDROID_PACKAGE = "X-Android-Package"
        const val ANDROID_PACKAGE_VALUE = "com.evcs.favorites"
        const val FIELD_MASK_VALUE = "originIndex,destinationIndex,status,condition,distanceMeters,duration,staticDuration"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Computes driving metrics matrix from origin to multiple destinations using Google Routes API v2.
     *
     * @param apiKey Google Cloud API key with Routes API enabled.
     * @param originLat Latitude of origin point.
     * @param originLng Longitude of origin point.
     * @param destinations List of destination points with station IDs.
     * @param timeoutMs Maximum duration to wait for the HTTP response before timing out.
     * @return Result wrapping Map of stationId -> DrivingMetrics on success, or failure exception.
     */
    suspend fun computeRouteMatrix(
        apiKey: String,
        originLat: Double,
        originLng: Double,
        destinations: List<RoutingDestination>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Result<Map<String, DrivingMetrics>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Google API Key cannot be blank"))
        }

        if (destinations.isEmpty()) {
            return@withContext Result.success(emptyMap())
        }

        val requestPayload = GoogleRouteMatrixRequest(
            origins = listOf(
                GoogleRouteWaypointWrapper(
                    waypoint = GoogleRouteWaypoint(
                        location = GoogleRouteLocation(
                            latLng = GoogleLatLng(latitude = originLat, longitude = originLng)
                        )
                    )
                )
            ),
            destinations = destinations.map { dest ->
                GoogleRouteWaypointWrapper(
                    waypoint = GoogleRouteWaypoint(
                        location = GoogleRouteLocation(
                            latLng = GoogleLatLng(latitude = dest.latitude, longitude = dest.longitude)
                        )
                    )
                )
            },
            travelMode = "DRIVE",
            routingPreference = "TRAFFIC_AWARE"
        )

        val jsonBody = json.encodeToString(requestPayload)
        val endpointUrl = "${baseUrl.trimEnd('/')}/distanceMatrix/v2:computeRouteMatrix"

        val request = Request.Builder()
            .url(endpointUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader(HEADER_API_KEY, apiKey.trim())
            .addHeader(HEADER_FIELD_MASK, FIELD_MASK_VALUE)
            .addHeader(HEADER_ANDROID_PACKAGE, ANDROID_PACKAGE_VALUE)
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()

        val effectiveClient = if (timeoutMs > 0L) {
            okHttpClient.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        } else {
            okHttpClient
        }
        val call = effectiveClient.newCall(request)

        try {
            val response = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation {
                    call.cancel()
                }
                call.enqueue(object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response)
                    }
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled) return
                        continuation.resumeWithException(e)
                    }
                })
            }

            response.use { resp ->
                val responseBody = resp.body?.string().orEmpty()

                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        RoutingApiException(
                            statusCode = resp.code,
                            message = "Google Routes API error HTTP ${resp.code}: $responseBody"
                        )
                    )
                }

                val elements = json.decodeFromString<List<GoogleRouteMatrixElement>>(responseBody)
                val resultMap = mutableMapOf<String, DrivingMetrics>()

                for (element in elements) {
                    val destIndex = element.destinationIndex
                    if (destIndex !in destinations.indices) continue

                    // Only parse when route exists and status is OK
                    val statusOk = element.status == null || element.status.code == 0
                    val routeExists = element.condition == "ROUTE_EXISTS" ||
                            (element.condition == null && statusOk)

                    if (!routeExists || !statusOk) continue

                    val durationSec = parseDurationSeconds(element.duration) ?: continue
                    val staticDurationSec = parseDurationSeconds(element.staticDuration)
                    val distanceM = element.distanceMeters ?: 0L

                    val trafficCondition = TrafficCondition.computeCondition(
                        durationSeconds = durationSec,
                        staticDurationSeconds = staticDurationSec,
                        distanceMeters = distanceM
                    )

                    val destination = destinations[destIndex]
                    resultMap[destination.id] = DrivingMetrics(
                        distanceMeters = distanceM,
                        durationSeconds = durationSec,
                        staticDurationSeconds = staticDurationSec,
                        trafficCondition = trafficCondition,
                        engineUsed = RoutingEngineType.GOOGLE
                    )
                }

                Result.success(resultMap)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private fun parseDurationSeconds(durationStr: String?): Long? {
        if (durationStr.isNullOrBlank()) return null
        val clean = durationStr.trim().removeSuffix("s").trim()
        return clean.toDoubleOrNull()?.roundToLong()
    }
}
