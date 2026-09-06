package com.evcs.favorites.ui.viewmodel

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.repository.EvcsTelemetryRepository
import com.evcs.favorites.data.telemetry.EvcsTelemetryDataSource
import com.evcs.favorites.domain.Station24hStatsCalculator
import com.evcs.favorites.domain.StationAccessTokens
import com.evcs.favorites.domain.StationRating
import com.evcs.favorites.domain.StationTelemetry
import com.evcs.favorites.domain.location.LocationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 03:
 * ViewModel On-Demand Telemetry Pipeline (`FavoritesViewModel` & `NearbyViewModel`).
 *
 * Verifies:
 * 1. `selectStationForDetail` emits initial state synchronously with the selected station and initial ports (zero delay).
 * 2. Live port availability, clean forecast, and rating are enriched during Stage 1.
 * 3. 24h usage statistics are enriched during Stage 2 while gracefully handling timeouts.
 * 4. `dismissStationDetail` cancels active background jobs and clears selection cleanly.
 * 5. Manual refresh updates `isRefreshing` state correctly and repopulates stats.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationDetailViewModelPipelineTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var storage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var authEngine: AuthEngine
    private lateinit var fakeEvcsRepo: FakeEvcsRepo
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var fakeTelemetryRepo: FakeEvcsTelemetryRepository

    class FakeEvcsRepo(
        storage: InMemorySessionStorage
    ) : EvcsRepository(
        apiClient = EvcsApiClient(SessionManager(storage)),
        cacheStorage = storage
    )

    class FakeLocationService : LocationService() {
        override fun hasLocationPermission(): Boolean = true
        override suspend fun getFreshLocation(): android.location.Location? = null
    }

    class FakeEvcsTelemetryRepository(
        sessionManager: SessionManager
    ) : EvcsTelemetryRepository(
        dataSource = EvcsTelemetryDataSource(sessionManager),
        statsCalculator = Station24hStatsCalculator,
        ioDispatcher = Dispatchers.Unconfined
    ) {
        var tokensResult: Result<StationAccessTokens> = Result.success(
            StationAccessTokens(
                chargeToken = "charge_tok_xyz",
                apiToken = "api_tok_abc",
                rating = StationRating(avg = 4.8, count = 25, mine = 5)
            )
        )

        var liveChargingResult: Result<StationTelemetry> = Result.success(
            StationTelemetry(
                busyByKw = mapOf(60 to 3, 120 to 1),
                rawTicker = "<b>Có 1 cổng 120kW sắp trống</b>",
                cleanForecast = "Có 1 cổng 120kW sắp trống",
                isLocked = false
            )
        )

        var historyResult: Result<List<Pair<Long, Int>>> = Result.success(
            listOf(
                1725400000000L to 2,
                1725403600000L to 5,
                1725407200000L to 4,
                1725410800000L to 3
            )
        )

        var historyHang: Boolean = false
        var historyDelayMs: Long = 0L

        var pingDispatched: Boolean = false
        var lastPingStationId: String? = null
        var lastPingApiToken: String? = null
        var lastPingBusy: Int? = null

        override suspend fun fetchStationTokens(station: Station): Result<StationAccessTokens> {
            return tokensResult
        }

        override suspend fun fetchLiveCharging(
            stationId: String,
            chargeToken: String,
            isVin: Boolean
        ): Result<StationTelemetry> {
            return liveChargingResult
        }

        override suspend fun fetch24hHistory(
            stationId: String,
            apiToken: String
        ): Result<List<Pair<Long, Int>>> {
            if (historyHang) {
                delay(10_000L)
            } else if (historyDelayMs > 0L) {
                delay(historyDelayMs)
            }
            return historyResult
        }

        override suspend fun sendTelemetryUpdate(
            stationId: String,
            apiToken: String,
            totalBusy: Int
        ): Result<Unit> {
            pingDispatched = true
            lastPingStationId = stationId
            lastPingApiToken = apiToken
            lastPingBusy = totalBusy
            return Result.success(Unit)
        }
    }

    private fun createTestStation(
        id: String = "C.HNI0001",
        name: String = "VinFast Smart City",
        totalPlugs: Int = 6,
        powerPorts: List<PowerPort> = listOf(
            PowerPort(typeWatts = 120000L, label = "120kW", availablePlugs = 2, totalPlugs = 2),
            PowerPort(typeWatts = 60000L, label = "60kW", availablePlugs = 2, totalPlugs = 4)
        ),
        connectors: String = "120kW x 2, 60kW x 4"
    ): Station {
        return Station(
            id = id,
            name = name,
            address = "Tây Mỗ, Nam Từ Liêm, Hà Nội",
            latitude = 20.998,
            longitude = 105.743,
            summary = "24/7 • Sạc siêu nhanh",
            connectors = connectors,
            depotStatus = "OPEN",
            powers = powerPorts,
            totalAvailablePlugs = 4,
            totalPlugs = totalPlugs
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        storage = InMemorySessionStorage()
        sessionManager = SessionManager(storage)
        authEngine = AuthEngine(sessionManager)
        fakeEvcsRepo = FakeEvcsRepo(storage)
        fakeLocationService = FakeLocationService()
        fakeTelemetryRepo = FakeEvcsTelemetryRepository(sessionManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createFavoritesViewModel(): FavoritesViewModel {
        return FavoritesViewModel(
            repository = fakeEvcsRepo,
            authEngine = authEngine,
            locationService = fakeLocationService,
            dispatcher = testDispatcher,
            telemetryRepository = fakeTelemetryRepo,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )
    }

    private fun createNearbyViewModel(): NearbyViewModel {
        return NearbyViewModel(
            repository = fakeEvcsRepo,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            dispatcher = testDispatcher,
            telemetryRepository = fakeTelemetryRepo,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )
    }

    // =========================================================================
    // Verification 1: Instant opening (0ms) synchronously with initial ports
    // =========================================================================

    @Test
    fun testSelectStationForDetail_emitsInitialStateSynchronously_withZeroDelay() {
        val viewModel = createFavoritesViewModel()
        val station = createTestStation()

        // Before selection, state is empty
        val initialState = viewModel.stationDetailState.value
        assertNull(initialState.station)
        assertFalse(initialState.isLoadingTelemetry)
        assertFalse(initialState.isLoadingStats)
        assertTrue(initialState.portStatuses.isEmpty())

        // Call selectStationForDetail synchronously - without advancing coroutine dispatcher
        viewModel.selectStationForDetail(station)

        // Immediately inspect StateFlow value (0ms delay)
        val synchronousState = viewModel.stationDetailState.value
        assertEquals("Station must be set immediately at 0ms", station, synchronousState.station)
        assertTrue("isLoadingTelemetry must be true instantly", synchronousState.isLoadingTelemetry)
        assertTrue("isLoadingStats must be true instantly", synchronousState.isLoadingStats)
        assertFalse("isRefreshing must be false on initial select", synchronousState.isRefreshing)
        assertNull("Rating not yet enriched at 0ms", synchronousState.rating)
        assertNull("Telemetry not yet enriched at 0ms", synchronousState.telemetry)
        assertNull("Stats not yet enriched at 0ms", synchronousState.stats24h)

        // Verify initial port statuses were derived from static powers
        assertEquals(2, synchronousState.portStatuses.size)
        val port120 = synchronousState.portStatuses.find { it.kw == 120 }
        assertNotNull(port120)
        assertEquals(120, port120!!.kw)
        assertEquals(2, port120.totalPorts)
        assertEquals(2, port120.availablePorts)
        assertEquals(0, port120.busyCount)

        val port60 = synchronousState.portStatuses.find { it.kw == 60 }
        assertNotNull(port60)
        assertEquals(60, port60!!.kw)
        assertEquals(4, port60.totalPorts)
        assertEquals(2, port60.availablePorts)
        assertEquals(2, port60.busyCount)

        // Also verify selectedStationForDetail compatibility StateFlow
        assertEquals(station, viewModel.selectedStationForDetail.value)
    }

    @Test
    fun testNearbyViewModel_selectStationForDetail_emitsInitialStateSynchronously() {
        val viewModel = createNearbyViewModel()
        val station = createTestStation(id = "C.HNI0002", name = "VinFast Royal City")

        viewModel.selectStationForDetail(station)

        val state = viewModel.stationDetailState.value
        assertEquals("C.HNI0002", state.station?.id)
        assertTrue(state.isLoadingTelemetry)
        assertTrue(state.isLoadingStats)
        assertEquals(2, state.portStatuses.size)
    }

    // =========================================================================
    // Verification 2: Stage 1 enrichment (live ports, forecast, rating, ping)
    // =========================================================================

    @Test
    fun testStage1_enrichesRatingLivePortsAndCleanForecast_andDispatchesPing() = runTest(testDispatcher) {
        val viewModel = createFavoritesViewModel()
        val station = createTestStation()

        viewModel.selectStationForDetail(station)

        // Advance dispatcher to allow Stage 1 and Stage 2 to execute
        advanceUntilIdle()

        val state = viewModel.stationDetailState.value
        assertFalse("isLoadingTelemetry must be false after Stage 1", state.isLoadingTelemetry)

        // Verify rating enriched from handshake
        assertNotNull("Rating must be enriched", state.rating)
        assertEquals(4.8, state.rating!!.avg, 0.001)
        assertEquals(25, state.rating!!.count)
        assertEquals(5, state.rating!!.mine)

        // Verify clean forecast sanitized
        assertEquals("Có 1 cổng 120kW sắp trống", state.cleanForecast)
        assertEquals(false, state.telemetry?.isLocked)

        // Verify live port availability derived from busyByKw (120kW has 1 busy, 60kW has 3 busy)
        val port120 = state.portStatuses.find { it.kw == 120 }
        assertNotNull(port120)
        assertEquals(2, port120!!.totalPorts)
        assertEquals(1, port120.busyCount)
        assertEquals(1, port120.availablePorts) // 2 - 1 = 1

        val port60 = state.portStatuses.find { it.kw == 60 }
        assertNotNull(port60)
        assertEquals(4, port60!!.totalPorts)
        assertEquals(3, port60.busyCount)
        assertEquals(1, port60.availablePorts) // 4 - 3 = 1

        // Verify Stage 3 telemetry sync ping was dispatched fire-and-forget
        assertTrue("Telemetry sync ping must be dispatched", fakeTelemetryRepo.pingDispatched)
        assertEquals(station.id, fakeTelemetryRepo.lastPingStationId)
        assertEquals("api_tok_abc", fakeTelemetryRepo.lastPingApiToken)
        assertEquals(4, fakeTelemetryRepo.lastPingBusy) // 1 + 3 = 4 total busy
    }

    // =========================================================================
    // Verification 3: Stage 2 24h usage stats calculation & timeout resilience
    // =========================================================================

    @Test
    fun testStage2_enriches24hUsageStats_andHandlesTimeoutGracefully() = runTest(testDispatcher) {
        val viewModel = createFavoritesViewModel()
        val station = createTestStation()

        // 3a: Success case
        viewModel.selectStationForDetail(station)
        advanceUntilIdle()

        val successState = viewModel.stationDetailState.value
        assertFalse("isLoadingStats must be false after Stage 2", successState.isLoadingStats)
        assertNotNull("stats24h must be computed", successState.stats24h)
        val stats = successState.stats24h!!
        assertEquals(5, stats.peakUsage)
        assertTrue("avgUsage must be > 0", stats.avgUsage > 0)
        assertTrue("fillRate must be > 0", stats.fillRate > 0)

        // 3b: Timeout / network hang case
        fakeTelemetryRepo.historyHang = true
        val station2 = createTestStation(id = "C.HNI0003", name = "VinFast Times City")

        viewModel.selectStationForDetail(station2)

        // Advance by 4500ms (exceeding 4000ms stats timeout)
        advanceTimeBy(4500L)
        advanceUntilIdle()

        val timeoutState = viewModel.stationDetailState.value
        // Stage 1 must still be intact and fully functional
        assertNotNull("Station must remain loaded", timeoutState.station)
        assertEquals("C.HNI0003", timeoutState.station?.id)
        assertFalse("isLoadingTelemetry must be false", timeoutState.isLoadingTelemetry)
        assertEquals("Có 1 cổng 120kW sắp trống", timeoutState.cleanForecast)
        assertNotNull("Rating must be preserved", timeoutState.rating)

        // Stage 2 must time out cleanly without crash, setting stats24h = null and isLoadingStats = false
        assertFalse("isLoadingStats must be false after timeout", timeoutState.isLoadingStats)
        assertNull("stats24h must be null on timeout", timeoutState.stats24h)
    }

    // =========================================================================
    // Verification 4: Clean lifecycle cancellation upon sheet dismissal
    // =========================================================================

    @Test
    fun testDismissStationDetail_cancelsActiveJobs_andClearsStateCleanly() = runTest(testDispatcher) {
        val viewModel = createFavoritesViewModel()
        val station = createTestStation()

        // Simulate slow network to keep jobs active
        fakeTelemetryRepo.historyDelayMs = 5000L

        val job = viewModel.stationDetailCoordinator.selectStationForDetail(station)
        assertTrue("Job must be active", job.isActive)

        // Dismiss while loading
        viewModel.dismissStationDetail()

        // Job must be cancelled immediately
        assertTrue("Job must be cancelled upon dismissal", job.isCancelled)
        assertNull("Active job reference must be cleared", viewModel.stationDetailCoordinator.activeJob)

        // State must be reset to initial empty state
        val dismissedState = viewModel.stationDetailState.value
        assertNull("Station must be null after dismissal", dismissedState.station)
        assertFalse(dismissedState.isLoadingTelemetry)
        assertFalse(dismissedState.isLoadingStats)
        assertFalse(dismissedState.isRefreshing)
        assertTrue(dismissedState.portStatuses.isEmpty())
        assertNull(dismissedState.rating)
        assertNull(dismissedState.cleanForecast)
        assertNull(dismissedState.stats24h)

        // Also verify selectedStationForDetail is cleared
        assertNull(viewModel.selectedStationForDetail.value)

        // Advancing time must not trigger any emissions or resurrect state
        advanceUntilIdle()
        assertNull(viewModel.stationDetailState.value.station)
    }

    // =========================================================================
    // Verification 5: Manual refresh updates isRefreshing and repopulates stats
    // =========================================================================

    @Test
    fun testManualRefresh_updatesIsRefreshingState_andRepopulatesStats() = runTest(testDispatcher) {
        val viewModel = createFavoritesViewModel()
        val station = createTestStation()

        // Initial load
        viewModel.selectStationForDetail(station)
        advanceUntilIdle()

        val initialDetailState = viewModel.stationDetailState.value
        assertEquals(4.8, initialDetailState.rating?.avg ?: 0.0, 0.001)
        assertEquals(4, initialDetailState.portStatuses.find { it.kw == 60 }?.busyCount?.plus(1) ?: 0)

        // Prepare updated data on server
        fakeTelemetryRepo.tokensResult = Result.success(
            StationAccessTokens(
                chargeToken = "new_charge_token",
                apiToken = "new_api_token",
                rating = StationRating(avg = 5.0, count = 30, mine = 5)
            )
        )
        fakeTelemetryRepo.liveChargingResult = Result.success(
            StationTelemetry(
                busyByKw = mapOf(60 to 1, 120 to 0), // Now only 1 port busy
                cleanForecast = "Trạm hiện có nhiều cổng trống",
                isLocked = false
            )
        )
        fakeTelemetryRepo.historyResult = Result.success(
            listOf(
                1725400000000L to 1,
                1725403600000L to 2
            )
        )

        // Trigger manual refresh
        val refreshJob = viewModel.refreshStationDetail()
        assertNotNull("Refresh job must be launched", refreshJob)

        // Verify isRefreshing is true immediately upon trigger
        val refreshingState = viewModel.stationDetailState.value
        assertTrue("isRefreshing must be true during refresh", refreshingState.isRefreshing)
        assertTrue("isLoadingTelemetry must be true during refresh", refreshingState.isLoadingTelemetry)
        assertTrue("isLoadingStats must be true during refresh", refreshingState.isLoadingStats)

        // Advance dispatcher to complete refresh
        advanceUntilIdle()

        val refreshedState = viewModel.stationDetailState.value
        assertFalse("isRefreshing must be false after completion", refreshedState.isRefreshing)
        assertFalse("isLoadingTelemetry must be false", refreshedState.isLoadingTelemetry)
        assertFalse("isLoadingStats must be false", refreshedState.isLoadingStats)

        // Verify new rating and forecast repopulated
        assertEquals(5.0, refreshedState.rating?.avg ?: 0.0, 0.001)
        assertEquals(30, refreshedState.rating?.count)
        assertEquals("Trạm hiện có nhiều cổng trống", refreshedState.cleanForecast)

        // Verify updated port statuses
        val refreshedPort60 = refreshedState.portStatuses.find { it.kw == 60 }
        assertNotNull(refreshedPort60)
        assertEquals(1, refreshedPort60!!.busyCount)
        assertEquals(3, refreshedPort60.availablePorts) // 4 - 1 = 3
    }

    // =========================================================================
    // Verification 6: Fallback for station with only connectors string (no powers)
    // =========================================================================

    @Test
    fun testSelectStationForDetail_parsesConnectorsWhenPowersListIsEmpty() = runTest(testDispatcher) {
        val viewModel = createFavoritesViewModel()
        val stationWithoutPowers = createTestStation(
            id = "C.HNI0004",
            name = "VinFast Long Biên",
            powerPorts = emptyList(),
            connectors = "60kW x 4, 30kW x 2"
        )

        viewModel.selectStationForDetail(stationWithoutPowers)

        // Synchronous initial state should have parsed connector definitions
        val syncState = viewModel.stationDetailState.value
        assertEquals(2, syncState.portStatuses.size)
        val port60 = syncState.portStatuses.find { it.kw == 60 }
        assertNotNull(port60)
        assertEquals(4, port60!!.totalPorts)

        val port30 = syncState.portStatuses.find { it.kw == 30 }
        assertNotNull(port30)
        assertEquals(2, port30!!.totalPorts)

        // Advance to verify enrichment
        advanceUntilIdle()
        assertNotNull(viewModel.stationDetailState.value.rating)
    }

    // =========================================================================
    // Verification 7: Rapid switching between stations cancels previous job
    // =========================================================================

    @Test
    fun testRapidStationSwitching_cancelsPreviousJob_andLoadsTargetStation() = runTest(testDispatcher) {
        val viewModel = createFavoritesViewModel()
        val station1 = createTestStation(id = "STATION_1", name = "Station One")
        val station2 = createTestStation(id = "STATION_2", name = "Station Two")

        val job1 = viewModel.stationDetailCoordinator.selectStationForDetail(station1)
        assertTrue(job1.isActive)

        // Immediately switch to station 2
        val job2 = viewModel.stationDetailCoordinator.selectStationForDetail(station2)
        assertTrue("Previous job must be cancelled", job1.isCancelled)
        assertTrue("New job must be active", job2.isActive)

        assertEquals("STATION_2", viewModel.stationDetailState.value.station?.id)

        advanceUntilIdle()

        val finalState = viewModel.stationDetailState.value
        assertEquals("STATION_2", finalState.station?.id)
        assertFalse(finalState.isLoadingTelemetry)
        assertFalse(finalState.isLoadingStats)
    }
}
