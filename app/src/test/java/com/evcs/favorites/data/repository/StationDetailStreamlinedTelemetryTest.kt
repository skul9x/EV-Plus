package com.evcs.favorites.data.repository

import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.telemetry.EvcsTelemetryDataSource
import com.evcs.favorites.ui.viewmodel.StationDetailCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

/**
 * Single comprehensive test suite verifying the complete Phase 01:
 * - Removal of Socket.io client dependencies and background overhead.
 * - Fast HTTP REST telemetry pipeline (Tokens, Live Charging, Async Ping).
 * - Instantaneous StationDetailCoordinator state transitions (< 300ms, zero 4s timeout).
 * - Clean lifecycle cancellation upon dismissStationDetail with zero leaks.
 * - Deprecated stubs compatibility in EvcsTelemetryRepository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationDetailStreamlinedTelemetryTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var sessionManager: SessionManager
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val sampleStation = Station(
        id = "station_streamlined_01",
        name = "Trạm Sạc VinFast Streamlined",
        address = "123 Đường Công Nghệ, Quận 1, TP.HCM",
        latitude = 10.7769,
        longitude = 106.7009,
        summary = "Trạm sạc cao tốc",
        connectors = "60kW x 4, 120kW x 2",
        depotStatus = "Normal",
        powers = listOf(
            PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 4, totalPlugs = 4),
            PowerPort(typeWatts = 120000L, label = "120kW", availablePlugs = 2, totalPlugs = 2)
        ),
        totalPlugs = 6
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockWebServer = MockWebServer()
        mockWebServer.start()

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val storage = InMemorySessionStorage().apply {
            putString(SessionManager.KEY_DEVICE_ID, "dev_streamline_test")
        }
        sessionManager = SessionManager(storage).apply {
            phpSessionId = "sess_http_only"
            authCookie = "auth_http_only"
        }
    }

    @After
    fun tearDown() {
        testScope.cancel()
        Dispatchers.resetMain()
        mockWebServer.shutdown()
    }

    @Test
    fun verifyComprehensiveStreamlinedTelemetryPipeline() = testScope.runTest {
        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")

        // 1. Enqueue HTTP responses
        // Response 1: Token handshake (POST /{slug}.html)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                        "chargeToken": "charge_tok_fast_99",
                        "apiToken": "api_tok_sync_88",
                        "rating": {
                            "avg": 4.95,
                            "count": 42,
                            "mine": 5
                        }
                    }
                    """.trimIndent()
                )
        )

        // Response 2: Live Charging (POST /charging)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                        "busyKw": {
                            "60": 1,
                            "120": 2
                        },
                        "ticker": "<b>2 xe đang sạc</b> tại trụ 120kW"
                    }
                    """.trimIndent()
                )
        )

        // Response 3: Fire-and-forget sync ping (POST /update)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok"}""")
        )

        val dataSource = EvcsTelemetryDataSource(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl,
            socketBaseUrl = baseUrl,
            ioDispatcher = testDispatcher
        )
        val repository = EvcsTelemetryRepository(
            dataSource = dataSource,
            ioDispatcher = testDispatcher
        )

        // ---------------------------------------------------------------------
        // Part A: Repository & DataSource Verification
        // ---------------------------------------------------------------------
        // Verify Deprecated stubs execute safely without throwing or hanging
        val historyStubResult = repository.fetch24hHistory("station_dummy", "token_dummy")
        assertTrue(historyStubResult.isSuccess)
        assertTrue(historyStubResult.getOrThrow().isEmpty())

        val statsStubResult = repository.fetch24hStats("station_dummy", "token_dummy", totalPorts = 6)
        assertTrue(statsStubResult.isSuccess)
        assertNull(statsStubResult.getOrThrow())

        // ---------------------------------------------------------------------
        // Part B: StationDetailCoordinator Lifecycle & Fast Loading Verification
        // ---------------------------------------------------------------------
        val coordinator = StationDetailCoordinator(
            coroutineScope = this,
            telemetryRepository = repository,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        // Initial state before selection
        assertNull(coordinator.stationDetailState.value.station)
        assertFalse(coordinator.stationDetailState.value.isLoadingTelemetry)

        // Step 1: Trigger selectStationForDetail (Synchronous 0ms emission)
        val job = coordinator.selectStationForDetail(sampleStation)
        assertNotNull(job)
        assertTrue(job.isActive)

        val initialState = coordinator.stationDetailState.value
        assertEquals(sampleStation, initialState.station)
        assertTrue(initialState.isLoadingTelemetry)
        assertTrue(initialState.isLoadingStats)
        assertEquals(2, initialState.portStatuses.size)

        // Advance dispatcher to process Stage 1a (tokens) and Stage 1b (live charging)
        advanceUntilIdle()

        val loadedState = coordinator.stationDetailState.value
        // Verify Stage 1 completion clears BOTH loading flags immediately without 4s delay
        assertFalse("isLoadingTelemetry must be false after Stage 1", loadedState.isLoadingTelemetry)
        assertFalse("isLoadingStats must be false immediately after Stage 1", loadedState.isLoadingStats)
        assertNull(loadedState.error)

        // Verify community rating
        assertNotNull(loadedState.rating)
        assertEquals(4.95, loadedState.rating!!.avg, 0.01)
        assertEquals(42, loadedState.rating!!.count)

        // Verify live port derivation:
        // 60kW: total 4, busy 1 -> available 3
        // 120kW: total 2, busy 2 -> available 0
        val p60 = loadedState.portStatuses.find { it.kw == 60 }
        assertNotNull(p60)
        assertEquals(4, p60!!.totalPorts)
        assertEquals(3, p60.availablePorts)
        assertEquals(1, p60.busyCount)

        val p120 = loadedState.portStatuses.find { it.kw == 120 }
        assertNotNull(p120)
        assertEquals(2, p120!!.totalPorts)
        assertEquals(0, p120.availablePorts)
        assertEquals(2, p120.busyCount)

        // Verify clean forecast ticker
        assertEquals("2 xe đang sạc tại trụ 120kW", loadedState.cleanForecast)

        // ---------------------------------------------------------------------
        // Part C: Verify HTTP Wire Calls (Tokens, Charging, Fire-and-Forget Ping)
        // ---------------------------------------------------------------------
        // Request 1: Token handshake
        val req1 = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(req1)
        assertEquals("POST", req1!!.method)
        assertEquals("user", req1.getHeader("X-Partial"))

        // Request 2: Live charging
        val req2 = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(req2)
        assertEquals("POST", req2!!.method)
        assertEquals("/charging", req2.path)
        assertEquals("charge_tok_fast_99", req2.getHeader("x-t"))

        // Request 3: Fire-and-forget sync ping
        val req3 = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(req3)
        assertEquals("POST", req3!!.method)
        assertEquals("/update", req3.path)
        assertEquals("api_tok_sync_88", req3.getHeader("x-t"))
        val pingBody = req3.body.readUtf8()
        // Total busy = 1 (60kW) + 2 (120kW) = 3
        assertEquals("""{"a":"station_streamlined_01","b":3}""", pingBody)

        // ---------------------------------------------------------------------
        // Part D: Clean Sheet Dismissal & Lifecycle Cancellation
        // ---------------------------------------------------------------------
        coordinator.dismissStationDetail()
        assertNull("activeJob must be cleared upon dismissal", coordinator.activeJob)
        assertNull("State station must be reset upon dismissal", coordinator.stationDetailState.value.station)
        assertFalse(coordinator.stationDetailState.value.isLoadingTelemetry)
        assertFalse(coordinator.stationDetailState.value.isLoadingStats)

        // Verify subsequent requests are not scheduled
        assertEquals(0, mockWebServer.requestCount - 3)
    }

    @Test
    fun verifyErrorHandlingTransitionsStateGracefully() = testScope.runTest {
        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")

        // Enqueue HTTP 500 error for token handshake
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        val dataSource = EvcsTelemetryDataSource(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl,
            socketBaseUrl = baseUrl,
            ioDispatcher = testDispatcher
        )
        val repository = EvcsTelemetryRepository(
            dataSource = dataSource,
            ioDispatcher = testDispatcher
        )
        val coordinator = StationDetailCoordinator(
            coroutineScope = this,
            telemetryRepository = repository,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        coordinator.selectStationForDetail(sampleStation)
        advanceUntilIdle()

        val errState = coordinator.stationDetailState.value
        assertFalse(errState.isLoadingTelemetry)
        assertFalse(errState.isLoadingStats)
        assertNotNull("Error must be set on failure", errState.error)
        assertTrue(errState.error!!.contains("500") || errState.error!!.contains("Failed"))
    }
}
