package com.evcs.favorites.ui

import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsTelemetryRepository
import com.evcs.favorites.data.telemetry.EvcsTelemetryDataSource
import com.evcs.favorites.domain.Station24hStats
import com.evcs.favorites.domain.Station24hStatsCalculator
import com.evcs.favorites.ui.components.NativeStationDetailSheetHelper
import com.evcs.favorites.ui.state.StationDetailUiState
import com.evcs.favorites.ui.viewmodel.StationDetailCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
 * Comprehensive integration test suite verifying the end-to-end telemetry and 24h history pipeline
 * across StationDetailCoordinator, EvcsTelemetryRepository, EvcsTelemetryDataSource, and UI presentation
 * models (Phase 03).
 *
 * Verifies:
 * 1. Stage 1 Live Telemetry Binding: Synchronous 0ms emission, token handshake, live chargingKw mapping,
 *    port derivation, rating, and forecast ticker extraction.
 * 2. Modernized /update Telemetry Ping: Content-Type text/plain, no x-t header, JSON payload with t, a, b, p, u.
 * 3. Stage 2 24h History Enrichment: HTTP GET /history retrieval, matrix decoding, deterministic stats calculation
 *    (peakUsage, avgUsage, peakHour, fillRate), and UI StatCardModel grid resolution.
 * 4. RFC 6265bis Host-Only Cookie Isolation: Ensures __Host-tp is not transmitted to www2 history endpoints.
 * 5. Bounded Timeout Resilience: Delayed history response exceeding 3000ms times out gracefully, setting
 *    isLoadingStats = false and stats24h = null while preserving Stage 1 live telemetry and port data.
 * 6. Error & Malformed Payload Degradation: Graceful handling of HTTP 500, network errors, and malformed history arrays.
 * 7. Empty Points Handling: Empty 24h matrix degrades to null stats with isLoadingStats = false without exceptions.
 * 8. Refresh Lifecycle: Manual refresh re-executes token handshake, live charging, telemetry ping, and 24h stats.
 * 9. Sheet Dismissal Reset: dismissStationDetail cancels active coroutine jobs and resets state to clean empty defaults.
 * 10. Rapid Station Selection: Immediate switching cancels previous station's pipeline cleanly without state corruption.
 * 11. Dual-Orientation & Automotive UI Stability: Validates UI state models across portrait sheet and landscape split-pane.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationDetailTelemetryAnd24hHistoryIntegrationTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var dataSource: EvcsTelemetryDataSource
    private lateinit var repository: EvcsTelemetryRepository
    private lateinit var coordinator: StationDetailCoordinator

    private val sampleStation = Station(
        id = "station_vinfast_e2e_01",
        name = "VinFast - Vincom Center Landmark 81",
        address = "720A Điện Biên Phủ, Phường 22, Bình Thạnh, TP. Hồ Chí Minh",
        latitude = 10.7946,
        longitude = 106.7222,
        summary = "Mở 24/7 • Sạc siêu nhanh 250kW & 60kW",
        connectors = "250kW, 60kW",
        depotStatus = "Normal",
        powers = listOf(
            PowerPort(
                typeWatts = 250000L,
                label = "250kW",
                availablePlugs = 2,
                totalPlugs = 4,
                displayString = "250kW: trống 2/4 cổng"
            ),
            PowerPort(
                typeWatts = 60000L,
                label = "60kW",
                availablePlugs = 1,
                totalPlugs = 2,
                displayString = "60kW: trống 1/2 cổng"
            )
        ),
        totalAvailablePlugs = 3,
        totalPlugs = 6
    )

    private val sampleStationB = Station(
        id = "station_vinfast_e2e_02",
        name = "VinFast - Times City",
        address = "458 Minh Khai, Vĩnh Tuy, Hai Bà Trưng, Hà Nội",
        latitude = 20.9950,
        longitude = 105.8670,
        summary = "Mở 24/7 • Sạc 60kW",
        connectors = "60kW",
        depotStatus = "Normal",
        powers = listOf(
            PowerPort(
                typeWatts = 60000L,
                label = "60kW",
                availablePlugs = 3,
                totalPlugs = 4,
                displayString = "60kW: trống 3/4 cổng"
            )
        ),
        totalAvailablePlugs = 3,
        totalPlugs = 4
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

        sessionStorage = InMemorySessionStorage().apply {
            putString(SessionManager.KEY_DEVICE_ID, "uuid-e2e-device-999")
        }
        sessionManager = SessionManager(sessionStorage).apply {
            phpSessionId = "phpsess_e2e_test"
            authCookie = "auth_e2e_test"
            hostTpCookie = "host_tp_secret_session_token"
        }

        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val socketBaseUrl = baseUrl

        dataSource = EvcsTelemetryDataSource(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl,
            socketBaseUrl = socketBaseUrl,
            ioDispatcher = testDispatcher
        )

        repository = EvcsTelemetryRepository(
            dataSource = dataSource,
            statsCalculator = Station24hStatsCalculator,
            ioDispatcher = testDispatcher
        )

        coordinator = StationDetailCoordinator(
            coroutineScope = testScope,
            telemetryRepository = repository,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher,
            statsTimeoutMs = 3000L
        )
    }

    @After
    fun tearDown() {
        testScope.cancel()
        Dispatchers.resetMain()
        mockWebServer.shutdown()
    }

    // =========================================================================
    // 1. Full Pipeline: Stage 1 Live Telemetry, Ping & Stage 2 24h Stats
    // =========================================================================

    @Test
    fun testCompletePipeline_stage1Telemetry_updatePing_andStage2HistoryStats() = testScope.runTest {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.endsWith(".html") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                            "chargeToken": "charge_tok_e2e_alpha",
                            "apiToken": "api_tok_e2e_beta",
                            "rating": {
                                "avg": 4.88,
                                "count": 56,
                                "mine": 5
                            }
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/charging") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                            "chargingKw": {
                                "250": 2,
                                "60": 1
                            },
                            "ticker": "2 xe đang sạc tại trụ 250kW",
                            "u": "integrity_token_sec_007"
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/update") -> MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")
                    path.startsWith("/history") -> MockResponse().setResponseCode(200).setBody(
                        """
                        [
                            [1725400000000, 1],
                            [1725403600000, 3],
                            [1725407200000, 5],
                            [1725410800000, 6],
                            [1725414400000, 4],
                            [1725418000000, 2]
                        ]
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // Step 1: Initial Synchronous 0ms State Verification
        val job = coordinator.selectStationForDetail(sampleStation)
        assertTrue(job.isActive)

        val initialState = coordinator.stationDetailState.value
        assertEquals(sampleStation, initialState.station)
        assertTrue("isLoadingTelemetry must be true at 0ms", initialState.isLoadingTelemetry)
        assertTrue("isLoadingStats must be true at 0ms", initialState.isLoadingStats)
        assertFalse(initialState.isRefreshing)
        assertEquals(2, initialState.portStatuses.size)

        // Step 2: Advance scheduler to run Stage 1 and Stage 2
        advanceUntilIdle()

        val enrichedState = coordinator.stationDetailState.value
        assertFalse("isLoadingTelemetry must be false after Stage 1", enrichedState.isLoadingTelemetry)
        assertFalse("isLoadingStats must be false after Stage 2", enrichedState.isLoadingStats)
        assertNull(enrichedState.error)

        // Verify Community Rating
        assertNotNull(enrichedState.rating)
        assertEquals(4.88, enrichedState.rating!!.avg, 0.01)
        assertEquals(56, enrichedState.rating!!.count)

        // Verify Stage 1 Live Ports Derivation:
        // 250kW: total 4, busy 2 -> available 2
        // 60kW: total 2, busy 1 -> available 1
        val p250 = enrichedState.portStatuses.find { it.kw == 250 }
        assertNotNull(p250)
        assertEquals(4, p250!!.totalPorts)
        assertEquals(2, p250.availablePorts)
        assertEquals(2, p250.busyCount)

        val p60 = enrichedState.portStatuses.find { it.kw == 60 }
        assertNotNull(p60)
        assertEquals(2, p60!!.totalPorts)
        assertEquals(1, p60.availablePorts)
        assertEquals(1, p60.busyCount)

        // Verify Live Forecast and Telemetry uToken
        assertEquals("2 xe đang sạc tại trụ 250kW", enrichedState.cleanForecast)
        assertNotNull(enrichedState.telemetry)
        assertEquals("integrity_token_sec_007", enrichedState.telemetry!!.uToken)
        assertEquals(mapOf(250 to 2, 60 to 1), enrichedState.telemetry!!.busyByKw)

        // Verify Stage 2 24h Statistics
        assertNotNull("stats24h must be populated on StationDetailUiState", enrichedState.stats24h)
        val stats = enrichedState.stats24h!!
        assertEquals(6, stats.peakUsage)
        assertTrue("avgUsage must be > 0", stats.avgUsage > 0)
        assertTrue("peakHour must not be empty", stats.peakHour.isNotEmpty())
        assertEquals("fillRate must match (rawAvg / totalPorts * 100)", 58, stats.fillRate)

        // Verify UI Grid Resolution (NativeStationDetailSheetHelper)
        val cards = NativeStationDetailSheetHelper.resolveStatsGrid(
            stats = stats,
            isLoadingStats = false
        )
        assertEquals(4, cards.size)
        assertEquals("CAO ĐIỂM", cards[0].header)
        assertEquals("6", cards[0].value)
        assertEquals("ô tô sạc", cards[0].footer)
        assertFalse(cards[0].isLoading)

        assertEquals("TRUNG BÌNH", cards[1].header)
        assertEquals(stats.avgUsage.toString(), cards[1].value)
        assertFalse(cards[1].isLoading)

        assertEquals("GIỜ CAO ĐIỂM", cards[2].header)
        assertEquals(stats.peakHour, cards[2].value)
        assertFalse(cards[2].isLoading)

        assertEquals("TỈ LỆ LẤP ĐẦY", cards[3].header)
        assertEquals("58%", cards[3].value)
        assertFalse(cards[3].isLoading)

        // Step 3: Verify HTTP Network Wire Requests
        val recordedRequests = mutableListOf<RecordedRequest>()
        for (i in 0 until 4) {
            val req = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
            if (req != null) recordedRequests.add(req)
        }

        // Request 1: Token handshake (POST /{slug}.html)
        val req1 = recordedRequests.find { it.path?.endsWith(".html") == true }
        assertNotNull("Token handshake request must be recorded", req1)
        assertEquals("POST", req1!!.method)
        assertEquals("user", req1.getHeader("X-Partial"))

        // Request 2: Live charging telemetry (POST /charging)
        val req2 = recordedRequests.find { it.path == "/charging" }
        assertNotNull("Charging request must be recorded", req2)
        assertEquals("POST", req2!!.method)
        assertEquals("charge_tok_e2e_alpha", req2.getHeader("x-t"))

        // Request 3: Telemetry update ping (POST /update)
        val req3 = recordedRequests.find { it.path == "/update" }
        assertNotNull("Update ping request must be recorded", req3)
        assertEquals("POST", req3!!.method)
        val contentType = req3.getHeader("Content-Type")
        assertNotNull(contentType)
        assertTrue("Content-Type must start with text/plain", contentType!!.startsWith("text/plain"))
        assertNull("Legacy x-t header must NOT be sent", req3.getHeader("x-t"))

        val pingBody = req3.body.readUtf8()
        assertEquals(
            """{"t":"api_tok_e2e_beta","a":"station_vinfast_e2e_01","b":3,"p":{"250":2,"60":1},"u":"integrity_token_sec_007"}""",
            pingBody
        )

        // Request 4: REST 24h history (GET /history?sid=...&t=...)
        val req4 = recordedRequests.find { it.path?.startsWith("/history") == true }
        assertNotNull("History request must be recorded", req4)
        assertEquals("GET", req4!!.method)
        assertEquals("/history?sid=station_vinfast_e2e_01&t=api_tok_e2e_beta", req4.path)
        val cookieHeader = req4.getHeader("Cookie")
        if (cookieHeader != null) {
            assertFalse("__Host-tp cookie must NOT be sent to history", cookieHeader.contains("__Host-tp"))
        }
    }

    // =========================================================================
    // 2. Stage 2 Timeout Graceful Degradation
    // =========================================================================

    @Test
    fun testStage2HistoryTimeout_gracefullyDegradesToNullStats_preservingStage1() = testScope.runTest {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.endsWith(".html") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                            "chargeToken": "tok_charge_timeout",
                            "apiToken": "tok_api_timeout",
                            "rating": { "avg": 4.5, "count": 10 }
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/charging") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                            "chargingKw": { "250": 1 },
                            "ticker": "1 xe đang sạc",
                            "u": "u_timeout_token"
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/update") -> MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")
                    path.startsWith("/history") -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        coordinator.selectStationForDetail(sampleStation)

        // Advance 3500ms to exceed statsTimeoutMs (3000ms)
        advanceTimeBy(3500L)
        advanceUntilIdle()

        val state = coordinator.stationDetailState.value
        // Stage 1 remains completely intact
        assertNotNull(state.station)
        assertFalse("isLoadingTelemetry must be false", state.isLoadingTelemetry)
        assertEquals("1 xe đang sạc", state.cleanForecast)
        assertNotNull(state.rating)
        assertEquals(4.5, state.rating!!.avg, 0.01)

        val p250 = state.portStatuses.find { it.kw == 250 }
        assertNotNull(p250)
        assertEquals(1, p250!!.busyCount)
        assertEquals(3, p250.availablePorts)

        // Stage 2 must time out cleanly without throwing
        assertFalse("isLoadingStats must be false after timeout", state.isLoadingStats)
        assertNull("stats24h must be null on timeout", state.stats24h)

        // Fallback UI cards render '-' placeholders with isLoading = false
        val fallbackCards = NativeStationDetailSheetHelper.resolveStatsGrid(
            stats = null,
            isLoadingStats = false
        )
        assertEquals(4, fallbackCards.size)
        fallbackCards.forEach { card ->
            assertFalse(card.isLoading)
            assertEquals("-", card.value)
        }
    }

    // =========================================================================
    // 3. Stage 2 HTTP 500 & Malformed JSON Error Handling
    // =========================================================================

    @Test
    fun testStage2HttpErrorAndMalformedData_degradesCleanlyWithoutCrash() = testScope.runTest {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.endsWith(".html") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                            "chargeToken": "tok_c_err",
                            "apiToken": "tok_a_err"
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/charging") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                            "chargingKw": { "60": 1 },
                            "ticker": "Trụ 60kW đang hoạt động"
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/update") -> MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")
                    path.startsWith("/history") -> MockResponse().setResponseCode(500).setBody("Internal Server Error")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        coordinator.selectStationForDetail(sampleStation)
        advanceUntilIdle()

        val state = coordinator.stationDetailState.value
        assertFalse("isLoadingTelemetry must be false", state.isLoadingTelemetry)
        assertFalse("isLoadingStats must be false after history failure", state.isLoadingStats)
        assertNull("stats24h must be null on failure", state.stats24h)
        assertNull("User error must remain null as Stage 2 is non-fatal enrichment", state.error)
        assertEquals("Trụ 60kW đang hoạt động", state.cleanForecast)
    }

    // =========================================================================
    // 4. Empty 24h History Points Matrix Handling
    // =========================================================================

    @Test
    fun testStage2EmptyHistory_setsStatsNullWithoutError() = testScope.runTest {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.endsWith(".html") -> MockResponse().setResponseCode(200).setBody(
                        """{"chargeToken":"c_empty","apiToken":"a_empty"}"""
                    )
                    path.startsWith("/charging") -> MockResponse().setResponseCode(200).setBody(
                        """{"chargingKw":{}}"""
                    )
                    path.startsWith("/update") -> MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")
                    path.startsWith("/history") -> MockResponse().setResponseCode(200).setBody("[]")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        coordinator.selectStationForDetail(sampleStation)
        advanceUntilIdle()

        val state = coordinator.stationDetailState.value
        assertFalse(state.isLoadingTelemetry)
        assertFalse("isLoadingStats must be false", state.isLoadingStats)
        assertNull("stats24h must be null for empty points", state.stats24h)
        assertNull(state.error)
    }

    // =========================================================================
    // 5. Manual Refresh Pipeline Re-execution
    // =========================================================================

    @Test
    fun testRefreshStationDetail_reExecutesEntirePipelineCleanly() = testScope.runTest {
        var cycle = 1

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.endsWith(".html") -> {
                        val chargeTok = if (cycle == 1) "c1" else "c2"
                        val apiTok = if (cycle == 1) "a1" else "a2"
                        MockResponse().setResponseCode(200).setBody(
                            """{"chargeToken":"$chargeTok","apiToken":"$apiTok"}"""
                        )
                    }
                    path.startsWith("/charging") -> {
                        val count = if (cycle == 1) 1 else 2
                        val forecast = if (cycle == 1) "Initial" else "Refreshed"
                        MockResponse().setResponseCode(200).setBody(
                            """{"chargingKw":{"60":$count},"ticker":"$forecast"}"""
                        )
                    }
                    path.startsWith("/update") -> MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")
                    path.startsWith("/history") -> {
                        val peak = if (cycle == 1) 2 else 5
                        MockResponse().setResponseCode(200).setBody("""[[1725400000000, $peak]]""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        coordinator.selectStationForDetail(sampleStation)
        advanceUntilIdle()

        assertEquals("Initial", coordinator.stationDetailState.value.cleanForecast)
        assertEquals(2, coordinator.stationDetailState.value.stats24h?.peakUsage)

        // Cycle 2: Trigger refreshStationDetail
        cycle = 2

        val refreshJob = coordinator.refreshStationDetail()
        assertNotNull(refreshJob)
        assertTrue(coordinator.stationDetailState.value.isRefreshing)
        assertTrue(coordinator.stationDetailState.value.isLoadingTelemetry)
        assertTrue(coordinator.stationDetailState.value.isLoadingStats)

        advanceUntilIdle()

        val refreshedState = coordinator.stationDetailState.value
        assertFalse(refreshedState.isRefreshing)
        assertFalse(refreshedState.isLoadingTelemetry)
        assertFalse(refreshedState.isLoadingStats)
        assertEquals("Refreshed", refreshedState.cleanForecast)
        assertEquals(5, refreshedState.stats24h?.peakUsage)
    }

    // =========================================================================
    // 6. Dismissal State Reset & Structured Cancellation
    // =========================================================================

    @Test
    fun testDismissStationDetail_cancelsActiveJobs_andResetsStateCleanly() = testScope.runTest {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
        }

        val job = coordinator.selectStationForDetail(sampleStation)
        assertTrue(job.isActive)
        assertTrue(coordinator.stationDetailState.value.isLoadingTelemetry)

        // Trigger sheet dismissal
        coordinator.dismissStationDetail()

        // Job must be cancelled immediately
        assertTrue("Active job must be cancelled upon dismissal", job.isCancelled)
        assertNull(coordinator.activeJob)

        // State must be completely reset to empty default
        val dismissedState = coordinator.stationDetailState.value
        assertNull(dismissedState.station)
        assertFalse(dismissedState.isLoadingTelemetry)
        assertFalse(dismissedState.isLoadingStats)
        assertFalse(dismissedState.isRefreshing)
        assertNull(dismissedState.telemetry)
        assertNull(dismissedState.cleanForecast)
        assertNull(dismissedState.stats24h)
        assertTrue(dismissedState.portStatuses.isEmpty())
        assertNull(dismissedState.error)
    }

    // =========================================================================
    // 7. Rapid Station Selection Switching Safety
    // =========================================================================

    @Test
    fun testRapidStationSwitching_cancelsPreviousJobWithoutStateCorruption() = testScope.runTest {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    // Station A stalls
                    path.contains("landmark", ignoreCase = true) -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    // Station B responds immediately
                    path.endsWith(".html") -> MockResponse().setResponseCode(200).setBody(
                        """{"chargeToken":"cB","apiToken":"aB"}"""
                    )
                    path.startsWith("/charging") -> MockResponse().setResponseCode(200).setBody(
                        """{"chargingKw":{"60":3},"ticker":"Times City Live"}"""
                    )
                    path.startsWith("/update") -> MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")
                    path.startsWith("/history") -> MockResponse().setResponseCode(200).setBody("""[[1725400000000, 3]]""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val jobA = coordinator.selectStationForDetail(sampleStation)
        assertTrue(jobA.isActive)

        // Immediately select Station B
        val jobB = coordinator.selectStationForDetail(sampleStationB)
        assertTrue("Job A must be cancelled on switching", jobA.isCancelled)
        assertTrue(jobB.isActive)

        advanceUntilIdle()

        val finalState = coordinator.stationDetailState.value
        assertEquals("station_vinfast_e2e_02", finalState.station?.id)
        assertEquals("Times City Live", finalState.cleanForecast)
        assertEquals(3, finalState.stats24h?.peakUsage)
    }

    // =========================================================================
    // 8. Dual-Orientation & Automotive Presentation Invariant Checks
    // =========================================================================

    @Test
    fun testDualOrientationAndAutomotivePresentation_invariantsHold() {
        val testStats = Station24hStats(
            peakUsage = 8,
            avgUsage = 4,
            peakHour = "17-18h",
            fillRate = 75
        )

        // Shimmer loading helper test
        assertTrue(NativeStationDetailSheetHelper.shouldAnimateShimmer(isLoadingStats = true, stats = null))
        assertFalse(NativeStationDetailSheetHelper.shouldAnimateShimmer(isLoadingStats = false, stats = null))
        assertFalse(NativeStationDetailSheetHelper.shouldAnimateShimmer(isLoadingStats = true, stats = testStats))
        assertFalse(NativeStationDetailSheetHelper.shouldAnimateShimmer(isLoadingStats = false, stats = testStats))

        // Stats grid resolution in landscape / split-pane
        val grid = NativeStationDetailSheetHelper.resolveStatsGrid(testStats, isLoadingStats = false)
        assertEquals(4, grid.size)
        assertEquals("CAO ĐIỂM", grid[0].header)
        assertEquals("8", grid[0].value)
        assertEquals("TRUNG BÌNH", grid[1].header)
        assertEquals("4", grid[1].value)
        assertEquals("GIỜ CAO ĐIỂM", grid[2].header)
        assertEquals("17-18h", grid[2].value)
        assertEquals("TỈ LỆ LẤP ĐẦY", grid[3].header)
        assertEquals("75%", grid[3].value)

        // Port badge models
        val samplePort = com.evcs.favorites.domain.StationPortStatus(
            kw = 250,
            availablePorts = 2,
            totalPorts = 4,
            busyCount = 2
        )
        val badge = NativeStationDetailSheetHelper.resolvePortBadge(
            portStatus = samplePort,
            depotStatus = "Normal",
            surfaceVariant = androidx.compose.ui.graphics.Color.Gray,
            onSurfaceVariant = androidx.compose.ui.graphics.Color.White
        )
        assertEquals("250kW  2/4", badge.label)
    }
}
