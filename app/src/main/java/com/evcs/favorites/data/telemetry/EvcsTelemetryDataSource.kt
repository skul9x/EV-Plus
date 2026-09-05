package com.evcs.favorites.data.telemetry

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.AppOkHttpClientProvider
import com.evcs.favorites.domain.StationAccessTokens
import com.evcs.favorites.domain.StationTelemetry
import com.evcs.favorites.domain.StationTelemetryParser
import com.evcs.favorites.util.StationUrlBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * On-demand HTTP telemetry data source for EVCS station details.
 */
open class EvcsTelemetryDataSource(
    private val sessionManager: SessionManager,
    private val client: OkHttpClient = AppOkHttpClientProvider.getSharedClient(),
    private val baseUrl: String = EvcsApiClient.DEFAULT_BASE_URL,
    private val socketBaseUrl: String = DEFAULT_SOCKET_URL,
    @Suppress("UNUSED_PARAMETER") socketFactory: Any? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        const val DEFAULT_SOCKET_URL = "https://www2.evcs.vn/"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val SOCKET_TIMEOUT_MS = 4000L
    }

    /**
     * Step 1: Fetches ephemeral access tokens (`chargeToken`, `apiToken`, `rating`)
     * from `POST /{station-slug}.html` with header `X-Partial: user`.
     */
    open suspend fun fetchStationTokens(station: Station): Result<StationAccessTokens> = withContext(ioDispatcher) {
        try {
            val url = StationUrlBuilder.buildStationDetailUrl(station, baseUrl)
            val requestBuilder = Request.Builder()
                .url(url)
                .post("".toRequestBody())
                .addHeader("X-Partial", "user")
                .addHeader("User-Agent", EvcsApiClient.USER_AGENT_BROWSER)
                .addHeader("Origin", baseUrl)
                .addHeader("Referer", url)

            val cookieHeader = sessionManager.getCookieHeader()
            if (cookieHeader.isNotBlank()) {
                requestBuilder.addHeader("Cookie", cookieHeader)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Failed to fetch station tokens: HTTP ${response.code}")
                    )
                }

                for (cookie in response.headers("Set-Cookie")) {
                    sessionManager.saveFromSetCookieHeader(cookie)
                }

                val body = response.body?.string().orEmpty()
                StationTelemetryParser.parseAccessTokens(body)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Step 2: Fetches real-time charging vehicle telemetry and forecast ticker
     * from `POST /charging` with `x-t: chargeToken`.
     */
    open suspend fun fetchLiveCharging(
        stationId: String,
        chargeToken: String,
        isVin: Boolean = true
    ): Result<StationTelemetry> = withContext(ioDispatcher) {
        try {
            val url = "$baseUrl/charging"
            val payload = """{"id":"$stationId","t":"${if (isVin) "vinfast" else "other"}"}"""
            val requestBuilder = Request.Builder()
                .url(url)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .addHeader("x-t", chargeToken)
                .addHeader("User-Agent", EvcsApiClient.USER_AGENT_BROWSER)
                .addHeader("Origin", baseUrl)
                .addHeader("Referer", "$baseUrl/")

            val cookieHeader = sessionManager.getCookieHeader()
            if (cookieHeader.isNotBlank()) {
                requestBuilder.addHeader("Cookie", cookieHeader)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Failed to fetch live charging: HTTP ${response.code}")
                    )
                }

                for (cookie in response.headers("Set-Cookie")) {
                    sessionManager.saveFromSetCookieHeader(cookie)
                }

                val body = response.body?.string().orEmpty()
                StationTelemetryParser.parseChargingResponse(body)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Step 3: Dispatches fire-and-forget sync ping to `POST https://www2.evcs.vn/update`.
     */
    open suspend fun sendTelemetryUpdate(
        stationId: String,
        apiToken: String,
        totalBusy: Int
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val url = if (socketBaseUrl.endsWith("/")) "${socketBaseUrl}update" else "$socketBaseUrl/update"
            val payload = """{"a":"$stationId","b":$totalBusy}"""
            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .addHeader("x-t", apiToken)
                .addHeader("User-Agent", EvcsApiClient.USER_AGENT_BROWSER)
                .addHeader("Origin", baseUrl)
                .build()

            client.newCall(request).execute().use {
                // Fire and forget
            }
            Unit
        }
    }
}
