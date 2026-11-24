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
import kotlinx.coroutines.NonCancellable
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
        private val TEXT_PLAIN_MEDIA_TYPE = "text/plain".toMediaType()
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
     * Retained 3-parameter overload delegating to 5-parameter overload to maintain 100%
     * binary/source override compatibility with existing test fakes.
     */
    open suspend fun sendTelemetryUpdate(
        stationId: String,
        apiToken: String,
        totalBusy: Int
    ): Result<Unit> = sendTelemetryUpdate(stationId, apiToken, totalBusy, emptyMap(), null)

    /**
     * Step 3: Dispatches fire-and-forget sync ping to `POST https://www2.evcs.vn/update`.
     * Modern protocol: Content-Type text/plain, tokens {t, a, b, p, u} embedded in payload, no x-t header.
     * Wrapped in ioDispatcher + NonCancellable to guarantee sync completion without socket abortion.
     */
    open suspend fun sendTelemetryUpdate(
        stationId: String,
        apiToken: String,
        totalBusy: Int,
        chargingKw: Map<Int, Int> = emptyMap(),
        uToken: String? = null
    ): Result<Unit> = withContext(ioDispatcher + NonCancellable) {
        runCatching {
            val url = if (socketBaseUrl.endsWith("/")) "${socketBaseUrl}update" else "$socketBaseUrl/update"
            val pJson = chargingKw.entries.joinToString(separator = ",", prefix = "{", postfix = "}") {
                "\"${it.key}\":${it.value}"
            }
            val payload = """{"t":"$apiToken","a":"$stationId","b":$totalBusy,"p":$pJson,"u":"${uToken.orEmpty()}"}"""
            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody(TEXT_PLAIN_MEDIA_TYPE))
                .addHeader("User-Agent", EvcsApiClient.USER_AGENT_BROWSER)
                .addHeader("Origin", baseUrl)
                .addHeader("Referer", "$baseUrl/")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} from telemetry update")
                }
            }
            Unit
        }
    }

    /**
     * Dispatches bulk telemetry sync ping for multiple stations to `POST https://www2.evcs.vn/update`.
     */
    open suspend fun sendBatchTelemetryUpdate(
        apiToken: String,
        items: List<TelemetryBatchItem>
    ): Result<Unit> = withContext(ioDispatcher + NonCancellable) {
        runCatching {
            val url = if (socketBaseUrl.endsWith("/")) "${socketBaseUrl}update" else "$socketBaseUrl/update"
            val itemsJson = items.joinToString(separator = ",", prefix = "[", postfix = "]") { item ->
                val pJson = item.p.entries.joinToString(separator = ",", prefix = "{", postfix = "}") {
                    "\"${it.key}\":${it.value}"
                }
                """{"a":"${item.a}","b":${item.b},"p":$pJson,"u":"${item.u}"}"""
            }
            val payload = """{"t":"$apiToken","items":$itemsJson}"""
            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody(TEXT_PLAIN_MEDIA_TYPE))
                .addHeader("User-Agent", EvcsApiClient.USER_AGENT_BROWSER)
                .addHeader("Origin", baseUrl)
                .addHeader("Referer", "$baseUrl/")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} from batch telemetry update")
                }
            }
            Unit
        }
    }

    /**
     * Step 4: Retrieves 24-hour historical usage time-series matrix via REST GET `/history`.
     * RFC 6265bis: __Host-tp cookie is host-only (evcs.vn) and is not required for www2.evcs.vn/history.
     */
    open suspend fun fetch24hHistory(
        stationId: String,
        apiToken: String
    ): Result<List<Pair<Long, Int>>> = withContext(ioDispatcher) {
        try {
            val url = "${socketBaseUrl.trimEnd('/')}/history?sid=$stationId&t=$apiToken"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", EvcsApiClient.USER_AGENT_BROWSER)
                .addHeader("Origin", baseUrl)
                .addHeader("Referer", "$baseUrl/")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Failed to fetch 24h history: HTTP ${response.code}")
                    )
                }
                val body = response.body?.string().orEmpty()
                StationTelemetryParser.parse24hHistory(body)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Batch telemetry payload item for bulk station sync.
 */
data class TelemetryBatchItem(
    val a: String,
    val b: Int,
    val p: Map<String, Int> = emptyMap(),
    val u: String = ""
)
