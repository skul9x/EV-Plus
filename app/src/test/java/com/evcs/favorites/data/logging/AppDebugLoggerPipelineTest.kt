package com.evcs.favorites.data.logging

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.auth.SessionStorage
import com.evcs.favorites.data.cache.ForecastCache
import com.evcs.favorites.data.model.ChargingForecastResponse
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class AppDebugLoggerPipelineTest {

    private lateinit var mockWebServer: MockWebServer

    private class InMemorySessionStorage : SessionStorage {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String?) {
            if (value != null) map[key] = value else map.remove(key)
        }
        override fun remove(key: String) { map.remove(key) }
        override fun clear() { map.clear() }
    }

    @Before
    fun setUp() {
        AppDebugLogger.clear()
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        AppDebugLogger.clear()
    }

    @Test
    fun testCircularBufferFifoEvictionAndClear() {
        AppDebugLogger.clear()
        assertEquals(0, AppDebugLogger.getLogs().size)
        assertEquals(0, AppDebugLogger.logsFlow.value.size)

        // Insert 510 entries into the 500-capacity buffer
        for (i in 1..510) {
            AppDebugLogger.log(
                tag = DebugLogTag.NETWORK,
                level = DebugLogLevel.INFO,
                message = "Test entry $i"
            )
        }

        val logs = AppDebugLogger.getLogs()
        assertEquals(500, logs.size)
        assertEquals(500, AppDebugLogger.logsFlow.value.size)

        // The oldest 10 entries (1..10) should have been evicted
        assertEquals("Test entry 11", logs.first().message)
        assertEquals("Test entry 510", logs.last().message)

        // Clear buffer
        AppDebugLogger.clear()
        assertEquals(0, AppDebugLogger.getLogs().size)
        assertEquals(0, AppDebugLogger.logsFlow.value.size)
    }

    @Test
    fun testOkHttpInterceptorRecordsRequestAndPeeksBodySafely() {
        val client = OkHttpClient.Builder()
            .addInterceptor(DebugLoggingInterceptor())
            .build()

        val responseBodyJson = """{"status":"ok","count":42}"""
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBodyJson)
        )

        val targetUrl = mockWebServer.url("/search?t=test").toString()
        val request = Request.Builder().url(targetUrl).get().build()

        val response = client.newCall(request).execute()
        // Stream must be unconsumed and readable
        val consumedBody = response.body?.string().orEmpty()
        assertEquals(responseBodyJson, consumedBody)

        val logs = AppDebugLogger.getLogs()
        assertEquals(1, logs.size)
        val log = logs.first()
        assertEquals(DebugLogTag.SEARCH, log.tag)
        assertEquals(DebugLogLevel.INFO, log.level)
        assertEquals(200, log.statusCode)
        assertEquals("GET", log.method)
        assertEquals(targetUrl, log.endpointUrl)
        assertTrue(log.latencyMs != null && log.latencyMs!! >= 0)
        assertEquals(responseBodyJson, log.responseSnippet)
    }

    @Test
    fun testChargingForecastHandshakeStep1AndStep2DiagnosticLogging() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor(DebugLoggingInterceptor())
            .build()

        val baseUrl = mockWebServer.url("").toString().removeSuffix("/")
        val sessionManager = SessionManager(InMemorySessionStorage())
        val apiClient = EvcsApiClient(
            sessionManager = sessionManager,
            client = client,
            baseUrl = baseUrl
        )

        // Mock Step 1: user partial returning chargeToken
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"chargeToken":"test_token_abc_123"}""")
        )

        // Mock Step 2: /charging endpoint returning ticker response
        val tickerHtml = "Dự kiến 1 xe sạc trụ 60kW sẽ xong trong 7 phút nữa"
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ticker":"$tickerHtml","busyKw":{"60":1},"partial":false}""")
        )

        val forecastResult = apiClient.fetchChargingForecast("VinFast Station", "station_999", isVinFast = true)
        assertTrue("Forecast handshake should succeed", forecastResult.isSuccess)
        assertEquals(tickerHtml, forecastResult.getOrNull()?.ticker)

        val logs = AppDebugLogger.getLogs()
        // Interceptor + Step 1 + Step 2 logs are all present
        val forecastLogs = logs.filter { it.tag == DebugLogTag.FORECAST }
        assertTrue("Should have multiple FORECAST logs", forecastLogs.size >= 2)

        val step1Log = forecastLogs.find { it.message.contains("Step 1: Lấy chargeToken thành công") }
        assertNotNull("Step 1 success log should be recorded", step1Log)
        assertTrue("Step 1 snippet must contain token", step1Log?.responseSnippet?.contains("test_token_abc_123") == true)

        val step2Log = forecastLogs.find { it.message.contains("Step 2: Nhận dữ liệu ticker thành công") }
        assertNotNull("Step 2 success log should be recorded", step2Log)
        assertTrue("Step 2 snippet must contain ticker HTML", step2Log?.responseSnippet?.contains("60kW") == true)
    }

    @Test
    fun testStationForecastParsingSuccessAndDiagnosticLogging() = runBlocking {
        val testStation = Station(
            id = "loc_42",
            name = "VinFast Long Bien",
            address = "Hanoi",
            latitude = 21.03,
            longitude = 105.85,
            summary = "Summary",
            connectors = "CCS2",
            depotStatus = "Normal"
        )

        val fakeClient = object : EvcsApiClient(SessionManager(InMemorySessionStorage())) {
            override suspend fun fetchChargingForecast(
                stationName: String,
                locationId: String,
                isVinFast: Boolean
            ): Result<ChargingForecastResponse> {
                return Result.success(
                    ChargingForecastResponse(
                        ticker = "Dự kiến 1 xe sạc trụ 60kW sẽ xong trong 7 phút nữa",
                        busyKw = mapOf("60" to 1)
                    )
                )
            }
        }

        val repo = EvcsRepository(
            apiClient = fakeClient,
            forecastCache = ForecastCache(),
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = repo.fetchStationForecast(testStation)
        assertTrue(result.isSuccess)
        assertNotNull(result.getOrNull())

        val successLogs = AppDebugLogger.getLogs().filter {
            it.tag == DebugLogTag.FORECAST && it.level == DebugLogLevel.SUCCESS
        }
        assertEquals(1, successLogs.size)
        val successLog = successLogs.first()
        assertEquals("⏱️ Dự kiến 1 xe sạc trụ 60kW sẽ xong trong 7 phút nữa", successLog.parsedForecastSummary)
        assertTrue(successLog.message.contains("Dự kiến 1 xe sạc trụ 60kW sẽ xong trong 7 phút nữa"))
    }

    @Test
    fun testStationForecastFailureAndEmptyTickerLogging() = runBlocking {
        val testStation = Station(
            id = "loc_43",
            name = "VinFast Cau Giay",
            address = "Hanoi",
            latitude = 21.03,
            longitude = 105.85,
            summary = "Summary",
            connectors = "CCS2",
            depotStatus = "Normal"
        )

        // Case A: Empty ticker
        val emptyClient = object : EvcsApiClient(SessionManager(InMemorySessionStorage())) {
            override suspend fun fetchChargingForecast(stationName: String, locationId: String, isVinFast: Boolean): Result<ChargingForecastResponse> {
                return Result.success(ChargingForecastResponse(ticker = "   "))
            }
        }

        val repoEmpty = EvcsRepository(
            apiClient = emptyClient,
            forecastCache = ForecastCache(),
            ioDispatcher = Dispatchers.Unconfined
        )

        repoEmpty.fetchStationForecast(testStation)
        val emptyLogs = AppDebugLogger.getLogs().filter {
            it.tag == DebugLogTag.FORECAST && it.level == DebugLogLevel.INFO &&
                it.message.contains("Không có dữ liệu ticker sạc")
        }
        assertEquals(1, emptyLogs.size)

        // Case B: Malformed / unparseable ticker
        AppDebugLogger.clear()
        val malformedClient = object : EvcsApiClient(SessionManager(InMemorySessionStorage())) {
            override suspend fun fetchChargingForecast(stationName: String, locationId: String, isVinFast: Boolean): Result<ChargingForecastResponse> {
                return Result.success(ChargingForecastResponse(ticker = "Trụ đang cập nhật phần mềm không rõ số phút"))
            }
        }

        val repoMalformed = EvcsRepository(
            apiClient = malformedClient,
            forecastCache = ForecastCache(),
            ioDispatcher = Dispatchers.Unconfined
        )

        repoMalformed.fetchStationForecast(testStation, forceRefresh = true)
        val warnLogs = AppDebugLogger.getLogs().filter {
            it.tag == DebugLogTag.FORECAST && it.level == DebugLogLevel.WARN
        }
        assertTrue("Should log WARN for unparseable non-empty ticker", warnLogs.isNotEmpty())
        assertEquals("Trụ đang cập nhật phần mềm không rõ số phút", warnLogs.first().responseSnippet)

        // Case C: Network error
        AppDebugLogger.clear()
        val errorClient = object : EvcsApiClient(SessionManager(InMemorySessionStorage())) {
            override suspend fun fetchChargingForecast(stationName: String, locationId: String, isVinFast: Boolean): Result<ChargingForecastResponse> {
                return Result.failure(IOException("Connection timed out: 504 Gateway"))
            }
        }

        val repoError = EvcsRepository(
            apiClient = errorClient,
            forecastCache = ForecastCache(),
            ioDispatcher = Dispatchers.Unconfined
        )

        repoError.fetchStationForecast(testStation, forceRefresh = true)
        val errorLogs = AppDebugLogger.getLogs().filter {
            it.tag == DebugLogTag.FORECAST && it.level == DebugLogLevel.ERROR
        }
        assertTrue("Should log ERROR on network failure", errorLogs.isNotEmpty())
        assertTrue(errorLogs.first().errorDetails?.contains("504 Gateway") == true)
    }

    @Test
    fun testFormattedLogTextExport() {
        AppDebugLogger.clear()
        assertEquals("Chưa có nhật ký hoạt động mạng.", AppDebugLogger.getFormattedLogText())

        AppDebugLogger.log(
            tag = DebugLogTag.ROUTING,
            level = DebugLogLevel.INFO,
            message = "OSRM Matrix calculation completed",
            endpointUrl = "https://router.project-osrm.org/table/v1/driving/...",
            method = "GET",
            statusCode = 200,
            latencyMs = 120,
            requestSnippet = "sources=0&annotations=duration,distance",
            responseSnippet = """{"code":"Ok"}"""
        )

        AppDebugLogger.log(
            tag = DebugLogTag.FORECAST,
            level = DebugLogLevel.SUCCESS,
            message = "⏱️ Dự kiến 1 xe sạc trụ 60kW sẽ xong trong 7 phút nữa",
            parsedForecastSummary = "⏱️ Dự kiến 1 xe sạc trụ 60kW sẽ xong trong 7 phút nữa"
        )

        val formatted = AppDebugLogger.getFormattedLogText()
        assertTrue(formatted.contains("=== EVCS DEBUG LOGS ==="))
        assertTrue(formatted.contains("Tổng số mục: 2"))
        assertTrue(formatted.contains("[INFO] [ROUTING]"))
        assertTrue(formatted.contains("https://router.project-osrm.org/table/v1/driving/..."))
        assertTrue(formatted.contains("[SUCCESS] [FORECAST]"))
        assertTrue(formatted.contains("⏱️ Dự kiến 1 xe sạc trụ 60kW sẽ xong trong 7 phút nữa"))
        assertTrue(formatted.contains("----------------------------------------"))
    }
}
