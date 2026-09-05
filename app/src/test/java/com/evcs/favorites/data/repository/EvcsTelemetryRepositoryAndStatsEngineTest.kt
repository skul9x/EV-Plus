package com.evcs.favorites.data.repository

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.telemetry.EvcsTelemetryDataSource
import com.evcs.favorites.domain.Station24hStatsCalculator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class EvcsTelemetryRepositoryAndStatsEngineTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var testSessionManager: SessionManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val storage = com.evcs.favorites.data.auth.InMemorySessionStorage()
        storage.putString(SessionManager.KEY_DEVICE_ID, "test_device_id")
        testSessionManager = SessionManager(storage).apply {
            phpSessionId = "test_php_session_id"
            authCookie = "test_auth_cookie"
        }
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // =========================================================================
    // 1. Station24hStatsCalculator Tests
    // =========================================================================

    @Test
    fun statsCalculator_emptyAndZeroPoints_producesDefaultEmptyStats() {
        val emptyStats = Station24hStatsCalculator.calculate(emptyList(), totalPorts = 10)
        assertEquals(0, emptyStats.peakUsage)
        assertEquals(0, emptyStats.avgUsage)
        assertEquals("-", emptyStats.peakHour)
        assertEquals(0, emptyStats.fillRate)

        val allZeroPoints = listOf(
            Pair(1725444000000L, 0),
            Pair(1725447600000L, 0),
            Pair(1725451200000L, 0)
        )
        val zeroStats = Station24hStatsCalculator.calculate(allZeroPoints, totalPorts = 8)
        assertEquals(0, zeroStats.peakUsage)
        assertEquals(0, zeroStats.avgUsage)
        assertEquals("-", zeroStats.peakHour)
        assertEquals(0, zeroStats.fillRate)
    }

    @Test
    fun statsCalculator_lowAverage_appliesCeilingRuleToReturnOne() {
        // Average = (1 + 0 + 0 + 0) / 4 = 0.25 -> must be rounded up to 1
        val lowPoints = listOf(
            Pair(1725444000000L, 1),
            Pair(1725447600000L, 0),
            Pair(1725451200000L, 0),
            Pair(1725454800000L, 0)
        )
        val stats = Station24hStatsCalculator.calculate(lowPoints, totalPorts = 10)
        assertEquals(1, stats.peakUsage)
        assertEquals(1, stats.avgUsage) // Ceiling rule for 0.0 < rawAvg < 1.0
        assertEquals(3, stats.fillRate)  // 0.25 / 10 * 100 = 2.5% -> 3%
    }

    @Test
    fun statsCalculator_normalPoints_calculatesStandardRoundingAndFillRate() {
        // Average = (4 + 3 + 4) / 3 = 3.666... -> rounds to 4
        val points = listOf(
            Pair(1725444000000L, 4),
            Pair(1725447600000L, 3),
            Pair(1725451200000L, 4)
        )
        val stats = Station24hStatsCalculator.calculate(points, totalPorts = 6)
        assertEquals(4, stats.peakUsage)
        assertEquals(4, stats.avgUsage)
        assertEquals(61, stats.fillRate) // 3.6667 / 6 * 100 = 61.11% -> 61%
    }

    @Test
    fun statsCalculator_vietnamTimezonePeakHour_calculatesCorrectRushHour() {
        // 1725444000000L = 2024-09-04 10:00:00 UTC -> 17:00:00 UTC+7 (Hour 17)
        // 1725447600000L = 2024-09-04 11:00:00 UTC -> 18:00:00 UTC+7 (Hour 18)
        val points = listOf(
            Pair(1725444000000L, 8), // 17:00 UTC+7, peak 8
            Pair(1725445800000L, 9), // 17:30 UTC+7, peak 9
            Pair(1725447600000L, 5), // 18:00 UTC+7, peak 5
            Pair(1725451200000L, 3)  // 19:00 UTC+7, peak 3
        )
        val stats = Station24hStatsCalculator.calculate(points, totalPorts = 10)
        assertEquals(9, stats.peakUsage)
        assertEquals("17-18h", stats.peakHour)
    }

    @Test
    fun statsCalculator_peakHourTieBreaker_selectsHourWithHigherAverage() {
        // Hour 14:00 UTC+7 (07:00 UTC = 1725433200000L): counts 8, 4 -> max 8, avg 6.0
        // Hour 15:00 UTC+7 (08:00 UTC = 1725436800000L): counts 8, 8 -> max 8, avg 8.0 (Winner!)
        val points = listOf(
            Pair(1725433200000L, 8),
            Pair(1725435000000L, 4),
            Pair(1725436800000L, 8),
            Pair(1725438600000L, 8)
        )
        val stats = Station24hStatsCalculator.calculate(points, totalPorts = 10)
        assertEquals("15-16h", stats.peakHour)
    }

    @Test
    fun statsCalculator_midnightPeakHour_formats23To0Hour() {
        // 2024-09-04 16:00:00 UTC = 23:00:00 UTC+7 (1725465600000L)
        val points = listOf(
            Pair(1725465600000L, 10),
            Pair(1725444000000L, 2)
        )
        val stats = Station24hStatsCalculator.calculate(points, totalPorts = 10)
        assertEquals("23-0h", stats.peakHour)
    }

    // =========================================================================
    // 2. HTTP Endpoints & Parser Verifications via MockWebServer
    // =========================================================================

    @Test
    fun fetchStationTokens_sendsCorrectHeadersAndParsesAccessTokens() = runTest(testDispatcher) {
        val tokensJson = """
            {
                "chargeToken": "charge_token_test_123",
                "apiToken": "api_token_test_456",
                "rating": {
                    "avg": 4.85,
                    "count": 28,
                    "mine": 5
                },
                "ratingCsrf": "csrf_token_xyz",
                "hasGo": true,
                "hasBiz": false,
                "historyToken7": "h7_abc",
                "historyToken30": "h30_def"
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(tokensJson)
                .addHeader("Set-Cookie", "PHPSESSID=new_session_789; path=/")
        )

        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val dataSource = EvcsTelemetryDataSource(
            sessionManager = testSessionManager,
            client = okHttpClient,
            baseUrl = baseUrl,
            socketBaseUrl = baseUrl,
            ioDispatcher = testDispatcher
        )
        val repository = EvcsTelemetryRepository(dataSource, ioDispatcher = testDispatcher)

        val station = Station(
            id = "vinfast_vincom_center",
            name = "Trạm Sạc VinFast Vincom Center",
            address = "72 Lê Thánh Tôn, Q1, TP.HCM",
            latitude = 10.778,
            longitude = 106.702,
            summary = "",
            connectors = "60kW x 4, 120kW x 2",
            depotStatus = "Normal",
            totalPlugs = 6
        )

        val result = repository.fetchStationTokens(station)
        assertTrue(result.isSuccess)
        val tokens = result.getOrThrow()
        assertEquals("charge_token_test_123", tokens.chargeToken)
        assertEquals("api_token_test_456", tokens.apiToken)
        assertEquals(4.85, tokens.rating?.avg ?: 0.0, 0.01)
        assertEquals(28, tokens.rating?.count)
        assertEquals(5, tokens.rating?.mine)
        assertTrue(tokens.hasGo)
        assertFalse(tokens.hasBiz)

        // Verify request sent to MockWebServer
        val recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertEquals("POST", recordedRequest!!.method)
        assertEquals("user", recordedRequest.getHeader("X-Partial"))
        assertEquals(EvcsApiClient.USER_AGENT_BROWSER, recordedRequest.getHeader("User-Agent"))
        assertEquals(baseUrl, recordedRequest.getHeader("Origin"))
        assertTrue(recordedRequest.getHeader("Cookie")!!.contains("PHPSESSID=test_php_session_id"))
        assertTrue(recordedRequest.getHeader("Cookie")!!.contains("evcs=test_auth_cookie"))
        // Verify Set-Cookie was updated in sessionManager
        assertEquals("new_session_789", testSessionManager.phpSessionId)
    }

    @Test
    fun fetchLiveCharging_sendsAuthHeaderAndParsesTelemetry() = runTest(testDispatcher) {
        val chargingJson = """
            {
                "partial": false,
                "busyKw": {
                    "60": 2,
                    "120": 1
                },
                "ticker": "<div class=\"amd-ticker\">Dự kiến <b>1 xe sạc trụ 120kW</b> sẽ xong trong 5-10 phút...</div>"
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(chargingJson)
        )

        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val dataSource = EvcsTelemetryDataSource(
            sessionManager = testSessionManager,
            client = okHttpClient,
            baseUrl = baseUrl,
            socketBaseUrl = baseUrl,
            ioDispatcher = testDispatcher
        )
        val repository = EvcsTelemetryRepository(dataSource, ioDispatcher = testDispatcher)

        val result = repository.fetchLiveCharging("station_123", "charge_token_abc")
        assertTrue(result.isSuccess)
        val telemetry = result.getOrThrow()
        assertEquals(2, telemetry.busyByKw[60])
        assertEquals(1, telemetry.busyByKw[120])
        assertFalse(telemetry.isLocked)
        assertEquals("Dự kiến 1 xe sạc trụ 120kW sẽ xong trong 5-10 phút...", telemetry.cleanForecast)

        val recorded = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("/charging", recorded!!.path)
        assertEquals("charge_token_abc", recorded.getHeader("x-t"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"id\":\"station_123\""))
        assertTrue(body.contains("\"t\":\"vinfast\""))
    }

    @Test
    fun sendTelemetryUpdate_sendsFireAndForgetPing() = runTest(testDispatcher) {
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val dataSource = EvcsTelemetryDataSource(
            sessionManager = testSessionManager,
            client = okHttpClient,
            baseUrl = baseUrl,
            socketBaseUrl = baseUrl,
            ioDispatcher = testDispatcher
        )
        val repository = EvcsTelemetryRepository(dataSource, ioDispatcher = testDispatcher)

        val result = repository.sendTelemetryUpdate("st_456", "api_token_live", totalBusy = 3)
        assertTrue(result.isSuccess)

        val recorded = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("/update", recorded!!.path)
        assertEquals("api_token_live", recorded.getHeader("x-t"))
        val body = recorded.body.readUtf8()
        assertEquals("""{"a":"st_456","b":3}""", body)
    }

    // =========================================================================
    // 3. Deprecated 24h History & Stats Stubs
    // =========================================================================

    @Test
    fun fetch24hHistory_stub_returnsEmptyList() = runTest(testDispatcher) {
        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val dataSource = EvcsTelemetryDataSource(
            sessionManager = testSessionManager,
            client = okHttpClient,
            baseUrl = baseUrl,
            socketBaseUrl = baseUrl,
            ioDispatcher = testDispatcher
        )
        val repository = EvcsTelemetryRepository(dataSource, ioDispatcher = testDispatcher)

        val result = repository.fetch24hHistory("station_test", "api_token_test")
        assertTrue(result.isSuccess)
        val points = result.getOrThrow()
        assertTrue(points.isEmpty())
    }

    @Test
    fun fetch24hStats_stub_returnsNull() = runTest(testDispatcher) {
        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val dataSource = EvcsTelemetryDataSource(
            sessionManager = testSessionManager,
            client = okHttpClient,
            baseUrl = baseUrl,
            socketBaseUrl = baseUrl,
            ioDispatcher = testDispatcher
        )
        val repository = EvcsTelemetryRepository(dataSource, ioDispatcher = testDispatcher)

        val statsResult = repository.fetch24hStats("st_test", "token_abc", totalPorts = 8)
        assertTrue(statsResult.isSuccess)
        assertNull(statsResult.getOrNull())
    }

    // =========================================================================
    // 4. End-to-End Streamlined HTTP Snapshot Orchestration
    // =========================================================================

    @Test
    fun fetchStationTelemetrySnapshot_orchestratesTokensAndChargingWithoutSocket() = runTest(testDispatcher) {
        // Enqueue 1: Token handshake
        val tokensJson = """
            {
                "chargeToken": "ch_token_full",
                "apiToken": "api_token_full",
                "rating": { "avg": 4.9, "count": 15, "mine": 0 }
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(tokensJson))

        // Enqueue 2: Live charging telemetry
        val chargingJson = """
            {
                "busyKw": { "60": 2, "120": 1 },
                "ticker": "Dự kiến 1 xe sắp hoàn thành..."
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(chargingJson))

        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val dataSource = EvcsTelemetryDataSource(
            sessionManager = testSessionManager,
            client = okHttpClient,
            baseUrl = baseUrl,
            socketBaseUrl = baseUrl,
            ioDispatcher = testDispatcher
        )
        val repository = EvcsTelemetryRepository(dataSource, ioDispatcher = testDispatcher)

        val station = Station(
            id = "station_full_flow",
            name = "Trạm Sạc Test Flow",
            address = "Địa chỉ test",
            latitude = 10.0,
            longitude = 106.0,
            summary = "",
            connectors = "60kW x 4, 120kW x 2",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 4, totalPlugs = 4),
                PowerPort(typeWatts = 120000L, label = "120kW", availablePlugs = 2, totalPlugs = 2)
            ),
            totalPlugs = 6
        )

        val snapshotResult = repository.fetchStationTelemetrySnapshot(station)
        assertTrue(snapshotResult.isSuccess)
        val snapshot = snapshotResult.getOrThrow()

        // 1. Tokens
        assertEquals("ch_token_full", snapshot.tokens.chargeToken)
        assertEquals("api_token_full", snapshot.tokens.apiToken)
        assertEquals(4.9, snapshot.tokens.rating?.avg ?: 0.0, 0.01)

        // 2. Telemetry
        assertEquals(2, snapshot.telemetry.busyByKw[60])
        assertEquals(1, snapshot.telemetry.busyByKw[120])
        assertEquals("Dự kiến 1 xe sắp hoàn thành...", snapshot.telemetry.cleanForecast)

        // 3. Port Statuses (Derived: available = total - busy)
        assertEquals(2, snapshot.portStatuses.size)
        val port120 = snapshot.portStatuses.find { it.kw == 120 }!!
        val port60 = snapshot.portStatuses.find { it.kw == 60 }!!
        assertEquals(1, port120.availablePorts) // 2 total - 1 busy = 1 available
        assertEquals(2, port120.totalPorts)
        assertEquals(2, port60.availablePorts)  // 4 total - 2 busy = 2 available
        assertEquals(4, port60.totalPorts)

        // 4. 24h Stats are null (Zero socket overhead)
        assertNull(snapshot.stats24h)
    }
}
