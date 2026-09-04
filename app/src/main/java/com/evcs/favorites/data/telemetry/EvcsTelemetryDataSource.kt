package com.evcs.favorites.data.telemetry

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.AppOkHttpClientProvider
import com.evcs.favorites.domain.StationAccessTokens
import com.evcs.favorites.domain.StationTelemetry
import com.evcs.favorites.domain.StationTelemetryParser
import com.evcs.favorites.util.StationUrlBuilder
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Abstraction for Socket.io client interactions.
 */
interface SocketClient {
    fun on(event: String, listener: (Array<*>) -> Unit): SocketClient
    fun emit(event: String, vararg args: Any?): SocketClient
    fun connect(): SocketClient
    fun disconnect(): SocketClient
    fun off(): SocketClient
}

/**
 * Socket client implementation delegating to [io.socket.client.Socket].
 */
class RealSocketClient(val socket: Socket) : SocketClient {
    override fun on(event: String, listener: (Array<*>) -> Unit): SocketClient {
        socket.on(event) { args -> listener(args) }
        return this
    }

    override fun emit(event: String, vararg args: Any?): SocketClient {
        socket.emit(event, *args)
        return this
    }

    override fun connect(): SocketClient {
        socket.connect()
        return this
    }

    override fun disconnect(): SocketClient {
        socket.disconnect()
        return this
    }

    override fun off(): SocketClient {
        socket.off()
        return this
    }
}

/**
 * Pluggable factory for Socket.io client creation.
 */
fun interface SocketClientFactory {
    fun create(url: String, options: IO.Options): SocketClient
}

/**
 * On-demand HTTP and Socket.io telemetry data source for EVCS station details.
 */
open class EvcsTelemetryDataSource(
    private val sessionManager: SessionManager,
    private val client: OkHttpClient = AppOkHttpClientProvider.getSharedClient(),
    private val baseUrl: String = EvcsApiClient.DEFAULT_BASE_URL,
    private val socketBaseUrl: String = DEFAULT_SOCKET_URL,
    private val socketFactory: SocketClientFactory = SocketClientFactory { url, options ->
        RealSocketClient(IO.socket(url, options))
    },
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
     * Step 3: Fetches 24h historical usage points via Socket.io connection to `https://www2.evcs.vn/`.
     * Automatically enforces a 4000ms timeout and disconnects immediately upon receiving data or error.
     */
    open suspend fun fetch24hHistory(
        stationId: String,
        apiToken: String,
        timeoutMs: Long = SOCKET_TIMEOUT_MS
    ): Result<List<Pair<Long, Int>>> = withContext(ioDispatcher) {
        try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val resumed = AtomicBoolean(false)
                    var socket: SocketClient? = null

                    fun safelyFinish(result: Result<List<Pair<Long, Int>>>) {
                        if (resumed.compareAndSet(false, true)) {
                            try {
                                socket?.disconnect()
                                socket?.off()
                            } catch (_: Exception) {}
                            if (continuation.isActive) {
                                continuation.resume(result)
                            }
                        }
                    }

                    try {
                        val options = IO.Options().apply {
                            auth = mapOf("t" to apiToken)
                            transports = arrayOf("websocket", "polling")
                            callFactory = client
                            webSocketFactory = client
                            timeout = timeoutMs
                        }

                        val s = socketFactory.create(socketBaseUrl, options)
                        socket = s

                        s.on(Socket.EVENT_CONNECT) {
                            try {
                                s.emit("subscribe", stationId)
                                val historyPayload = JSONObject().apply {
                                    put("stationId", stationId)
                                    put("hours", 24)
                                    put("token", "")
                                    put("detail", false)
                                }
                                s.emit("history", historyPayload)
                            } catch (e: Exception) {
                                safelyFinish(Result.failure(e))
                            }
                        }

                        s.on("history_data") { args ->
                            try {
                                val data = if (args.isNotEmpty()) args[0] else null
                                val points = parseHistoryData(data)
                                safelyFinish(Result.success(points))
                            } catch (e: Exception) {
                                safelyFinish(Result.failure(e))
                            }
                        }

                        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
                            val errMessage = args.getOrNull(0)?.toString() ?: "Socket connection error"
                            safelyFinish(Result.failure(IOException(errMessage)))
                        }

                        s.on(Socket.EVENT_DISCONNECT) {
                            safelyFinish(Result.failure(IOException("Socket disconnected before receiving data")))
                        }

                        continuation.invokeOnCancellation {
                            safelyFinish(Result.failure(CancellationException("Socket operation cancelled")))
                        }

                        s.connect()
                    } catch (e: Exception) {
                        safelyFinish(Result.failure(e))
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(IOException("Socket.io history request timed out after ${timeoutMs}ms", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Step 4: Dispatches fire-and-forget sync ping to `POST https://www2.evcs.vn/update`.
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

    /**
     * Parses diverse payload representations of time-series data returned by Socket.io.
     */
    fun parseHistoryData(raw: Any?): List<Pair<Long, Int>> {
        if (raw == null) return emptyList()
        val result = mutableListOf<Pair<Long, Int>>()

        when (raw) {
            is JSONArray -> {
                for (i in 0 until raw.length()) {
                    val item = raw.opt(i)
                    parseHistoryItem(item)?.let { result.add(it) }
                }
            }
            is List<*> -> {
                for (item in raw) {
                    parseHistoryItem(item)?.let { result.add(it) }
                }
            }
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.startsWith("[")) {
                    val jsonArr = JSONArray(trimmed)
                    for (i in 0 until jsonArr.length()) {
                        val item = jsonArr.opt(i)
                        parseHistoryItem(item)?.let { result.add(it) }
                    }
                }
            }
        }
        return result
    }

    private fun parseHistoryItem(item: Any?): Pair<Long, Int>? {
        return when (item) {
            is JSONArray -> {
                if (item.length() >= 2) {
                    val ts = item.optLong(0)
                    val count = item.optInt(1)
                    Pair(ts, count)
                } else null
            }
            is List<*> -> {
                if (item.size >= 2) {
                    val ts = (item[0] as? Number)?.toLong() ?: item[0]?.toString()?.toLongOrNull() ?: 0L
                    val count = (item[1] as? Number)?.toInt() ?: item[1]?.toString()?.toIntOrNull() ?: 0
                    Pair(ts, count)
                } else null
            }
            is JSONObject -> {
                val ts = item.optLong("time", item.optLong("timestamp", item.optLong("t", 0L)))
                val count = item.optInt("count", item.optInt("busy", item.optInt("c", item.optInt("val", 0))))
                Pair(ts, count)
            }
            is Map<*, *> -> {
                val ts = (item["time"] ?: item["timestamp"] ?: item["t"])?.let {
                    (it as? Number)?.toLong() ?: it.toString().toLongOrNull()
                } ?: 0L
                val count = (item["count"] ?: item["busy"] ?: item["c"] ?: item["val"])?.let {
                    (it as? Number)?.toInt() ?: it.toString().toIntOrNull()
                } ?: 0
                Pair(ts, count)
            }
            else -> null
        }
    }
}
